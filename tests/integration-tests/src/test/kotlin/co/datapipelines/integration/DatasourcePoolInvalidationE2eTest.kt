package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * §5.7 cross-instance pool invalidation, live (050/R1, ARCH-AUDIT M3): **two application
 * contexts** — instance A (this test's `@SpringBootTest` context, serving HTTP on
 * [port]) and instance B (a second full application booted beside it) — against ONE Postgres
 * and ONE Redis. Both instances are driven through their HTTP surfaces only: this suite may
 * depend on `:modules:app` alone (module-structure §5.11), and the black-box drive is the
 * honest M3 story anyway.
 *
 * The defect the channel closes: a pool built by B from the OLD row keeps serving the old
 * database until B restarts. The proof is behavioral, not log-based:
 *
 * 1. An execution on B against datasource `mi2_shared` returns marker value `'one'` — B's
 *    pool is now warm, built from the old row.
 * 2. A PUTs the datasource to point at the second H2 database. A's own eviction is
 *    synchronous; the Redis channel is B's only signal.
 * 3. An execution on B now returns `'two'` — within seconds, not at B's next restart.
 *    Without the channel (subscriber disabled) the final assertion fails: B keeps answering
 *    `'one'` forever, which is exactly M3.
 *
 * A single-instance test cannot go red on M3 — the synchronous local eviction produces the
 * same observable on one instance. That is why this suite boots a second context.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class DatasourcePoolInvalidationE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `a datasource edited on A is served from the new row by B's next execution`() {
        // After context A's Flyway has run (a @BeforeAll runs BEFORE that): idempotent.
        seedAuthRows()
        registerDatasource(DS, "jdbc:h2:mem:$H2_ONE;DB_CLOSE_DELAY=-1")
        createTemplate(TEMPLATE_SQL, "SELECT v FROM marker")
        val pipelineId = createPipeline()

        // B's first execution warms B's pool from the OLD row and reads the OLD database.
        executeOnB(pipelineId) shouldBe "one"

        // The edit crosses A's HTTP surface: A evicts its own pool synchronously and publishes
        // the name; B's subscriber evicts, and B's next lease rebuilds from the new row.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"display_name": "MI2 repointed", "dialect": "H2",
                 "jdbc_url": "jdbc:h2:mem:$H2_TWO;DB_CLOSE_DELAY=-1", "username": "$H2_USER"}
                """.trimIndent(),
            ).`when`()
            .put("/api/v1/datasources/$DS")
            .then()
            .statusCode(200)

        // Pub/sub is asynchronous: keep executing on B until it reaches the new database, with
        // a deadline far under "B's next restart" — the pre-050 behaviour never gets there.
        val deadline = System.nanoTime() + PROPAGATION_BUDGET
        var marker: String? = null
        while (System.nanoTime() < deadline) {
            marker = executeOnB(pipelineId)
            if (marker == "two") break
            Thread.sleep(POLL_MILLIS)
        }
        marker shouldBe "two"
    }

    @Test
    fun `an execution mid-query on A survives a delete issued on B and completes`() {
        // 094's whole point. Before it, a delete `close()`d the pool at once and HikariCP
        // ABORTED the connections still in use once its shutdown grace elapsed — the execution
        // running against that datasource lost its connection and failed. Retirement means the
        // pool leaves the map immediately and closes only once the statement finishes.
        //
        // The datasource is the metadata Postgres itself, because the query has to be genuinely
        // SLOW and H2 has no sleep function: `pg_sleep` is the same lever the two-instance shell
        // harness pulls.
        seedAuthRows()
        // Testcontainers' own URL carries driver query parameters; §5.6's URL guard refuses
        // several of them, so the datasource is registered with the bare host/port/database form.
        registerDatasource(
            SLOW_DS,
            metadataJdbcUrl(),
            dialect = "POSTGRES",
            username = postgres.username,
            password = postgres.password,
        )
        createTemplate(SLOW_TEMPLATE, "SELECT 'slept' AS v FROM (SELECT pg_sleep($SLEEP_SECONDS)) s", dialect = "POSTGRES")
        val pipelineId = createPipeline(name = "test/mi2_slow_read", datasource = SLOW_DS, template = SLOW_TEMPLATE)

        // Warm A's pool, so the execution below is served by a pool that already exists — the
        // state a real mid-query delete finds.
        executeOn(port, pipelineId) shouldBe SLEPT

        val running = Executors.newSingleThreadExecutor()
        try {
            val execution = running.submit<String?> { executeOn(port, pipelineId) }
            // Let the statement actually reach the database before anything is deleted. The
            // assertion below is what makes this a real mid-query test rather than a race:
            // the pipeline must still be RUNNING when the delete lands.
            awaitRunningSleepStatement()

            // The in-use guard is real: the pipeline has to go first, which is exactly the
            // sequence an operator retiring a datasource follows. 101: retiring the pipeline
            // is a session-only lifecycle verb (discard the release — the entity goes
            // DISCARDED — its inert pins stop blocking), outside this key-driven harness — so
            // the metadata DB is flipped to the same derived state directly; the DATASOURCE
            // delete below remains the real REST surface under test.
            retirePipelineMetadata(pipelineId)
            deleteOn(portB, "/api/v1/datasources/$SLOW_DS")

            // THE ASSERTION: the execution that was mid-statement finishes, with its rows.
            execution.get(SLOW_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe SLEPT
        } finally {
            running.shutdownNow()
        }

        // …and afterwards the datasource is gone — asserted on B, the instance that took the
        // DELETE, deliberately. A's READ surface serves the §6.3 metadata cache and may keep
        // answering 200 for up to its 60 s TTL: that is the documented cross-instance bound and
        // predates this round. It is harmless because every path that would USE the datasource
        // reads live past that cache (044 F4) — A's pool is already retired by the invalidation
        // message, and A's executor refuses the name before any lease. Waiting out a 60 s TTL
        // here would buy a slower test and no new fact.
        given()
            .port(portB)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/datasources/$SLOW_DS")
            .then()
            .statusCode(404)
    }

    @Test
    fun `a peer that never received the invalidation catches up when it re-subscribes`() {
        // The missed-message backstop (094). Pub/sub has no replay: a message published while
        // an instance was disconnected is gone forever, and this round's ruling is explicitly NO
        // retry queue. So the row is changed with NO message published at all — by writing the
        // metadata DB directly, which is exactly the state a peer is in when it was disconnected
        // at publish time — and then B's subscriber connection is killed, forcing the
        // re-subscription whose callback reconciles.
        seedAuthRows()
        registerDatasource(RECONCILE_DS, "jdbc:h2:mem:$H2_ONE;DB_CLOSE_DELAY=-1")
        createTemplate(RECONCILE_TEMPLATE, "SELECT v FROM marker")
        val pipelineId = createPipeline(name = "test/mi2_reconcile_read", datasource = RECONCILE_DS, template = RECONCILE_TEMPLATE)

        // B's pool is warm, built from the OLD row.
        executeOnB(pipelineId) shouldBe "one"

        // The row moves with nobody told. `updated_at` moves with it — that is the version the
        // reconcile compares against, and the reason this needs no Redis key of its own.
        repointDatasourceInDatabase(RECONCILE_DS, "jdbc:h2:mem:$H2_TWO;DB_CLOSE_DELAY=-1")

        // Force every pub/sub client to reconnect. B's listener container re-SUBSCRIBEs, its
        // callback runs reconcilePools(), the stale pool is retired, and B's next lease builds
        // from the new row. Without the reconcile B would serve `one` forever.
        killPubSubConnections()

        val deadline = System.nanoTime() + RECONCILE_BUDGET
        var marker: String? = null
        while (System.nanoTime() < deadline) {
            marker = executeOnB(pipelineId)
            if (marker == "two") break
            Thread.sleep(POLL_MILLIS)
        }
        marker shouldBe "two"
    }

    // ------------------------------------------------------------- HTTP on either instance

    /** Runs the pipeline on instance B and returns the marker value its result rows carry. */
    private fun executeOnB(pipelineId: String): String? = executeOn(portB, pipelineId)

    /** Runs the pipeline on [targetPort] and returns the single scalar its result rows carry. */
    private fun executeOn(
        targetPort: Int,
        pipelineId: String,
    ): String? {
        val events =
            assertTimeoutPreemptively(Duration.ofSeconds(SLOW_EXECUTION_TIMEOUT_SECONDS)) {
                consumeExecutionStream(targetPort, pipelineId)
            }
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        val executionId = events.first().second["execution_id"].asText()
        val result =
            given()
                .port(targetPort)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        val rows: List<List<Any?>> = result.get("data.rows")
        return rows.single().single().toString()
    }

    private fun consumeExecutionStream(
        targetPort: Int,
        pipelineId: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$targetPort/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("DP-Correlation-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper)
    }

    /** 101: the derived retired state (every version DISCARDED, pointer NULL), via SQL. */
    private fun retirePipelineMetadata(pipelineId: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    UPDATE pipeline_versions SET status = 'DISCARDED', discarded_at = NOW()
                     WHERE pipeline_id = '$pipelineId'
                    """.trimIndent(),
                )
                it.execute("UPDATE pipelines SET current_version = NULL WHERE id = '$pipelineId'")
            }
        }
    }

    private fun metadataJdbcUrl(): String = postgres.jdbcUrl.substringBefore('?')

    /** A DELETE on one instance, asserting the documented 204. */
    private fun deleteOn(
        targetPort: Int,
        path: String,
    ) {
        given()
            .port(targetPort)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .delete(path)
            .then()
            .statusCode(204)
    }

    /**
     * Blocks until the `pg_sleep` statement is visible in the metadata Postgres's own
     * `pg_stat_activity` — proof the execution is genuinely MID-QUERY, not merely submitted.
     * A test that deleted before the statement started would pass without testing anything.
     */
    private fun awaitRunningSleepStatement() {
        val deadline = System.nanoTime() + MID_QUERY_BUDGET
        while (System.nanoTime() < deadline) {
            if (liveSleepStatements() > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("the pg_sleep statement never reached the database — the mid-query window never opened")
    }

    /** How many `pg_sleep` statements the metadata Postgres is running right now. */
    private fun liveSleepStatements(): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND query NOT LIKE '%pg_stat_activity%'",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }
        }

    /**
     * Repoints a datasource by writing the metadata database DIRECTLY — no HTTP, so no
     * invalidation message is published to anyone. `updated_at` moves, which is the row version
     * the reconcile compares against.
     */
    private fun repointDatasourceInDatabase(
        name: String,
        jdbcUrl: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("UPDATE datasources SET jdbc_url = ?, updated_at = NOW() WHERE name = ?").use { ps ->
                ps.setString(1, jdbcUrl)
                ps.setString(2, name)
                ps.executeUpdate() shouldBe 1
            }
        }
    }

    /** Kills every pub/sub client, forcing both instances' listener containers to re-subscribe. */
    private fun killPubSubConnections() {
        redis.execInContainer("redis-cli", "CLIENT", "KILL", "TYPE", "pubsub").exitCode shouldBe 0
    }

    private fun registerDatasource(
        name: String,
        jdbcUrl: String,
        dialect: String = "H2",
        username: String = H2_USER,
        password: String = H2_PASSWORD,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$name", "display_name": "MI2 shared", "dialect": "$dialect",
                 "jdbc_url": "$jdbcUrl", "username": "$username", "password": "$password"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        sql: String,
        dialect: String = "H2",
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "MI2 E2E $id",
                 "description": "MI2 pool invalidation marker read", "imports": [],
                 "body": ${mapper.writeValueAsString(sql)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(
        name: String = "test/mi2_marker_read",
        datasource: String = DS,
        template: String = TEMPLATE_SQL,
    ): String {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"name": "$name", "nodes": [{
                        "id": "read_marker", "description": "Read the marker table",
                        "type": "DQL", "source": "$datasource",
                        "template": {"id": "$template", "version": 1},
                        "output": {"target": "caller"}, "depends_on": []}]}
                    """.trimIndent().replace("\n", " "),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        return response.jsonPath().getString("data.id")
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val API_KEY_HEADER = "DP-API-Key"

        /** Instance B — the second application context this suite boots beside its own. */
        private var instanceB: ConfigurableApplicationContext? = null

        /** Instance B's random HTTP port, read from its own environment after boot. */
        private var portB: Int = 0

        /**
         * Booted in `@BeforeAll` (static): with the containers running, the second context
         * starts before the first test and stays up for the whole class — instance B's pool
         * outlives individual test methods, exactly like a real peer instance.
         */
        @BeforeAll
        @JvmStatic
        fun bootInstanceB() {
            seedMarker(H2_ONE, "one")
            seedMarker(H2_TWO, "two")
            // Command-line args, not builder `.properties(...)`: those are DEFAULT properties
            // and application.yml's `${SPRING_DATASOURCE_URL}` would override them — args win.
            instanceB =
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .run(
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.datasource.url=${postgres.jdbcUrl}",
                        "--spring.datasource.username=${postgres.username}",
                        "--spring.datasource.password=${postgres.password}",
                        "--spring.data.redis.host=${redis.host}",
                        "--spring.data.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                        "--spring.data.redis.password=",
                        "--datapipelines.redis.host=${redis.host}",
                        "--datapipelines.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                        "--datapipelines.jwt.secret=$SECRET",
                        "--datapipelines.db.encryption-key=$SECRET",
                        "--datapipelines.auth.oidc.providers[0].name=google",
                        "--datapipelines.auth.oidc.providers[0].client-id=test-google-client-id",
                        "--datapipelines.auth.oidc.providers[0].client-secret=test-google-client-secret",
                        "--datapipelines.auth.oidc.providers[0].issuer-uri=${oidc.issuer}",
                        "--datapipelines.auth.oidc.providers[0].display-name=Test google",
                        "--datapipelines.auth.base-url=http://localhost:8080",
                    )
            portB = Integer.parseInt(checkNotNull(instanceB?.environment?.getProperty("local.server.port")))
        }

        @AfterAll
        @JvmStatic
        fun closeInstanceB() {
            instanceB?.close()
            oidc.close()
        }

        private fun seedMarker(
            h2Db: String,
            value: String,
        ) {
            DriverManager.getConnection("jdbc:h2:mem:$h2Db;DB_CLOSE_DELAY=-1", H2_USER, H2_PASSWORD).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE marker (v VARCHAR(10))")
                    statement.execute("INSERT INTO marker VALUES ('$value')")
                }
            }
        }

        private fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-mi2@datapipelines.test', 'E2E MI2', 'test', 'e2e-mi2-sub', TRUE, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
                val insertSql =
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING"
                connection.prepareStatement(insertSql).use { ps ->
                    ps.setString(1, ADMIN_KEY.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, ADMIN_KEY.name)
                    ps.setString(4, ADMIN_KEY.hash)
                    ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                    ps.executeUpdate()
                }
            }
        }

        /** The one datasource both instances touch — the M3 scenario's name. */
        private const val DS = "mi2_shared"
        private const val TEMPLATE_SQL = "test/mi2_read_marker.sql"

        // Two distinguishable in-memory "customer databases": the marker value each returns is
        // the identity of the pool that served the execution.
        private const val H2_ONE = "mi2_one"
        private const val H2_TWO = "mi2_two"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val POLL_MILLIS = 250L
        private const val PROPAGATION_BUDGET = 30_000_000_000L

        /** The slow datasource's fixtures — the metadata Postgres, read through `pg_sleep`. */
        private const val SLOW_DS = "mi2_slow"
        private const val SLOW_TEMPLATE = "test/mi2_slow.sql"

        /** Long enough that the delete lands mid-statement; far under the executor's own timeout. */
        private const val SLEEP_SECONDS = 5

        /** The one value the slow query returns — proof the ROWS came back, not just the stream. */
        private const val SLEPT = "slept"

        /** The SSE consumer's ceiling — the sleep plus room for a slow container. */
        private const val SLOW_EXECUTION_TIMEOUT_SECONDS = 60L

        /** How long the mid-query window may take to open before the test calls it a failure. */
        private const val MID_QUERY_BUDGET = 20_000_000_000L

        /** The reconnect-and-reconcile budget: Spring's container backs off before re-SUBSCRIBEing. */
        private const val RECONCILE_BUDGET = 60_000_000_000L

        /** The reconcile test's own fixtures, so it cannot collide with the edit test's row. */
        private const val RECONCILE_DS = "mi2_reconcile"
        private const val RECONCILE_TEMPLATE = "test/mi2_reconcile.sql"

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000002"
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-mi2-key", arrayOf("admin"))

        /** The module's shared Postgres — migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        // OWN Redis, deliberately not the shared one: this suite's subject is cross-instance
        // pub/sub, and Spring's context cache keeps EARLIER suites' contexts (and their
        // subscriptions) alive until JVM exit — a shared Redis would deliver this suite's
        // invalidations to stale listeners of suites that already finished.
        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") { SECRET }

            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
