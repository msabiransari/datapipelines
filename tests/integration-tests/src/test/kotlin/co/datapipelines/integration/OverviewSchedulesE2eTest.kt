package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
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
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.UUID

/**
 * **The Schedules home counts what the explorer lists** (#584, ui-screens §4.20a) — over the real
 * database, through the real lens, against §20.1's own answer.
 *
 * Four schedules over two released pipelines: `ov/a_enabled_ok` (enabled, its last run succeeded),
 * `ov/b_paused_failed` (paused, its last run failed), `ov/c_blocked` (blocked, never ran) and
 * `ov/d_enabled_new` (enabled, never ran). Schedules b and d target the pipeline the higher
 * environment already holds at its released version — hidden from the promoter (versioning §10.2),
 * the [SchedulePromoterPagingE2eTest] stub. A second workspace holds one more schedule the page
 * must never count or name. The schedules never fire (a yearly pattern; the scheduler is off).
 *
 * #641: each condition tile links the explorer filtered to it, so each count must equal what
 * `?condition=` walks to over §20.1 — for the viewer and through the promoter's lens.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OverviewSchedulesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `a viewer's home counts every schedule of the workspace, as the list does`() {
        seed()

        val page = page(VIEWER_SESSION)
        withClue("the four tiles and both charts, from the one summary") {
            counts(page) shouldBe mapOf("schedules" to 4, "enabled" to 2, "paused" to 1, "blocked" to 1)
            bars(page, "condition") shouldBe mapOf("enabled" to 2, "paused" to 1, "blocked" to 1)
            bars(page, "last-run") shouldBe
                mapOf("succeeded" to 1, "failed" to 1, "in-progress" to 0, "did-not-run" to 0, "never-ran" to 2)
        }
        withClue("§20.1 lists exactly the schedules the page counts") {
            listedNames(VIEWER_SESSION).size shouldBe counts(page).getValue("schedules")
        }
        withClue("Up next is the enabled, unblocked ones; Recently changed names all four") {
            section(page, "Up next") shouldContainExactly listOf("ov/a_enabled_ok", "ov/d_enabled_new")
            section(page, "Recently changed").sorted() shouldContainExactly NAMES
        }
        withClue("the other workspace's schedule is neither counted nor named; a viewer gets no New schedule") {
            page.contains(OTHER_NAME) shouldBe false
            page.contains("data-overview-new-schedule") shouldBe false
        }
    }

    @Test
    @Order(2)
    fun `a promoter's home counts only the admitted schedules, as its list does`() {
        val page = page(PROMOTER_SESSION)

        counts(page) shouldBe mapOf("schedules" to 2, "enabled" to 1, "paused" to 0, "blocked" to 1)
        bars(page, "last-run") shouldBe
            mapOf("succeeded" to 1, "failed" to 0, "in-progress" to 0, "did-not-run" to 0, "never-ran" to 1)
        listedNames(PROMOTER_SESSION) shouldContainExactly listOf("ov/a_enabled_ok", "ov/c_blocked")
        section(page, "Up next") shouldContainExactly listOf("ov/a_enabled_ok")
        withClue("no hidden schedule's name reaches the promoter's page") {
            listOf("ov/b_paused_failed", "ov/d_enabled_new", OTHER_NAME).filter { it in page } shouldBe emptyList()
        }
        page.contains("Admitted inventory") shouldBe true
    }

    @Test
    @Order(3)
    fun `an author's home offers New schedule`() {
        page(ADMIN_SESSION).contains("data-overview-new-schedule") shouldBe true
    }

    @Test
    @Order(4)
    fun `each condition tile counts what the explorer's condition filter lists, and the Unblock row opens it`() {
        val page = page(VIEWER_SESSION)

        withClue("the one blocked schedule is the Unblock row, first, linked to the filtered explorer") {
            tasks(page).first() shouldBe "unblock"
            page.substringAfter("data-task=\"unblock\"").substringBefore("</li>").let { row ->
                row.contains("href=\"/schedules?condition=blocked\"") shouldBe true
                row.contains("Unblock 1 schedule<") shouldBe true
            }
        }
        CONDITIONS.forEach { word ->
            withClue("§20.1 ?condition=$word walks to exactly the home's $word count") {
                walk(VIEWER_SESSION, word).size shouldBe counts(page).getValue(word)
            }
        }
        walk(VIEWER_SESSION, "blocked") shouldContainExactly listOf("ov/c_blocked")
        walk(VIEWER_SESSION, "paused") shouldContainExactly listOf("ov/b_paused_failed")
        walk(VIEWER_SESSION, "enabled") shouldContainExactly listOf("ov/a_enabled_ok", "ov/d_enabled_new")
    }

    @Test
    @Order(5)
    fun `a promoter's condition filter reads its admitted rows only, as its home counts them`() {
        val page = page(PROMOTER_SESSION)

        CONDITIONS.forEach { word ->
            withClue("the promoter's ?condition=$word walk equals its home's $word count") {
                walk(PROMOTER_SESSION, word).size shouldBe counts(page).getValue(word)
            }
        }
        walk(PROMOTER_SESSION, "blocked") shouldContainExactly listOf("ov/c_blocked")
        walk(PROMOTER_SESSION, "paused") shouldBe emptyList()
        walk(PROMOTER_SESSION, "enabled") shouldContainExactly listOf("ov/a_enabled_ok")
    }

    @Test
    @Order(6)
    fun `the explorer page and the list refuse a word outside the three`() {
        val filtered =
            session(VIEWER_SESSION)
                .get("/schedules?condition=blocked")
                .then()
                .statusCode(200)
                .extract()
                .asString()
        filtered.contains("data-schedule-condition=\"blocked\"") shouldBe true
        session(VIEWER_SESSION).get("/schedules?condition=x").then().statusCode(400)

        val refused = session(VIEWER_SESSION).get("/api/v1/schedules?condition=nope")
        refused.statusCode shouldBe 400
        refused.jsonPath().getString("error.code") shouldBe "schedule.validation.request_invalid"
        refused.jsonPath().getString("error.details.field") shouldBe "condition"
        refused.jsonPath().getString("error.details.expected") shouldBe "one of enabled, paused, blocked"
    }

    @Test
    @Order(7)
    fun `a paused schedule that blocks counts and filters as blocked - blocked wins on both sides`() {
        // Last: it changes the fixture. b is paused; blocking it makes the row a paused clause
        // without its blocked_at term would still list.
        sql(
            "UPDATE schedules SET blocked_reason = 'e2e', blocked_at = NOW() " +
                "WHERE workspace_id = '$WORKSPACE_ID' AND name = 'ov/b_paused_failed'",
        )
        val page = page(VIEWER_SESSION)

        counts(page) shouldBe mapOf("schedules" to 4, "enabled" to 2, "paused" to 0, "blocked" to 2)
        CONDITIONS.forEach { word ->
            withClue("?condition=$word equals the home's $word count") {
                walk(VIEWER_SESSION, word).size shouldBe counts(page).getValue(word)
            }
        }
        walk(VIEWER_SESSION, "blocked") shouldContainExactly listOf("ov/b_paused_failed", "ov/c_blocked")
        page.substringAfter("data-task=\"unblock\"").substringBefore("</li>").contains("Unblock 2 schedules<") shouldBe true
    }

    // ------------------------------------------------------------------------------- reading

    private fun page(jwt: String): String =
        session(jwt)
            .get("/overview/schedules")
            .then()
            .statusCode(200)
            .extract()
            .asString()

    private fun listedNames(jwt: String): List<String> {
        val body =
            session(jwt)
                .get("/api/v1/schedules?limit=100")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        body.getBoolean("data.pagination.has_more") shouldBe false
        return body.getList("data.items.name")
    }

    /** §20.1 with `condition`, one row a page through `has_more` — the filter must page, not only answer. */
    private fun walk(
        jwt: String,
        condition: String,
    ): List<String> {
        val names = ArrayList<String>()
        var offset = 0
        do {
            val body =
                session(jwt)
                    .get("/api/v1/schedules?condition=$condition&offset=$offset&limit=1")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
            names += body.getList<String>("data.items.name")
            offset += 1
        } while (body.getBoolean("data.pagination.has_more"))
        return names
    }

    private fun tasks(html: String): List<String> = TASK.findAll(html).map { it.groupValues[1] }.toList()

    private fun counts(html: String): Map<String, Int> = COUNT.findAll(html).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    private fun bars(
        html: String,
        chart: String,
    ): Map<String, Int> {
        val list = html.substringAfter("data-chart=\"$chart\"", "").substringBefore("</ul>")
        return BAR.findAll(list).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }

    private fun section(
        html: String,
        heading: String,
    ): List<String> {
        val recent = html.substringAfter("data-overview-recent", "")
        val part = recent.substringAfter(">$heading</h3>", "").substringBefore("</ul>")
        return ENTRY.findAll(part).map { it.groupValues[1] }.toList()
    }

    // ------------------------------------------------------------------------------- fixture

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    private fun seed() {
        sql(
            """
            INSERT INTO workspaces (id, name, display_name) VALUES
                ('$WORKSPACE_ID', '$WORKSPACE', 'Schedules home E2E'),
                ('$OTHER_ID', '$OTHER', 'Schedules home E2E, other')
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'ov-admin@e2e.test', 'OV Admin', 'test', 'ov-admin-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'ov-viewer@e2e.test', 'OV Viewer', 'test', 'ov-viewer-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'ov-promoter@e2e.test', 'OV Promoter', 'test', 'ov-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter'),
                ('$OTHER_ID', '$ADMIN_ID', 'workspace_admin')
            """.trimIndent(),
        )
        release(ADMIN_SESSION, VISIBLE_PIPELINE)
        release(ADMIN_SESSION, HIDDEN_PIPELINE)
        val ids =
            mapOf(
                "ov/a_enabled_ok" to VISIBLE_PIPELINE,
                "ov/b_paused_failed" to HIDDEN_PIPELINE,
                "ov/c_blocked" to VISIBLE_PIPELINE,
                "ov/d_enabled_new" to HIDDEN_PIPELINE,
            ).mapValues { (name, pipeline) -> schedule(ADMIN_SESSION, name, pipeline) }
        session(ADMIN_SESSION).post("/api/v1/schedules/${ids["ov/b_paused_failed"]}/pause").then().statusCode(200)
        sql("UPDATE schedules SET blocked_reason = 'e2e', blocked_at = NOW() WHERE id = '${ids["ov/c_blocked"]}'")
        run(ids.getValue("ov/a_enabled_ok"), "succeeded")
        run(ids.getValue("ov/b_paused_failed"), "failed")

        release(OTHER_SESSION, OTHER_PIPELINE)
        schedule(OTHER_SESSION, OTHER_NAME, OTHER_PIPELINE)
    }

    private fun release(
        jwt: String,
        name: String,
    ) {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to "OV $name",
                    "description" to "Schedules home leg",
                    "nodes" to
                        listOf(
                            mapOf(
                                "id" to "fq",
                                "type" to "CALCULATOR",
                                "kind" to "fiscal_quarter",
                                "context_key" to "run_fiscal_quarter",
                                "inputs" to mapOf("date" to "\$current_date", "fiscal_start" to "01-01"),
                            ),
                        ),
                ),
            )
        val created = session(jwt).contentType(ContentType.JSON).body(body).post("/api/v1/pipelines")
        check(created.statusCode == 201) { "pipeline $name → ${created.statusCode}: ${created.body().asString().take(400)}" }
        session(jwt)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
    }

    private fun schedule(
        jwt: String,
        name: String,
        pipeline: String,
    ): String {
        val created =
            session(jwt)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to name,
                            "payload" to mapOf("pipeline" to pipeline, "version" to "current"),
                            "cron" to "0 3 1 1 *",
                            "timezone" to "UTC",
                        ),
                    ),
                ).post("/api/v1/schedules")
        check(created.statusCode == 201) { "schedule $name → ${created.statusCode}: ${created.body().asString().take(400)}" }
        return created.jsonPath().getString("data.id")
    }

    /** A terminal run row straight into `schedule_runs` — the home reads states, not how they were reached. */
    private fun run(
        scheduleId: String,
        state: String,
    ) {
        sql(
            """
            INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at, reference_timezone,
                                       admit_by, executor_id, payload_schema_version, payload_json, actor_user_id, state)
            VALUES ('${UUID.randomUUID()}', '$scheduleId', '$WORKSPACE_ID', 'cron', NOW(), NOW(), 'UTC', NOW(),
                    'pipeline', 1, '{}'::jsonb, '$ADMIN_ID', '$state')
            """.trimIndent(),
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        private val NAMES = listOf("ov/a_enabled_ok", "ov/b_paused_failed", "ov/c_blocked", "ov/d_enabled_new")
        private const val VISIBLE_PIPELINE = "ov/visible_pipe"
        private const val HIDDEN_PIPELINE = "ov/hidden_pipe"
        private const val OTHER_PIPELINE = "ovo/other_pipe"
        private const val OTHER_NAME = "ovo/other_schedule"

        private val COUNT =
            Regex("""data-count="([a-z-]+)">.*?<dd class="app-overview-count-v">(\d+)</dd>""", RegexOption.DOT_MATCHES_ALL)
        private val BAR =
            Regex(
                """data-bar="([a-z-]+)" data-tone="[a-z]+">.*?<span class="app-overview-bar-v">(\d+)</span>""",
                RegexOption.DOT_MATCHES_ALL,
            )
        private val ENTRY = Regex("""href="/schedules\?id=[0-9a-f-]+"[^>]*>([^<]+)</a>""")
        private val TASK = Regex("""data-task="([a-z-]+)"""")
        private val CONDITIONS = listOf("enabled", "paused", "blocked")

        private const val WORKSPACE = "ov-e2e"
        private const val OTHER = "ov-e2e-other"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val OTHER_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "ov-admin@e2e.test", WORKSPACE)
        private val OTHER_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "ov-admin@e2e.test", OTHER)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "ov-viewer@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "ov-promoter@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /** The stub higher environment: it already holds the HIDDEN pipeline at its released version. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE",""" +
                                """"pipelines":[{"name":"$HIDDEN_PIPELINE","current_version":1,"body_hash":"hash-on-target"}],""" +
                                """"templates":[],"datasources":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
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

            // API mode: this suite only reads — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }

            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "ov-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
