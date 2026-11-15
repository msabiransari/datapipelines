package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * **A9: the mechanical check's chart assertions judged by the engine, on the real wire** (js-rendered record §4.12, §8
 * A9; brief 986 A.2, G1). The whole application with the isolate on its classpath (this suite's test runtime), so the
 * runtime answers Available and the check is the engine's canonical render at 640 × 400. Charts are saved through REST
 * (the save never calls the engine) and judged by the on-demand check of their stored version 1.
 *
 * One case per row: a green and a red case for each assertion kind (`rendered`, `mark_count`, `no_data`,
 * `text_visible`), each judged from the source §4.12's table names; and one case per `state` the server can reach —
 * zero rows and every measure null (`no-data`), an over-cap chart and a ninth series (`refused`). Not reachable here:
 * `too-small` (the judge's frame is fixed at 640 × 400) and a negative pie (no pie type exists yet).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChartJudgeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** One expected case: its rendered state and its failures as (assertion path suffix, message), in order. */
    private data class Expect(
        val rendered: String,
        val failures: List<Pair<String, String>> = emptyList(),
    )

    @Test
    fun `each assertion kind is green and red on the engine's canonical render - and each reachable state is judged`() {
        seed()
        val report = check(save(KINDS_NAME, kindsDocument()))
        report.path("unjudged").size() shouldBe 0
        assertCases(report, KINDS_EXPECT)
    }

    @Test
    fun `eight series are drawn - a ninth is the engine's refusal, and every assertion over it fails`() {
        seed()
        val report = check(save(SERIES_NAME, seriesDocument()))
        report.path("unjudged").size() shouldBe 0
        assertCases(report, SERIES_EXPECT)
    }

    private fun assertCases(
        report: JsonNode,
        expected: Map<String, Expect>,
    ) {
        // The non-vacuity floor: every expected case was judged, and the report holds no other.
        report
            .path("cases")
            .fieldNames()
            .asSequence()
            .toSet() shouldBe expected.keys
        expected.size shouldBeGreaterThanOrEqual 2
        expected.forEach { (case, expect) ->
            withClue(case) {
                report
                    .path("cases")
                    .path(case)
                    .path("rendered")
                    .asText() shouldBe expect.rendered
                val failures =
                    report.path("failures").filter { it.path("case").asText() == case }.map {
                        it.path("path").asText() to it.path("message").asText()
                    }
                failures shouldBe expect.failures.map { (suffix, message) -> "tests.cases[${index(expected, case)}]$suffix" to message }
                report.path("failures").filter { it.path("case").asText() == case }.forEach {
                    it.path("step").asText() shouldBe "assertions"
                    it.path("code").asText() shouldBe "visualization.validation.test_case_invalid"
                }
            }
        }
        report.path("ok").asBoolean() shouldBe expected.values.all { it.failures.isEmpty() }
    }

    private fun index(
        expected: Map<String, Expect>,
        case: String,
    ): Int = expected.keys.indexOf(case)

    // ---- the documents -------------------------------------------------------------------------------------

    private fun row(
        region: String,
        amount: Number?,
    ): ObjectNode =
        mapper.createObjectNode().put("region", region).also {
            if (amount ==
                null
            ) {
                it.putNull("amount")
            } else {
                it.put("amount", amount.toDouble())
            }
        }

    private fun case(
        name: String,
        rows: List<ObjectNode>,
        vararg assertions: String,
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("name", name)
            putObject("fixtures").set<ArrayNode>("revenue", mapper.createArrayNode().addAll(rows))
            set<ArrayNode>("assertions", mapper.createArrayNode().apply { assertions.forEach { add(mapper.readTree(it)) } })
        }

    private fun kindsDocument(): String {
        val two = listOf(row("North", 3), row("South", 4), row("North", 5))
        val cases =
            listOf(
                case("rendered green", two, RENDERED),
                case("rendered red - every measure null", listOf(row("North", null), row("South", null)), RENDERED),
                case("mark_count green", two, marks(2)),
                case("mark_count red", two, marks(3)),
                case("no_data green - zero rows", emptyList(), NO_DATA, marks(0)),
                case("no_data red", listOf(row("North", 3)), NO_DATA),
                case("text_visible green", listOf(row("North", 3)), text("Regional revenue"), text("North")),
                case("text_visible red", listOf(row("North", 3)), text("Atlantis")),
                case("over the row cap", List(OVER_CAP) { row("North", 1) }, marks(1)),
            )
        return document(KINDS_NAME, color = false, cases)
    }

    private fun seriesDocument(): String {
        fun products(n: Int) = (1..n).map { row("North", it).put("product", "P$it") }
        val cases =
            listOf(
                case("eight series", products(MAX_SERIES), RENDERED, marks(MAX_SERIES)),
                case("a ninth series", products(MAX_SERIES + 1), RENDERED, marks(MAX_SERIES + 1)),
            )
        return document(SERIES_NAME, color = true, cases)
    }

    private fun document(
        name: String,
        color: Boolean,
        cases: List<ObjectNode>,
    ): String {
        // A colored bar names its stacking (the v1 schema requires it beside color).
        val stack = if (color) ""","stack":"zero"""" else ""
        val product = if (color) ""","color":{"field":"product","type":"nominal","sort":null}""" else ""
        val productColumn = if (color) """,{"name":"product","type":"STRING","nullable":false}""" else ""
        val bindings =
            if (color) """{"amount":"amount","product":"product","region":"region"}""" else """{"amount":"amount","region":"region"}"""
        val tree =
            mapper.readTree(
                """
                {"name":"$PREFIX/$name","display_name":"Chart judge","description":"#986 A9",
                 "renderer":{"kind":"chart","version":"1"},
                 "inputs":{"revenue":{"columns":[{"name":"region","type":"STRING","nullable":false},
                                                 {"name":"amount","type":"DECIMAL","nullable":true}$productColumn]}},
                 "config":{"title":{"text":"Regional revenue"},"mark":{"type":"bar"},
                           "encoding":{"x":{"field":"region","type":"nominal","sort":null},
                                       "y":{"field":"amount","type":"quantitative","aggregate":"sum"$stack}$product}},
                 "bindings":$bindings,
                 "tests":{"cases":[]}}
                """.trimIndent(),
            ) as ObjectNode
        (tree.path("tests").path("cases") as ArrayNode).addAll(cases)
        return mapper.writeValueAsString(tree)
    }

    // ---- the wire --------------------------------------------------------------------------------------------

    private fun save(
        name: String,
        document: String,
    ): String {
        val created = request().body(document).post("/api/v1/visualizations")
        withClue("$name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe HTTP_CREATED }
        return created.jsonPath().getString("data.id")
    }

    private fun check(id: String): JsonNode {
        val answer = request().post("/api/v1/visualizations/$id/versions/1/check")
        withClue(answer.asString().take(EXCERPT)) { answer.statusCode shouldBe HTTP_OK }
        val report = mapper.readTree(answer.asString()).path("data")
        println("event=chart_judge.report data=${mapper.writeValueAsString(report).take(REPORT_EXCERPT)}")
        return report
    }

    private fun request() =
        given()
            .port(port)
            .asSession(E2eSession.jwt(JWT_SECRET, ADMIN, "admin@chartjudge.test", WORKSPACE))
            .contentType(ContentType.JSON)

    private var seeded = false

    private fun seed() {
        if (seeded) return
        seeded = true
        E2eClean.beforeSeeding()
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { s ->
                s.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Chart judge')")
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN', 'admin@chartjudge.test', 'judge', 'test', 'chartjudge-$ADMIN', TRUE, FALSE)",
                )
                s.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN', 'workspace_admin')",
                )
            }
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val EXCERPT = 800
        const val REPORT_EXCERPT = 4_000

        /** The engine's row cap (record §4.9, lane 985 round 2) plus one; the fixture cap is raised to admit it. */
        const val OVER_CAP = 20_001

        /** The engine's series cap (O-15). */
        const val MAX_SERIES = 8

        const val WORKSPACE = "chartjudge-e2e"
        val WORKSPACE_ID = UUID.randomUUID().toString()
        val ADMIN = UUID.randomUUID().toString()
        const val PREFIX = "test/chartjudge"
        const val KINDS_NAME = "kinds"
        const val SERIES_NAME = "series"
        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()

        const val RENDERED = """{"kind":"rendered"}"""
        const val NO_DATA = """{"kind":"no_data"}"""

        fun marks(n: Int) = """{"kind":"mark_count","equals":$n}"""

        fun text(t: String) = """{"kind":"text_visible","text":"$t"}"""

        const val FRAME = "640 × 400"

        val KINDS_EXPECT =
            linkedMapOf(
                "rendered green" to Expect("rendered"),
                "rendered red - every measure null" to
                    Expect(
                        "no-data",
                        listOf(".assertions[0]" to "The chart drew no mark at $FRAME (state no-data) over the case's 2 rows."),
                    ),
                "mark_count green" to Expect("rendered"),
                "mark_count red" to Expect("rendered", listOf(".assertions[0]" to "The case's rows draw 2 marks; the case asserts 3.")),
                "no_data green - zero rows" to Expect("no-data"),
                "no_data red" to Expect("rendered", listOf(".assertions[0]" to "The case expects the empty state; its rows draw 1 marks.")),
                "text_visible green" to Expect("rendered"),
                "text_visible red" to
                    Expect("rendered", listOf(".assertions[0]" to "The text is not in any text the chart shows at $FRAME.")),
                "over the row cap" to
                    Expect(
                        "refused",
                        listOf(
                            ".assertions[0]" to
                                "The chart was not drawn at $FRAME (state refused, chart.too_many_rows); the case asserts 1 marks.",
                        ),
                    ),
            )

        val SERIES_EXPECT =
            linkedMapOf(
                "eight series" to Expect("rendered"),
                "a ninth series" to
                    Expect(
                        "refused",
                        listOf(
                            ".assertions[0]" to "The chart was not drawn at $FRAME (state refused, chart.too_many_series).",
                            ".assertions[1]" to
                                "The chart was not drawn at $FRAME (state refused, chart.too_many_series); the case asserts 9 marks.",
                        ),
                    ),
            )

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
            // The over-cap case: one case's fixture holds the engine's row cap plus one (the cap's own bound is 100,000).
            registry.add("datapipelines.visualization.max-fixture-rows-per-case") { OVER_CAP.toString() }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
