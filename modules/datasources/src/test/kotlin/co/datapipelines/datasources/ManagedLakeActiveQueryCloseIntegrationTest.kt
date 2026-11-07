package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.datasources.pooling.LakeInstanceDataSource
import co.datapipelines.datasources.pooling.LakeInstanceOwner
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.util.DriverDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * #818b D (dp-lake record §10, proof (c)) — an EXECUTING query on a retired managed generation is stopped by the
 * production close path, observed rather than inferred.
 *
 * The managed generation is real: [DefaultDatasourceRegistry] over the Postgres metadata row, a Hikari pool of
 * duplicates of one DuckDB owner, the §15a lock, and a view over the checked-in Iceberg fixture on MinIO. The query is
 * a cross join of that view with itself, which runs for minutes, so it cannot complete on its own inside the
 * assertion bound. It runs on the physical DuckDB statement behind a real lease (the lease Hikari counts as in use),
 * and the "it is executing" evidence is that thread's CPU time rising between two reads before the retirement.
 *
 * Then the generation is retired (the registry's own entry point), the ceiling passes, and the test calls the
 * production reaper ([DefaultDatasourceRegistry.reapRetiredPools], the same call the `dp-reaper` reaper makes).
 * The query must end within [TERMINATION_BOUND] of the reap call. What else could end it is ruled out: the query
 * has no statement timeout (`Statement.setQueryTimeout` is never called), the test never interrupts the
 * thread, and the cleanup that cancels the statement runs only after the assertion.
 *
 * Falsified by skipping the close in `ConnectionPoolManager.reapRetiring`'s ceiling branch: the query is then still
 * running at the bound, so the termination assertion fails.
 *
 * The second case is the guard of #818c (record §10 RULING 4 amended 2026-11-05, item 3; it replaces 818b's claim-2
 * characterization, which showed that nothing but a later lease retired an over-age generation). With no later
 * borrower, no freshness tick and no invalidation, the reaper's own pass retires the executing reader's generation
 * once it is past its age bound (the first reap after `built + age`: `expired = 1`, the live generation gone, the
 * query still running, since the lease is held), and the next reap past that retirement's ceiling hard-closes it and
 * the query ends. No `poolFor` is called after the first one, so no lease judges the age. The borrower half of the
 * old characterization is dropped: the lease path's retirement is `ConnectionPoolManagerGenerationAgeTest`'s, and a
 * borrower here would only hide whether the reaper retired on its own. Falsified by removing the retirement from
 * `ConnectionPoolManager.reapRetiring`: the first reap then retires nothing.
 *
 * The third case is #818d's (record §10 RULING 4, second amendment, item 6): proof (c) where Hikari's shutdown cannot
 * reach the reader in time, a connection creation hung in the pool's `getConnection` — see its own KDoc.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedLakeActiveQueryCloseIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @Test
    fun `an executing managed query is terminated by the reaper's hard close at the ceiling - proof c`() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        val owner = actor()
        val registry =
            DefaultDatasourceRegistry(
                DatasourceRepository(jdbc),
                testEncryptor(),
                lakeTables = { emptyList() },
                retireCeiling = CEILING,
                managedLake = { listOf(TRIPS) },
                maxGenerationAge = Duration.ofMinutes(MAX_AGE_MINUTES),
            )
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull()).shouldBeInstanceOf<HikariConnectionPool>()
            val builtAt = registry.generationBuiltAt(LAKE).shouldNotBeNull()
            val lease = pool.leaseConnection()
            // The Hikari proxy unwraps to the physical driver connection (the author-SQL guard sits above it and
            // refuses unwrap): the statement is the driver's own, with no statement timeout set.
            val physical = pool.poolConnectionOf(lease).unwrap(java.sql.Connection::class.java)
            val statement = physical.prepareStatement(LONG_QUERY)
            val run = RunningQuery(statement)
            try {
                run.start()
                val cpu = awaitExecuting(run)
                val retiredAt = Instant.now()
                registry.retirePool(LAKE) shouldBe true
                val deadline = retiredAt.plus(CEILING)
                // The query is still running when the deadline passes: what ends it below is the close, not completion.
                Thread.sleep(Duration.between(Instant.now(), deadline).toMillis().coerceAtLeast(0) + DEADLINE_MARGIN_MS)
                withClue("the query must still be executing at the ceiling") { run.ended.count shouldBe 1L }
                val reapStarted = Instant.now()
                val outcome = registry.reapRetiredPools()
                val reapReturned = Instant.now()
                val ended = run.ended.await(TERMINATION_BOUND.toMillis(), TimeUnit.MILLISECONDS)
                val endedAt = run.endedAt.get()
                println(
                    "ACTIVE_QUERY_CLOSE built=$builtAt executing_cpu_ns=$cpu retired=$retiredAt deadline=$deadline " +
                        "reap_started=$reapStarted reap_returned=$reapReturned hard_closed=${outcome.hardClosed} " +
                        "ended=$endedAt outcome=${run.outcome.get()}",
                )
                withClue("the reaper's close must stop the executing query within $TERMINATION_BOUND of the reap call") {
                    outcome.hardClosed shouldBe 1
                    ended shouldBe true
                    Duration.between(reapStarted, endedAt.shouldNotBeNull()).toMillis() shouldBeLessThanOrEqual TERMINATION_BOUND.toMillis()
                    // Terminated, not completed: a completed count would carry a row.
                    run.outcome.get().startsWith("failed:") shouldBe true
                }
            } finally {
                // Cleanup only after the assertion: a planted (still running) query is cancelled here.
                runCatching { statement.cancel() }
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
            }
        } finally {
            registry.shutdown()
        }
    }

    @Test
    @Suppress("LongMethod") // one timeline is the witness: build, burn, retiring reap, deadline, closing reap, end
    fun `with no later borrower the reaper retires an executing reader's generation past its age bound and closes it`() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        val owner = actor()
        val registry =
            DefaultDatasourceRegistry(
                DatasourceRepository(jdbc),
                testEncryptor(),
                lakeTables = { emptyList() },
                retireCeiling = CEILING,
                managedLake = { listOf(TRIPS) },
                maxGenerationAge = SHORT_AGE,
            )
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull()).shouldBeInstanceOf<HikariConnectionPool>()
            val builtAt = registry.generationBuiltAt(LAKE).shouldNotBeNull()
            val lease = pool.leaseConnection()
            val statement = pool.poolConnectionOf(lease).unwrap(java.sql.Connection::class.java).prepareStatement(LONG_QUERY)
            val run = RunningQuery(statement)
            try {
                run.start()
                awaitExecuting(run)
                // Past the age bound, with no lease, no tick and no invalidation: only the reaper's pass can retire it.
                val ageBound = builtAt.plus(SHORT_AGE)
                Thread.sleep(Duration.between(Instant.now(), ageBound).toMillis().coerceAtLeast(0) + DEADLINE_MARGIN_MS)
                val retiringReapAt = Instant.now()
                val retiring = registry.reapRetiredPools()
                val liveAfterRetire = registry.generationBuiltAt(LAKE)
                val runningAfterRetire = run.ended.count == 1L
                val deadline = retiringReapAt.plus(CEILING)
                Thread.sleep(Duration.between(Instant.now(), deadline).toMillis().coerceAtLeast(0) + DEADLINE_MARGIN_MS)
                // Still executing at the ceiling: what ends it below is the close, not completion.
                val runningAtCeiling = run.ended.count == 1L
                val closingReapAt = Instant.now()
                val closing = registry.reapRetiredPools()
                val ended = run.ended.await(TERMINATION_BOUND.toMillis(), TimeUnit.MILLISECONDS)
                val endedAt = run.endedAt.get()
                println(
                    "REAPER_RETIRES_NO_BORROWER built=$builtAt age=$SHORT_AGE ceiling=$CEILING age_bound=$ageBound " +
                        "retiring_reap=$retiringReapAt expired=${retiring.expired} hard_closed_first=${retiring.hardClosed} " +
                        "live_after_retire=$liveAfterRetire running_after_retire=$runningAfterRetire " +
                        "running_at_ceiling=$runningAtCeiling closing_reap=$closingReapAt hard_closed=${closing.hardClosed} " +
                        "ended=$endedAt outcome=${run.outcome.get()}",
                )
                assertAll(
                    { withClue("the first reap past the age bound retires the generation") { retiring.expired shouldBe 1 } },
                    { withClue("its lease is held, so the first reap closes nothing") { retiring.hardClosed shouldBe 0 } },
                    { withClue("the retired generation is no longer the live one") { liveAfterRetire shouldBe null } },
                    { runningAfterRetire shouldBe true },
                    { runningAtCeiling shouldBe true },
                    { withClue("the reap past the ceiling hard-closes it") { closing.hardClosed shouldBe 1 } },
                    { closing.expired shouldBe 0 },
                    { ended shouldBe true },
                    {
                        Duration.between(closingReapAt, endedAt.shouldNotBeNull()).toMillis() shouldBeLessThanOrEqual
                            TERMINATION_BOUND.toMillis()
                    },
                    { run.outcome.get().startsWith("failed:") shouldBe true },
                )
            } finally {
                runCatching { statement.cancel() }
                run.thread.join(CLEANUP_JOIN_MS)
                runCatching { lease.close() }
            }
        } finally {
            registry.shutdown()
        }
    }

    /**
     * #818d — proof (c) with Hikari's shutdown held: a connection creation hangs in the pool's `getConnection` (a
     * stand-in over the generation's [LakeInstanceDataSource] that blocks once armed), so `HikariPool.shutdown()`
     * first waits on its add-connection executor for the pool's login timeout, which Hikari derives from
     * `connectionTimeout` — here [HUNG_CONNECTION_TIMEOUT], longer than [HTTP_HYGIENE_TEST_BUDGET_SECONDS], as a datasource's
     * own pool setting may make it — before it aborts any leased connection. The reader is the engine's own work (an
     * in-memory range sum on a duplicate of the generation's owner: minutes of CPU) on a real lease, seen executing
     * by its thread's CPU time. The pool is retired in a real [ConnectionPoolManager] with a zero ceiling, and the pass
     * hands the close to a thread of its own, as the application's deadline closer does.
     *
     * The query must end within [HTTP_HYGIENE_TEST_BUDGET_SECONDS] of the pass: interrupt-first reaches it before Hikari's
     * first wait. Falsified by removing interrupt-first from `HikariConnectionPool.close`: the reader then waits behind
     * the hung creation, and the bound passes with the query still running.
     */
    @Test
    @Suppress("LongMethod") // one timeline is the witness: lease, executing, hung creation, pass, end
    fun `a reader stops within stalled-store hygiene budget of the close while Hikari's shutdown waits on a hung creation - proof c`() {
        val creationEntered = CountDownLatch(1)
        val releaseCreation = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val owner = LakeInstanceOwner.open(duckdbMemory(), HUNG) {}
        val lakeSource = LakeInstanceDataSource(owner, sessionInit = emptyList())
        val standIn =
            object : DataSource by lakeSource {
                override fun getConnection(): java.sql.Connection {
                    if (armed.get()) {
                        creationEntered.countDown()
                        releaseCreation.await()
                    }
                    return lakeSource.connection
                }
            }
        val pool = HikariConnectionPool(HUNG, hungHikari(standIn), owner, Dialect.LAKE)
        val manager =
            ConnectionPoolManager(
                poolFactory = { pool },
                retireCeiling = Duration.ZERO,
                closer = { retired -> Thread(retired::close, "test-pool-close").apply { isDaemon = true }.start() },
            )
        manager.poolFor(Datasource(name = HUNG, displayName = HUNG, dialect = Dialect.LAKE, jdbcUrl = "jdbc:duckdb::memory:"))
        val lease = pool.leaseConnection()
        val statement = pool.poolConnectionOf(lease).unwrap(java.sql.Connection::class.java).prepareStatement(ENGINE_QUERY)
        val run = RunningQuery(statement)
        try {
            run.start()
            val cpu = awaitExecuting(run)
            armed.set(true)
            Thread({ runCatching { pool.leaseConnection() } }, "test-borrower").apply { isDaemon = true }.start()
            withClue("Hikari's creator must be inside the hung getConnection") {
                creationEntered.await(CREATION_WAIT.toMillis(), TimeUnit.MILLISECONDS) shouldBe true
            }
            manager.retire(HUNG) shouldBe true
            val passStarted = Instant.now()
            val outcome = manager.reapRetiring()
            val ended = run.ended.await(HTTP_HYGIENE_TEST_BUDGET_SECONDS, TimeUnit.SECONDS)
            val endedAt = run.endedAt.get()
            println(
                "HUNG_CREATION_CLOSE executing_cpu_ns=$cpu pass_started=$passStarted hard_closed=${outcome.hardClosed} " +
                    "ended=$endedAt hikari_closed=${pool.isClosed} outcome=${run.outcome.get()}",
            )
            withClue("the reader must stop within stalled-store hygiene budget (${HTTP_HYGIENE_TEST_BUDGET_SECONDS} s) of the pass") {
                outcome.hardClosed shouldBe 1
                ended shouldBe true
                Duration.between(passStarted, endedAt.shouldNotBeNull()).seconds shouldBeLessThanOrEqual HTTP_HYGIENE_TEST_BUDGET_SECONDS
                run.outcome.get().startsWith("failed:") shouldBe true
            }
        } finally {
            // Cleanup only after the assertion: the hung creation is released and a planted (running) query cancelled.
            releaseCreation.countDown()
            runCatching { statement.cancel() }
            run.thread.join(CLEANUP_JOIN_MS)
            runCatching { lease.close() }
            manager.close()
        }
    }

    private fun duckdbMemory(): DataSource = DriverDataSource("jdbc:duckdb::memory:", "org.duckdb.DuckDBDriver", Properties(), null, null)

    private fun hungHikari(source: DataSource): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                dataSource = source
                minimumIdle = 0
                maximumPoolSize = 2
                connectionTimeout = HUNG_CONNECTION_TIMEOUT.toMillis()
                initializationFailTimeout = -1 // no connection at construction: the test drives every creation
                poolName = HUNG
            },
        )

    /**
     * Returns the CPU nanoseconds the query's thread has burned once two reads [EXECUTING_CPU_GAP] apart show it
     * rising by at least [EXECUTING_CPU_MIN] while the statement has not returned: the engine is executing on it,
     * not waiting on the store. (The driver's own progress counter reads 0 here: the engine's progress tracking is
     * off and the generation's §15a lock forbids switching it on, so the thread's CPU time is the observation.)
     */
    private fun awaitExecuting(run: RunningQuery): Long {
        val threads = ManagementFactory.getThreadMXBean()
        val until = System.nanoTime() + EXECUTING_BUDGET.toNanos()
        while (System.nanoTime() < until) {
            val before = threads.getThreadCpuTime(run.thread.threadId())
            Thread.sleep(EXECUTING_CPU_GAP.toMillis())
            val after = threads.getThreadCpuTime(run.thread.threadId())
            withClue("the query ended before it was seen executing: ${run.outcome.get()}") { run.ended.count shouldBe 1L }
            if (before >= 0 && after - before >= EXECUTING_CPU_MIN.toNanos()) return after
        }
        error("the query thread never burned $EXECUTING_CPU_MIN of CPU per $EXECUTING_CPU_GAP within $EXECUTING_BUDGET")
    }

    /** The query on its own thread; [outcome] is `completed:<count>` or `failed:<class>: <message>`. */
    private class RunningQuery(
        private val statement: java.sql.PreparedStatement,
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
            }, "managed-active-query")

        fun start() = thread.start()
    }

    private fun actor(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO users (email, display_name, provider, provider_subject) " +
                    "VALUES ('managed-close-it@example.com', 'Managed close IT', 'google', 'sub-managed-close-it') RETURNING id",
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private fun managed() =
        Datasource(
            name = LAKE,
            displayName = "Managed lake",
            description = "The server's managed lake.",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            username = MINIO_USER,
            credentialKind = CredentialKind.PASSWORD,
            secret = MINIO_PASSWORD,
            properties =
                DatasourceProperties(
                    dialect =
                        mapOf(
                            "catalog.kind" to "s3",
                            MANAGED_DIALECT_KEY to "true",
                            "region" to "us-east-1",
                            "endpoint" to "localhost:${minio.getMappedPort(MINIO_PORT)}",
                            "url_style" to "path",
                            "memory_limit" to "512MB",
                        ),
                ),
            isReadonly = true,
        )

    private companion object {
        const val HTTP_HYGIENE_TEST_BUDGET_SECONDS = 92L

        const val LAKE = "lake"
        const val BUCKET = "dp-lake-it"
        const val MINIO_PORT = 9000
        const val MINIO_USER = "minioadmin"
        const val MINIO_PASSWORD = "minioadmin"
        const val MAX_AGE_MINUTES = 15L
        const val METADATA = "s3://$BUCKET/iceberg/trips_iceberg/metadata"
        const val CURRENT = "$METADATA/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"

        /** 2 000⁴ rows: minutes of work, so nothing but the close can end it inside the bound. */
        const val LONG_QUERY = "SELECT count(*) FROM trips a, trips b, trips c, trips d"

        val TRIPS = ManagedLakeTable(listOf(LAKE, "sales"), "trips", "iceberg", metadataLocation = CURRENT, status = "active")

        /** A short ceiling, so the test waits seconds; the production default is 90 s. */
        val CEILING: Duration = Duration.ofSeconds(2)

        /** The reaper case's age bound: short, so the test crosses age + ceiling in seconds. */
        val SHORT_AGE: Duration = Duration.ofSeconds(3)

        /** How long after the reap call the query must have ended. */
        val TERMINATION_BOUND: Duration = Duration.ofSeconds(15)

        val EXECUTING_BUDGET: Duration = Duration.ofSeconds(60)
        val EXECUTING_CPU_GAP: Duration = Duration.ofMillis(500)
        val EXECUTING_CPU_MIN: Duration = Duration.ofMillis(200)
        const val DEADLINE_MARGIN_MS = 250L
        const val CLEANUP_JOIN_MS = 30_000L
        const val MESSAGE_CHARS = 200

        /** #818d's hung-creation case: its own generation name and the engine-only reader. */
        const val HUNG = "lake_hung_creation"
        const val ENGINE_QUERY = "SELECT sum(i) FROM range(1000000000000) t(i)"

        /** Longer than stalled-store hygiene budget, so a reader left to Hikari's shutdown would outlive the bound. */
        val HUNG_CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(HTTP_HYGIENE_TEST_BUDGET_SECONDS + 60)
        val CREATION_WAIT: Duration = Duration.ofSeconds(10)

        @Container
        @JvmStatic
        val minio: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(LakeConnectivityIntegrationTest.MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))

        /** The checked-in Iceberg fixture, uploaded key-for-key: its metadata records absolute `s3://dp-lake-it/` URIs. */
        @BeforeAll
        @JvmStatic
        fun seedBucket() {
            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:${minio.getMappedPort(MINIO_PORT)}"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build()
                .use { client ->
                    client.createBucket { it.bucket(BUCKET) }
                    val fixture =
                        Path.of(
                            checkNotNull(
                                ManagedLakeActiveQueryCloseIntegrationTest::class.java.getResource("/lake-fixture/iceberg"),
                            ).toURI(),
                        )
                    Files.walk(fixture).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { file ->
                            client.putObject({ it.bucket(BUCKET).key("iceberg/${fixture.relativize(file)}") }, RequestBody.fromFile(file))
                        }
                    }
                }
        }
    }
}
