package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * A visualization as an author submits it (the dashboard implementation spec §3.1, Dashboards §2.1): its
 * [name] — the folder-path grammar pipelines, templates and parameter sets use — and its versioned [body].
 * The name is NOT part of the body (`visualization_versions.body_json` stores the document with `name`
 * omitted, `chk_visualization_versions_body`), and a visualization is never renamed.
 * [VisualizationReader] is the only way in from JSON.
 */
data class VisualizationDocument(
    val name: String,
    val body: VisualizationBody,
)

/**
 * The versioned visualization artifact (the spec's §3.1, field for field — the JSON there is normative) —
 * what `body_hash` covers, what a draft carries and what a release freezes. [displayName] and [description]
 * are content: they ride the release and the index row indexes the current values (versioning §3.7).
 *
 * Every key is spelled on all three use sites — the Kotlin-Jackson naming trap. [config] is the renderer's
 * native configuration, stored VERBATIM and never interpreted server-side beyond the renderer validator's
 * shape checks (D4: no common chart language).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VisualizationBody(
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
    @field:JsonProperty("renderer") @get:JsonProperty("renderer") @param:JsonProperty("renderer")
    val renderer: RendererSpec,
    /** Named input contracts in declaration order (the transform contract's column vocabulary). */
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, InputContract>,
    @field:JsonProperty("transform") @get:JsonProperty("transform") @param:JsonProperty("transform")
    val transform: TransformBinding? = null,
    @field:JsonProperty("config") @get:JsonProperty("config") @param:JsonProperty("config")
    val config: ObjectNode,
    /** A JSON path into [config] → a column of the transform's OUTPUT (or of the single input). */
    @field:JsonProperty("bindings") @get:JsonProperty("bindings") @param:JsonProperty("bindings")
    val bindings: Map<String, String> = emptyMap(),
    @field:JsonProperty("presentation") @get:JsonProperty("presentation") @param:JsonProperty("presentation")
    val presentation: VisualizationPresentation? = null,
    @field:JsonProperty("tests") @get:JsonProperty("tests") @param:JsonProperty("tests")
    val tests: VisualizationTests? = null,
)

/** The renderer the configuration targets (D4) and the major version the host must provide. */
data class RendererSpec(
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: RendererKind,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: String,
)

/**
 * The renderer kinds (enums.md §31). `html` and `svg` are RESERVED (D13, D46): a body naming them binds, and
 * the validator refuses it `visualization.validation.renderer_unsupported` in round one.
 */
enum class RendererKind(
    @JsonValue val wire: String,
    /** False for the reserved kinds round one does not render. */
    val renderable: Boolean,
) {
    PLOTLY("plotly", renderable = true),
    TABLE("table", renderable = true),
    KPI("kpi", renderable = true),
    HTML("html", renderable = false),
    SVG("svg", renderable = false),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): RendererKind? = entries.firstOrNull { it.wire == value }
    }
}

/** One named input: the columns a source (or a fixture) must supply. */
data class InputContract(
    @field:JsonProperty("columns") @get:JsonProperty("columns") @param:JsonProperty("columns")
    val columns: List<InputColumn>,
)

/** One column of an input contract — the transform contract's vocabulary (`TransformContractView.Column`). */
data class InputColumn(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: LogicalType,
    @field:JsonProperty("nullable") @get:JsonProperty("nullable") @param:JsonProperty("nullable")
    val nullable: Boolean = false,
)

/**
 * The owned transformer binding (D9, D33): the pinned template version and, per contract input name, the
 * visualization input that feeds it. Absent, the renderer binds directly to the one input.
 */
data class TransformBinding(
    @field:JsonProperty("template") @get:JsonProperty("template") @param:JsonProperty("template")
    val template: ArtifactRef,
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, String>,
)

/** Presentation defaults (D11, D25): a title and theme-token references, never CSS. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class VisualizationPresentation(
    @field:JsonProperty("title") @get:JsonProperty("title") @param:JsonProperty("title")
    val title: String? = null,
    @field:JsonProperty("tokens") @get:JsonProperty("tokens") @param:JsonProperty("tokens")
    val tokens: Map<String, String> = emptyMap(),
)

/** The saved static-data tests (D34, the spec's §11.1) — versioned with the visualization. */
data class VisualizationTests(
    @field:JsonProperty("cases") @get:JsonProperty("cases") @param:JsonProperty("cases")
    val cases: List<TestCase> = emptyList(),
)

/** One case: fixture rows for EVERY input and the assertions the agent verifies against the preview. */
data class TestCase(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    /** Input name → rows in that input's column vocabulary (the `TransformTestInput.rows` shape). */
    @field:JsonProperty("fixtures") @get:JsonProperty("fixtures") @param:JsonProperty("fixtures")
    val fixtures: Map<String, List<ObjectNode>>,
    @field:JsonProperty("assertions") @get:JsonProperty("assertions") @param:JsonProperty("assertions")
    val assertions: List<Assertion>,
)

/** One assertion from the closed vocabulary ([AssertionKind]); `equals` and `text` are per-kind arguments. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Assertion(
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: AssertionKind,
    @field:JsonProperty("equals") @get:JsonProperty("equals") @param:JsonProperty("equals")
    val equals: Int? = null,
    @field:JsonProperty("text") @get:JsonProperty("text") @param:JsonProperty("text")
    val text: String? = null,
)

/** The assertion vocabulary (enums.md §32, the spec's §3.1) and the argument each kind takes. */
enum class AssertionKind(
    @JsonValue val wire: String,
    val argument: Argument,
) {
    /** The renderer reported completion. */
    RENDERED("rendered", Argument.NONE),

    /** The rendered trace count equals `equals`. */
    TRACE_COUNT("trace_count", Argument.EQUALS),

    /** The page logged no console error while rendering. */
    NO_CONSOLE_ERRORS("no_console_errors", Argument.NONE),

    /** A title, legend or cell string `text` is visible. */
    TEXT_VISIBLE("text_visible", Argument.TEXT),

    /** The case expects the empty state. */
    NO_DATA("no_data", Argument.NONE),

    /** A KPI value `text` is visible. */
    VALUE_VISIBLE("value_visible", Argument.TEXT),
    ;

    /** The one argument a kind carries, if any. */
    enum class Argument { NONE, EQUALS, TEXT }

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}

/** The JSON node types a reader reports, lower-case — `object`, `array`, `string`, `number`, … */
internal fun JsonNode.kindName(): String = nodeType.name.lowercase()
