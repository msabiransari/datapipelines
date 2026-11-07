package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.PoolCloser
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #818 (dp-lake P5, design record §10, §15a) — the managed lake datasource's generations against a real S3 (MinIO)
 * and the checked-in Iceberg fixture, through [DefaultDatasourceRegistry] and real Hikari pools:
 *
 * - A2: the view reads the CATALOG's metadata location while the registry projection names a file that does not
 *   exist; ten rebuilds, each preceded by a non-vacuity check that the projection is still stale (count printed).
 * - A4 check: a generation built from L1 (fixture metadata 00001), the catalog moved to L2 (00002), the tick retires
 *   it, and the next lease reads L2. The catalog move is the scripted port's answer here; the Postgres
 *   `iceberg_tables` UPDATE version of it is the web E2E.
 * - §15a (owner ruling 2026-11-04): a lease of a managed generation reaches no local file, by table function,
 *   `COPY` or `ATTACH`, and cannot turn the lock back; every lease, not just the first.
 * - A8 (proof (c)): a query on a retired managed generation still out at the ceiling is hard-closed.
 * - #818d: the HTTP request bounds are in force and locked on every lease, and a reader waiting on a STALLED store
 *   (a proxy that accepts and answers nothing) stops within `stalled-store hygiene budget` of its generation's hard close.
 * - A7: one rebuild timed at 1, 20 and 100 managed tables (`REBUILD_TIMING` lines in the report's system-out).
 * - The Iceberg extensions load from the plan under `catalog.kind = s3`, which by itself loads only httpfs + aws.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedLakeMinioIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var owner: UUID

    @Volatile private var catalogTables: List<ManagedLakeTable> = emptyList()

    /** The managed catalog port both the build and the tick read (the tick takes its catalog as an argument, #818b A). */
    private val catalog = ManagedLakeCatalog { catalogTables }

    /** The registry projection the planner must NOT read: a stale location, for every managed table. */
    @Volatile private var projection: List<LakeRegisteredTable> = emptyList()

    private val recorded = CopyOnWriteArrayList<Pair<String, String?>>()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES ('managed-it@example.com', 'Managed IT', 'google', 'sub-managed-it') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        catalogTables = emptyList()
        projection = emptyList()
        recorded.clear()
    }

    private fun registry(
        retireCeiling: Duration = Duration.ofSeconds(DEFAULT_CEILING_SECONDS),
        poolCloser: PoolCloser = PoolCloser.SYNCHRONOUS,
    ): DefaultDatasourceRegistry =
        DefaultDatasourceRegistry(
            DatasourceRepository(jdbc),
            testEncryptor(),
            lakeTables = { projection },
            lakeViewRecorder = { _, namespace, table, error -> recorded += (namespace + table).joinToString(".") to error },
            retireCeiling = retireCeiling,
            managedLake = catalog,
            maxGenerationAge = Duration.ofMinutes(MAX_AGE_MINUTES),
            poolCloser = poolCloser,
        )

    @Test
    fun `ten rebuilds read the catalog's location while the projection names a missing file`() {
        manage(table("trips", CURRENT))
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val counts =
                (1..TEN_REBUILDS).map { round ->
                    // Non-vacuity: the projection the planner must ignore is stale before every build.
                    withClue("round $round: the projection must still name the stale file") {
                        projection.single().location shouldBe STALE_LOCATION
                    }
                    val count = registry.leaseCount("SELECT count(*) FROM lake.sales.trips")
                    registry.retirePool(LAKE) shouldBe true
                    registry.reapRetiredPools()
                    count
                }
            println("MANAGED_REBUILDS count=${counts.size} rows=${counts.distinct()}")
            assertAll(
                { counts.size shouldBe TEN_REBUILDS },
                { counts.distinct() shouldBe listOf(ICEBERG_ROWS) },
                { recorded.filter { it.second != null }.shouldBeEmpty() },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `the tick retires a generation the catalog moved past, and the next lease reads the new snapshot`() {
        manage(table("trips", PREVIOUS))
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val l1 = registry.leaseCount("SELECT count(*) FROM trips")
            val firstGeneration = registry.liveManagedGeneration(LAKE).shouldNotBeNull()
            registry.refreshManagedGenerations(catalog) shouldBe 0

            catalogTables = listOf(table("trips", CURRENT))
            registry.refreshManagedGenerations(catalog) shouldBe 1
            val l2 = registry.leaseCount("SELECT count(*) FROM trips")
            assertAll(
                { l1 shouldBeInRange 1..(ICEBERG_ROWS - 1) },
                { l2 shouldBe ICEBERG_ROWS },
                { registry.liveManagedGeneration(LAKE).shouldNotBeNull() shouldNotBe firstGeneration },
                { registry.liveManagedGeneration(LAKE).shouldNotBeNull().locations["lake.sales.trips"] shouldBe CURRENT },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a managed lease reaches no local file and cannot unlock the configuration - on every lease`() {
        manage(table("trips", CURRENT))
        val local = Files.createTempDirectory("managed-lock-it")
        val parquet = local.resolve("secret.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { c ->
            c.createStatement().use { it.execute("COPY (SELECT 42 AS answer) TO '$parquet' (FORMAT PARQUET)") }
        }
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            // Two connections out at once: the lock is the engine's, so it holds on every duplicate.
            val first = pool.leaseConnection()
            val second = pool.leaseConnection()
            try {
                listOf(first, second).forEach { assertLockedDown(it, parquet, local) }
            } finally {
                second.close()
                first.close()
            }
            Files.exists(local.resolve("out.csv")) shouldBe false
        } finally {
            registry.shutdown()
            local.toFile().deleteRecursively()
        }
    }

    /**
     * The §15a lockdown as one leased [connection] sees it; the S3 view still serves through the same connection.
     *
     * #818d (record §10 RULING 4, second amendment, item 7): the silent-store HTTP settings are enforced
     * from are in force on the generation, and author SQL cannot set them back — `SET`, `SET SESSION`, `SET GLOBAL`
     * and `RESET` of each are refused by the configuration lock, so the term holds for every query a lease runs.
     */
    private fun assertLockedDown(
        connection: Connection,
        parquet: Path,
        local: Path,
    ) = assertAll(
        *(
            listOf<() -> Unit>(
                { refusal(connection, "SELECT * FROM read_parquet('$parquet')") shouldContain "disabled" },
                { refusal(connection, "COPY (SELECT 1) TO '${local.resolve("out.csv")}'") shouldContain "disabled" },
                { refusal(connection, "ATTACH '${local.resolve("x.duckdb")}' AS x") shouldContain "disabled" },
                { refusal(connection, "SET disabled_filesystems = ''") shouldContain "locked" },
                { refusal(connection, "SET lock_configuration = false") shouldContain "locked" },
                { count(connection, "SELECT count(*) FROM trips") shouldBe ICEBERG_ROWS },
            ) + httpBoundsLocked(connection)
        ).toTypedArray(),
    )

    /** Each HTTP bound holds its constant's value and refuses every way back to the engine's default. */
    private fun httpBoundsLocked(connection: Connection): List<() -> Unit> =
        HTTP_BOUNDS.flatMap { (setting, value) ->
            listOf<() -> Unit>(
                { count(connection, "SELECT CAST(current_setting('$setting') AS BIGINT)").toLong() shouldBe value },
                { refusal(connection, "SET $setting = 30") shouldContain "locked" },
                { refusal(connection, "SET SESSION $setting = 30") shouldContain "locked" },
                { refusal(connection, "SET GLOBAL $setting = 30") shouldContain "locked" },
                { refusal(connection, "RESET $setting") shouldContain "locked" },
            )
        }

    @Test
    fun `a query still out on a retired managed generation at the ceiling is hard-closed - proof c`() {
        manage(table("trips", CURRENT))
        val registry = registry(retireCeiling = Duration.ofSeconds(1))
        try {
            registry.syncManagedLake(managed(), owner)
            val held = registry.poolFor(registry.get(LAKE).shouldNotBeNull()).leaseConnection()
            count(held, "SELECT count(*) FROM trips") shouldBe ICEBERG_ROWS
            registry.retirePool(LAKE) shouldBe true
            Thread.sleep(CEILING_WAIT_MS)
            val outcome = registry.reapRetiredPools()
            assertAll(
                { outcome.hardClosed shouldBe 1 },
                // The hard-closed generation's connection cannot run another statement.
                { shouldThrow<SQLException> { count(held, "SELECT count(*) FROM trips") } },
            )
            runCatching { held.close() }
        } finally {
            registry.shutdown()
        }
    }

    /**
     * #818d (record §10 RULING 4, second amendment, item 7) — the stalled-store proof of `stalled-store hygiene budget`, on the
     * real reader: a managed generation built through [StallingTcpProxy] in front of MinIO, a lease's query sent into
     * a store that accepts and then answers nothing, the generation hard-closed at the ceiling while that query waits.
     * Interrupt-first lands on a thread inside the driver's native execute, blocked in DuckDB's HTTP retry loop (which
     * checks no interrupt), so the query ends only when the request chain the [LakeHttpBounds] allow has run out:
     * about 3 × 10 s + 0.1 s, inside [HTTP_HYGIENE_TEST_BUDGET_SECONDS]. Red with the bounds' `SET`s removed: the engine's
     * defaults hold the reader about 4 × 30 s + 0.5 s, past the term.
     */
    @Test
    fun `a reader waiting on a stalled store stops within stalled-store hygiene budget of its generation's hard close`() {
        manage(table("trips", CURRENT))
        StallingTcpProxy("localhost", minio.getMappedPort(MINIO_PORT)).use { proxy ->
            val registry = registry(retireCeiling = Duration.ZERO, poolCloser = threadPerClose())
            try {
                registry.syncManagedLake(managed(endpoint = proxy.endpoint), owner)
                val held = registry.poolFor(registry.get(LAKE).shouldNotBeNull()).leaseConnection()
                val reader = StalledReader(held)
                proxy.stall()
                reader.thread.start()
                withClue("the query must reach the store after the stall (non-vacuity), outcome=${reader.outcome}") {
                    proxy.awaitStalledRequest(STALL_WAIT_SECONDS) shouldBe true
                }
                val stallSeen = System.nanoTime()
                registry.retirePool(LAKE) shouldBe true
                val closeStarted = System.nanoTime()
                val outcome = registry.reapRetiredPools()
                val landedOn =
                    reader.thread.stackTrace
                        .take(STACK_FRAMES)
                        .joinToString(" < ")
                reader.thread.join(Duration.ofSeconds(READER_WAIT_SECONDS))
                val ended = reader.endedAt
                val fromClose = ended?.let { Duration.ofNanos(it - closeStarted) }
                val fromStall = ended?.let { Duration.ofNanos(it - stallSeen) }
                println(
                    "STALLED_STORE from_close_ms=${fromClose?.toMillis()} from_stall_ms=${fromStall?.toMillis()} " +
                        "hard_closed=${outcome.hardClosed} accepts_since_stall=${proxy.acceptsSinceStall.get()} " +
                        "stalled_requests=${proxy.stalledRequests.get()} outcome=\"${reader.outcome}\" landed_on=\"$landedOn\"",
                )
                withClue("from_close=$fromClose outcome=${reader.outcome} landed_on=$landedOn") {
                    assertAll(
                        { outcome.hardClosed shouldBe 1 },
                        { fromClose.shouldNotBeNull() shouldBeLessThanOrEqualTo Duration.ofSeconds(HTTP_HYGIENE_TEST_BUDGET_SECONDS) },
                        // Non-vacuity: the stall held the reader for at least one whole HTTP timeout after it began.
                        {
                            fromStall.shouldNotBeNull() shouldBeGreaterThanOrEqualTo
                                Duration.ofSeconds(LakeHttpBounds.HTTP_TIMEOUT_SECONDS)
                        },
                        { reader.outcome shouldStartWith "failed:" },
                    )
                }
                runCatching { held.close() }
            } finally {
                registry.shutdown()
            }
        }
    }

    /** One query on [connection] on its own thread, recording when and how it ended. */
    private class StalledReader(
        connection: Connection,
    ) {
        @Volatile var outcome: String = "running"

        @Volatile var endedAt: Long? = null

        val thread =
            Thread({
                outcome =
                    runCatching {
                        connection.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM trips").use { it.next() } }
                    }.fold({ "returned" }, { "failed:${it::class.simpleName}: ${it.message?.lineSequence()?.first()}" })
                endedAt = System.nanoTime()
            }, "test-stalled-reader").apply { isDaemon = true }
    }

    /** Each close on a thread of its own, as the application's deadline closer does (`dp-pool-close`). */
    private fun threadPerClose() = PoolCloser { pool -> Thread(pool::close, "test-pool-close").apply { isDaemon = true }.start() }

    @Test
    fun `an unutterable catalog location gets no view while the other tables serve, and an omission is logged not recorded`() {
        manage(
            table("trips", CURRENT),
            table("hostile", "s3://$BUCKET/iceberg/x'; ATTACH 'y"),
            table("archived", CURRENT).copy(status = "archived"),
        )
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val logs = capturingLogs { registry.leaseCount("SELECT count(*) FROM lake.sales.trips") shouldBe ICEBERG_ROWS }
            assertAll(
                {
                    recorded
                        .single {
                            it.first == "lake.sales.hostile"
                        }.second
                        .shouldNotBeNull() shouldContain "fails the registry's grammar"
                },
                { recorded.filter { it.first == "lake.sales.archived" }.shouldBeEmpty() },
                {
                    logs.any {
                        it.contains("event=lake.table_unavailable_omitted") && it.contains("table=lake.sales.archived") &&
                            it.contains("reason=\"status archived\"")
                    } shouldBe true
                },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `one rebuild timed at 1, 20 and 100 managed tables - the per-table view follow-up's input`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val timings =
                listOf(1, TWENTY_TABLES, HUNDRED_TABLES).associateWith { n ->
                    manage(*(1..n).map { table("t$it", CURRENT) }.toTypedArray())
                    // Warm-up build, then the timed rebuild: the measured build reuses no pool.
                    registry.leaseCount("SELECT count(*) FROM t1")
                    registry.retirePool(LAKE)
                    registry.reapRetiredPools()
                    val started = System.nanoTime()
                    val rows = registry.leaseCount("SELECT count(*) FROM t$n")
                    val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                    rows shouldBe ICEBERG_ROWS
                    registry.retirePool(LAKE)
                    registry.reapRetiredPools()
                    millis
                }
            timings.forEach { (n, ms) -> println("REBUILD_TIMING tables=$n ms=$ms") }
            recorded.filter { it.second != null }.shouldBeEmpty()
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ fixtures

    /** The catalog answers [tables]; the projection names the stale file for each. */
    private fun manage(vararg tables: ManagedLakeTable) {
        catalogTables = tables.toList()
        projection = tables.map { LakeRegisteredTable(it.namespace, it.name, "iceberg", STALE_LOCATION, managed = true) }
    }

    private fun table(
        name: String,
        location: String,
    ) = ManagedLakeTable(listOf(LAKE, "sales"), name, "iceberg", metadataLocation = location, status = "active")

    private fun managed(endpoint: String = "localhost:${minio.getMappedPort(MINIO_PORT)}") =
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
                            "endpoint" to endpoint,
                            "url_style" to "path",
                            "memory_limit" to "512MB",
                        ),
                ),
            isReadonly = true,
        )

    private fun DefaultDatasourceRegistry.leaseCount(sql: String): Int =
        poolFor(get(LAKE).shouldNotBeNull()).leaseConnection().use { count(it, sql) }

    private fun count(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }

    private fun refusal(
        connection: Connection,
        sql: String,
    ): String = shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }.message.orEmpty()

    private companion object {
        const val HTTP_HYGIENE_TEST_BUDGET_SECONDS = 92L

        /** #818d — the four engine settings and the constants the adapter sets them to (`LakeHttpBounds`). */
        val HTTP_BOUNDS: List<Pair<String, Long>> =
            listOf(
                "http_timeout" to LakeHttpBounds.HTTP_TIMEOUT_SECONDS,
                "http_retries" to LakeHttpBounds.HTTP_RETRIES.toLong(),
                "http_retry_wait_ms" to LakeHttpBounds.HTTP_RETRY_WAIT_MS,
                "http_retry_backoff" to LakeHttpBounds.HTTP_RETRY_BACKOFF.toLong(),
            )
        const val LAKE = "lake"
        const val BUCKET = "dp-lake-it"
        const val MINIO_PORT = 9000
        const val MINIO_USER = "minioadmin"
        const val MINIO_PASSWORD = "minioadmin"
        const val ICEBERG_ROWS = 2000
        const val TEN_REBUILDS = 10
        const val TWENTY_TABLES = 20
        const val HUNDRED_TABLES = 100
        const val MAX_AGE_MINUTES = 15L
        const val DEFAULT_CEILING_SECONDS = 90L
        const val CEILING_WAIT_MS = 1_500L
        const val NANOS_PER_MILLI = 1_000_000L
        const val STALL_WAIT_SECONDS = 30L
        const val READER_WAIT_SECONDS = 150L
        const val STACK_FRAMES = 3

        const val METADATA = "s3://$BUCKET/iceberg/trips_iceberg/metadata"
        const val PREVIOUS = "$METADATA/00001-7c81713e-1543-46ec-b6f4-6b3875c8b8c3.metadata.json"
        const val CURRENT = "$METADATA/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"
        const val STALE_LOCATION = "$METADATA/00009-stale-projection.metadata.json"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(LakeConnectivityIntegrationTest.MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))

        private var s3: S3Client? = null

        /** The checked-in Iceberg fixture, uploaded key-for-key: its metadata records absolute `s3://dp-lake-it/` URIs. */
        @BeforeAll
        @JvmStatic
        fun seedBucket() {
            val client =
                S3Client
                    .builder()
                    .endpointOverride(URI.create("http://localhost:${minio.getMappedPort(MINIO_PORT)}"))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .httpClient(
                        software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
                            .builder()
                            .build(),
                    ).build()
            s3 = client
            client.createBucket { it.bucket(BUCKET) }
            val fixture =
                Path.of(checkNotNull(ManagedLakeMinioIntegrationTest::class.java.getResource("/lake-fixture/iceberg")).toURI())
            Files.walk(fixture).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    client.putObject({ it.bucket(BUCKET).key("iceberg/${fixture.relativize(file)}") }, RequestBody.fromFile(file))
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            s3?.close()
        }
    }
}
