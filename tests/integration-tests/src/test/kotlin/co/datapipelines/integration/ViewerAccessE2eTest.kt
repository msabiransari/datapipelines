package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 143 (T315) — a VIEWER's session against every write the reader-facing screens hide, called
 * DIRECTLY: the template create/update/release and the editor's own Edit and Preview partials,
 * the pipeline release, the promotion send, the workspace member add and the instance user
 * administration. Each is refused with the catalogued role code, and the database is read
 * before and after — every template and pipeline version row, the member rows, the users'
 * active flags — so "refused" means "and nothing moved", not "answered 403".
 *
 * The reads the same session MAY make are asserted alongside (the editor page and its source
 * partial answer 200 — the route 143 lowered), and an AUTHOR's write through the same path
 * lands afterwards, so the refusals above are not the silence of a broken fixture.
 *
 * Beside `WorkspaceSurfacesE2eTest`, whose session-JWT and seeding shape this follows.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ViewerAccessE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a viewer's direct writes are refused with the role code and change nothing - an author's lands`() {
        ensureSeeded()
        val before = snapshot()

        // --- the reads a viewer may make (the doors 143 opened) answer 200
        viewer().get("/templates/editor?name=$TPL_NAME").then().statusCode(200)
        viewer().get("/partials/templates/editor/source?name=$TPL_NAME&version=1").then().statusCode(200)

        // --- the writes, each refused on the ROLE axis (a session has no scope axis)
        everyViewerWriteIsRefused()

        snapshot() shouldBe before

        // --- the positive control: the author's update through the same route lands as v2's new body
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test"))
            .cookie(CSRF_COOKIE, CSRF)
            .header(CSRF_HEADER, CSRF)
            .contentType(ContentType.JSON)
            .header(IF_MATCH, V2_HASH)
            .body(templateBody("SELECT 99 AS by_author"))
            .put("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.draft.version", Matchers.equalTo(2))
        snapshot().templateVersions.map { it.first } shouldBe before.templateVersions.map { it.first }
        (snapshot().templateVersions != before.templateVersions) shouldBe true
    }

    private fun everyViewerWriteIsRefused() {
        refused(
            viewer()
                .contentType(ContentType.JSON)
                .header(IF_MATCH, V2_HASH)
                .body(templateBody("SELECT 99"))
                .put("/api/v1/templates"),
        )
        refused(
            viewer().contentType(ContentType.JSON).body(templateBody("SELECT 3", id = "acme/new_by_viewer.sql")).post("/api/v1/templates"),
        )
        refused(
            viewer()
                .contentType(
                    ContentType.URLENC,
                ).formParam("name", TPL_NAME)
                .formParam("version", 1)
                .post("/partials/templates/editor/edit"),
        )
        refused(
            viewer()
                .contentType(ContentType.URLENC)
                .formParam("name", TPL_NAME)
                .formParam("version", 1)
                .formParam("body", "SELECT 1")
                .formParam("context", "{}")
                .post("/partials/templates/render"),
        )
        refused(
            viewer()
                .contentType(
                    ContentType.JSON,
                ).header(IF_MATCH, V2_HASH)
                .body("""{"name":"$TPL_NAME"}""")
                .post("/api/v1/templates/release"),
        )
        refused(viewer().contentType(ContentType.JSON).header(IF_MATCH, PIPE_HASH).post("/api/v1/pipelines/$PIPE_ID/release"))
        refused(viewer().contentType(ContentType.URLENC).formParam("name", "report").post("/promotion/promote"))
        refused(
            viewer()
                .contentType(ContentType.URLENC)
                .formParam("email", "newcomer@acme.test")
                .formParam("author", "on")
                .post("/workspaces/acme/members"),
        )
        refused(viewer().contentType(ContentType.JSON).post("/api/v1/auth/users/$ALICE/deactivate"))
        refused(viewer().contentType(ContentType.JSON).post("/api/v1/auth/users/$VERA/grant-admin"))
    }

    private fun refused(response: io.restassured.response.Response) {
        response
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.role_required"))
    }

    private fun viewer(): RequestSpecification =
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(VERA, "vera@acme.test"))
            .cookie(CSRF_COOKIE, CSRF)
            .header(CSRF_HEADER, CSRF)
            .accept("text/html, application/json")

    private fun templateBody(
        body: String,
        id: String = TPL_NAME,
    ): String = """{"id":"$id","type":"sql","dialect":"POSTGRES","display_name":"Sales","description":"","body":"$body"}"""

    /** The rows a reader must not move: template + pipeline versions, memberships, users' active/admin flags. */
    private data class Snapshot(
        val templateVersions: List<Pair<Int, String>>,
        val pipelineVersions: List<Pair<Int, String>>,
        val members: List<String>,
        val users: List<String>,
    )

    private fun snapshot(): Snapshot =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                fun rows(
                    sql: String,
                    map: (java.sql.ResultSet) -> String,
                ): List<String> = statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) map(rs) else null }.toList() }
                Snapshot(
                    templateVersions =
                        rows(
                            "SELECT version, status, body_hash, COALESCE(updated_at::text, '') FROM template_versions" +
                                " WHERE template_id = '$TPL_ID' ORDER BY version",
                        ) { "${it.getInt(1)}|${it.getString(2)}|${it.getString(3)}|${it.getString(4)}" }.map {
                            it.substringBefore("|").toInt() to
                                it
                        },
                    pipelineVersions =
                        rows(
                            "SELECT version, status, body_hash FROM pipeline_versions WHERE pipeline_id = '$PIPE_ID' ORDER BY version",
                        ) { "${it.getInt(1)}|${it.getString(2)}|${it.getString(3)}" }.map { it.substringBefore("|").toInt() to it },
                    members =
                        rows(
                            "SELECT user_id::text || ':' || author || promoter || admin FROM workspace_members ORDER BY 1",
                        ) { it.getString(1) },
                    users = rows("SELECT id::text || ':' || is_active || is_admin FROM users ORDER BY 1") { it.getString(1) },
                )
            }
        }

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "viewer-access-csrf"
        private const val IF_MATCH = "If-Match"
        private const val SECRET_BYTES = 32

        private const val ALICE = "aaa00000-0000-0000-0000-000000000143"
        private const val VERA = "eee00000-0000-0000-0000-000000000143"
        private const val WS_ACME = "aca00000-0000-0000-0000-000000000143"
        private const val PIPE_ID = "a1b00000-0000-0000-0000-000000000143"
        private const val TPL_ID = "a3b00000-0000-0000-0000-000000000143"
        private const val TPL_NAME = "acme/sales.sql"
        private const val V2_HASH = "seed-hash-v2"
        private const val PIPE_HASH = "seed-pipe-hash"

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"report","display_name":"Report","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"$TPL_NAME","version":1}}]}"""

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private fun sessionJwt(
            userId: String,
            email: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Test User","scopes":["read","execute","author"],""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":${now.plusSeconds(
                            3600,
                        ).epochSecond},"active_workspace":"acme"}""",
                )
            val signature =
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
                    b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
                }
            return "$header.$payload.$signature"
        }

        private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private var seeded = false

        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ACME', 'acme', 'Acme')")
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ALICE', 'alice@acme.test', 'Alice', 'test', 'alice-sub', TRUE, FALSE),
                            ('$VERA', 'vera@acme.test', 'Vera', 'test', 'vera-sub', TRUE, FALSE)
                        """.trimIndent(),
                    )
                    // Alice authors AND promotes (the positive control); Vera is a plain viewer.
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                            ('$WS_ACME', '$ALICE', 'promoter'),
                            ('$WS_ACME', '$VERA', 'viewer')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) VALUES
                            ('$TPL_ID', '$TPL_NAME', 'Sales', '', 1, '$WS_ACME', '$ALICE')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, body_hash, status, created_by, released_by, released_at) VALUES
                            ('$TPL_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'seed-hash-v1', 'RELEASED', '$ALICE', '$ALICE', NOW()),
                            ('$TPL_ID', 2, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 2', '$V2_HASH', 'DRAFT', '$ALICE', NULL, NULL)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                            ('$PIPE_ID', 'report', 'Acme Report', '', '$ALICE', '$WS_ACME', NULL)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by) VALUES
                            ('$PIPE_ID', 1, '$PIPELINE_BODY'::jsonb, '$PIPE_HASH', 'DRAFT', '$ALICE')
                        """.trimIndent(),
                    )
                }
            }
        }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
