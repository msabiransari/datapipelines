package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * **The tree's continuation over the real HTTP stack (#464 A.6).**
 *
 * [LEAVES] released pipelines in ONE folder — more than a transport page (`NavigationRequest.PAGE_SIZE`,
 * 200) — walked through `GET /api/v1/pipelines/tree` as a signed-in author, following
 * `data.next_cursor` until it is null: at least two pages, every leaf exactly once, no key twice,
 * in binary path order. Then the continuation's refusals, each the catalogued §13.24 code in the
 * §4.2 envelope (never the `pipeline.execution.aborted` stand-in):
 *
 * - a cursor with ONE character of its signature flipped is `navigation.request.invalid`. The
 *   FIRST signature character is flipped, never the last: a 32-byte HMAC's 43rd base64url
 *   character carries two padding bits a lenient decoder ignores, so a last-character flip can
 *   decode to the same bytes and prove nothing;
 * - the first page's cursor replayed by a SECOND member of the same workspace is
 *   `navigation.request.invalid` (the continuation is bound to the actor's view);
 * - a 201-character `q` on the search route is `request.query_too_long` (`SearchNeedle.MAX_LENGTH`
 *   is 200; this module compiles against `:modules:app` alone, so the bound is spelled here).
 *
 * No refusal body echoes the cursor it refused.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class NavigationTreeContinuationE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `one folder past a page continues to every leaf exactly once in binary path order`() {
        ensureSeeded()
        val pages = walk(AUTHOR_SESSION)

        withClue("non-vacuity: the folder must span more than one transport page") { pages.size shouldBeGreaterThanOrEqual 2 }
        val kinds = pages.flatMap { it.getList<String>("data.nodes.kind") }
        val paths = pages.flatMap { it.getList<String>("data.nodes.path") }
        val keys = pages.flatMap { it.getList<String>("data.nodes.key") }
        kinds.toSet() shouldBe setOf("artifact")
        withClue("every leaf once, in binary path order (ASCII names, so String order is byte order)") {
            paths shouldContainExactly LEAF_NAMES
        }
        keys.distinct().size shouldBe keys.size
        pages.last().getString("data.next_cursor") shouldBe null
    }

    @Test
    fun `a cursor with one signature character flipped is navigation request invalid`() {
        ensureSeeded()
        val cursor = firstCursor(AUTHOR_SESSION)
        val signatureStart = cursor.indexOf('.') + 1
        val flipped = cursor.replaceRange(signatureStart, signatureStart + 1, if (cursor[signatureStart] == 'A') "B" else "A")
        flipped shouldNotBe cursor

        val refused = tree(AUTHOR_SESSION, cursor = flipped)
        refused.refusedWith(HTTP_BAD_REQUEST, NAVIGATION_REQUEST_INVALID)
        refused.body().asString().contains(flipped) shouldBe false
    }

    @Test
    fun `the first page's cursor replayed by another member of the workspace is navigation request invalid`() {
        ensureSeeded()
        val cursor = firstCursor(AUTHOR_SESSION)
        withClue("control: the cursor continues for the member it was issued to") {
            tree(AUTHOR_SESSION, cursor = cursor).statusCode shouldBe HTTP_OK
        }

        val replayed = tree(VIEWER_SESSION, cursor = cursor)
        replayed.refusedWith(HTTP_BAD_REQUEST, NAVIGATION_REQUEST_INVALID)
        replayed.body().asString().contains(cursor) shouldBe false
    }

    @Test
    fun `a search term over the shared bound is request query too long`() {
        ensureSeeded()
        val response =
            given()
                .port(port)
                .asSession(AUTHOR_SESSION)
                .queryParam("root", "")
                .queryParam("q", "q".repeat(SEARCH_NEEDLE_MAX + 1))
                .get("/api/v1/pipelines/tree/search")
        response.refusedWith(HTTP_BAD_REQUEST, "request.query_too_long")
    }

    // ------------------------------------------------------------------------------- helpers

    /** Every page of the seeded folder, following `next_cursor` until the server stops issuing one. */
    private fun walk(session: String): List<JsonPath> {
        val pages = mutableListOf<JsonPath>()
        var cursor: String? = null
        do {
            val response = tree(session, cursor)
            withClue("page ${pages.size + 1}: ${response.body().asString().take(EXCERPT)}") { response.statusCode shouldBe HTTP_OK }
            val page = response.jsonPath()
            pages += page
            cursor = page.getString("data.next_cursor")
            check(pages.size <= MAX_PAGES) { "the continuation did not terminate within $MAX_PAGES pages" }
        } while (cursor != null)
        return pages
    }

    private fun firstCursor(session: String): String =
        checkNotNull(tree(session).jsonPath().getString("data.next_cursor")) { "the first page issued no continuation" }

    private fun tree(
        session: String,
        cursor: String? = null,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .queryParam("root", "")
            .queryParam("parent", FOLDER)
            .apply { if (cursor != null) queryParam("cursor", cursor) }
            .get("/api/v1/pipelines/tree")

    /** The §4.2 envelope with [code], and none of the `onResponseStatus` stand-in's markers. */
    private fun Response.refusedWith(
        status: Int,
        code: String,
    ) {
        val body = body().asString()
        withClue("$statusCode ${body.take(EXCERPT)}") {
            statusCode shouldBe status
            val json = jsonPath()
            json.getString("error.code") shouldBe code
            json.getString("correlation_id") shouldNotBe null
            json.getString("error.details.reason") shouldBe null
        }
    }

    private companion object {
        /** One folder holding a page and a bit; the padding keeps the names' binary order numeric. */
        private const val LEAVES = 205
        private const val FOLDER = "navcont/pipelines"
        private val LEAF_NAMES = (0 until LEAVES).map { "$FOLDER/leaf_${it.toString().padStart(3, '0')}" }

        /** `SearchNeedle.MAX_LENGTH` (pipeline-contract), not on this module's compile classpath. */
        private const val SEARCH_NEEDLE_MAX = 200
        private const val NAVIGATION_REQUEST_INVALID = "navigation.request.invalid"
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val MAX_PAGES = 10
        private const val EXCERPT = 400

        private const val WORKSPACE = "navcont-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val AUTHOR_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_ID, "navcont-author@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "navcont-viewer@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        private var seeded = false

        /** The workspace, its two members and [LEAVES] released pipelines, one statement per table. */
        private fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            val ids = LEAF_NAMES.associateWith { UUID.randomUUID() }
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Tree E2E')")
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$AUTHOR_ID', 'navcont-author@e2e.test', 'Author', 'test', 'navcont-author', TRUE, FALSE), " +
                            "('$VIEWER_ID', 'navcont-viewer@e2e.test', 'Viewer', 'test', 'navcont-viewer', TRUE, FALSE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                            "('$WORKSPACE_ID', '$AUTHOR_ID', 'author'), ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')",
                    )
                    statement.execute(
                        "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES " +
                            ids.entries.joinToString(", ") { (name, id) ->
                                "('$id', '$name', '$name', '', '$AUTHOR_ID', '$WORKSPACE_ID', 1)"
                            },
                    )
                    statement.execute(
                        "INSERT INTO pipeline_versions" +
                            " (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES " +
                            ids.values.joinToString(", ") { id ->
                                "('$id', 1, '{}'::jsonb, 'hash-navcont-$id', 'RELEASED', '$AUTHOR_ID', '$AUTHOR_ID', NOW())"
                            },
                    )
                }
            }
        }

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

            // API mode: this suite only reads trees — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
