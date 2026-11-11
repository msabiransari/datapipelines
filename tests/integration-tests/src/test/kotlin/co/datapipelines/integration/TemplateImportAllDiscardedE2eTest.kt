package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * #883 — an import onto a registered template whose EVERY version is DISCARDED, over the real application, the real
 * route (`POST /api/v1/templates/import`) and the shared test Postgres.
 *
 * Such a template is registered (versioning §3.2: `existsId` counts any status), so both import modes take the
 * existing-template branch, and the §5.3 (046) type resolution read only the WORKING version — of which an
 * all-discarded template has none — and answered `template.not_found` before any write. The published contract is the
 * lifecycle table's `{X,X}` import row and §9.2's pointer rule (D60): the import lands the next release (version-less:
 * `max(version) + 1`; exact-version: the payload's absent number), sets the NULL pointer, the old versions stay
 * DISCARDED, and the type is the one the template's retained history established.
 *
 * Each test owns its template ids, so the class needs no ordering.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateImportAllDiscardedE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun rest(session: String = SESSION_A): RequestSpecification = given().port(port).asSession(session)

    private fun json(
        body: Any,
        session: String = SESSION_A,
    ): RequestSpecification = rest(session).contentType(ContentType.JSON).body(mapper.writeValueAsString(body))

    private fun importTemplates(
        vararg entries: Any,
        session: String = SESSION_A,
    ): Response = json(mapOf("templates" to entries.toList()), session).post("/api/v1/templates/import")

    private fun imported(
        vararg entries: Any,
        session: String = SESSION_A,
    ): JsonNode {
        val response = importTemplates(*entries, session = session)
        withClue(response.asString()) { response.statusCode shouldBe 200 }
        return mapper.readTree(response.asString())["data"]["templates"][0]
    }

    private fun sqlEntry(
        id: String,
        body: String = "SELECT 1 AS n",
        typed: Boolean = false,
    ): Map<String, Any?> =
        buildMap {
            put("id", id)
            if (typed) put("type", "sql")
            put("dialect", "POSTGRES")
            put("display_name", "883 import")
            put("description", "Expects: nothing (plain SQL).")
            put("body", body)
        }

    private fun htmlEntry(
        id: String,
        body: String = "<p>hello</p>",
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "type" to "html",
            "display_name" to "883 card",
            "description" to "Expects: nothing (static HTML).",
            "body" to body,
        )

    private fun discard(
        id: String,
        version: Int,
        session: String = SESSION_A,
    ) {
        val response = json(mapOf("name" to id, "version" to version), session).post("/api/v1/templates/version/discard")
        withClue("discard $id@$version: ${response.asString()}") { response.statusCode shouldBe 200 }
    }

    /** [count] released versions of [id] (version-less imports of [entry]), then every one of them discarded. */
    private fun allDiscarded(
        id: String,
        count: Int,
        entry: (Int) -> Map<String, Any?>,
        session: String = SESSION_A,
    ) {
        (1..count).forEach { n -> imported(entry(n), session = session)["version"].asInt() shouldBe n }
        (count downTo 1).forEach { discard(id, it, session) }
        versions(id, workspaceOf(session)) shouldBe (1..count).joinToString(",") { "$it:DISCARDED" }
        pointer(id, workspaceOf(session)) shouldBe null
    }

    /** The preserved-version import entry of [id]@[version] as `GET /versions` exports it. */
    private fun exported(
        id: String,
        version: Int = 1,
    ): ObjectNode =
        mapper.readTree(
            rest()
                .queryParam("name", id)
                .queryParam("version", version)
                .get("/api/v1/templates/versions")
                .asString(),
        )["data"] as ObjectNode

    /** [donor]'s export re-targeted at [id]@[version] — `body_hash` covers neither the id, the number nor the type. */
    private fun retargeted(
        donor: ObjectNode,
        id: String,
        version: Int,
        withType: Boolean = true,
    ): ObjectNode =
        donor.deepCopy().apply {
            put("id", id)
            put("version", version)
            if (!withType) remove("type")
        }

    private fun error(response: Response): JsonNode = mapper.readTree(response.asString())["error"]

    // ---- the persisted state ----------------------------------------------------------------------------------

    private fun jdbc() = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    private fun workspaceOf(session: String): String = if (session == SESSION_B) WORKSPACE_B_ID else WORKSPACE_A_ID

    /** Every stored version of [id] in [workspaceId], `version:STATUS` ascending. */
    private fun versions(
        id: String,
        workspaceId: String = WORKSPACE_A_ID,
    ): String =
        jdbc().queryForObject(
            "SELECT coalesce(string_agg(v.version || ':' || v.status, ',' ORDER BY v.version), '') FROM template_versions v" +
                " JOIN templates t ON t.id = v.template_id WHERE t.workspace_id = ?::uuid AND t.name = ?",
            String::class.java,
            workspaceId,
            id,
        ) ?: ""

    private fun typeOf(
        id: String,
        version: Int,
        workspaceId: String = WORKSPACE_A_ID,
    ): String? =
        jdbc()
            .queryForList(
                "SELECT v.type FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                    " WHERE t.workspace_id = ?::uuid AND t.name = ? AND v.version = ?",
                String::class.java,
                workspaceId,
                id,
                version,
            ).singleOrNull()

    private fun pointer(
        id: String,
        workspaceId: String = WORKSPACE_A_ID,
    ): Int? =
        jdbc()
            .queryForList(
                "SELECT current_version FROM templates WHERE workspace_id = ?::uuid AND name = ?",
                Int::class.javaObjectType,
                workspaceId,
                id,
            ).single()

    /** Every version row in workspace A — what a refused import must leave unchanged. */
    private fun versionRows(): Int =
        jdbc().queryForObject(
            "SELECT count(*) FROM template_versions v JOIN templates t ON t.id = v.template_id WHERE t.workspace_id = ?::uuid",
            Int::class.java,
            WORKSPACE_A_ID,
        ) ?: 0

    /** Every column of [id]'s entity row and version rows in [workspaceId], as text — the byte-level snapshot. */
    private fun snapshot(
        id: String,
        workspaceId: String,
    ): List<String> =
        jdbc().queryForList(
            "SELECT row_to_json(t)::text FROM templates t WHERE t.workspace_id = ?::uuid AND t.name = ?" +
                " UNION ALL SELECT x.j FROM (SELECT row_to_json(v)::text AS j, v.version FROM template_versions v" +
                " JOIN templates t ON t.id = v.template_id WHERE t.workspace_id = ?::uuid AND t.name = ?" +
                " ORDER BY v.version) x",
            String::class.java,
            workspaceId,
            id,
            workspaceId,
            id,
        )

    // ---- B.1, B.2: the version-less path ----------------------------------------------------------------------

    @Test
    fun `B1 - a version-less import onto an all-discarded template lands v2 RELEASED, v1 stays DISCARDED, the pointer names v2`() {
        val id = "test/i883/reported.sql"
        allDiscarded(id, 1, { sqlEntry(id) })

        val landed = imported(sqlEntry(id, body = "SELECT 2 AS n"))

        landed["version"].asInt() shouldBe 2
        landed["status"].asText() shouldBe "RELEASED"
        versions(id) shouldBe "1:DISCARDED,2:RELEASED"
        pointer(id) shouldBe 2
    }

    @Test
    fun `B2 - the allocation reads the history - versions 1 to 3 all discarded, the import lands v4`() {
        val id = "test/i883/three.sql"
        allDiscarded(id, 3, { n -> sqlEntry(id, body = "SELECT $n AS n") })

        imported(sqlEntry(id, body = "SELECT 4 AS n"))["version"].asInt() shouldBe 4

        versions(id) shouldBe "1:DISCARDED,2:DISCARDED,3:DISCARDED,4:RELEASED"
        pointer(id) shouldBe 4
    }

    // ---- B.3: the type, version-less ----------------------------------------------------------------------------

    @Test
    fun `B3a - version-less - an omitted type inherits the retained SQL type`() {
        val id = "test/i883/omitted.sql"
        allDiscarded(id, 1, { sqlEntry(id) })

        val landed = imported(sqlEntry(id, body = "SELECT 2 AS n", typed = false))

        landed["type"].asText() shouldBe "sql"
        typeOf(id, 2) shouldBe "sql"
        versions(id) shouldBe "1:DISCARDED,2:RELEASED"
    }

    @Test
    fun `B3b - version-less - the retained HTML identity is honoured`() {
        val id = "test/i883/card.html"
        allDiscarded(id, 1, { htmlEntry(id) })

        val landed = imported(htmlEntry(id, body = "<p>again</p>"))

        landed["type"].asText() shouldBe "html"
        typeOf(id, 2) shouldBe "html"
        versions(id) shouldBe "1:DISCARDED,2:RELEASED"
        pointer(id) shouldBe 2
    }

    @Test
    fun `B3c - version-less - an SQL payload onto a retained HTML template is type_immutable and writes nothing`() {
        val id = "test/i883/held.html"
        allDiscarded(id, 1, { htmlEntry(id) })
        val before = versionRows()

        val refused = importTemplates(sqlEntry(id, typed = true))

        withClue(refused.asString()) { refused.statusCode shouldBe 400 }
        error(refused)["code"].asText() shouldBe TYPE_IMMUTABLE
        error(refused)["details"]["established_type"].asText() shouldBe "html"
        versionRows() shouldBe before
        versions(id) shouldBe "1:DISCARDED"
        pointer(id) shouldBe null
    }

    // ---- B.5: the exact-version path (ruling 2) ---------------------------------------------------------------

    @Test
    fun `B5 - exact-version - a present DISCARDED number is still refused, an absent higher number lands and sets the pointer`() {
        val id = "test/i883/exact.sql"
        imported(sqlEntry(id, body = "SELECT 1 AS n"))
        imported(sqlEntry(id, body = "SELECT 2 AS n"))
        val v1 = exported(id)
        discard(id, 2)
        discard(id, 1)
        versions(id) shouldBe "1:DISCARDED,2:DISCARDED"

        withClue("present, DISCARDED: §9.2's row, unchanged") {
            val refused = importTemplates(v1)
            refused.statusCode shouldBe 409
            error(refused)["code"].asText() shouldBe VERSION_CONFLICT
            error(refused)["details"]["target_status"].asText() shouldBe "DISCARDED"
        }

        val landed = imported(retargeted(v1, id, 5))

        landed["version"].asInt() shouldBe 5
        landed["status"].asText() shouldBe "RELEASED"
        versions(id) shouldBe "1:DISCARDED,2:DISCARDED,5:RELEASED"
        pointer(id) shouldBe 5
    }

    @Test
    fun `B5-3a - exact-version - an omitted type inherits the retained SQL type`() {
        val id = "test/i883/exact-omitted.sql"
        allDiscarded(id, 1, { sqlEntry(id) })

        val landed = imported(retargeted(sqlDonor, id, 2, withType = false))

        landed["type"].asText() shouldBe "sql"
        typeOf(id, 2) shouldBe "sql"
        versions(id) shouldBe "1:DISCARDED,2:RELEASED"
        pointer(id) shouldBe 2
    }

    @Test
    fun `B5-3b - exact-version - the retained HTML identity is honoured`() {
        val id = "test/i883/exact-card.html"
        allDiscarded(id, 1, { htmlEntry(id) })

        val landed = imported(retargeted(htmlDonor, id, 3))

        landed["type"].asText() shouldBe "html"
        typeOf(id, 3) shouldBe "html"
        versions(id) shouldBe "1:DISCARDED,3:RELEASED"
        pointer(id) shouldBe 3
    }

    @Test
    fun `B5-3c - exact-version - an SQL payload onto a retained HTML template is type_immutable and writes nothing`() {
        val id = "test/i883/exact-held.html"
        allDiscarded(id, 1, { htmlEntry(id) })
        val before = versionRows()

        val refused = importTemplates(retargeted(sqlDonor, id, 2))

        withClue(refused.asString()) { refused.statusCode shouldBe 400 }
        error(refused)["code"].asText() shouldBe TYPE_IMMUTABLE
        error(refused)["details"]["established_type"].asText() shouldBe "html"
        versionRows() shouldBe before
        versions(id) shouldBe "1:DISCARDED"
        pointer(id) shouldBe null
    }

    // ---- B.6: workspaces --------------------------------------------------------------------------------------

    @Test
    fun `B6 - an id all-discarded only in A is absent for B - B gets v1, A is untouched, then each allocates its own next`() {
        val id = "test/i883/shared.sql"
        allDiscarded(id, 2, { n -> sqlEntry(id, body = "SELECT '$A_SECRET' AS n$n") })
        val aBefore = snapshot(id, WORKSPACE_A_ID)

        val inB = importTemplates(sqlEntry(id, body = "SELECT 'b' AS n"), session = SESSION_B)

        withClue(inB.asString()) { inB.statusCode shouldBe 200 }
        mapper.readTree(inB.asString())["data"]["templates"][0]["version"].asInt() shouldBe 1
        withClue("B's answer names nothing of A") {
            inB.asString() shouldNotContain A_SECRET
            inB.asString() shouldNotContain WORKSPACE_A_ID
            inB.asString() shouldNotContain WORKSPACE_A
        }
        versions(id, WORKSPACE_B_ID) shouldBe "1:RELEASED"
        withClue("A's rows are byte-identical") { snapshot(id, WORKSPACE_A_ID) shouldBe aBefore }

        imported(sqlEntry(id, body = "SELECT 'a3' AS n"))["version"].asInt() shouldBe 3
        imported(sqlEntry(id, body = "SELECT 'b2' AS n"), session = SESSION_B)["version"].asInt() shouldBe 2
        versions(id, WORKSPACE_A_ID) shouldBe "1:DISCARDED,2:DISCARDED,3:RELEASED"
        versions(id, WORKSPACE_B_ID) shouldBe "1:RELEASED,2:RELEASED"
        pointer(id, WORKSPACE_A_ID) shouldBe 3
        pointer(id, WORKSPACE_B_ID) shouldBe 1
    }

    // ---- Security: the role row is unchanged (auth.md §7.6 `template.import`: viewer and promoter refused) -------

    @Test
    fun `a viewer is still refused the import of an all-discarded template, and nothing is written`() {
        val id = "test/i883/viewer.sql"
        allDiscarded(id, 1, { sqlEntry(id) })
        val before = versionRows()

        val refused = importTemplates(sqlEntry(id, body = "SELECT 2 AS n"), session = SESSION_VIEWER)

        withClue(refused.asString()) { refused.statusCode shouldBe 403 }
        versionRows() shouldBe before
        versions(id) shouldBe "1:DISCARDED"
    }

    // ---- the fixture ------------------------------------------------------------------------------------------

    /** Released donors in A whose exports supply valid `body_hash` payloads for the exact-version cases. */
    private val sqlDonor: ObjectNode by lazy { donor(sqlEntry(SQL_DONOR, body = "SELECT 42 AS n"), "sql") }
    private val htmlDonor: ObjectNode by lazy { donor(htmlEntry(HTML_DONOR, body = "<p>donor</p>"), "html") }

    private fun donor(
        entry: Map<String, Any?>,
        type: String,
    ): ObjectNode {
        imported(entry)
        return exported(entry.getValue("id") as String).also { it["type"].asText() shouldBe type }
    }

    companion object {
        private const val SQL_DONOR = "test/i883/donor.sql"
        private const val HTML_DONOR = "test/i883/donor.html"
        private const val WORKSPACE_A = "i883-a"
        private const val WORKSPACE_B = "i883-b"
        private const val A_SECRET = "a-only-883"
        private const val TYPE_IMMUTABLE = "template.validation.type_immutable"
        private const val VERSION_CONFLICT = "template.version.conflict"

        private val WORKSPACE_A_ID: String = UUID.randomUUID().toString()
        private val WORKSPACE_B_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val VIEWER_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION_A get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-883-admin@datapipelines.test", WORKSPACE_A)
        private val SESSION_B get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-883-admin@datapipelines.test", WORKSPACE_B)
        private val SESSION_VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "e2e-883-viewer@datapipelines.test", WORKSPACE_A)

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
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES" +
                            " ('$WORKSPACE_A_ID', '$WORKSPACE_A', 'Import 883 A'), ('$WORKSPACE_B_ID', '$WORKSPACE_B', 'Import 883 B')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-883-admin@datapipelines.test', 'E2E 883 admin', 'test', 'e2e-883-admin', TRUE, FALSE)," +
                            " ('$VIEWER_ID', 'e2e-883-viewer@datapipelines.test', 'E2E 883 viewer', 'test', 'e2e-883-view', TRUE, FALSE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_A_ID', '$ADMIN_ID', 'workspace_admin'), ('$WORKSPACE_B_ID', '$ADMIN_ID', 'workspace_admin')," +
                            " ('$WORKSPACE_A_ID', '$VIEWER_ID', 'viewer')",
                    )
                }
            }
        }
    }
}
