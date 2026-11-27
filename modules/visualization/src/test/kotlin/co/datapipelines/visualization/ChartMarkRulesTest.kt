package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Gauge member rules through an independent value descriptor and the served descriptor (brief 1103 D1–D6). */
class ChartMarkRulesTest {
    private val channels = listOf(ChartChannel("value", required = true, node = ChannelNode.Shape("value")))
    private val columns = listOf(InputColumn("amount", LogicalType.DECIMAL))

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `bands and target have exact ordered refusals`(case: Case) {
        verdict(body(case.domain, case.mark)).refusals.map { it.code to it.pointer } shouldBe case.expected
    }

    @ParameterizedTest(name = "silent {0}")
    @MethodSource("silentCases")
    fun `mark rules are silent and an invalid domain keeps its sole refusal`(case: Case) {
        val refusals = verdict(body(case.domain, case.mark)).refusals.map { it.code to it.pointer }
        refusals.filter { it.first in MARK_CODES } shouldBe emptyList()
        refusals shouldBe case.expected
    }

    @Test
    fun `missing scale or domain and missing value shape leave the mark rules silent`() {
        for (value in listOf(
            """{"field":"amount","aggregate":"sum","type":"quantitative"}""",
            """{"field":"amount","aggregate":"sum","type":"quantitative","scale":{}}""",
            """{"field":"amount","aggregate":"sum","type":"quantitative","scale":{"domain":[0]}}""",
        )) {
            val body =
                ArtifactJson.mapper.readTree(
                    """{"inputs":{"sales":{}},"config":{
                      "mark":{"type":"gauge","bands":[{"to":130}],"target":130},"encoding":{"value":$value}}}""",
                )
            verdict(body).refusals shouldBe emptyList()
        }
        val body = body("[0,100]", """"bands":[{"to":130}],"target":130""")
        ChartSaveRules.check(body, columns, emptyList(), ChartMarkPolicy.LINE).refusals shouldBe emptyList()
    }

    @Test
    fun `non-number members are skipped without coercion`() {
        for (mark in listOf(
            """"bands":[{"to":"130"},{"to":null},{}],"target":"130"""",
            """"bands":{},"target":null""",
            """"bands":[]""",
        )) {
            verdict(body("[0,100]", mark)).refusals shouldBe emptyList()
        }
    }

    @Test
    fun `the served gauge descriptor reaches the same rules and fixed messages`() {
        val body = body("[0,100]", """"bands":[{"to":50},{"to":50},{"to":130}],"target":-1""")
        ChartSaveRules.check(body, columns).refusals shouldBe
            listOf(
                ChartRefusal(
                    ChartReason.BANDS_NOT_ASCENDING,
                    "/config/mark/bands/1/to",
                    "Each band's end must be greater than the ends before it.",
                ),
                ChartRefusal(
                    ChartReason.BANDS_OUTSIDE_DOMAIN,
                    "/config/mark/bands/2/to",
                    "A band's end must be above the scale's low end and at most its high end.",
                ),
                ChartRefusal(
                    ChartReason.TARGET_OUTSIDE_DOMAIN,
                    "/config/mark/target",
                    "The target must lie inside the scale's domain.",
                ),
            )
    }

    @Test
    fun `a reversed gauge domain with bands and target has only the scale refusal through save validation`() {
        val tree =
            ArtifactJson.mapper.readTree(
                checkNotNull(javaClass.getResource("/chart-examples/invalid/gauge-reversed-domain.json")).readText(),
            ) as ObjectNode
        tree.put("name", "examples/visualizations/reversed-gauge")
        (tree.path("config").path("mark") as ObjectNode).apply {
            putArray("bands").addObject().put("to", 50).put("tone", "warn")
            put("target", 130)
        }
        val outcome =
            ValidatorFakes.Fakes().visualizationValidator().validate(
                ValidatorFakes.WORKSPACE,
                ValidatorFakes.visualizationDocument(tree),
            )
        val failures = outcome.shouldBeInstanceOf<ArtifactValidation.Invalid>().result.failures
        failures.map { Triple(it.code, it.details["reason"], it.path) } shouldBe
            listOf(
                Triple(
                    VisualizationErrorCodes.CONFIG_SCHEMA_INVALID,
                    "chart.scale.domain_invalid",
                    ChartPaths.dotted(tree, "/config/encoding/value/scale/domain"),
                ),
            )
    }

    private fun body(
        domain: String,
        mark: String,
    ): JsonNode {
        val members = if (mark.isEmpty()) "" else ",$mark"
        return ArtifactJson.mapper.readTree(
            """{"inputs":{"sales":{}},"config":{"mark":{"type":"gauge"$members},"encoding":{
              "value":{"field":"amount","type":"quantitative","aggregate":"sum","scale":{"domain":$domain}}}}}""",
        )
    }

    private fun verdict(body: JsonNode): ChartSaveVerdict = ChartSaveRules.check(body, columns, channels, ChartMarkPolicy.LINE)

