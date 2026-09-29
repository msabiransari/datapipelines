package co.datapipelines.visualization

import co.datapipelines.parameters.ParametersKey
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [DashboardReader]: the spec's §3.2 tree binds whole or every shape problem comes back with its path; each
 * list's `type` is its own; the vocabulary literals; the two exactly-one shapes; the occurrence bound.
 */
class DashboardReaderTest {
    private val reader = DashboardReader()

    @Test
    fun `the spec's worked document binds unchanged - every field where the spec puts it`() {
        val document = read(DocumentFixtures.dashboard())
        document.name shouldBe DocumentFixtures.DASHBOARD_NAME
        val body = document.body
        body.parameterSet shouldBe ArtifactRef("finance/parameters/reporting_period", 1)
        body.sources
            .single()
            .parameters
            .getValue("year") shouldBe ParameterBinding(parameter = "year")
        body.sources
            .single()
            .parameters
            .getValue("currency")
            .value
            ?.asText() shouldBe "USD"
        body.visualizations.single().visualization shouldBe ArtifactRef("finance/visualizations/monthly_revenue", 3)
        body.visualizations.single().timeoutSeconds shouldBe 120
        body.groups.single().members shouldBe listOf("year", "revenue_chart", "refresh_button")
        body.actions.single() shouldBe
            DashboardAction("refresh_overview", DashboardObjectType.REFRESH, ActionScope.TARGETS, listOf("revenue_chart"), initial = true)
        body.actionControls.single().action shouldBe "refresh_overview"
        body.parameterScopes shouldBe mapOf("year" to listOf("overview_group"))
        body.parameterState?.parameters?.getValue("currency") shouldBe StateOverride(visible = StateSetting.FORCE_FALSE)
        body.outgoingOverrides
            .getValue("revenue_source")
            .getValue("currency")
            .value
            .asText() shouldBe "USD"
        body.layout.grid.single() shouldBe GridItem("revenue_chart", 0, 0, 6, 4)
        body.layout.parameterSet shouldBe SetPlacement(LayoutPosition.LEFT)
        body.timeouts shouldBe DashboardTimeouts(300)
    }

    @Test
    fun `an unknown key is refused at EVERY level, each with its path - all of them in one read`() {
        val tree = DocumentFixtures.dashboard()
        tree.put("theme", "dark")
        tree.obj("parameter_set").put("id", "x")
        tree.obj("sources[0]").put("cache", true)
        tree.obj("sources[0].parameters.year").put("default", 1)
        tree.obj("visualizations[0]").put("width", 6)
        tree.obj("visualizations[0].inputs.revenue").put("column", "x")
        tree.obj("groups[0]").put("collapsed", true)
        tree.obj("actions[0]").put("debounce", 1)
        tree.obj("action_controls[0]").put("icon", "x")
        tree.obj("parameter_state").put("all", "x")
        tree.obj("parameter_state.dashboard").put("style", "x")
        tree.obj("outgoing_overrides.revenue_source.currency").put("type", "x")
        tree.obj("layout").put("gap", 1)
        tree.obj("layout.parameter_set").put("width", 1)
        tree.obj("layout.parameter_placements.year").put("order", 1)
        tree.obj("layout.grid[0]").put("z", 1)
        tree.obj("timeouts").put("render_seconds", 1)
        val failures = refused(tree)
        failures.map { it.details["reason"] }.toSet() shouldBe setOf(JsonScan.REASON_UNKNOWN_KEY)
        failures.map { it.path } shouldContainExactlyInAnyOrder
            listOf(
                "theme",
                "parameter_set.id",
                "sources[0].cache",
                "sources[0].parameters.year.default",
                "visualizations[0].width",
                "visualizations[0].inputs.revenue.column",
                "groups[0].collapsed",
                "actions[0].debounce",
                "action_controls[0].icon",
                "parameter_state.all",
                "parameter_state.dashboard.style",
                "outgoing_overrides.revenue_source.currency.type",
                "layout.gap",
                "layout.parameter_set.width",
                "layout.parameter_placements.year.order",
                "layout.grid[0].z",
                "timeouts.render_seconds",
            )
    }

