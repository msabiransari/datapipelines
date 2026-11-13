package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.logcapture.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * #845 (owner ruling 2026-11-06 option B — hygiene, not safety; lake.md §9): the managed lake datasource's reads
 * leave through the shipped egress proxy, which resets every object-store stream at its max-age, so a store that
 * trickles above curl's low-speed limit (the #839 shape) cannot hold a reader — or the close waiting behind it —
 * forever.
 *
 * The proxy is the SHIPPED one ([EgressProxyFixture]: compose's `egress-proxy` image, the config RENDERED by
 * `EgressProxyConfigMain` from this suite's own store coordinates, only the listener moved to a free loopback port);
 * it runs on the host network so it reaches the [ReaderTrickleProxy] in this JVM, which passes S3 through to this
 * suite's own MinIO until armed. The allowlist's bypass matrix is `ManagedLakeEgressAllowlistE2eTest`'s. The app
 * boots with `node-query-timeout-max-seconds` = [CEILING_SECONDS], so [MAX_AGE_SECONDS] sits exactly on
 * `LakeRules`' floor (ceiling + 30).
 *
 * - **D.4** boot is refused `lake.boot.egress_proxy` with no proxy listening, with a proxy declaring another
 *   max-age, and with a store the proxy does not allowlist (its own stamped 403, #862); green with the right one
 *   (the `event=lake.egress_proxy_verified` line), and the managed lease carries `http_proxy` and cannot change it
 *   (the §15a lock).
 * - **D.3** a multi-MB read goes through the proxy and is correct; nothing stays connected to the proxy past max-age +
 *   drain; the read then succeeds again.
 * - **D.1** the trickling read ENDS within the derived bound — httpfs retries a cut transfer, so the bound is
 *   `LakeHttpBounds.egressReaderEndByMillis` ((http_retries + 1) × max-age + slack, probe A.0b), not one max-age — and
 *   the `Connection.close()` thread
 *   blocked behind it is gone with it.
 * - **D.2** the control: the same read with no proxy is NOT ended within 2 × max-age (bounded, then released).
 * - **D.5** the allowlist: the declaration route answers with the cap, and a host outside the store's domains is
 *   refused 403 in both request forms (CONNECT and absolute-form GET) on the real Envoy.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ManagedLakeEgressProxyE2eTest {
    @Test
    @Order(1)
    fun `D4 - boot is refused with no proxy listening and with another declared max-age, and green with the proxy`() {
        val closedPort = freePort()
        val absent = refusal(proxied(egressProxy = "127.0.0.1:$closedPort", maxAge = MAX_AGE_SECONDS))
        println("EGRESS_E2E D4 refused=absent proxy=127.0.0.1:$closedPort cause=\"$absent\"")
        absent shouldStartWith REFUSAL_ID
        absent shouldContain "unreachable"

        val otherCap = MAX_AGE_SECONDS + OTHER_CAP_DELTA_SECONDS
        val wrongCap = refusal(proxied(egressProxy = envoyAddress, maxAge = otherCap))
        println("EGRESS_E2E D4 refused=cap proxy=$envoyAddress declared=$MAX_AGE_SECONDS configured=$otherCap cause=\"$wrongCap\"")
        wrongCap shouldStartWith REFUSAL_ID
        wrongCap shouldContain "'max-age=$otherCap'"

        // #862 (review R2): the proxy allowlists ONE store authority (this suite's trickle); an app configured with
        // another one — here the same MinIO, addressed directly — gets the proxy's own stamped 403, never mistaken
        // for the store's.
        val direct = "http://${minio.host}:${minio.getMappedPort(MINIO_PORT)}"
        val otherStore = refusal(proxied(egressProxy = envoyAddress, maxAge = MAX_AGE_SECONDS, endpoint = direct))
        println("EGRESS_E2E D4 refused=allowlist proxy=$envoyAddress store=$direct cause=\"$otherStore\"")
        otherStore shouldStartWith REFUSAL_ID
        otherStore shouldContain "refused the store $direct/$BUCKET itself (403, x-dp-egress-refused)"

        // Twice: the first green boot writes the managed row the table's definition needs (its system actor); the
        // second registers that table at boot, so its generations attach the catalog D.1 reads.
        repeat(2) { round ->
            val capture = SnapshotListAppender()
            val started = System.nanoTime()
            val context = boot(proxied(egressProxy = envoyAddress, maxAge = MAX_AGE_SECONDS), capture)
            val verified = capture.messages().filter { it.startsWith("event=lake.egress_proxy_verified") }
            verified.forEach { println(it) }
            println("EGRESS_E2E D4 green round=$round boot_ms=${millisSince(started)}")
            withClue("the green boot logs exactly one verification line naming the proxy and its cap") {
                verified.size shouldBe 1
                verified.single() shouldContain
                    "proxy=$envoyAddress max_age_seconds=$MAX_AGE_SECONDS reader_end_by_ms=${endByMillis()} "
            }
            if (round == 0) {
                ensureTable()
                context.close()
            } else {
                greenContext = context
            }
        }
        val context = checkNotNull(greenContext)
        leaseManaged(context).use { lease ->
            lease.createStatement().use { st ->
                st.executeQuery("SELECT current_setting('http_proxy')").use { rs ->
                    rs.next()
                    rs.getString(1) shouldBe envoyAddress
                }
                val refused = runCatching { st.execute("SET http_proxy = ''") }.exceptionOrNull().shouldNotBeNull()
                println("EGRESS_E2E D4 lease http_proxy=$envoyAddress set_refused=\"${refused.message?.take(MESSAGE_CHARS)}\"")
                refused.message.shouldNotBeNull() shouldContain "locked"
            }
        }
    }

    /**
     * D.3 — measured, not assumed (#845 handback): this httpfs closes a query's proxy connections when the query ends,
     * and a query is bounded below the max-age (`LakeRules`: max-age ≥ ceiling + 30), so a healthy read never meets
     * a cut keep-alive. What the proxy must not do is break a healthy multi-MB read, or leave a connection past its
     * age: the read goes THROUGH the proxy (sampled sockets, non-vacuity), returns the right answer, nothing is left
     * connected to the proxy past max-age + drain, and the same read then succeeds again.
     */
    @Test
    @Order(2)
    fun `D3 - a multi-MB read through the proxy is correct, and no proxy connection outlives the max-age`() {
        val context = checkNotNull(greenContext) { "D4's green boot did not run" }
        val expected = expectedBig()
        leaseManaged(context).use { lease ->
            val first = sampled { readBig(lease) }
            val right = establishedTo(envoyPort)
            println(
                "EGRESS_E2E D3 first result=${first.result} ms=${first.millis} proxy_connections_during_max=${first.maxConnections} " +
                    "proxy_connections_after=$right",
            )
            first.result shouldBe expected
            withClue("non-vacuity: the multi-MB read went through the proxy") { first.maxConnections.toLong() shouldBeGreaterThan 0L }
            val wait = MAX_AGE_SECONDS + ENVOY_DRAIN_SECONDS + SLACK_SECONDS
            Thread.sleep(Duration.ofSeconds(wait).toMillis())
            val after = establishedTo(envoyPort)
            println("EGRESS_E2E D3 after ${wait}s proxy_connections=$after")
            withClue("no connection to the proxy outlives max-age + drain") { after shouldBe 0 }
            val second = sampled { readBig(lease) }
            println(
                "EGRESS_E2E D3 second result=${second.result} ms=${second.millis} proxy_connections_during_max=${second.maxConnections}",
            )
            second.result shouldBe expected
        }
    }

    @Test
    @Order(3)
    fun `D1 - a trickling managed read ends within the derived bound and the close thread behind it is gone`() {
        val context = checkNotNull(greenContext) { "D4's green boot did not run" }
        try {
            // Observed past end-by so a reader that is NOT ended reports as such, then held to end-by itself.
            val outcome = trickle(context, greenTrickle, observeSeconds = endByMillis() / MILLIS_PER_SECOND + SLACK_SECONDS)
            val clue = "D.1: the reader ended within LakeHttpBounds.egressReaderEndByMillis($MAX_AGE_SECONDS) = ${endByMillis()} ms"
            withClue("$clue of the first GET") {
                outcome.readerEndedAfterMs.shouldNotBeNull() shouldBeLessThanOrEqual endByMillis()
            }
            withClue("D.1: the Connection.close() thread blocked behind the read is gone (#839)") {
                outcome.closeThreadsAlive shouldBe 0
            }
        } finally {
            context.close()
            greenContext = null
        }
    }

    @Test
    @Order(4)
    fun `D2 - control - with no proxy the same read is not ended within twice the max-age`() {
        ReaderTrickleProxy(minio.host, minio.getMappedPort(MINIO_PORT)).use { trickle ->
            val capture = SnapshotListAppender()
            val context = boot(direct(trickle.endpoint), capture)
            try {
                capture
                    .messages()
                    .filter { it.startsWith("event=lake.egress_proxy_absent") }
                    .also { it.forEach(::println) }
                    .size shouldBe 1
                val outcome = trickle(context, trickle, observeSeconds = CONTROL_FACTOR * MAX_AGE_SECONDS)
                withClue("D.2: with no proxy the reader is still reading after ${CONTROL_FACTOR * MAX_AGE_SECONDS} s") {
                    outcome.readerEndedAfterMs shouldBe null
                    outcome.closeThreadsAlive.toLong() shouldBeGreaterThan 0L
                }
            } finally {
                context.close()
            }
        }
    }

    /**
     * D.5 — #862: the proxy's config is the renderer's allowlist, spoken to the REAL Envoy container the shipped
     * image runs: the `/dp-egress` declaration on the proxy's own authority answers 200 with the cap (the boot
     * check's route, reachable from the catch-all vhost), and an authority outside the store allowlist is refused
     * 403 in both request forms — CONNECT and absolute-form GET — before any upstream is contacted.
     */
    @Test
    @Order(5)
    fun `D5 - the proxy answers the declaration and refuses a host outside the store allowlist`() {
        val declaration =
            proxyStatus("GET /dp-egress HTTP/1.1\r\nHost: $envoyAddress\r\nx-dp-egress-check: 1\r\nConnection: close\r\n\r\n")
        println("EGRESS_E2E D5 declaration=\"$declaration\"")
        withClue("the declaration route answers on the proxy's own authority") {
            declaration shouldStartWith "HTTP/1.1 200"
            declaration shouldContain "max-age=$MAX_AGE_SECONDS"
        }

        val connect =
            proxyStatus("CONNECT $ATTACK_HOST:443 HTTP/1.1\r\nHost: $ATTACK_HOST:443\r\nConnection: close\r\n\r\n")
        println("EGRESS_E2E D5 refused_connect=\"$connect\"")
        connect shouldStartWith "HTTP/1.1 403"

        val absolute =
            proxyStatus("GET http://$ATTACK_HOST/ HTTP/1.1\r\nHost: $ATTACK_HOST\r\nConnection: close\r\n\r\n")
        println("EGRESS_E2E D5 refused_absolute=\"$absolute\"")
        absolute shouldStartWith "HTTP/1.1 403"
    }

    /**
     * One raw HTTP request spoken to the shipped Envoy at [envoyAddress]; every caller sends `Connection: close`,
     * so the full response — status line, headers, body — is read to the stream's end.
     */
    private fun proxyStatus(request: String): String =
        Socket(InetAddress.getLoopbackAddress(), envoyPort).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            buildList {
                while (true) {
                    add(reader.readLine() ?: break)
                }
            }.joinToString("\n")
        }

    /** What one trickling read did within its observation: when it ended (null = still reading), and the closes left. */
    private data class TrickleOutcome(
        val readerEndedAfterMs: Long?,
        val closeThreadsAlive: Int,
    )

    /**
     * The #839 timeline (`ManagedLakeReadsE2eTest.verifyTrickle`'s shape): prepare, arm, start the read, wait for the
     * trickling GET, retire the generation and start its close, then watch for [observeSeconds] from the first GET.
     * Whatever happened, the trickle is released at the end so nothing outlives the test.
     */
    private fun trickle(
        context: ConfigurableApplicationContext,
        proxy: ReaderTrickleProxy,
        observeSeconds: Long,
    ): TrickleOutcome {
        val lease = leaseManaged(context)
        val run = Read(lease.prepareStatement("SELECT sum(fare) FROM $LAKE.sales.trips"))
        try {
            val generation = generationOf(context)
            proxy.arm()
            run.start()
            proxy.arrived.await(ReaderTrickleProxy.START_SECONDS, TimeUnit.SECONDS) shouldBe true
            val firstGet = System.nanoTime()
            retirePool(context) shouldBe true
            startRetiredClose(context, generation)
            val deadline = firstGet + Duration.ofSeconds(observeSeconds).toNanos()
            while (System.nanoTime() < deadline && run.thread.isAlive) Thread.sleep(WATCH_MILLIS)
            val endedAfter = if (run.thread.isAlive) null else (run.endedNanos.get()!! - firstGet) / NANOS_PER_MILLI
            // The close is handed the generation once the read lets go: give it the slack, never the whole bound.
            if (endedAfter != null) awaitGone(generation, Instant.now().plusSeconds(SLACK_SECONDS))
            val closes = closeThreads(generation)
            println(
                "EGRESS_E2E trickle generation=$generation observe_s=$observeSeconds reader_ended_after_ms=$endedAfter " +
                    "outcome=\"${run.outcome.get()}\" close_threads_alive=${closes.size} trickled_bytes=${proxy.bytes.get()}",
            )
            return TrickleOutcome(endedAfter, closes.size)
        } finally {
            proxy.close()
            run.thread.join(CLEANUP_JOIN_MS)
            closeThreads(null).forEach { it.join(CLEANUP_JOIN_MS) }
            runCatching { lease.close() }
        }
    }

    private class Read(
        private val statement: java.sql.PreparedStatement,
    ) {
        val endedNanos = AtomicReference<Long?>(null)
        val outcome = AtomicReference("running")
        val thread =
            Thread({
                try {
                    statement.executeQuery().use { rs ->
                        rs.next()
                        outcome.set("completed:${rs.getObject(1)}")
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    outcome.set("failed:${e.javaClass.simpleName}: ${e.message?.take(MESSAGE_CHARS)}")
                } finally {
                    endedNanos.set(System.nanoTime())
                }
            }, "egress-trickle-read")

        fun start() = thread.start()
    }

    private fun startRetiredClose(
        context: ConfigurableApplicationContext,
        generation: UUID,
    ) {
        val registry = context.getBean("datasourceRegistry")
        registry.javaClass.getMethod("reapRetiredPools").invoke(registry)
        val deadline = Instant.now().plusSeconds(ReaderTrickleProxy.START_SECONDS)
        while (Instant.now().isBefore(deadline)) {
            if (closeThreads(generation).any {
                    it.stackTrace.any { f ->
                        f.className == DUCKDB_CONNECTION && f.methodName == "close"
                    }
                }
            ) {
                return
            }
            Thread.sleep(WATCH_MILLIS)
        }
        error("the physical DuckDB close of generation $generation did not begin within ${ReaderTrickleProxy.START_SECONDS} s")
    }

    private fun awaitGone(
        generation: UUID,
        deadline: Instant,
    ) {
        while (Instant.now().isBefore(deadline) && closeThreads(generation).isNotEmpty()) Thread.sleep(WATCH_MILLIS)
    }

    /** The registry's per-generation physical close threads (`dp-pool-interrupt-<generation>-<n>`); null = every one. */
    private fun closeThreads(generation: UUID?): List<Thread> =
        Thread.getAllStackTraces().keys.filter {
            it.isAlive &&
                it.name.startsWith(CLOSE_THREAD_PREFIX + (generation?.let { g -> "$g-" } ?: ""))
        }

    private fun leaseManaged(context: ConfigurableApplicationContext): Connection {
        val pool = poolOf(context)
        return Class.forName(POOL_TYPE).getMethod("leaseConnection").invoke(pool) as Connection
    }

    private fun poolOf(context: ConfigurableApplicationContext): Any {
        val registry = context.getBean("datasourceRegistry")
        val type = Class.forName("co.datapipelines.datasources.DatasourceRegistry")
        val datasource = type.getMethod("get", String::class.java).invoke(registry, LAKE).shouldNotBeNull()
        return requireNotNull(
            type.getMethod("poolFor", Class.forName("co.datapipelines.datasources.Datasource")).invoke(registry, datasource),
        )
    }

    private fun generationOf(context: ConfigurableApplicationContext): UUID =
        UUID.fromString(Class.forName(POOL_TYPE).getMethod("getGeneration").invoke(poolOf(context)) as String)

    private fun retirePool(context: ConfigurableApplicationContext): Boolean {
        val registry = context.getBean("datasourceRegistry")
        return registry.javaClass.getMethod("retirePool", String::class.java).invoke(registry, LAKE) as Boolean
    }

    private fun readBig(lease: Connection): String =
        lease.createStatement().use { st ->
            st.executeQuery("SELECT count(*), sum(v) FROM read_parquet('s3://$BUCKET/$BIG_KEY')").use { rs ->
                rs.next()
                "${rs.getLong(1)}/${rs.getLong(2)}"
            }
        }

    private data class Sampled<T>(
        val result: T,
        val millis: Long,
        val maxConnections: Int,
    )

    /** Runs [block], sampling this namespace's ESTABLISHED connections to the proxy every [SAMPLE_MILLIS] meanwhile. */
    private fun <T> sampled(block: () -> T): Sampled<T> {
        val max = AtomicInteger()
        val running = AtomicBoolean(true)
        val sampler =
            Thread({
                while (running.get()) {
                    max.accumulateAndGet(establishedTo(envoyPort), ::maxOf)
                    Thread.sleep(SAMPLE_MILLIS)
                }
            }, "egress-socket-sampler").apply {
                isDaemon = true
                start()
            }
        val started = System.nanoTime()
        try {
            val result = block()
            return Sampled(result, millisSince(started), max.get())
        } finally {
            running.set(false)
            sampler.join(CLEANUP_JOIN_MS)
        }
    }

    /** The Iceberg fixture's `sales.trips`, defined in the catalog the managed generation reads, active. */
    private fun ensureTable() {
        metadata { connection ->
            val actor =
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT created_by FROM datasources WHERE name = '$LAKE'").use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }
            val namespace = UUID.randomUUID()
            connection.createStatement().use {
                it.execute("INSERT INTO lake_namespaces (id, name, status, created_by) VALUES ('$namespace', 'sales', 'active', '$actor')")
                it.execute(
                    "INSERT INTO lake_tables_managed (id, namespace_id, name, status, policy_json, created_by) " +
                        "VALUES ('${UUID.randomUUID()}', '$namespace', 'trips', 'active', '{}', '$actor')",
                )
            }
            val token = UUID.randomUUID()
            val operation = UUID.randomUUID()
            connection
                .prepareStatement(
                    "INSERT INTO lake_leases (kind, name, operation_id, token, acquired_at, expires_at, " +
                        "deadline_at, operation, holder_instance) VALUES ('table', 'sales.trips', ?, ?, now(), " +
                        "now() + interval '1 minute', now() + interval '1 minute', 'test', 'test')",
                ).use { ps ->
                    ps.setObject(1, operation)
                    ps.setObject(2, token)
                    ps.executeUpdate() shouldBe 1
                }
            connection.autoCommit = false
            try {
                val fence = "SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)"
                connection.prepareStatement(fence).use { ps ->
                    ps.setString(1, token.toString())
                    ps.setString(2, operation.toString())
                    ps.executeQuery().close()
                }
                connection
                    .prepareStatement(
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, " +
                            "previous_metadata_location, iceberg_type) VALUES (?, 'sales', 'trips', ?, NULL, 'TABLE')",
                    ).use { ps ->
                        ps.setString(1, LAKE)
                        ps.setString(2, CURRENT)
                        ps.executeUpdate() shouldBe 1
                    }
                connection.commit()
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private companion object {
        private const val LAKE = "lake845"
        private const val BUCKET = "dp-lake-it"
        private const val CURRENT =
            "s3://$BUCKET/iceberg/trips_iceberg/metadata/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"
        private const val BIG_KEY = "egress845/multi_mb.parquet"
        private const val BIG_ROWS = 1_000_000L

        /** `LakeRules`' floor exactly: max-age ≥ node-query-timeout-max-seconds + 30. */
        private const val CEILING_SECONDS = 5L
        private const val MAX_AGE_SECONDS = CEILING_SECONDS + 30L
        private const val OTHER_CAP_DELTA_SECONDS = 10L
        private const val CONTROL_FACTOR = 2L

        /** Per attempt: the reset's observed +1.0 s (probe A.0b), the retry wait and the next connect, rounded up. */
        private const val SLACK_SECONDS = 5L

        /** Envoy's default drain before it closes an idle connection past `max_connection_duration` (probe A.0b T5). */
        private const val ENVOY_DRAIN_SECONDS = 5L
        private const val REFUSAL_ID = "lake.boot.egress_proxy"
        private const val MINIO_PORT = 9000
        private const val WRITER_ACCESS = "dpwriter845"
        private val WRITER_SECRET = "w-" + UUID.randomUUID().toString().replace("-", "")
        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val POOL_TYPE = "co.datapipelines.datasources.pooling.ConnectionPool"
        private const val DUCKDB_CONNECTION = "org.duckdb.DuckDBConnection"
        private const val CLOSE_THREAD_PREFIX = "dp-pool-interrupt-"
        private const val PRODUCT_LOGGERS = "co.datapipelines"
        private const val WATCH_MILLIS = 100L
        private const val SAMPLE_MILLIS = 2L
        private const val CLEANUP_JOIN_MS = 30_000L
        private const val MESSAGE_CHARS = 200
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val ATTACK_HOST = "leak.example.invalid"

        private var greenContext: ConfigurableApplicationContext? = null
        private val oidc = OidcDiscoveryStub()
        private val database by lazy { SharedE2e.scratchDatabase("managed_lake_egress") }

        /**
         * The bound D.1 holds the reader to, from the product's ONE derivation, `LakeHttpBounds.egressReaderEndByMillis`
         * ((http_retries + 1) × max-age + slack): this suite does not restate the formula, it tests it. Reflection, because
         * this module compiles against `:modules:app` only.
         */
        fun endByMillis(): Long =
            Class
                .forName("co.datapipelines.datasources.LakeHttpBounds")
                .getMethod("egressReaderEndByMillis", Long::class.javaPrimitiveType)
                .invoke(null, MAX_AGE_SECONDS) as Long

        /**
         * The renderer's output for THIS suite's store at [maxAge] — the exact derivation the shipped `egress-config`
         * service runs in its container ([EgressProxyFixture.rendered]).
         */
        private fun renderedEgressConfig(maxAge: Long): String =
            EgressProxyFixture.rendered(EgressProxyFixture.derive(greenTrickle.endpoint, "us-east-1", BUCKET, pathStyle = true), maxAge)

        private val minioStarted =
            lazy {
                GenericContainer(DockerImageName.parse(SharedE2e.MINIO_IMAGE))
                    .withEnv("MINIO_ROOT_USER", WRITER_ACCESS)
                    .withEnv("MINIO_ROOT_PASSWORD", WRITER_SECRET)
                    .withCommand("server", "/data")
                    .withExposedPorts(MINIO_PORT)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))
                    .also { container ->
                        container.start()
                        seedBucket(container)
                    }
            }
        private val minio: GenericContainer<*> by minioStarted

        /** The trickle the green instance's store endpoint points at: S3 pass-through until D.1 arms it. */
        private val greenTrickleStarted = lazy { ReaderTrickleProxy(minio.host, minio.getMappedPort(MINIO_PORT)) }
        private val greenTrickle by greenTrickleStarted

        private val envoyPort by lazy { freePort() }
        private val envoyAddress get() = "127.0.0.1:$envoyPort"

        /**
         * The shipped proxy: compose's image, and a config RENDERED by [renderedEgressConfig] — the same
         * [co.datapipelines.web.config.EgressProxyConfigMain] derivation the shipped `egress-config` renderer
         * service runs (compose ships no Envoy template to read since #862), from this suite's own store
         * coordinates, with only the listener moved to a free loopback port.
         */
        private val envoyStarted =
            lazy {
                val rendered = renderedEgressConfig(MAX_AGE_SECONDS)
                withClue("the rendered config carries the cap") { rendered shouldContain "max-age=$MAX_AGE_SECONDS" }
                println("EGRESS_E2E envoy image=${EgressProxyFixture.shippedImage()} listener=$envoyAddress max_age_s=$MAX_AGE_SECONDS")
                EgressProxyFixture.start(EgressProxyFixture.relistened(rendered, envoyPort))
            }
        private val envoy: GenericContainer<*> by envoyStarted

        private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun millisSince(started: Long): Long = (System.nanoTime() - started) / NANOS_PER_MILLI

        /** ESTABLISHED IPv4 sockets in this network namespace whose REMOTE port is [port]: the clients' side only. */
        private fun establishedTo(port: Int): Int =
            File("/proc/net/tcp").readLines().drop(1).count { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields[2].substringAfter(':').toInt(16) == port && fields[3] == "01"
            }

        private fun seedBucket(container: GenericContainer<*>) {
            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:${container.getMappedPort(MINIO_PORT)}"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(WRITER_ACCESS, WRITER_SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build()
                .use { s3 ->
                    s3.createBucket { it.bucket(BUCKET) }
                    val fixture =
                        Path.of(checkNotNull(ManagedLakeEgressProxyE2eTest::class.java.getResource("/lake-fixture/iceberg")).toURI())
                    Files.walk(fixture).use { paths ->
                        paths.filter { it.isRegularFile() && it.name != ".DS_Store" }.forEach { file ->
                            s3.putObject({ it.bucket(BUCKET).key("iceberg/${file.relativeTo(fixture)}") }, RequestBody.fromFile(file))
                        }
                    }
                    val big = bigParquet()
                    println("EGRESS_E2E D3 object=s3://$BUCKET/$BIG_KEY bytes=${Files.size(big)} rows=$BIG_ROWS")
                    s3.putObject({ it.bucket(BUCKET).key(BIG_KEY) }, RequestBody.fromFile(big))
                }
        }

        /** A multi-MB Parquet object of incompressible values, written by a plain in-memory DuckDB. */
        private fun bigParquet(): Path {
            val file = Files.createTempDirectory("dp845-egress").resolve("multi_mb.parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                duck.createStatement().use {
                    it.execute(
                        "COPY (SELECT i AS id, hash(i) % 1000000007 AS v FROM range($BIG_ROWS) t(i)) TO '$file' (FORMAT parquet)",
                    )
                }
            }
            return file
        }

        /** The expected `count/sum` of the big object, computed the same way, without any store. */
        fun expectedBig(): String =
            DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                duck.createStatement().use { st ->
                    st.executeQuery("SELECT count(*), sum(hash(i) % 1000000007) FROM range($BIG_ROWS) t(i)").use { rs ->
                        rs.next()
                        "${rs.getLong(1)}/${rs.getLong(2)}"
                    }
                }
            }

        private fun <T> metadata(block: (Connection) -> T): T =
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

        /** A boot through the proxy at [egressProxy], the app configured with [maxAge]; the store is [endpoint]. */
        fun proxied(
            egressProxy: String,
            maxAge: Long,
            endpoint: String = greenTrickle.endpoint,
        ): List<Pair<String, String>> {
            envoy.isRunning shouldBe true
            return properties(endpoint) +
                listOf(
                    "datapipelines.lake.reads.egress-proxy" to egressProxy,
                    "datapipelines.lake.reads.egress-max-age-seconds" to maxAge.toString(),
                )
        }

        /** A boot with no proxy: the managed reads reach [endpoint] directly. */
        fun direct(endpoint: String): List<Pair<String, String>> =
            properties(endpoint) +
                listOf(
                    "datapipelines.lake.reads.egress-proxy" to "",
                    "datapipelines.lake.reads.egress-max-age-seconds" to MAX_AGE_SECONDS.toString(),
                )

        private fun properties(endpoint: String): List<Pair<String, String>> =
            listOf(
                "management.server.port" to "0",
                "spring.datasource.url" to database.jdbcUrl,
                "spring.datasource.username" to database.username,
                "spring.datasource.password" to database.password,
                "datapipelines.redis.password" to "",
                "datapipelines.redis.host" to SharedE2e.redisHost,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to SECRET,
                "datapipelines.db.encryption-key" to SECRET,
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.lake.enabled" to "true",
                "datapipelines.lake.name" to LAKE,
                "datapipelines.lake.storage.bucket" to BUCKET,
                "datapipelines.lake.storage.region" to "us-east-1",
                "datapipelines.lake.storage.endpoint" to endpoint,
                "datapipelines.lake.storage.path-style" to "true",
                "datapipelines.lake.storage.access-key" to WRITER_ACCESS,
                "datapipelines.lake.storage.secret-key" to WRITER_SECRET,
                // The trickle's generation is replaced only by the test's own retire (ManagedLakeReadsE2eTest's E1 values).
                "datapipelines.lake.reads.max-generation-age-seconds" to "900",
                "datapipelines.lake.reads.freshness-tick-seconds" to "600",
                "datapipelines.datasources.retire-ceiling-seconds" to "1",
                "datapipelines.datasources.managed-retire-ceiling-seconds" to "1",
                // The ceiling that puts MAX_AGE_SECONDS on LakeRules' floor, and the keys bounded by it.
                "datapipelines.executor.node-query-timeout-max-seconds" to CEILING_SECONDS.toString(),
                "datapipelines.executor.node-query-timeout-seconds" to CEILING_SECONDS.toString(),
                "datapipelines.executor.node-query-timeout-seconds-by-dialect.lake" to CEILING_SECONDS.toString(),
                "datapipelines.lake.admission.lease-wait-seconds" to (CEILING_SECONDS - 1).toString(),
            )

        /**
         * A full boot (command-line args win over application.yml's `${...}` defaults — DatasourcePoolInvalidationE2eTest's
         * note). [capture], when given, is attached to the product loggers once logging is initialised, so the boot's own
         * lines are in it.
         */
        fun boot(
            properties: List<Pair<String, String>>,
            capture: SnapshotListAppender? = null,
        ): ConfigurableApplicationContext {
            val attach =
                ApplicationContextInitializer<ConfigurableApplicationContext> {
                    capture?.let { (LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger).addAppender(it) }
                }
            return SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .initializers(attach)
                .run("--server.port=0", *properties.map { (k, v) -> "--$k=$v" }.toTypedArray())
        }

        /** The refused boot's cause chain, from the first message that carries the refusal id. */
        fun refusal(properties: List<Pair<String, String>>): String {
            val started = System.nanoTime()
            val failure = runCatching { boot(properties).close() }.exceptionOrNull()
            val chain = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
            println("EGRESS_E2E refused_boot_ms=${millisSince(started)}")
            withClue("the boot was expected to be refused; its cause chain: $chain") {
                chain.firstOrNull { it.startsWith(REFUSAL_ID) }.shouldNotBeNull()
            }
            return chain.first { it.startsWith(REFUSAL_ID) }
        }

        @AfterAll
        @JvmStatic
        fun closeAll() {
            greenContext?.close()
            if (greenTrickleStarted.isInitialized()) greenTrickle.close()
            if (envoyStarted.isInitialized()) envoy.stop()
            if (minioStarted.isInitialized()) minio.stop()
            oidc.close()
        }
    }
}
