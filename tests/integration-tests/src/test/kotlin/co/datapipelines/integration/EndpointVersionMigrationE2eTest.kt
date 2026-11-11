package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText

/**
 * #786 §E — the in-place conversion of existing APIs at a real upgrade boot (owner ruling 2026-11-08: one server,
 * convert in place, no floor). [LegacyEndpointRowE2eTest]'s shape: its own PostgreSQL, sequential boots of the shipped
 * application, and pre-V58 rows inserted by SQL BETWEEN boots — an entity with its legacy columns and no version row,
 * exactly what the previous version wrote.
 *
 * - every creation path writes v1 (the seeder, REST create): the selector is legacy-only by construction (E.2);
 * - the conversion runs after Flyway and BEFORE the web server starts (the ordering witness reads the database from a
 *   `WebServerInitializedEvent` listener); a RELEASED current → v1 RELEASED serving the same rows; a DRAFT current →
 *   v1 DRAFT; enabled state, timeout and description kept; the pipeline's pointer unmoved; one report line each;
 * - a rerun boot is a no-op;
 * - E.0: an API whose pipeline has no current version or no versions refuses the boot with the list, nothing written;
 *   the operator's fix (delete the API) lets the next boot convert the rest;
 * - conversion racing a discard of the version it would pin, both orders, under the real G0 guard: a raw connection
 *   holds the workspace row (as a writer in flight would), both writers queue on it — observed with
 *   `pg_blocking_pids`, never timed — and the holder's commit releases them in queue order;
 * - the same two-connection race for an API draft REPIN against the discard of the version it moves to (A39): the
 *   repin first protects the version (the discard is refused as pinned), the discard first leaves nothing to repin
 *   onto (the repin is the native 400).
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndpointVersionMigrationE2eTest {
    private var context: ConfigurableApplicationContext? = null
    private var port: Int = 0
    private var demoId: UUID = UUID(0, 0)
    private var demoPipeline: UUID = UUID(0, 0)
    private var adminId: UUID = UUID(0, 0)
    private var adminJwt: String = ""
    private val versionlessAtServerStart = AtomicInteger(-1)

    @AfterAll
    fun close() {
        context?.close()
    }

    @Test
    @Order(1)
    fun `boot one - every creation path writes its v1, so nothing is version-less`() {
        context = boot()
        demoId = scalar("SELECT id FROM workspaces WHERE name = 'demo'")
        demoPipeline = scalar("SELECT id FROM pipelines WHERE workspace_id = '$demoId' AND name = '$DEMO_PIPELINE'")
        adminId = scalar("SELECT id FROM users WHERE email = '$ADMIN_EMAIL'")
        sql(
            "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                "VALUES ('$demoId', '$adminId', 'workspace_admin') ON CONFLICT DO NOTHING",
        )
        adminJwt = E2eSession.jwt(jwtSecret, adminId.toString(), ADMIN_EMAIL, "demo")

        session()
            .contentType(ContentType.JSON)
            .body("""{"path": "/e2emig/v1/native", "pipeline": "$DEMO_PIPELINE", "pipeline_version": 1}""")
            .post("/api/v1/endpoints")
            .then()
            .statusCode(201)

        withClue("the demo seeder and REST create both wrote v1 in the creating transaction") {
            scalar<Long>("SELECT COUNT(*) FROM published_endpoints") shouldBe 2L
            scalar<Long>(VERSIONLESS) shouldBe 0L
        }
    }

    @Test
    @Order(2)
    fun `boot two converts the pre-V58 rows before the server starts - exact pins, status by the pinned version`(output: CapturedOutput) {
        close()
        val draftOnly = pipelineCopy("test/e2emig_draft_only", current = 1, "DRAFT")
        legacyApi(CONVERTED_PATH, demoPipeline, timeout = 45, description = "Converted in place", enabled = true)
        legacyApi("/e2emig/v1/draft", draftOnly, timeout = 30, description = "", enabled = true)
        legacyApi("/e2emig/v1/off", demoPipeline, timeout = 30, description = "", enabled = false)
        scalar<Long>(VERSIONLESS) shouldBe 3L

        context = boot()

        withClue("ordering: the conversion finished before the web server started (E.1, 'before serving')") {
            versionlessAtServerStart.get() shouldBe 0
        }
        version(CONVERTED_PATH) shouldBe listOf("RELEASED", DEMO_PIPELINE, 1, 45, "Converted in place", 1, true)
        version("/e2emig/v1/draft") shouldBe listOf("DRAFT", "test/e2emig_draft_only", 1, 30, "", 1, true)
        version("/e2emig/v1/off") shouldBe listOf("RELEASED", DEMO_PIPELINE, 1, 30, "", 1, false)
        scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$demoPipeline'") shouldBe 1
        withClue("the converted API serves the same rows as the API it was published beside") {
            serve(CONVERTED_PATH).statusCode shouldBe 200
            serve(CONVERTED_PATH).jsonPath().getList<Any>("data") shouldBe serve(DEMO_PATH).jsonPath().getList<Any>("data")
            serve("/e2emig/v1/off").statusCode shouldBe 404
        }
        output.out shouldContain
            "event=endpoint.conversion.converted path=$CONVERTED_PATH pipeline=$DEMO_PIPELINE pipeline_version=1 status=RELEASED"
        output.out shouldContain "event=endpoint.conversion.summary converted=3 released=2 draft=1"
    }

    @Test
    @Order(3)
    fun `a rerun boot is a no-op - versions, pointers and hashes unchanged`(output: CapturedOutput) {
        val before = rows(SNAPSHOT)
        close()

        context = boot()

        rows(SNAPSHOT) shouldBe before
        output.out shouldContain "event=endpoint.conversion.summary converted=0 released=0 draft=0"
    }

    @Test
    @Order(4)
    fun `E0 - a pipeline with no current version refuses the boot with the list and writes nothing`(output: CapturedOutput) {
        close()
        val noCurrent = pipelineCopy("test/e2emig_no_current", current = null, "DISCARDED")
        val noVersions = pipelineCopy("test/e2emig_no_versions", current = null)
        legacyApi("/e2emig/a/convertible", demoPipeline, timeout = 30, description = "", enabled = true)
        legacyApi("/e2emig/z/no-current", noCurrent, timeout = 30, description = "", enabled = true)
        legacyApi("/e2emig/z/no-versions", noVersions, timeout = 30, description = "", enabled = true)
        val versionsBefore = scalar<Long>("SELECT COUNT(*) FROM published_endpoint_versions")

        val refused = shouldThrowAny { boot() }

        val messages = generateSequence(refused) { it.cause }.mapNotNull { it.message }.toList()
        messages.any { it.contains("The API version conversion refused to start: 2 API(s)") } shouldBe true
        scalar<Long>("SELECT COUNT(*) FROM published_endpoint_versions") shouldBe versionsBefore
        scalar<Long>(VERSIONLESS) shouldBe 3L
        output.out shouldContain "path=/e2emig/z/no-current pipeline=test/e2emig_no_current reason=no_current"
        output.out shouldContain "path=/e2emig/z/no-versions pipeline=test/e2emig_no_versions reason=no_versions"
        output.out shouldContain "event=endpoint.conversion.refused_summary refused=2"

        // The operator's fix on the previous version: delete the refused APIs. The next boot converts the rest.
        sql("DELETE FROM published_endpoints WHERE path_pattern IN ('/e2emig/z/no-current', '/e2emig/z/no-versions')")
        context = boot()
        version("/e2emig/a/convertible") shouldBe listOf("RELEASED", DEMO_PIPELINE, 1, 30, "", 1, true)
    }

    @Test
    @Order(5)
    fun `conversion racing a discard - the discard first moves CURRENT, and the conversion pins what it left`() {
        val racing = pipelineCopy("test/e2emig_race_a", current = 2, "RELEASED", "RELEASED")
        legacyApi("/e2emig/v1/race-a", racing, timeout = 30, description = "", enabled = true)

        val (discard, booted) =
            holdingWorkspace { holder ->
                val discard = CompletableFuture.supplyAsync { session().post("/api/v1/pipelines/$racing/versions/2/discard") }
                awaitWaiters(holder, 1)
                val booted = CompletableFuture.supplyAsync { boot() }
                awaitWaiters(holder, 2)
                discard to booted
            }

        discard.get(2, TimeUnit.MINUTES).statusCode shouldBe 200
        booted.get(5, TimeUnit.MINUTES).close()
        scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$racing'") shouldBe 1
        version("/e2emig/v1/race-a") shouldBe listOf("RELEASED", "test/e2emig_race_a", 1, 30, "", 1, true)
    }

    @Test
    @Order(6)
    fun `conversion racing a discard - the conversion first pins the version, and the discard is refused as pinned`() {
        val racing = pipelineCopy("test/e2emig_race_b", current = 2, "RELEASED", "RELEASED")
        legacyApi("/e2emig/v1/race-b", racing, timeout = 30, description = "", enabled = true)

        val (booted, discard) =
            holdingWorkspace { holder ->
                val booted = CompletableFuture.supplyAsync { boot() }
                awaitWaiters(holder, 1)
                val discard = CompletableFuture.supplyAsync { session().post("/api/v1/pipelines/$racing/versions/2/discard") }
                awaitWaiters(holder, 2)
                booted to discard
            }

        booted.get(5, TimeUnit.MINUTES).close()
        val refused = discard.get(2, TimeUnit.MINUTES)
        refused.statusCode shouldBe 409
        refused.jsonPath().getString("error.code") shouldBe "pipeline.version.pinned"
        refused.jsonPath().getList<String>("error.details.referencing_api_versions.path") shouldContain "/e2emig/v1/race-b"
        scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$racing'") shouldBe 2
        version("/e2emig/v1/race-b") shouldBe listOf("RELEASED", "test/e2emig_race_b", 2, 30, "", 1, true)
    }

    @Test
    @Order(7)
    fun `an API draft repin racing a discard - the repin first pins the version, and the discard is refused as pinned (A39)`() {
        val name = "test/e2emig_repin_a"
        val racing = pipelineCopy(name, current = 2, "RELEASED", "RELEASED")
        val (apiId, hash) = nativeApi("/e2emig/v1/repin-a", name, pipelineVersion = 1)

        val (repin, discard) =
            holdingWorkspace { holder ->
                val repin = CompletableFuture.supplyAsync { saveDraft(apiId, hash, name, pipelineVersion = 2) }
                awaitWaiters(holder, 1)
                val discard = CompletableFuture.supplyAsync { session().post("/api/v1/pipelines/$racing/versions/2/discard") }
                awaitWaiters(holder, 2)
                repin to discard
            }

        repin.get(2, TimeUnit.MINUTES).statusCode shouldBe 200
        val refused = discard.get(2, TimeUnit.MINUTES)
        refused.statusCode shouldBe 409
        refused.jsonPath().getString("error.code") shouldBe "pipeline.version.pinned"
        refused.jsonPath().getList<String>("error.details.referencing_api_versions.path") shouldContain "/e2emig/v1/repin-a"
        scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$racing'") shouldBe 2
        version("/e2emig/v1/repin-a") shouldBe listOf("DRAFT", name, 2, 30, "", 1, true)
    }

    @Test
    @Order(8)
    fun `an API draft repin racing a discard - the discard first wins, and the repin onto the discarded version is refused (A39)`() {
        val name = "test/e2emig_repin_b"
        val racing = pipelineCopy(name, current = 2, "RELEASED", "RELEASED")
        val (apiId, hash) = nativeApi("/e2emig/v1/repin-b", name, pipelineVersion = 1)

        val (discard, repin) =
            holdingWorkspace { holder ->
                val discard = CompletableFuture.supplyAsync { session().post("/api/v1/pipelines/$racing/versions/2/discard") }
                awaitWaiters(holder, 1)
                val repin = CompletableFuture.supplyAsync { saveDraft(apiId, hash, name, pipelineVersion = 2) }
                awaitWaiters(holder, 2)
                discard to repin
            }

        discard.get(2, TimeUnit.MINUTES).statusCode shouldBe 200
        val refused = repin.get(2, TimeUnit.MINUTES)
        refused.statusCode shouldBe 400
        refused.jsonPath().getString("error.code") shouldBe "endpoint.pipeline_version.not_found"
        scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$racing'") shouldBe 1
        version("/e2emig/v1/repin-b") shouldBe listOf("DRAFT", name, 1, 30, "", 1, true)
    }

    /** A native API (REST create: v1 DRAFT, current) pinned to [pipelineVersion] of [pipeline]; its id and v1's hash. */
    private fun nativeApi(
        path: String,
        pipeline: String,
        pipelineVersion: Int,
    ): Pair<String, String> {
        val id =
            session()
                .contentType(ContentType.JSON)
                .body("""{"path": "$path", "pipeline": "$pipeline", "pipeline_version": $pipelineVersion, "timeout_seconds": 30}""")
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        val hash =
            session()
                .get("/api/v1/endpoints/$id/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        return id to hash
    }

    /** Saves the API's working draft onto [pipelineVersion] under `If-Match` [hash] — a repin. */
    private fun saveDraft(
        apiId: String,
        hash: String,
        pipeline: String,
        pipelineVersion: Int,
    ): Response =
        session()
            .contentType(ContentType.JSON)
            .header("If-Match", hash)
            .body("""{"pipeline": "$pipeline", "pipeline_version": $pipelineVersion}""")
            .put("/api/v1/endpoints/$apiId/draft")

    // ------------------------------------------------------------------ boot + fixtures

    private fun boot(): ConfigurableApplicationContext {
        val args = properties().map { (k, v) -> "--$k=$v" }.toTypedArray()
        val started =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .web(WebApplicationType.SERVLET)
                .listeners(
                    ApplicationListener<WebServerInitializedEvent> {
                        if (it.applicationContext.serverNamespace == null) versionlessAtServerStart.set(scalar<Long>(VERSIONLESS).toInt())
                    },
                ).run(*args)
        if (context?.isActive != true) {
            port = checkNotNull(started.environment.getProperty("local.server.port", Int::class.java)) { "no server port" }
        }
        return started
    }

    /** Holds the demo workspace row in an open transaction (a writer in flight), runs [block], then commits. */
    private fun <T> holdingWorkspace(block: (Int) -> T): T =
        connection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT 1 FROM workspaces WHERE id = '$demoId' FOR NO KEY UPDATE") }
            val pid =
                holder.createStatement().use { s ->
                    s.executeQuery("SELECT pg_backend_pid()").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            val result = block(pid)
            holder.commit()
            result
        }

    /** Waits until [count] backends are queued behind [holderPid]'s lock (directly or behind an earlier waiter). */
    private fun awaitWaiters(
        holderPid: Int,
        count: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (System.nanoTime() < deadline) {
            val waiting =
                scalar<Long>("SELECT COUNT(*) FROM pg_stat_activity WHERE pid <> $holderPid AND cardinality(pg_blocking_pids(pid)) > 0")
            if (waiting >= count) return
            Thread.sleep(POLL_MILLIS)
        }
        error("expected $count waiter(s) behind backend $holderPid")
    }

    /** A pipeline in the demo workspace whose versions 1..n are copies of the demo pipeline's v1 body with [statuses]. */
    private fun pipelineCopy(
        name: String,
        current: Int?,
        vararg statuses: String,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES ('$id', '$name', 'E2E', '$adminId', ${current ?: "NULL"}, '$demoId')",
        )
        statuses.forEachIndexed { index, status ->
            sql(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by," +
                    " released_at, discarded_by, discarded_at)" +
                    " SELECT '$id', ${index + 1}, body_json, body_hash, '$status', created_by," +
                    " CASE WHEN '$status' <> 'DRAFT' THEN created_by END, CASE WHEN '$status' <> 'DRAFT' THEN NOW() END," +
                    " CASE WHEN '$status' = 'DISCARDED' THEN created_by END, CASE WHEN '$status' = 'DISCARDED' THEN NOW() END" +
                    " FROM pipeline_versions WHERE pipeline_id = '$demoPipeline' AND version = 1",
            )
        }
        return id
    }

    /** A pre-V58 API row: the entity and its legacy columns, no version row — what the previous version wrote. */
    private fun legacyApi(
        path: String,
        pipeline: UUID,
        timeout: Int,
        description: String,
        enabled: Boolean,
    ) = sql(
        "INSERT INTO published_endpoints" +
            " (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by)" +
            " VALUES ('${UUID.randomUUID()}', '$demoId', '$path', '$pipeline', $timeout, '$description', $enabled, '$adminId')",
    )

    /** status, pipeline, pipeline_version, timeout, description of v1, the entity pointer and enabled state. */
    private fun version(path: String): List<Any?> =
        rows(
            "SELECT v.status, v.pipeline_name, v.pipeline_version, v.timeout_seconds, v.description, e.current_version, e.is_enabled" +
                " FROM published_endpoints e JOIN published_endpoint_versions v ON v.endpoint_id = e.id AND v.version = 1" +
                " WHERE e.path_pattern = '$path'",
        ).single().values.toList()

    private fun serve(path: String): Response = given().port(port).header("DP-API-Key", DEMO_KEY).get("/api$path")

    private fun session() = given().port(port).asSession(adminJwt)

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun sql(statement: String) = connection().use { c -> c.createStatement().use { it.execute(statement) } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(query: String): T = rows(query).single().values.first() as T

    private fun rows(query: String): List<Map<String, Any?>> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(query).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) })
                    }
                }
            }
        }

    companion object {
        private const val ADMIN_EMAIL = "migration-admin@e2e.test"
        private const val DEMO_PIPELINE = "test/demo_api_e2e_example"
        private const val DEMO_PATH = "/demo/test/demo-api-e2e-example"
        private const val CONVERTED_PATH = "$DEMO_PATH/converted"
        private const val DEMO_KEY = "dpk_MIGRATIONAAA.ONEKEYISPUBLICBYDESIGNDEMOONLYE2E786786786786786"
        private const val POLL_MILLIS = 50L
        private const val VERSIONLESS =
            "SELECT COUNT(*) FROM published_endpoints e WHERE NOT EXISTS " +
                "(SELECT 1 FROM published_endpoint_versions v WHERE v.endpoint_id = e.id)"
        private const val SNAPSHOT =
            "SELECT e.path_pattern, e.current_version, e.is_enabled, v.version, v.status, v.pipeline_version, v.body_hash" +
                " FROM published_endpoints e LEFT JOIN published_endpoint_versions v ON v.endpoint_id = e.id ORDER BY 1, 4"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()
        private val fixtures: java.nio.file.Path = Files.createTempDirectory("endpoint-migration-e2e")
        private val datasourcesFile: java.nio.file.Path =
            writeFile(
                "bootstrap-datasources.yml",
                """
                datasources:
                  - name: migration_e2e_ro
                    display_name: Migration E2E read-only
                    dialect: H2
                    jdbc_url: jdbc:h2:mem:migration_e2e_ro;DB_CLOSE_DELAY=-1
                    username: sa
                    password: secret
                    readonly: true
                    global: true
                """.trimIndent(),
            )
        private val examplesFile: java.nio.file.Path =
            writeFile(
                "examples.json",
                """
                {
                  "templates": [
                    {
                      "id": "test/demo_api_e2e_example.sql",
                      "dialect": "H2",
                      "display_name": "Demo API E2E example",
                      "description": "Seeded into the demo workspace",
                      "imports": [],
                      "body": "SELECT 1 AS n"
                    }
                  ],
                  "pipelines": [
                    {
                      "schema_version": 1,
                      "name": "test/demo_api_e2e_example",
                      "display_name": "Demo API E2E example",
                      "description": "Reads the seeded read-only datasource",
                      "parameters": {},
                      "nodes": [
                        {
                          "id": "read_sample",
                          "description": "DQL read from the seeded read-only datasource",
                          "type": "DQL",
                          "source": "migration_e2e_ro",
                          "template": {"id": "test/demo_api_e2e_example.sql", "version": 1},
                          "output": {"target": "caller"},
                          "depends_on": []
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        private fun writeFile(
            name: String,
            content: String,
        ): java.nio.file.Path = fixtures.resolve(name).also { it.writeText(content) }

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        private fun properties(): Map<String, String> =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "datapipelines.redis.password" to "",
                "datapipelines.redis.host" to SharedE2e.redis.host,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to jwtSecret,
                "datapipelines.db.encryption-key" to encryptionKey,
                "datapipelines.auth.local.enabled" to "true",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.auth.bootstrap-admin-email" to ADMIN_EMAIL,
                "datapipelines.bootstrap.demo-api-key" to DEMO_KEY,
                "datapipelines.bootstrap.datasources-file" to datasourcesFile.toString(),
                "datapipelines.bootstrap.examples-file" to examplesFile.toString(),
                // The race's two writers queue behind a held lock across a whole context start; the production bound (5 s)
                // would refuse the first waiter busy before the second arrives.
                "datapipelines.release.dependencies.lock-wait-seconds" to "60",
            )
    }
}
