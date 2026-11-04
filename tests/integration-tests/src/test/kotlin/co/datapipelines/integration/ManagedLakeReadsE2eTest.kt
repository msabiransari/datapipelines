package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * #818 (dp-lake P5, design record §6, §10, §15a): the managed lake's READ side in the booted application, with
 * `datapipelines.lake.enabled=true` against a real object store (an own MinIO) and the real catalog schema.
 *
 * Four boots share one metadata database (a scratch database, so no other suite's cached context sees the managed
 * row) and one store:
 *
 * - **A**, this class's `@SpringBootTest` context: the managed row is created and granted at boot; REST and UI writes
 *   to it are refused (`datasource.managed`); a workspace created after boot is granted within one tick; and
 *   **proof (a)**: with no catalog movement (so the tick retires nothing), every lease is served by a generation
 *   younger than `reads.max-generation-age-seconds` (A runs a 6 s bound) and the generation is replaced.
 * - **B**, a second full boot after a table was defined with no registry row: B's registrar BACKFILLS the row, and
 *   **A4**: the catalog pointer is moved by a direct UPDATE under a lease (simulating #799's `settle`, which P5 does
 *   not own) — nothing is published on the invalidation channel, B's age bound is 900 s, so only B's 2 s freshness
 *   tick can make B's next lease read the new snapshot.
 * - **C**, a boot whose `lake.name` is held by an ordinary datasource: refused with `lake.boot.datasource_name_taken`.
 * - **D**, a boot with the lake disabled: the managed row stays, and a read of a managed table is refused
 *   `lake.table.unavailable` with reason `lake.disabled`.
 *
 * The Iceberg fixture is the checked-in `lake-fixture/iceberg/trips_iceberg` (its metadata bakes the bucket name
 * `dp-lake-it`, see [LakeMinioE2eTest]); its `00001` snapshot holds fewer rows than `00002`'s 2 000, so a row count
 * names the snapshot a lease read. This suite runs its OWN MinIO: [LakeMinioE2eTest] creates `dp-lake-it` on the
 * shared one unconditionally, and its writer credential must differ from the username here (the test asserts the
 * secret never reaches `properties_json`).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ManagedLakeReadsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var contextA: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the boot creates the managed row, grants it, and refuses every REST and UI write to it`() {
        seedAuthRows()
        val row = managedRow().shouldNotBeNull()
        assertAll(
            { row.dialect shouldBe "LAKE" },
            { row.readonly shouldBe true },
            { row.jdbcUrl shouldBe "jdbc:duckdb::memory:" },
            { row.properties shouldContain "\"managed\"" },
            { row.properties shouldNotContain WRITER_SECRET },
            { grantedWorkspaces() shouldContain DEFAULT_WORKSPACE_ID },
        )
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/datasources/$LAKE")
            .then()
            .statusCode(200)

        val put =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                // A well-formed edit: a malformed body is a 400 before any datasource is consulted.
                .body(
                    """
                    {"display_name": "Renamed lake", "dialect": "LAKE", "jdbc_url": "jdbc:duckdb::memory:",
                     "credential": {"kind": "none"}}
                    """.trimIndent(),
                ).`when`()
                .put("/api/v1/datasources/$LAKE")
        val delete =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .delete("/api/v1/datasources/$LAKE")
        listOf("PUT" to put, "DELETE" to delete).forEach { (verb, response) ->
            withClue("$verb answered ${response.body.asString()}") {
                response.statusCode shouldBe 409
                response.jsonPath().getString("error.code") shouldBe MANAGED_CODE
            }
        }
        val ui =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.URLENC)
                .formParam("jdbcUrl", "jdbc:duckdb::memory:")
                .formParam("credentialKind", "none")
                .formParam("displayName", "Renamed lake")
                .`when`()
                .post("/partials/datasources/$LAKE")
                .then()
                .statusCode(400)
                .extract()
                .asString()
        ui shouldContain "is the managed lake"
        managedRow().shouldNotBeNull().displayName shouldBe row.displayName
    }

    @Test
    @Order(2)
    fun `a workspace created after boot is granted the managed lake within one tick`() {
        val workspace = UUID.randomUUID()
        metadata {
            it.createStatement().execute(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$workspace', 'later_ws', 'Later')",
            )
        }

        awaitTrue(Duration.ofSeconds(A_TICK_SECONDS * 4)) { workspace in grantedWorkspaces() }
    }

    @Test
    @Order(3)
    fun `a table defined while the instance was down is backfilled by the next boot's registrar`() {
        defineManagedTable(PREVIOUS)
        registryLocation().shouldBe(null)

        instanceB = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS)
        portB = Integer.parseInt(checkNotNull(instanceB?.environment?.getProperty("local.server.port")))

        registryLocation() shouldBe PREVIOUS
    }

    @Test
    @Order(4)
    fun `A4 - a catalog move nobody published reaches B's next lease through B's freshness tick alone`() {
        pipelineId = createReadPipeline()
        val before = countOn(portB)
        before shouldBeInRange 1..(ICEBERG_ROWS - 1)

        // #799's settle, simulated: P5 does not own it. A direct UPDATE under a lease publishes nothing.
        moveCatalogPointer(PREVIOUS, CURRENT)

        val deadline = System.nanoTime() + Duration.ofSeconds(B_TICK_SECONDS * 10).toNanos()
        var after = before
        while (System.nanoTime() < deadline && after != ICEBERG_ROWS) {
            Thread.sleep(POLL_MILLIS)
            after = countOn(portB)
        }
        withClue("B kept reading the old snapshot; its age bound is $B_MAX_AGE_SECONDS s, so only the tick could move it") {
            after shouldBe ICEBERG_ROWS
        }
        // The lagging projection was never the source: the registry row still names the old file.
        registryLocation() shouldBe PREVIOUS
    }

    @Test
    @Order(5)
    fun `proof a - with nothing moving, every lease is served by a generation younger than the age bound`() {
        val built = mutableSetOf<Instant>()
        val until = System.nanoTime() + Duration.ofSeconds(A_MAX_AGE_SECONDS * 3).toNanos()
        while (System.nanoTime() < until) {
            val leaseStarted = Instant.now()
            countOn(port) shouldBe ICEBERG_ROWS
            val builtAt = generationBuiltAt(contextA).shouldNotBeNull()
            withClue("generation built $builtAt served a lease started $leaseStarted with a ${A_MAX_AGE_SECONDS}s bound") {
                builtAt.isAfter(leaseStarted.minusSeconds(A_MAX_AGE_SECONDS)) shouldBe true
            }
            built += builtAt
            Thread.sleep(POLL_MILLIS)
        }
        built.size shouldBeGreaterThanOrEqual 2
    }

    @Test
    @Order(6)
    fun `a boot whose lake name an ordinary datasource holds is refused with the catalogued id`() {
        registerH2Datasource(TAKEN_NAME)

        val refused = shouldThrowAny { boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, name = TAKEN_NAME).close() }

        generateSequence(refused) { it.cause }.joinToString(" | ") { it.message.orEmpty() } shouldContain
            "lake.boot.datasource_name_taken"
        dialectOf(TAKEN_NAME) shouldBe "H2"
    }

    @Test
    @Order(7)
    fun `with the lake disabled the managed row stays and a managed table read is refused lake disabled`() {
        val disabled = boot(maxAgeSeconds = B_MAX_AGE_SECONDS, tickSeconds = B_TICK_SECONDS, enabled = false)
        try {
            val portD = Integer.parseInt(checkNotNull(disabled.environment.getProperty("local.server.port")))
            managedRow().shouldNotBeNull()
            val failed =
                executeOn(portD)
                    .firstOrNull { it.first == "node_failed" }
                    ?.second
                    .shouldNotBeNull()
                    .toString()
            assertAll(
                { failed shouldContain "lake.table.unavailable" },
                { failed shouldContain "lake.disabled" },
            )
        } finally {
            disabled.close()
        }
    }

    // ------------------------------------------------------------- reads through the pipeline surface

    private fun countOn(targetPort: Int): Int {
        val events = executeOn(targetPort)
        val failed = events.firstOrNull { it.first == "node_failed" }?.second
        withClue("node_failed payload: $failed") {
            events.lifecycleNames() shouldContainExactly
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        }
        val executionId = events.first().second["execution_id"].asText()
        val rows: List<List<Any?>> =
            given()
                .port(targetPort)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .get("data.rows")
        return rows
            .single()
            .single()
            .toString()
            .toInt()
    }

    private fun executeOn(targetPort: Int): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(EXECUTION_BUDGET) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$targetPort/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    private fun createReadPipeline(): String {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "$TEMPLATE_ID", "dialect": "LAKE", "display_name": "Managed lake count",
                 "description": "818 managed read", "imports": [],
                 "body": "SELECT count(*) AS n FROM $LAKE.sales.trips"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "test/managed_lake_count", "nodes": [{
                    "id": "count_trips", "description": "Count the managed table",
                    "type": "DQL", "source": "$LAKE",
                    "template": {"id": "$TEMPLATE_ID", "version": 1},
                    "output": {"target": "caller"}, "depends_on": []}]}
                """.trimIndent().replace("\n", " "),
            ).`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    private fun registerH2Datasource(name: String) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$name", "display_name": "Not the lake", "dialect": "H2",
                 "jdbc_url": "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** The registry's own clock for its live managed pool, read by name: integration-tests reaches app only. */
    private fun generationBuiltAt(context: ApplicationContext): Instant? {
        val registry = context.getBean("datasourceRegistry")
        return registry.javaClass.getMethod("generationBuiltAt", String::class.java).invoke(registry, LAKE) as Instant?
    }

    // ------------------------------------------------------------- the metadata database, directly

    private data class ManagedRow(
        val dialect: String,
        val readonly: Boolean,
        val jdbcUrl: String,
        val properties: String,
        val displayName: String,
    )

    private fun managedRow(): ManagedRow? =
        metadata { connection ->
            connection
                .prepareStatement(
                    "SELECT dialect, is_readonly, jdbc_url, CAST(properties_json AS text), display_name FROM datasources " +
                        "WHERE name = ? AND NOT is_deleted",
                ).use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            ManagedRow(
                                rs.getString(1),
                                rs.getBoolean(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                            )
                        }
                    }
                }
        }

    private fun dialectOf(name: String): String? =
        metadata { connection ->
            connection.prepareStatement("SELECT dialect FROM datasources WHERE name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun grantedWorkspaces(): Set<UUID> =
        metadata { connection ->
            connection.prepareStatement("SELECT workspace_id FROM datasource_workspaces WHERE datasource_name = ?").use { ps ->
                ps.setString(1, LAKE)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getObject(1, UUID::class.java) else null }.toSet() }
            }
        }

    private fun registryLocation(): String? =
        metadata { connection ->
            connection
                .prepareStatement("SELECT location FROM lake_tables WHERE datasource_id = ? AND name = 'trips' AND origin = 'managed'")
                .use { ps ->
                    ps.setString(1, LAKE)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }

    /** A namespace, its lifecycle row and the catalog pointer (written under a lease, so the revision trigger runs). */
    private fun defineManagedTable(location: String) {
        metadata { connection ->
            val actor = systemActor(connection)
            val namespace = UUID.randomUUID()
            connection.createStatement().use {
                it.execute("INSERT INTO lake_namespaces (id, name, status, created_by) VALUES ('$namespace', 'sales', 'active', '$actor')")
                it.execute(
                    "INSERT INTO lake_tables_managed (id, namespace_id, name, status, policy_json, created_by) " +
                        "VALUES ('${UUID.randomUUID()}', '$namespace', 'trips', 'active', '{}', '$actor')",
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

    private fun moveCatalogPointer(
        from: String,
        to: String,
    ) {
        metadata { connection ->
            underLease(connection) {
                connection
                    .prepareStatement(
                        "UPDATE iceberg_tables SET metadata_location = ?, previous_metadata_location = ? " +
                            "WHERE catalog_name = ? AND table_namespace = 'sales' AND table_name = 'trips' AND metadata_location = ?",
                    ).use { ps ->
                        ps.setString(1, to)
                        ps.setString(2, from)
                        ps.setString(3, LAKE)
                        ps.setString(4, from)
                        ps.executeUpdate() shouldBe 1
                    }
            }
        }
    }

    /** The V53 fence: a catalog write needs a live table lease and its token set in the same transaction. */
    private fun underLease(
        connection: Connection,
        write: () -> Unit,
    ) {
        val token = UUID.randomUUID()
        val operation = UUID.randomUUID()
        connection
            .prepareStatement(
                "INSERT INTO lake_leases " +
                    "(kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation, holder_instance) " +
                    "VALUES ('table', 'sales.trips', ?, ?, now(), now() + interval '1 minute', " +
                    "now() + interval '1 minute', 'test', 'test') " +
                    "ON CONFLICT (kind, name) DO UPDATE SET operation_id = EXCLUDED.operation_id, token = EXCLUDED.token, " +
                    "expires_at = EXCLUDED.expires_at, deadline_at = EXCLUDED.deadline_at",
            ).use { ps ->
                ps.setObject(1, operation)
                ps.setObject(2, token)
                ps.executeUpdate()
            }
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)").use { ps ->
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

    private fun systemActor(connection: Connection): UUID =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT created_by FROM datasources WHERE name = '$LAKE'").use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }

    private fun awaitTrue(
        budget: Duration,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + budget.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        condition() shouldBe true
    }

    companion object {
        private const val LAKE = "lake"
        private const val TAKEN_NAME = "lake_taken"
        private const val MANAGED_CODE = "datasource.managed"
        private const val TEMPLATE_ID = "test/managed_lake_count.sql"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val DEFAULT_WORKSPACE_ID = UUID.fromString(DEFAULT_WORKSPACE)

        private const val BUCKET = "dp-lake-it"
        private const val METADATA = "s3://$BUCKET/iceberg/trips_iceberg/metadata"
        private const val PREVIOUS = "$METADATA/00001-7c81713e-1543-46ec-b6f4-6b3875c8b8c3.metadata.json"
        private const val CURRENT = "$METADATA/00002-dba28368-bd68-452e-bd41-3bac13f6d77b.metadata.json"
        private const val ICEBERG_ROWS = 2000

        /** A: a short age bound for proof (a), the tick below it (LakeProperties refuses a tick at or above the bound). */
        private const val A_MAX_AGE_SECONDS = 6L
        private const val A_TICK_SECONDS = 5L

        /** B: the shipped age bound, so within this test only the tick can retire B's generation. */
        private const val B_MAX_AGE_SECONDS = 900L
        private const val B_TICK_SECONDS = 2L

        private const val POLL_MILLIS = 500L
        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private const val MINIO_PORT = 9000
        private const val WRITER_ACCESS = "dpwriter818"

        /** Generated per run: no literal credential in a fixture. Distinct from the username, so the dialect check bites. */
        private val WRITER_SECRET = "w-" + UUID.randomUUID().toString().replace("-", "")

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000818"
        private val ADMIN_SESSION get() = E2eSession.jwt(SECRET, ADMIN_USER_ID, "e2e-managed-lake@datapipelines.test")

        private val oidc = OidcDiscoveryStub()

        /** The OTHER instances this suite boots; B lives for the class, C and D only for their test. */
        private var instanceB: ConfigurableApplicationContext? = null
        private var portB: Int = 0
        private var pipelineId: String = ""

        /** The suite's own metadata database: every boot migrates or reuses it; no other suite's context touches it. */
        private val database by lazy { SharedE2e.scratchDatabase("managed_lake_reads") }

        /** Its own MinIO (see the class KDoc), seeded with the Iceberg fixture under `dp-lake-it` on first touch. */
        private val minio: GenericContainer<*> by lazy {
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

        private val endpoint get() = "http://localhost:${minio.getMappedPort(MINIO_PORT)}"

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
                    val fixture = Path.of(checkNotNull(ManagedLakeReadsE2eTest::class.java.getResource("/lake-fixture/iceberg")).toURI())
                    Files.walk(fixture).use { paths ->
                        paths.filter { it.isRegularFile() && it.name != ".DS_Store" }.forEach { file ->
                            s3.putObject({ it.bucket(BUCKET).key("iceberg/${file.relativeTo(fixture)}") }, RequestBody.fromFile(file))
                        }
                    }
                }
        }

        private fun <T> metadata(block: (Connection) -> T): T =
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

        /** After A's Flyway (a static @BeforeAll runs before it): idempotent. */
        private fun seedAuthRows() {
            metadata { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-managed-lake@datapipelines.test', 'E2E Managed Lake', 'test',
                                'e2e-managed-lake-sub', TRUE, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
        }

        /** Every property a boot needs, as `key to value`; A takes them as dynamic properties, B, C and D as args. */
        private fun bootProperties(
            maxAgeSeconds: Long,
            tickSeconds: Long,
            name: String = LAKE,
            enabled: Boolean = true,
        ): List<Pair<String, String>> =
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
                "datapipelines.lake.enabled" to enabled.toString(),
                "datapipelines.lake.name" to name,
                "datapipelines.lake.storage.bucket" to BUCKET,
                "datapipelines.lake.storage.region" to "us-east-1",
                "datapipelines.lake.storage.endpoint" to endpoint,
                "datapipelines.lake.storage.path-style" to "true",
                "datapipelines.lake.storage.access-key" to WRITER_ACCESS,
                "datapipelines.lake.storage.secret-key" to WRITER_SECRET,
                "datapipelines.lake.reads.max-generation-age-seconds" to maxAgeSeconds.toString(),
                "datapipelines.lake.reads.freshness-tick-seconds" to tickSeconds.toString(),
            )

        // Command-line args, not builder `.properties(...)`: those are DEFAULT properties and application.yml's
        // `${SPRING_DATASOURCE_URL}` would override them; args win (DatasourcePoolInvalidationE2eTest's note).
        private fun boot(
            maxAgeSeconds: Long,
            tickSeconds: Long,
            name: String = LAKE,
            enabled: Boolean = true,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java).run(
                "--server.port=0",
                *bootProperties(maxAgeSeconds, tickSeconds, name, enabled).map { (k, v) -> "--$k=$v" }.toTypedArray(),
            )

        @AfterAll
        @JvmStatic
        fun closeInstances() {
            instanceB?.close()
            oidc.close()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            bootProperties(A_MAX_AGE_SECONDS, A_TICK_SECONDS).forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