    @Test
    fun `every object's type is mandatory and must be its list's - wrong_object_type or missing`() {
        val tree = DocumentFixtures.dashboard()
        tree.obj("groups[0]").put("type", "visualization")
        tree.obj("visualizations[0]").remove("type")
        tree.obj("actions[0]").put("type", "action_control")
        refused(tree).map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "groups[0].type" to DashboardReader.REASON_WRONG_OBJECT_TYPE,
                "visualizations[0].type" to JsonScan.REASON_MISSING,
                "actions[0].type" to DashboardReader.REASON_WRONG_OBJECT_TYPE,
            )
    }

    @Test
    fun `the vocabulary literals are closed - scope, state settings, positions`() {
        val tree = DocumentFixtures.dashboard()
        tree.obj("actions[0]").put("scope", "group")
        tree.obj("parameter_state.dashboard").put("visible", "hidden")
        tree.obj("layout.parameter_set").put("position", "center")
        refused(tree).map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "actions[0].scope" to JsonScan.REASON_NOT_ALLOWED,
                "parameter_state.dashboard.visible" to JsonScan.REASON_NOT_ALLOWED,
                "layout.parameter_set.position" to JsonScan.REASON_NOT_ALLOWED,
            )
    }

    @Test
    fun `a parameter binding and a placement name exactly one alternative - both or neither is ambiguous`() {
        val tree = DocumentFixtures.dashboard()
        tree.obj("sources[0].parameters.year").put("value", 2026)
        tree.obj("sources[0].parameters").putObject("region")
        tree.obj("layout.parameter_placements.year").put("region", "top")
        tree.obj("layout.parameter_placements").putObject("currency")
        refused(tree).map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "sources[0].parameters.year" to DashboardReader.REASON_AMBIGUOUS,
                "sources[0].parameters.region" to DashboardReader.REASON_AMBIGUOUS,
                "layout.parameter_placements.year" to DashboardReader.REASON_AMBIGUOUS,
                "layout.parameter_placements.currency" to DashboardReader.REASON_AMBIGUOUS,
            )
    }

    @Test
    fun `a literal is a scalar or a list of scalars - deeper is too_deep, walked without the host stack`() {
        var deep: JsonNode = JsonNodeFactory.instance.textNode("x")
        repeat(100_000) { deep = JsonNodeFactory.instance.arrayNode().add(deep) }
        val tree = DocumentFixtures.dashboard()
        tree.obj("sources[0].parameters.currency").set<JsonNode>("value", deep)
        tree.obj("outgoing_overrides.revenue_source.currency").set<JsonNode>("value", deep)
        refused(tree).map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
            listOf(
                "sources[0].parameters.currency.value" to JsonScan.REASON_TOO_DEEP,
                "outgoing_overrides.revenue_source.currency.value" to JsonScan.REASON_TOO_DEEP,
            )
    }

    @Test
    fun `the occurrence bound refuses BEFORE the occurrences are read`() {
        val tree = DocumentFixtures.dashboard()
        (tree.get("visualizations") as ArrayNode).add(JsonNodeFactory.instance.textNode("not an occurrence"))
        DashboardReader(VisualizationConfig(maxVisualizationsPerDashboard = 1))
            .read(tree)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .map { it.path to it.details["config_key"] } shouldBe
            listOf("visualizations" to VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path)
    }

    @Test
    fun `a missing name is name_invalid, a missing layout or occurrence list body_invalid`() {
        val tree = DocumentFixtures.dashboard()
        tree.remove("name")
        tree.remove("layout")
        tree.remove("visualizations")
        refused(tree).associate { it.path to it.code } shouldBe
            mapOf(
                "name" to DashboardErrorCodes.NAME_INVALID,
                "layout" to DashboardErrorCodes.BODY_INVALID,
                "visualizations" to DashboardErrorCodes.BODY_INVALID,
            )
    }

    @Test
    fun `a bind exception is body_invalid, never a 500`() {
        val drifted = DocumentFixtures.dashboard().put("display_name", 5)
        DocumentBinding
            .bind(drifted, DashboardBody::class.java, DashboardErrorCodes.BODY_INVALID)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .single()
            .code shouldBe DashboardErrorCodes.BODY_INVALID
    }

    @Test
    fun `readOrThrow carries every failure in the exception's details`() {
        val thrown = shouldThrow<ArtifactValidationException> { reader.readOrThrow(DocumentFixtures.dashboard().put("a", 1).put("b", 2)) }
        thrown.code shouldBe DashboardErrorCodes.BODY_INVALID
        (thrown.details["failures"] as List<*>).size shouldBe 2
    }

    @Test
    fun `the pre-scan's key tables are exactly the binding's properties - one definition, no drift`() {
        withClue("document (+ the name)") { propertiesOf(DashboardBody::class.java) + "name" shouldBe DashboardReader.DOCUMENT_KEYS }
        withClue("source") { propertiesOf(DashboardSource::class.java) shouldBe DashboardReader.SOURCE_KEYS }
        withClue("parameter binding") { propertiesOf(ParameterBinding::class.java) shouldBe DashboardReader.PARAMETER_BINDING_KEYS }
        withClue("occurrence") { propertiesOf(VisualizationOccurrence::class.java) shouldBe DashboardReader.OCCURRENCE_KEYS }
        withClue("input mapping") { propertiesOf(InputMapping::class.java) shouldBe DashboardReader.INPUT_MAPPING_KEYS }
        withClue("group") { propertiesOf(DashboardGroup::class.java) shouldBe DashboardReader.GROUP_KEYS }
        withClue("action") { propertiesOf(DashboardAction::class.java) shouldBe DashboardReader.ACTION_KEYS }
        withClue("action control") { propertiesOf(ActionControl::class.java) shouldBe DashboardReader.ACTION_CONTROL_KEYS }
        withClue("parameter state") { propertiesOf(ParameterStateOverrides::class.java) shouldBe DashboardReader.PARAMETER_STATE_KEYS }
        withClue("state override") { propertiesOf(StateOverride::class.java) shouldBe DashboardReader.STATE_OVERRIDE_KEYS }
        withClue("literal") { propertiesOf(LiteralValue::class.java) shouldBe DashboardReader.LITERAL_KEYS }
        withClue("layout") { propertiesOf(DashboardLayout::class.java) shouldBe DashboardReader.LAYOUT_KEYS }
        withClue("set placement") { propertiesOf(SetPlacement::class.java) shouldBe DashboardReader.SET_PLACEMENT_KEYS }
        withClue("placement") { propertiesOf(ParameterPlacement::class.java) shouldBe DashboardReader.PLACEMENT_KEYS }
        withClue("grid item") { propertiesOf(GridItem::class.java) shouldBe DashboardReader.GRID_ITEM_KEYS }
        withClue("timeouts") { propertiesOf(DashboardTimeouts::class.java) shouldBe DashboardReader.TIMEOUT_KEYS }
    }

    @Test
    fun `a source's parameter map and an occurrence's input map are bounded BEFORE their members are walked`() {
        val wideSource = DocumentFixtures.dashboard()
        val parameters = wideSource.obj("sources[0].parameters")
        val ceiling = checkNotNull(ParametersKey.MAX_PARAMETERS_PER_SET.max).toInt() // no set declares more; the reader's bound
        repeat(ceiling + 1 - parameters.size()) { i -> parameters.putObject("extra_$i").put("value", i) }
        refused(wideSource).map { it.path to it.details["config_key"] } shouldBe
            listOf("sources[0].parameters" to ParametersKey.MAX_PARAMETERS_PER_SET.path)
        val wideOccurrence = DocumentFixtures.dashboard()
        wideOccurrence.obj("visualizations[0].inputs").putObject("extra").put("source", "revenue_source")
        DashboardReader(VisualizationConfig(maxInputsPerVisualization = 1))
            .read(wideOccurrence)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .map { it.path to it.details["config_key"] } shouldBe
            listOf("visualizations[0].inputs" to VisualizationKey.MAX_INPUTS_PER_VISUALIZATION.path)
    }

    /** The L1b pass's F3: `sources[]` had no count bound — `dashboards_get` runs one release read per source. */
    @Test
    fun `the sources list is bounded by what the visualizations can consume - refused BEFORE any source is walked`() {
        val config = VisualizationConfig(maxVisualizationsPerDashboard = 2, maxInputsPerVisualization = 2)
        val wide = DocumentFixtures.dashboard()
        val sources = wide.get("sources") as ArrayNode
        repeat(DashboardReader.maxSources(config) + 1 - sources.size()) { i ->
            sources.addObject().put("name", "extra_$i").put("pipeline", "finance/pipelines/extra@1")
        }
        DashboardReader(config)
            .read(wide)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .map { it.path to it.details["reason"] } shouldBe listOf("sources" to JsonScan.REASON_TOO_MANY)
    }

    private fun read(tree: JsonNode): DashboardDocument =
        reader.read(tree).shouldBeInstanceOf<ReadOutcome.Read<DashboardDocument>>().document

    private fun refused(tree: JsonNode): List<ValidationFailure> =
        reader
            .read(tree)
            .shouldBeInstanceOf<ReadOutcome.Refused>()
            .result.failures
            .also { it.isEmpty() shouldBe false }
}
