package co.datapipelines.integration

import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.logcapture.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
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
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.reflect.KClass

/**
 * #982: what the managed-lake read family shares — [ManagedLakeReadsE2eTest] and the three classes split out of it so that
 * different test workers can run its four long timelines side by side: [ManagedLakeReadsTrickleE2eTest] (E1, E1shutdown),
 * [ManagedLakeReadsPausedMetadataE2eTest] (E3b) and [ManagedLakeReadsStuckCloseE2eTest] (I3). The same real proofs run;
 * only where their code lives changed.
 *
 * Shared code lives here; state that changes at run time is never shared. Each class creates ONE instance in its companion
 * and no class touches another's. An instance owns its scratch metadata database ([databaseName]; one name per class), its
 * own MinIO with its own generated writer secret, its own [OidcDiscoveryStub], and every application instance booted
 * through [boot].
 *
 * The three split classes are plain JUnit classes: they call [start] from `@BeforeAll` and [close] from `@AfterAll`, and
 * the instance owns their whole life. [ManagedLakeReadsE2eTest] calls neither: its `@SpringBootTest` context A migrates
 * the database and creates the managed row, its own ordered tests create and move the table, its `closeInstances` closes
 * B, F and the OIDC stub, and its MinIO is left to the container reaper (context A stays in Spring's context cache after
 * the class and still points at it).
 */