    data class Case(
        val name: String,
        val domain: String,
        val mark: String,
        val expected: List<Pair<String, String>>,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val MARK_CODES = setOf("chart.bands.outside_domain", "chart.bands.not_ascending", "chart.target.outside_domain")

        @JvmStatic
        fun cases(): List<Case> = bandCases() + targetAndRangeCases()

        private fun bandCases(): List<Case> =
            listOf(
                Case("50,100", "[0,100]", """"bands":[{"to":50},{"to":100}]""", emptyList()),
                Case(
                    "50,100.0001",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":100.0001}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/1/to"),
                ),
                Case(
                    "0,50,100",
                    "[0,100]",
                    """"bands":[{"to":0},{"to":50},{"to":100}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/0/to"),
                ),
                Case("0.0001,100", "[0,100]", """"bands":[{"to":0.0001},{"to":100}]""", emptyList()),
                Case(
                    "-0.0001,100",
                    "[0,100]",
                    """"bands":[{"to":-0.0001},{"to":100}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/0/to"),
                ),
                Case(
                    "50,50,100",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":50},{"to":100}]""",
                    listOf("chart.bands.not_ascending" to "/config/mark/bands/1/to"),
                ),
                Case(
                    "80,50,100",
                    "[0,100]",
                    """"bands":[{"to":80},{"to":50},{"to":100}]""",
                    listOf("chart.bands.not_ascending" to "/config/mark/bands/1/to"),
                ),
                Case(
                    "80,50,70",
                    "[0,100]",
                    """"bands":[{"to":80},{"to":50},{"to":70}]""",
                    listOf(
                        "chart.bands.not_ascending" to "/config/mark/bands/1/to",
                        "chart.bands.not_ascending" to "/config/mark/bands/2/to",
                    ),
                ),
                Case(
                    "50,130,100",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":130},{"to":100}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/1/to"),
                ),
                Case(
                    "50,-5,100",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":-5},{"to":100}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/1/to"),
                ),
                Case("100", "[0,100]", """"bands":[{"to":100}]""", emptyList()),
                Case("25,50,75,100", "[0,100]", """"bands":[{"to":25},{"to":50},{"to":75},{"to":100}]""", emptyList()),
                Case("stop short 50,80", "[0,100]", """"bands":[{"to":50},{"to":80}]""", emptyList()),
            )

        private fun targetAndRangeCases(): List<Case> =
            listOf(
                Case("target 0", "[0,100]", """"target":0""", emptyList()),
                Case("target 100", "[0,100]", """"target":100""", emptyList()),
                Case("target 100.0001", "[0,100]", """"target":100.0001""", listOf("chart.target.outside_domain" to "/config/mark/target")),
                Case("target -0.0001", "[0,100]", """"target":-0.0001""", listOf("chart.target.outside_domain" to "/config/mark/target")),
                Case(
                    "bands then target",
                    "[0,100]",
                    """"bands":[{"to":80},{"to":50}],"target":130""",
                    listOf(
                        "chart.bands.not_ascending" to "/config/mark/bands/1/to",
                        "chart.target.outside_domain" to "/config/mark/target",
                    ),
                ),
                Case("negative -20,0,50", "[-50,50]", """"bands":[{"to":-20},{"to":0},{"to":50}]""", emptyList()),
                Case(
                    "negative -50,0,50",
                    "[-50,50]",
                    """"bands":[{"to":-50},{"to":0},{"to":50}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/0/to"),
                ),
                Case("negative target -50", "[-50,50]", """"target":-50""", emptyList()),
                Case("fractional", "[0.5,1]", """"bands":[{"to":0.75},{"to":1}]""", emptyList()),
                Case(
                    "huge band 1e400",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":1e400}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/1/to"),
                ),
                Case("huge target 1e400", "[0,100]", """"target":1e400""", listOf("chart.target.outside_domain" to "/config/mark/target")),
                Case(
                    "huge band -1e400",
                    "[0,100]",
                    """"bands":[{"to":50},{"to":-1e400}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/1/to"),
                ),
                Case(
                    "huge target -1e400",
                    "[0,100]",
                    """"target":-1e400""",
                    listOf("chart.target.outside_domain" to "/config/mark/target"),
                ),
                Case(
                    "fourth band",
                    "[0,100]",
                    """"bands":[{"to":25},{"to":50},{"to":75},{"to":101}]""",
                    listOf("chart.bands.outside_domain" to "/config/mark/bands/3/to"),
                ),
            )

        @JvmStatic
        fun silentCases(): List<Case> =
            listOf(
                Case(
                    "no mark members reversed",
                    "[100,0]",
                    """""",
                    listOf("chart.scale.domain_invalid" to "/config/encoding/value/scale/domain"),
                ),
                Case(
                    "reversed with target",
                    "[100,0]",
                    """"bands":[{"to":50}],"target":130""",
                    listOf("chart.scale.domain_invalid" to "/config/encoding/value/scale/domain"),
                ),
                Case(
                    "equal",
                    "[5,5]",
                    """"bands":[{"to":5}]""",
                    listOf("chart.scale.domain_invalid" to "/config/encoding/value/scale/domain"),
                ),
                Case(
                    "nonfinite",
                    "[0,1e400]",
                    """"bands":[{"to":50}]""",
                    listOf("chart.scale.domain_invalid" to "/config/encoding/value/scale/domain"),
                ),
                Case(
                    "reversed band outside",
                    "[100,0]",
                    """"bands":[{"to":130}]""",
                    listOf("chart.scale.domain_invalid" to "/config/encoding/value/scale/domain"),
                ),
                Case("no mark members valid", "[0,100]", """""", emptyList()),
            )
    }
}
