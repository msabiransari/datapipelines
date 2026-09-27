package co.datapipelines.parameters

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterConstraints
import co.datapipelines.typesystem.ParameterDeclaration
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode

/**
 * A parameter set as an author submits it (parameter-engine design record §3.1): the set's [name]
 * — the folder-path grammar pipelines and templates use — and its versioned [body].
 *
 * The name is NOT part of the body: `parameter_set_versions.body_json` stores the document with
 * `name` omitted (record §8.1), the `parameter_sets` row carries it, and a set is never renamed
 * (§3.1) — a write whose document names another set is refused. [ParameterSetReader] is the only
 * way in from JSON; it refuses what this model cannot represent before anything binds.
 */
data class ParameterSetDocument(
    val name: String,
    val body: ParameterSetBody,
)

/**
 * The versioned artifact (record §3.1, §8.1) — what `body_hash` covers, what a draft carries and
 * what a release freezes. Like a pipeline body (versioning §3.7), [displayName] and [description]
 * are content: they ride the release, and the `parameter_sets` row indexes the released values.
 *
 * [parameters] is ordered: display order is part of the definition and is what a renderer lays out.
 * Every key is spelled on all three use sites — the Kotlin-Jackson naming trap (a naming strategy
 * configured anywhere upstream must not be able to rewrite `display_name`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterSetBody(
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
    @field:JsonProperty("parameters") @get:JsonProperty("parameters") @param:JsonProperty("parameters")
    val parameters: List<ParameterDefinition> = emptyList(),
)

/**
 * One parameter of a set (record §3.2).
 *
 * Three fields stay [JsonNode] on purpose — [defaultValue], [hiddenExpression] and
 * [disabledExpression] — because their meaning depends on OTHER fields (the default is wire-encoded
 * in [type] and shaped by [cardinality]; an expression's literals are typed by the parameters it
 * references). Binding them to a Kotlin type here would either erase what the save-time validator
 * checks or throw at binding, where no catalogued code can be reported. The validator parses them;
 * the stored body carries the canonical form it printed (record §7, lane B's A.2).
 *
 * The value-level half of a parameter IS `typesystem`'s [ParameterDeclaration] ([declaration]) —
 * never a copy: the ONE validator (record P28) judges a submitted value against exactly what a
 * pipeline parameter would be judged against.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterDefinition(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("label") @get:JsonProperty("label") @param:JsonProperty("label")
    val label: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String? = null,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: LogicalType,
    @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
    val precision: Int? = null,
    @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
    val scale: Int? = null,
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: ParameterKind,
    @field:JsonProperty("cardinality") @get:JsonProperty("cardinality") @param:JsonProperty("cardinality")
    val cardinality: ParameterCardinality = ParameterCardinality.SINGLE,
    @field:JsonProperty("required") @get:JsonProperty("required") @param:JsonProperty("required")
    val required: Boolean = false,
    /** Wire-encoded for [type]; an array for a `MULTI`. Null means "no default" (a JSON null reads the same). */
    @field:JsonProperty("default_value") @get:JsonProperty("default_value") @param:JsonProperty("default_value")
    val defaultValue: JsonNode? = null,
    @field:JsonProperty("source") @get:JsonProperty("source") @param:JsonProperty("source")
    val source: SelectorSource? = null,
    @field:JsonProperty("depends_on") @get:JsonProperty("depends_on") @param:JsonProperty("depends_on")
    val dependsOn: List<String> = emptyList(),
    /** A §7 expression AST, or null (= `false`). */
    @field:JsonProperty("hidden_expression") @get:JsonProperty("hidden_expression") @param:JsonProperty("hidden_expression")
    val hiddenExpression: JsonNode? = null,
    /** A §7 expression AST, or null (= `false`). */
    @field:JsonProperty("disabled_expression") @get:JsonProperty("disabled_expression") @param:JsonProperty("disabled_expression")
    val disabledExpression: JsonNode? = null,
    /** `INPUT` only (record §3.5) — `typesystem`'s type, so a pipeline and a set declare rules identically. */
    @field:JsonProperty("constraints") @get:JsonProperty("constraints") @param:JsonProperty("constraints")
    val constraints: ParameterConstraints? = null,
    @field:JsonProperty("presentation") @get:JsonProperty("presentation") @param:JsonProperty("presentation")
    val presentation: Presentation? = null,
) {
    /** The value-level declaration the shared validator judges a value against (record P28). Derived — never on the wire. */
    @get:JsonIgnore
    val declaration: ParameterDeclaration
        get() = ParameterDeclaration(type, precision, scale, required, defaultValue, constraints, cardinality)
}

/**
 * The two kinds of control (record P23, enums.md §27): a value the user TYPES, or a value the user
 * PICKS. There is deliberately no third kind — a caller-supplied "fixed" value was considered and
 * rejected by the owner (record §13).
 */
