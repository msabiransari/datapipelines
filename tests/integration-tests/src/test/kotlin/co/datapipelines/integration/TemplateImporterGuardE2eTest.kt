package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID

/**
 * **#877 §D — the importer arm over the real HTTP stack**: a template version another template's `imports[i]` pins is
 * protected exactly like one a live pipeline pins (record §23.3 item 5, ruling A.0 option 1). Every verb that removes a
 * template version reads the template family's importer arm through `TemplateUsage`:
 *
 * - the release discard asks LIVE: a RELEASED importer refuses it, a DISCARDED one does not (the pipelines' rule);
 * - the entity purge, and the draft purge of a template's ONLY version (which takes the entity), ask ANY: a
 *   DISCARDED version of an importer that still has a live version refuses them. An importer whose EVERY version is
 *   DISCARDED pins nothing — the rule every inverse arm shares (`InverseSql.scope`, the pipelines' arm included) — and
 *   restoring it afterwards is refused `template.validation.import_not_found`, so nothing dangles silently;
 * - the draft purge of a draft a DRAFT importer pins refuses on REST and through `templates_purge_draft`'s fourth arm;
 * - the pipeline purge's exclusive-draft offer keeps a draft template an importer also pins, and says so.
 *
 * Every refusal is `template.in_use` naming the importer under `referencing_templates` (and `pinned_by: []` — no
 * pipeline pins anything here), each with its UNPINNED control. Importers are seeded by SQL: a stored `imports_json`
 * row is the state under test, and the import validator would refuse a DRAFT library under a RELEASED importer.
 *
 * Not reachable here: a refusal whose importer is outside the caller's view (`pins_hidden`). No role whose lens narrows
 * (the promoter) holds `TEMPLATE_VERSION_MANAGE`, so no lensed caller reaches any of these verbs; the narrowing is
 * pinned at the unit level (`TemplateReleaseServiceTest`, `TemplatesPurgeDraftToolTest`).
 *
 * Non-vacuity: every refusal is collected by code and the multiset asserted last.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TemplateImporterGuardE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** Every refusal the walk met, by code — the non-vacuity count. */
    private val refusals = mutableListOf<String>()

    /** The admin key's service identity — the MCP purge removes only drafts its own user created. */
    private val keyIdentity = UUID.randomUUID().toString()

    @Test
    @Order(1)
    fun `fixture - the workspace, its admin and the admin's MCP key`() {
        seed()
    }

    @Test
    @Order(2)
    fun `a RELEASED importer pinning lib v2 refuses the discard of lib v2 - and the unpinned v1 discards`() {
        seedTemplate(LIB, 1, "RELEASED")
        seedTemplate(LIB, 2, "RELEASED")
        seedTemplate(IMPORTER, 1, "RELEASED", imports = LIB to 2)

        val discard = post("/api/v1/templates/version/discard", """{"name": "$LIB", "version": 2}""")
        refused(discard, 409) shouldBe "template.in_use"
        discard.jsonPath().getList<String>("error.details.referencing_templates") shouldContainExactly listOf(IMPORTER)
        discard.jsonPath().getList<String>("error.details.pinned_by") shouldContainExactly emptyList()
        discard.jsonPath().getString("error.details.pins_hidden") shouldBe null
        versionStatus(LIB, 2) shouldBe "RELEASED"

        post("/api/v1/templates/version/discard", """{"name": "$LIB", "version": 1}""").statusCode shouldBe 200
        versionStatus(LIB, 1) shouldBe "DISCARDED"
    }

    @Test
    @Order(3)
    fun `a DISCARDED importer does not protect the release discard - LIVE scope, the pipelines' rule`() {
        seedTemplate(ABANDONED_LIB, 1, "RELEASED")
        seedTemplate(ABANDONED_IMPORTER, 1, "DISCARDED", imports = ABANDONED_LIB to 1)

        val discard = post("/api/v1/templates/version/discard", """{"name": "$ABANDONED_LIB", "version": 1}""")

        withClue(discard.asString().take(EXCERPT)) { discard.statusCode shouldBe 200 }
        versionStatus(ABANDONED_LIB, 1) shouldBe "DISCARDED"
    }

    @Test
    @Order(4)
    fun `a DISCARDED importer version of a live importer refuses the entity purge and the sole-draft purge - ANY scope`() {
        val hash = createTemplate(SOLE_DRAFT_LIB)
        seedTemplate(SOLE_DRAFT_IMPORTER, 1, "DISCARDED", imports = SOLE_DRAFT_LIB to 1)
        seedTemplate(SOLE_DRAFT_IMPORTER, 2, "RELEASED")

        val entity = delete("/api/v1/templates?name=$SOLE_DRAFT_LIB")
        refused(entity, 409) shouldBe "template.in_use"
        entity.jsonPath().getList<String>("error.details.referencing_templates") shouldContainExactly listOf(SOLE_DRAFT_IMPORTER)

        val draft = post("/api/v1/templates/draft/discard", """{"name": "$SOLE_DRAFT_LIB"}""", ifMatch = hash)
        refused(draft, 409) shouldBe "template.in_use"
        draft.jsonPath().getList<String>("error.details.referencing_templates") shouldContainExactly listOf(SOLE_DRAFT_IMPORTER)
        templateExists(SOLE_DRAFT_LIB) shouldBe true
    }

    @Test
    @Order(5)
    fun `an importer whose every version is DISCARDED pins nothing - the shared ANY rule - and its restore refuses the gone import`() {
        createTemplate(ORPHAN_LIB)
        seedTemplate(ORPHAN_IMPORTER, 1, "DISCARDED", imports = ORPHAN_LIB to 1)

        val entity = delete("/api/v1/templates?name=$ORPHAN_LIB")
        withClue(entity.asString().take(EXCERPT)) { entity.statusCode shouldBe 204 }
        templateExists(ORPHAN_LIB) shouldBe false

        // Nothing dangles silently: bringing the importer back rechecks its import closure (#873 D.2).
        val restore = post("/api/v1/templates/version/restore", """{"name": "$ORPHAN_IMPORTER", "version": 1}""")
        refused(restore, 400) shouldBe "template.validation.import_not_found"
        versionStatus(ORPHAN_IMPORTER, 1) shouldBe "DISCARDED"
    }

    @Test
    @Order(6)
    fun `a DRAFT importer refuses the draft purge on REST and the version purge - the guard refuses only what something holds`() {
        val hash = createTemplate(DRAFT_LIB)
        seedTemplate(DRAFT_IMPORTER, 1, "DRAFT", imports = DRAFT_LIB to 1)

        val draft = post("/api/v1/templates/draft/discard", """{"name": "$DRAFT_LIB"}""", ifMatch = hash)
        refused(draft, 409) shouldBe "template.in_use"
        draft.jsonPath().getList<String>("error.details.referencing_templates") shouldContainExactly listOf(DRAFT_IMPORTER)
        draft.jsonPath().getString("error.message") shouldBe
            "Version of template '$DRAFT_LIB' is pinned by 1 template version(s): $DRAFT_IMPORTER; discard or repoint them first."

        val version = delete("/api/v1/templates/version?name=$DRAFT_LIB&version=1")
        refused(version, 409) shouldBe "template.in_use"
        templateExists(DRAFT_LIB) shouldBe true

        // The control: the same draft purge on a draft nothing imports.
        val free = createTemplate(FREE_LIB)
        post("/api/v1/templates/draft/discard", """{"name": "$FREE_LIB"}""", ifMatch = free).statusCode shouldBe 204
        templateExists(FREE_LIB) shouldBe false
    }

    @Test
    @Order(7)
    fun `templates_purge_draft's fourth arm refuses a draft a DRAFT importer pins - and purges the unpinned control`() {
        seedTemplate(MCP_LIB, 1, "DRAFT", createdBy = keyIdentity)
        seedTemplate(MCP_IMPORTER, 1, "DRAFT", imports = MCP_LIB to 1)
        seedTemplate(MCP_FREE_LIB, 1, "DRAFT", createdBy = keyIdentity)

        val (payload, isError) = callTool("templates_purge_draft", mapOf("id" to MCP_LIB))
        withClue(payload) { isError shouldBe true }
        payload["error"]["code"].asText().also { refusals += it } shouldBe "template.in_use"
        payload["error"]["details"]["referencing_templates"].map { it.asText() } shouldContainExactly listOf(MCP_IMPORTER)
        payload["error"]["details"].has("pins_hidden") shouldBe false
        templateExists(MCP_LIB) shouldBe true

        val (_, freeError) = callTool("templates_purge_draft", mapOf("id" to MCP_FREE_LIB))
        freeError shouldBe false
        templateExists(MCP_FREE_LIB) shouldBe false
    }

    @Test
    @Order(8)
    fun `the pipeline purge's exclusive-draft offer keeps a draft template an importer also pins, and says so`() {
        createTemplate(OFFER_LIB)
        seedTemplate(OFFER_IMPORTER, 1, "DRAFT", imports = OFFER_LIB to 1)
        seedDraftPipeline(PIPELINE_ID, PIPELINE_NAME, OFFER_LIB)

        val purged = delete("/api/v1/pipelines/$PIPELINE_ID?include_exclusive_draft_templates=true")

        withClue(purged.asString().take(EXCERPT)) { purged.statusCode shouldBe 200 }
        purged.jsonPath().getList<String>("data.exclusive_draft_templates") shouldContainExactly emptyList()
        purged.jsonPath().getList<String>("data.kept_draft_templates.id") shouldContainExactly listOf(OFFER_LIB)
        purged.jsonPath().getList<String>("data.kept_draft_templates[0].referencing_templates") shouldContainExactly
            listOf(OFFER_IMPORTER)
        templateExists(OFFER_LIB) shouldBe true
    }

    @Test
    @Order(9)
    fun `non-vacuity - every refusal the walk exists for happened, counted by code`() {
        println("event=template_importer_guard_e2e.refusals total=${refusals.size} ${refusals.groupingBy { it }.eachCount()}")
        refusals.groupingBy { it }.eachCount() shouldBe mapOf("template.in_use" to 6, "template.validation.import_not_found" to 1)
    }

    // ------------------------------------------------------------------------------- helpers

    private fun refused(
        response: Response,
        status: Int,
    ): String {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe status }
        return response.jsonPath().getString("error.code").also { refusals += it }
    }

    private fun get(path: String): Response = given().port(port).asSession(ADMIN_SESSION).get(path)

    private fun delete(path: String): Response = given().port(port).asSession(ADMIN_SESSION).delete(path)

    private fun post(
        path: String,
        body: String,
        ifMatch: String? = null,
    ): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .post(path)

    /** Creates a DRAFT template through the API (D55: version 1 DRAFT); returns its `body_hash`. */
    private fun createTemplate(name: String): String {
        val created =
            post(
                "/api/v1/templates",
                """{"id": "$name", "dialect": "POSTGRES", "display_name": "Importer guard fixture", "description": "",
                    "imports": [], "body": "SELECT 1 AS v"}""",
            )
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        created.jsonPath().getString("data.status") shouldBe "DRAFT"
        return created.jsonPath().getString("data.body_hash")
    }

    private fun templateExists(name: String): Boolean = get("/api/v1/templates?name=$name").statusCode == 200

    /** The payload of one MCP tool call under the admin key, and whether it was an `isError` result. */
    private fun callTool(
        tool: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to mapOf("name" to tool, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        withClue("MCP $tool: ${response.body().take(EXCERPT)}") { response.statusCode() shouldBe 200 }
        val tree = mapper.readTree(response.body())
        withClue("JSON-RPC error on $tool: ${response.body().take(EXCERPT)}") { tree.has("error") shouldBe false }
        val result = tree["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    /** The stored status of version [version] of template [name] — the ground truth behind a refusal. */
    private fun versionStatus(
        name: String,
        version: Int,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val statement =
                connection.prepareStatement(
                    "SELECT v.status FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                        " WHERE t.workspace_id = ?::uuid AND t.name = ? AND v.version = ?",
                )
            statement.setString(1, WORKSPACE_ID)
            statement.setString(2, name)
            statement.setInt(3, version)
            val rows = statement.executeQuery()
            if (rows.next()) rows.getString(1) else null
        }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Importer guard E2E')")
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                "('$ADMIN_ID', 'ti-admin@e2e.test', 'TI Admin', 'test', 'ti-admin-sub', TRUE, FALSE)",
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')")
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                "('$keyIdentity', '${ADMIN_KEY.id.lowercase()}@keys.invalid', '${ADMIN_KEY.name}', 'key', '${ADMIN_KEY.id}', " +
                "TRUE, FALSE, 'service')",
        )
        sql(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                "('${ADMIN_KEY.id}', '$keyIdentity', '$ADMIN_ID', '${ADMIN_KEY.name}', '${ADMIN_KEY.hash}', '$WORKSPACE_ID', " +
                "'mcp', 'workspace_admin')",
        )
    }

    /**
     * Version [version] of template [name] in [status], importing [imports] (`name to version`, alias `lib`) when
     * given; the entity row is created on first use, and a RELEASED row moves `current_version` up (D60).
     */
    private fun seedTemplate(
        name: String,
        version: Int,
        status: String,
        imports: Pair<String, Int>? = null,
        createdBy: String = ADMIN_ID,
    ) {
        sql(
            "INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) " +
                "VALUES ('${UUID.randomUUID()}', '$name', 'T', '', NULL, '$WORKSPACE_ID', '$createdBy') " +
                "ON CONFLICT (workspace_id, name) DO NOTHING",
        )
        val importsJson = imports?.let { """[{"id":"${it.first}","version":${it.second},"alias":"lib"}]""" } ?: "[]"
        val released = if (status == "DRAFT") "NULL, NULL" else "'$ADMIN_ID', NOW()"
        val discarded = if (status == "DISCARDED") "'$ADMIN_ID', NOW()" else "NULL, NULL"
        sql(
            "INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, status, " +
                "body_hash, created_by, released_by, released_at, discarded_by, discarded_at) " +
                "SELECT t.id, $version, 'freemarker', 'POSTGRES', false, '$importsJson'::jsonb, 'SELECT 1', '$status', " +
                "'seeded-$name-$version', '$createdBy', $released, $discarded " +
                "FROM templates t WHERE t.workspace_id = '$WORKSPACE_ID' AND t.name = '$name'",
        )
        if (status == "RELEASED") {
            sql(
                "UPDATE templates SET current_version = GREATEST(COALESCE(current_version, 0), $version) " +
                    "WHERE workspace_id = '$WORKSPACE_ID' AND name = '$name'",
            )
        }
    }

    private fun seedDraftPipeline(
        id: String,
        name: String,
        template: String,
    ) {
        sql(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES ('$id', '$name', 'P', '', '$ADMIN_ID', '$WORKSPACE_ID', NULL)",
        )
        val body =
            """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"$template","version":1}}]}"""
        sql(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by) " +
                "VALUES ('$id', 1, '$body'::jsonb, 'seeded-$id', 'DRAFT', '$ADMIN_ID')",
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "ti-e2e"
        const val ROOT = "ti"
        const val LIB = "$ROOT/libs/released.sql"
        const val IMPORTER = "$ROOT/reports/imports_released.sql"
        const val ABANDONED_LIB = "$ROOT/libs/abandoned.sql"
        const val ABANDONED_IMPORTER = "$ROOT/reports/discarded_importer.sql"
        const val SOLE_DRAFT_LIB = "$ROOT/libs/sole_draft.sql"
        const val SOLE_DRAFT_IMPORTER = "$ROOT/reports/discarded_pins_a_draft.sql"
        const val DRAFT_LIB = "$ROOT/libs/draft.sql"
        const val DRAFT_IMPORTER = "$ROOT/reports/draft_importer.sql"
        const val FREE_LIB = "$ROOT/libs/free.sql"
        const val ORPHAN_LIB = "$ROOT/libs/orphaned.sql"
        const val ORPHAN_IMPORTER = "$ROOT/reports/every_version_discarded.sql"
        const val MCP_LIB = "$ROOT/libs/agent_draft.sql"
        const val MCP_IMPORTER = "$ROOT/reports/imports_agent_draft.sql"
        const val MCP_FREE_LIB = "$ROOT/libs/agent_free.sql"
        const val OFFER_LIB = "$ROOT/libs/offered.sql"
        const val OFFER_IMPORTER = "$ROOT/reports/imports_offered.sql"
        const val PIPELINE_NAME = "$ROOT/pipelines/draft_only"

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val PIPELINE_ID = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("ti-e2e-admin-key")

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "ti-admin@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // API mode: this suite only authors — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
