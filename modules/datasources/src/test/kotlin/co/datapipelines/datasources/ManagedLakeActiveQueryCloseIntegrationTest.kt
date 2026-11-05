package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.HikariConnectionPool
import co.datapipelines.typesystem.Dialect
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * production reaper ([DefaultDatasourceRegistry.reapRetiredPools], the same call the `dp-scheduled` reaper makes).
 * The query must end within [TERMINATION_BOUND] of the reap call. What else could end it is ruled out: the query
 * has no statement timeout (`Statement.setQueryTimeout` is never called), the test never interrupts the
 * thread, and the cleanup that cancels the statement runs only after the assertion.
 *
 * Falsified by skipping the close in `ConnectionPoolManager.reapRetiring`'s ceiling branch: the query is then still
 * running at the bound, so the termination assertion fails.
 *
 * The second case is the brief's claim 2, a characterization of what IS, kept while the owner rules on it: with no
 * later borrower, no freshness tick and no invalidation, nothing retires a generation past its age bound (the bound
 * is judged only at a lease), so the reaper has nothing to close and an executing reader runs on past age + ceiling.
 * Only a later borrower's lease retires it, and the close then follows at the ceiling.
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
    fun `claim 2 - with no later borrower nothing retires an executing reader's generation past its age bound`() {
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
            val datasource = registry.get(LAKE).shouldNotBeNull()
            val pool = registry.poolFor(datasource).shouldBeInstanceOf<HikariConnectionPool>()
            val builtAt = registry.generationBuiltAt(LAKE).shouldNotBeNull()
            val lease = pool.leaseConnection()
            val statement = pool.poolConnectionOf(lease).unwrap(java.sql.Connection::class.java).prepareStatement(LONG_QUERY)
            val run = RunningQuery(statement)
            try {
                run.start()
                awaitExecuting(run)
                // Past age + ceiling, with no lease, no tick and no invalidation: the reaper finds nothing retired.
                val quietUntil = builtAt.plus(SHORT_AGE).plus(CEILING)
                Thread.sleep(Duration.between(Instant.now(), quietUntil).toMillis().coerceAtLeast(0) + DEADLINE_MARGIN_MS)
                val quiet = registry.reapRetiredPools()
                val stillRunningAt = Instant.now()
                val stillRunning = run.ended.count == 1L
                val stillLive = registry.generationBuiltAt(LAKE)
                // A later borrower: its lease judges the age bound, retires the generation, and the close follows.
                val borrowedAt = Instant.now()
                registry.poolFor(datasource)
                Thread.sleep(CEILING.toMillis() + DEADLINE_MARGIN_MS)
                val reapStarted = Instant.now()
                val closing = registry.reapRetiredPools()
                val ended = run.ended.await(TERMINATION_BOUND.toMillis(), TimeUnit.MILLISECONDS)
                println(
                    "CLAIM2_NO_BORROWER built=$builtAt age=$SHORT_AGE ceiling=$CEILING quiet_reap_hard_closed=${quiet.hardClosed} " +
                        "still_running_at=$stillRunningAt still_running=$stillRunning live_generation_built=$stillLive " +
                        "borrowed=$borrowedAt reap_started=$reapStarted hard_closed=${closing.hardClosed} " +
                        "ended=${run.endedAt.get()} outcome=${run.outcome.get()}",
                )
                assertAll(
                    { quiet.hardClosed shouldBe 0 },
                    { stillRunning shouldBe true },
                    { stillLive shouldBe builtAt },
                    { closing.hardClosed shouldBe 1 },
                    { ended shouldBe true },
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

        /** Claim 2's age bound: short, so the test crosses age + ceiling in seconds. */
        val SHORT_AGE: Duration = Duration.ofSeconds(3)

        /** How long after the reap call the query must have ended. */
        val TERMINATION_BOUND: Duration = Duration.ofSeconds(15)

        val EXECUTING_BUDGET: Duration = Duration.ofSeconds(60)
        val EXECUTING_CPU_GAP: Duration = Duration.ofMillis(500)
        val EXECUTING_CPU_MIN: Duration = Duration.ofMillis(200)
        const val DEADLINE_MARGIN_MS = 250L
        const val CLEANUP_JOIN_MS = 30_000L
        const val MESSAGE_CHARS = 200

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