enum class ParameterKind(
    @JsonValue val wire: String,
) {
    /** A free value the user types; always `SINGLE`; initial value hard-coded or from a one-row template. */
    INPUT("INPUT"),

    /** A value the user picks from options; `SINGLE` or `MULTI`; options hard-coded or from a template. */
    SELECT("SELECT"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): ParameterKind? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): ParameterKind =
            fromWireOrNull(value) ?: throw IllegalArgumentException("Unknown ParameterKind: ${value.safeEcho()}")
    }
}

/**
 * Where a parameter's options (a `SELECT`) or its initial value (an `INPUT`) come from (record §3.3,
 * §3.4): exactly one of [constants] or [template] + [datasource]. Both shapes deliver the same
 * `{value, display_value, is_default}` rows to a renderer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectorSource(
    @field:JsonProperty("constants") @get:JsonProperty("constants") @param:JsonProperty("constants")
    val constants: List<ConstantOption>? = null,
    /** The pipeline node's pin shape (`{id, version}`, both required) — pipeline-contract §4.6. */
    @field:JsonProperty("template") @get:JsonProperty("template") @param:JsonProperty("template")
    val template: TemplateRef? = null,
    /** A datasource name visible from the workspace (bound or global). */
    @field:JsonProperty("datasource") @get:JsonProperty("datasource") @param:JsonProperty("datasource")
    val datasource: String? = null,
) {
    /**
     * Which of the two shapes this is, or null when it states neither or both (the validator refuses
     * both). Derived — never on the wire.
     */
    @get:JsonIgnore
    val kind: SelectorSourceKind?
        get() =
            when {
                constants != null && template == null && datasource == null -> SelectorSourceKind.CONSTANTS
                constants == null && template != null -> SelectorSourceKind.TEMPLATE
                else -> null
            }
}

/** The closed set of source shapes (record §3.3/§3.4, enums.md §27). Derived — never a wire key. */
enum class SelectorSourceKind(
    val wire: String,
) {
    CONSTANTS("constants"),
    TEMPLATE("template"),
}

/** One hard-coded option (record §3.3, P6): `value` is canonical, `display_value` is presentation — never inferred from each other. */
data class ConstantOption(
    /** Wire-encoded for the parameter's type; never JSON null (a deliberate "nothing" is a sentinel row, §6.2). */
    @field:JsonProperty("value") @get:JsonProperty("value") @param:JsonProperty("value")
    val value: JsonNode,
    @field:JsonProperty("display_value") @get:JsonProperty("display_value") @param:JsonProperty("display_value")
    val displayValue: String,
    @field:JsonProperty("is_default") @get:JsonProperty("is_default") @param:JsonProperty("is_default")
    val isDefault: Boolean = false,
)

/**
 * How a renderer MAY show a parameter (record §3.7, P23c) — never what it means: nothing in
 * validation, binding or evaluation reads it except its own save-time check and the echo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Presentation(
    @field:JsonProperty("control") @get:JsonProperty("control") @param:JsonProperty("control")
    val control: PresentationControl? = null,
    @field:JsonProperty("format") @get:JsonProperty("format") @param:JsonProperty("format")
    val format: DisplayFormat? = null,
)

/**
 * The closed control catalogue (record §3.7, enums.md §27). Which ones a parameter may name
 * depends on kind × cardinality × type ([PresentationCatalogue]); the catalogue is additive — a new
 * value is a change-log row, never a bump.
 */
enum class PresentationControl(
    @JsonValue val wire: String,
) {
    TEXT("text"),
    TEXTAREA("textarea"),
    NUMBER("number"),
    TOGGLE("toggle"),
    CHECKBOX("checkbox"),
    CALENDAR("calendar"),
    CLOCK("clock"),
    DATETIME("datetime"),
    DROPDOWN("dropdown"),
    RADIO("radio"),
    LIST("list"),
    CHECKBOXES("checkboxes"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): PresentationControl? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): PresentationControl =
            fromWireOrNull(value) ?: throw IllegalArgumentException("Unknown PresentationControl: ${value.safeEcho()}")
    }
}

/**
 * A display format (record §3.7): [kind] for the numeric family, [pattern] (a `DateTimeFormatter`
 * pattern) for the temporal family, nothing for `STRING`/`BOOLEAN`/`BINARY`. The wire value is
 * always the canonical form; a format only changes what a renderer shows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DisplayFormat(
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: NumericFormatKind? = null,
    @field:JsonProperty("pattern") @get:JsonProperty("pattern") @param:JsonProperty("pattern")
    val pattern: String? = null,
)

/** The numeric display formats (record §3.7, enums.md §27). Decimal places come from `scale`, never from here. */
enum class NumericFormatKind(
    @JsonValue val wire: String,
) {
    PLAIN("plain"),

    /** The deployment's currency, carried once per evaluate response as `org` (record §5.3). */
    CURRENCY("currency"),
    PERCENT("percent"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWireOrNull(value: String?): NumericFormatKind? = entries.firstOrNull { it.wire == value }

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): NumericFormatKind =
            fromWireOrNull(value) ?: throw IllegalArgumentException("Unknown NumericFormatKind: ${value.safeEcho()}")
    }
}