@Suppress("TooManyFunctions") // the family's shared helpers, moved from one class (#982); each stays the function it was
class ManagedLakeReadsFixture(
    private val databaseName: String,
    private val owner: KClass<*>,
) : AutoCloseable {
    /** Every instance booted through [boot], oldest first; [close] closes those still open. */
    private val booted = CopyOnWriteArrayList<ConfigurableApplicationContext>()

    /** The split classes' setup instance (see [start]); null in [ManagedLakeReadsE2eTest], which never calls [start]. */
    private var setup: ConfigurableApplicationContext? = null

    /** Generated per run: no literal credential in a fixture. Distinct from the username, so the dialect check bites. */
    internal val writerSecret = "w-" + UUID.randomUUID().toString().replace("-", "")

    internal val secret = Base64.getEncoder().encodeToString(ByteArray(32))

    private val oidcStub = lazy { OidcDiscoveryStub() }
    internal val oidc by oidcStub

    /** The suite's own metadata database: every boot migrates or reuses it; no other suite's context touches it. */
    private val scratchDatabase = lazy { SharedE2e.scratchDatabase(databaseName) }
    private val database by scratchDatabase

    /** Its own MinIO (see the class KDoc), seeded with the Iceberg fixture under `dp-lake-it` on first touch. */
    private val minioContainer: Lazy<GenericContainer<*>> =
        lazy {
            GenericContainer(DockerImageName.parse(SharedE2e.MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", WRITER_ACCESS)
                .withEnv("MINIO_ROOT_PASSWORD", writerSecret)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))
                .also { container ->
                    container.start()
                    seedBucket(container)
                }
        }
    internal val minio by minioContainer

    private val endpoint get() = "http://localhost:${minio.getMappedPort(MINIO_PORT)}"

    private fun seedBucket(container: GenericContainer<*>) {
        S3Client
            .builder()
            .endpointOverride(URI.create("http://localhost:${container.getMappedPort(MINIO_PORT)}"))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(WRITER_ACCESS, writerSecret)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build()
            .use { s3 ->
                s3.createBucket { it.bucket(BUCKET) }
                val fixture = Path.of(checkNotNull(ManagedLakeReadsFixture::class.java.getResource("/lake-fixture/iceberg")).toURI())
                Files.walk(fixture).use { paths ->
                    paths.filter { it.isRegularFile() && it.name != ".DS_Store" }.forEach { file ->
                        s3.putObject({ it.bucket(BUCKET).key("iceberg/${file.relativeTo(fixture)}") }, RequestBody.fromFile(file))
                    }
                }
            }
    }

    internal fun <T> metadata(block: (Connection) -> T): T =
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

    /** Every property a boot needs, as `key to value`; A takes them as dynamic properties, B, C and D as args. */
    internal fun bootProperties(
        maxAgeSeconds: Long,
        tickSeconds: Long,
        name: String = LAKE,
        enabled: Boolean = true,
        application: String? = null,
    ): List<Pair<String, String>> =
        listOf(
            "management.server.port" to "0",
            "spring.datasource.url" to (application?.let { urlNamed(it) } ?: database.jdbcUrl),
            "spring.datasource.username" to database.username,
            "spring.datasource.password" to database.password,
            "datapipelines.redis.password" to "",
            "datapipelines.redis.host" to SharedE2e.redisHost,
            "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
            "datapipelines.jwt.secret" to secret,
            "datapipelines.db.encryption-key" to secret,
            "datapipelines.auth.oidc.providers[0].name" to "google",
            "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
            "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
            "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
            "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
            "datapipelines.auth.base-url" to "http://localhost:8080",
            "datapipelines.lake.enabled" to enabled.toString(),
            "datapipelines.lake.name" to name,
            "datapipelines.lake.storage.bucket" to BUCKET,
            "datapipelines.lake.storage.region" to "us-east-1",
            "datapipelines.lake.storage.endpoint" to endpoint,
            "datapipelines.lake.storage.path-style" to "true",
            "datapipelines.lake.storage.access-key" to WRITER_ACCESS,
            "datapipelines.lake.storage.secret-key" to writerSecret,
            "datapipelines.lake.reads.max-generation-age-seconds" to maxAgeSeconds.toString(),
            "datapipelines.lake.reads.freshness-tick-seconds" to tickSeconds.toString(),
        )

    // Command-line args, not builder `.properties(...)`: those are DEFAULT properties and application.yml's
    // `${SPRING_DATASOURCE_URL}` would override them; args win (DatasourcePoolInvalidationE2eTest's note).
    internal fun boot(
        maxAgeSeconds: Long,
        tickSeconds: Long,
        name: String = LAKE,
        enabled: Boolean = true,
        application: String? = null,
        recordTicks: Boolean = false,
        extra: List<Pair<String, String>> = emptyList(),
        initializers: List<ApplicationContextInitializer<ConfigurableApplicationContext>> = emptyList(),
    ): ConfigurableApplicationContext {
        val sources =
            listOfNotNull(
                DatapipelinesApplication::class.java,
                ScheduledJobsSchedulerE2eTest.TickRecorderConfiguration::class.java.takeIf { recordTicks },
            )
        return SpringApplicationBuilder(*sources.toTypedArray())
            .initializers(*initializers.toTypedArray())
            .run(
                "--server.port=0",
                *(bootProperties(maxAgeSeconds, tickSeconds, name, enabled, application).toMap() + extra.toMap())
                    .map { (k, v) -> "--$k=$v" }
                    .toTypedArray(),
            ).also { booted += it }
    }

    /** The suite's database under a Postgres `ApplicationName`, so `pg_stat_activity` names the instance. */
    private fun urlNamed(application: String): String =
        database.jdbcUrl + (if ('?' in database.jdbcUrl) "&" else "?") + "ApplicationName=" + application

    /** H's profile (short age, tick, ceiling) under its own `ApplicationName`. */
    internal fun bootReaderProfile(
        application: String,
        initializers: List<ApplicationContextInitializer<ConfigurableApplicationContext>> = emptyList(),
    ): ConfigurableApplicationContext =
        boot(
            maxAgeSeconds = H_MAX_AGE_SECONDS,
            tickSeconds = H_TICK_SECONDS,
            application = application,
            extra = hCeilings(),
            initializers = initializers,
        )

    /** Acceptance methods can also run alone during falsification. */
    internal fun ensureReaderFixture() {
        val present =
            metadata { connection ->
                connection.createStatement().use {
                    it
                        .executeQuery(
                            "SELECT EXISTS(SELECT 1 FROM iceberg_tables WHERE table_namespace='sales' AND table_name='trips')",
                        ).use { rows ->
                            rows.next()
                            rows.getBoolean(1)
                        }
                }
            }
        if (!present) defineManagedTable(CURRENT)
        setStatus("active")
    }

    internal fun databaseNow(): Instant =
        metadata { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT clock_timestamp()").use { rows ->
                    rows.next()
                    rows.getTimestamp(1).toInstant()
                }
            }
        }

    internal fun readerRows(): Map<UUID, Boolean> =
        metadata { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT operation_id,bool_and(expires_at>now() AND deadline_at>now()) " +
                            "FROM lake_leases WHERE kind='reader' GROUP BY operation_id",
                    ).use { rows ->
                        buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getBoolean(2)) }
                    }
            }
        }

    /** A lifecycle status the V54 checks admit: each non-active status carries its own stamp, active carries none. */
    internal fun setStatus(status: String) {
        metadata { connection ->
            connection
                .prepareStatement(
                    "UPDATE lake_tables_managed SET status = ?, " +
                        "deprecated_at = CASE WHEN ? = 'deprecated' THEN now() END, " +
                        "archived_at = CASE WHEN ? = 'archived' THEN now() END WHERE name = 'trips'",
                ).use { ps ->
                    ps.setString(1, status)
                    ps.setString(2, status)
                    ps.setString(3, status)
                    ps.executeUpdate() shouldBe 1
                }
        }
    }

    /** A namespace, its lifecycle row and the catalog pointer (written under a lease, so the revision trigger runs). */
    internal fun defineManagedTable(location: String) {
        metadata { connection ->
            val actor = systemActor(connection)
            val namespace = UUID.randomUUID()
            connection.createStatement().use {
                it.execute("INSERT INTO lake_namespaces (id, name, status, created_by) VALUES ('$namespace', 'sales', 'active', '$actor')")
                it.execute(
                    "INSERT INTO lake_tables_managed (id, namespace_id, name, status, policy_json, profile_id, created_by) " +
                        "VALUES ('${UUID.randomUUID()}', '$namespace', 'trips', 'active', '{}', " +
                        "(SELECT id FROM lake_profiles WHERE is_default), '$actor')",
                )
            }
            underLease(connection) {
                connection
                    .prepareStatement(
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, " +
                            "previous_metadata_location, iceberg_type) VALUES (?, 'sales', 'trips', ?, NULL, 'TABLE')",
                    ).use { ps ->
                        ps.setString(1, LAKE)
                        ps.setString(2, location)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        }
    }

    /**
     * The split classes' startup, in this order: (a) the scratch database; (b) the MinIO, started and seeded, then the OIDC
     * stub; (c) ONE setup instance through [boot] with context A's properties and no application name — its Flyway migrates
     * the database and its boot creates the managed datasource row — kept up for the class, as context A is up beside these
     * tests in [ManagedLakeReadsE2eTest]; (d) [ensureReaderFixture]: table `sales.trips` at CURRENT with its profile, status
     * active. Prints one `FAMILY_SETUP` line with its seconds (#982).
     */
    fun start() {
        val started = System.nanoTime()
        scratchDatabase.value
        minioContainer.value
        oidcStub.value
        setup = boot(maxAgeSeconds = A_MAX_AGE_SECONDS, tickSeconds = A_TICK_SECONDS)
        ensureReaderFixture()
        println("FAMILY_SETUP class=${owner.simpleName} seconds=${secondsSince(started)}")
    }

    /**
     * The split classes' teardown, every step attempted even when an earlier one throws: the instances booted through
     * [boot] that are still open (newest first), the setup instance, the OIDC stub, the MinIO (stopped). After a failed or
     * partial [start] it closes what exists. The scratch database is left; the next creation under its name drops it.
     * Prints one `FAMILY_TEARDOWN` line with its seconds; the first failure is rethrown, the others suppressed into it.
     */
    override fun close() {
        val started = System.nanoTime()
        val failures = mutableListOf<Throwable>()
        val attempt = { step: () -> Unit -> runCatching(step).onFailure { failures += it } }
        booted.reversed().filter { it !== setup && it.isActive }.forEach { context -> attempt { context.close() } }
        setup?.let { context -> attempt { context.close() } }
        if (oidcStub.isInitialized()) attempt { oidc.close() }
        if (minioContainer.isInitialized()) attempt { minio.stop() }
        println("FAMILY_TEARDOWN class=${owner.simpleName} seconds=${secondsSince(started)}")
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    /** What [observeRetirementAndClose] saw; [assertInBound] is H's tight fits and the record's bound. */
    internal class ReapTimeline(
        val builtAt: Instant,
        val expired: List<Line>,
        val hardClosed: List<Line>,
        val ended: Boolean,
        val run: LongRead,
        val label: String,
    ) {
        val text: String
            get() =
                "built=$builtAt expired=${expired.map { "${it.at} ${it.thread}" }} " +
                    "hard_closed=${hardClosed.map { "${it.at} ${it.thread}" }} " +
                    "ended=${run.endedAt.get()} outcome=${run.outcome.get()} bound=${lifetimeBound()}"

        fun assertInBound() {
            withClue("$label: $text") {
                expired.size shouldBe 1
                expired.single().thread shouldBe REAPER_THREAD
                (expired.single().at >= builtAt.plusSeconds(H_MAX_AGE_SECONDS)) shouldBe true
                hardClosed.size shouldBe 1
                hardClosed.single().thread shouldBe REAPER_THREAD
                ended shouldBe true
                run.outcome.get().startsWith("failed:") shouldBe true
                Duration.between(builtAt, run.endedAt.get().shouldNotBeNull()).toMillis() shouldBeLessThanOrEqual
                    lifetimeBound().toMillis()
            }
        }
    }

    /** One captured line: its instant, thread and formatted text. */
    internal data class Line(
        val at: Instant,
        val thread: String,
        val text: String,
    )

    /** The long read on its own thread; [outcome] is `completed:<count>` or `failed:<class>: <message>`. */
    internal class LongRead(
        internal val statement: java.sql.PreparedStatement,
    ) {
        val ended = CountDownLatch(1)
        val endedAt = AtomicReference<Instant?>(null)
        val outcome = AtomicReference("running")
        val thread =
            Thread({
                try {
                    statement.executeQuery().use { rs ->
                        rs.next()
                        outcome.set("completed:${rs.getLong(1)}")
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    outcome.set("failed:${e.javaClass.simpleName}: ${e.message?.take(MESSAGE_CHARS)}")
                } finally {
                    endedAt.set(Instant.now())
                    ended.countDown()
                }
            }, "managed-long-read")

        fun start() = thread.start()
    }

    companion object {
        internal const val LAKE = "lake"
        internal const val BUCKET = "dp-lake-it"
        internal const val METADATA = "s3://$BUCKET/iceberg/trips_iceberg/metadata"
        internal const val CURRENT = "$METADATA/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"

        /** A: a short age bound for proof (a), the tick below it (LakeProperties refuses a tick at or above the bound). */
        internal const val A_MAX_AGE_SECONDS = 6L
        internal const val A_TICK_SECONDS = 5L

        /** B: the shipped age bound, so within this test only the tick can retire B's generation. */
        internal const val B_MAX_AGE_SECONDS = 900L

        internal const val REAPER_CADENCE_SECONDS = 5L
        internal const val BOUND_SLACK_SECONDS = 3L
        internal const val WATCH_MILLIS = 100L

        /** F (#818b B): a tick longer than the test, so only an explicit retire builds a new generation. */
        internal const val LIFECYCLE_TICK_SECONDS = 600L

        /**
         * H (#818c C): a short age bound and ceiling, so the test crosses both in seconds, the tick below the age (the
         * binder refuses one at or above it). LakeRules admits it: 12 + 2 × 7 + 6 + 92 + 10 = 134 ≤ 7 × 3600 (#818d).
         * I1–I3 run the same profile.
         */
        internal const val H_MAX_AGE_SECONDS = 12L
        internal const val H_TICK_SECONDS = 10L
        internal const val H_CEILING_SECONDS = 6L

        /**
         * The reaper delay the record's bound adds twice (#818d: cadence 5 + 2 × pass bound 1); integration-tests
         * cannot see web's constant, so a literal, never read back from the class under test.
         */
        internal const val REAPER_DELAY_SECONDS = 7L

        /** #818d — the reaper's own thread, and the jobs' shared one, by the spec's names. */
        internal const val REAPER_THREAD = "dp-reaper"

        /** 2 000⁴ rows: minutes of work, so nothing but the close can end it inside the bound. */
        internal const val H_TRIPS = "$LAKE.sales.trips"
        internal const val H_LONG_QUERY = "SELECT count(*) FROM $H_TRIPS a, $H_TRIPS b, $H_TRIPS c, $H_TRIPS d"

        /** The parent logger of the pool manager's and registry's lines (datasources) and the reaper's count (web). */
        internal const val PRODUCT_LOGGERS = "co.datapipelines"
        internal const val EXECUTING_BUDGET_SECONDS = 60L
        internal const val EXECUTING_GAP_MILLIS = 500L
        internal const val EXECUTING_CPU_MIN_MILLIS = 200L
        internal const val CLEANUP_JOIN_MS = 30_000L
        internal const val MESSAGE_CHARS = 200
        internal const val MINIO_PORT = 9000
        internal const val WRITER_ACCESS = "dpwriter818"

        /** `FAMILY_SETUP` and `FAMILY_TEARDOWN` print seconds from a monotonic clock (#982). */
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * H's and the reader profile's ceilings: the general one as before, and since #824 the MANAGED one too, which is
         * the ceiling a retired managed generation is actually held to; both at [H_CEILING_SECONDS], as the cases expect.
         */
        internal fun hCeilings(): List<Pair<String, String>> =
            listOf(
                "datapipelines.datasources.retire-ceiling-seconds" to H_CEILING_SECONDS.toString(),
                "datapipelines.datasources.managed-retire-ceiling-seconds" to H_CEILING_SECONDS.toString(),
            )

        internal fun readerPool(reader: ApplicationContext): Any {
            val registry = reader.getBean("datasourceRegistry")
            val type = Class.forName("co.datapipelines.datasources.DatasourceRegistry")
            val datasource = type.getMethod("get", String::class.java).invoke(registry, LAKE)
            return requireNotNull(
                type.getMethod("poolFor", Class.forName("co.datapipelines.datasources.Datasource")).invoke(registry, datasource),
            )
        }

        internal fun readerGeneration(reader: ApplicationContext): UUID {
            val pool = readerPool(reader)
            val id = Class.forName("co.datapipelines.datasources.pooling.ConnectionPool").getMethod("getGeneration").invoke(pool) as String
            return UUID.fromString(id)
        }

        /** Waits for the reaper's retirement of the generation built at [builtAt], then for the close to end [run]. */
        internal fun observeRetirementAndClose(
            reader: ConfigurableApplicationContext,
            capture: SnapshotListAppender,
            run: LongRead,
            builtAt: Instant,
            label: String,
        ): ReapTimeline {
            val retireBy = builtAt.plusSeconds(H_MAX_AGE_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
            // The live-map removal and its log are separate observations on the reaper thread.
            awaitUntil(retireBy, "$label: the reaper retires the generation with no lease") {
                generationBuiltAt(reader) == null &&
                    linesOf(capture, "event=lake.generation_expired").any {
                        "max_age_seconds=$H_MAX_AGE_SECONDS " in it.text && "trigger=reaper" in it.text && it.at >= builtAt
                    }
            }
            val expired =
                linesOf(capture, "event=lake.generation_expired").filter {
                    "max_age_seconds=$H_MAX_AGE_SECONDS " in it.text && "trigger=reaper" in it.text && it.at >= builtAt
                }
            val retiredAt = expired.firstOrNull()?.at ?: Instant.now()
            val closeBy = retiredAt.plusSeconds(H_CEILING_SECONDS + REAPER_CADENCE_SECONDS + BOUND_SLACK_SECONDS)
            val ended = run.ended.await(Duration.between(Instant.now(), closeBy).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            val hardClosed =
                linesOf(capture, "event=datasource.pool_hard_closed").filter {
                    "ceiling_seconds=$H_CEILING_SECONDS " in it.text && it.at >= builtAt
                }
            return ReapTimeline(builtAt, expired, hardClosed, ended, run, label)
        }

        /** The staleness budget; these CPU-query regressions also finish inside it, unlike a trickling reader. */
        internal fun lifetimeBound(): Duration = Duration.ofSeconds(H_MAX_AGE_SECONDS + 2 * REAPER_DELAY_SECONDS + H_CEILING_SECONDS)

        internal fun linesOf(
            capture: SnapshotListAppender,
            prefix: String,
        ): List<Line> = capture.events().filter { it.formattedMessage.startsWith(prefix) }.map(::lineOf)

        private fun lineOf(event: ILoggingEvent): Line = Line(event.instant, event.threadName, event.formattedMessage)

        /** A lease on [context]'s live managed generation, through the registry's own entry points (by name). */
        internal fun leaseManaged(context: ApplicationContext): Connection {
            val registry = context.getBean("datasourceRegistry")
            val registryType = Class.forName("co.datapipelines.datasources.DatasourceRegistry")
            val datasource = registryType.getMethod("get", String::class.java).invoke(registry, LAKE).shouldNotBeNull()
            val pool =
                registryType.getMethod("poolFor", Class.forName("co.datapipelines.datasources.Datasource")).invoke(registry, datasource)
            return Class
                .forName("co.datapipelines.datasources.pooling.ConnectionPool")
                .getMethod("leaseConnection")
                .invoke(pool) as Connection
        }

        /** The CPU nanoseconds [run]'s thread burned once two reads apart show it rising: the engine is executing. */
        internal fun awaitBurning(run: LongRead): Long {
            val threads = ManagementFactory.getThreadMXBean()
            val until = System.nanoTime() + Duration.ofSeconds(EXECUTING_BUDGET_SECONDS).toNanos()
            while (System.nanoTime() < until) {
                val before = threads.getThreadCpuTime(run.thread.threadId())
                Thread.sleep(EXECUTING_GAP_MILLIS)
                val after = threads.getThreadCpuTime(run.thread.threadId())
                withClue("the query ended before it was seen executing: ${run.outcome.get()}") { run.ended.count shouldBe 1L }
                if (before >= 0 && after - before >= Duration.ofMillis(EXECUTING_CPU_MIN_MILLIS).toNanos()) return after
            }
            error("the query thread never burned CPU within $EXECUTING_BUDGET_SECONDS s")
        }

        internal fun awaitUntil(
            deadline: Instant,
            what: String,
            condition: () -> Boolean,
        ) {
            while (Instant.now().isBefore(deadline)) {
                if (condition()) return
                Thread.sleep(WATCH_MILLIS)
            }
            withClue("$what by $deadline") { condition() shouldBe true }
        }

        /** The registry's own clock for its live managed pool, read by name: integration-tests reaches app only. */
        internal fun generationBuiltAt(context: ApplicationContext): Instant? {
            val registry = context.getBean("datasourceRegistry")
            return registry.javaClass.getMethod("generationBuiltAt", String::class.java).invoke(registry, LAKE) as Instant?
        }

        /** The registry's own retire, by name (the hook #799's settle and an operator use); true when a pool was live. */
        internal fun retireManagedPool(context: ApplicationContext): Boolean {
            val registry = context.getBean("datasourceRegistry")
            return registry.javaClass.getMethod("retirePool", String::class.java).invoke(registry, LAKE) as Boolean
        }

        /** The V53 fence: a catalog write needs a live table lease and its token set in the same transaction. */
        internal fun underLease(
            connection: Connection,
            leaseNames: List<String> = listOf("sales.trips"),
            write: () -> Unit,
        ) {
            val token = UUID.randomUUID()
            val operation = UUID.randomUUID()
            leaseNames.forEach { leaseName -> lease(connection, leaseName, operation, token) }
            connection.autoCommit = false
            try {
                connection
                    .prepareStatement("SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)")
                    .use { ps ->
                        ps.setString(1, token.toString())
                        ps.setString(2, operation.toString())
                        ps.executeQuery().close()
                    }
                write()
                connection.commit()
            } finally {
                connection.autoCommit = true
            }
        }

        /** One table lease row for [leaseName], held by [operation] with [token] (the fence matches both). */
        internal fun lease(
            connection: Connection,
            leaseName: String,
            operation: UUID,
            token: UUID,
        ) {
            connection
                .prepareStatement(
                    "INSERT INTO lake_leases " +
                        "(kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation, holder_instance) " +
                        "VALUES ('table', ?, ?, ?, now(), now() + interval '1 minute', " +
                        "now() + interval '1 minute', 'test', 'test') " +
                        "ON CONFLICT (kind, name) DO UPDATE SET operation_id = EXCLUDED.operation_id, token = EXCLUDED.token, " +
                        "expires_at = EXCLUDED.expires_at, deadline_at = EXCLUDED.deadline_at",
                ).use { ps ->
                    ps.setString(1, leaseName)
                    ps.setObject(2, operation)
                    ps.setObject(3, token)
                    ps.executeUpdate()
                }
        }

        internal fun systemActor(connection: Connection): UUID =
            connection.createStatement().use { st ->
                st.executeQuery("SELECT created_by FROM datasources WHERE name = '$LAKE'").use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }

        private fun secondsSince(started: Long): String =
            String.format(Locale.ROOT, "%.1f", (System.nanoTime() - started) / NANOS_PER_SECOND)
    }
}
