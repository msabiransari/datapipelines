package co.datapipelines.templates

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

/*
 * The transform-nodes design's §2.2 model: the three blocks a transform template version
 * carries (`contract`, `invariants`, `tests`), stored as `contract_json` / `invariants_json` /
 * `tests_json` jsonb columns inside the version's body hash (D-T7).
 *
 * ## Unknown keys refuse, never drop
 *
 * Every shape here binds with Jackson's DEFAULT unknown-key rule (fail), which the
 * [TransformBlocks.mapper] keeps on: a typo (`contarct`, `collumns`) is a save-time refusal
 * (`template.contract_invalid`, detail `unknown_field`), not a silently dropped section. The
 * enclosing [TemplateDraft] keeps its own `ignoreUnknown = true` — the DTO rule at the wire
 * boundary — and the blocks are the strict interior.
 */

/** The mode a transform contract declares (record §2.2) — the wire value is the lowercase word. */
enum class TransformMode(
    @JsonValue val wire: String,
) {
    ROW("row"),
    TABLE("table"),
    VALUE("value"),
    ;

    companion object {
        /** Binds a wire value, or null when it is none of them — callers own the refusal code. */
        fun fromWire(wire: String): TransformMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One declared column of a contract's table input or table output (record §2.2): name, a
 * `LogicalType` wire name, optional precision/scale (type-system.md §4), and `nullable`
 * (default false — the CONTRACT's rule, deliberately not `ColumnSchema.nullable`'s tri-state;
 * see the scripting TypeGate's KDoc).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class ContractColumn(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: LogicalType,
    @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
    val precision: Int? = null,
    @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
    val scale: Int? = null,
    @field:JsonProperty("nullable") @get:JsonProperty("nullable") @param:JsonProperty("nullable")
    val nullable: Boolean = false,
)

/** One declared input of a transform contract — a staged table, or a single Context value. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(TransformInput.Table::class, name = "table"),
    JsonSubTypes.Type(TransformInput.Value::class, name = "value"),
)
sealed interface TransformInput {
    val kind: String

    /** A staged table: the contract names its columns (record §2.2). */
    @JsonIgnoreProperties(ignoreUnknown = false)
    data class Table(
        @field:JsonProperty("columns") @get:JsonProperty("columns") @param:JsonProperty("columns")
        val columns: List<ContractColumn>,
    ) : TransformInput {
        @get:JsonProperty("kind")
        override val kind: String get() = "table"
    }

    /** A single value: the contract names its LogicalType and optional precision/scale. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    data class Value(
        @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
        val type: LogicalType,
        @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
        val precision: Int? = null,
        @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
        val scale: Int? = null,
    ) : TransformInput {
        @get:JsonProperty("kind")
        override val kind: String get() = "value"
    }
}

/** The contract's declared output — a table of rows, one value, or one object (R4). */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(TransformOutput.Table::class, name = "table"),
    JsonSubTypes.Type(TransformOutput.Value::class, name = "value"),
    JsonSubTypes.Type(TransformOutput.Obj::class, name = "object"),
)
sealed interface TransformOutput {
    val kind: String

    /** A table output — `row`/`table` modes — with its declared columns. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    data class Table(
        @field:JsonProperty("columns") @get:JsonProperty("columns") @param:JsonProperty("columns")
        val columns: List<ContractColumn>,
    ) : TransformOutput {
        @get:JsonProperty("kind")
        override val kind: String get() = "table"
    }

    /** One value output (`value` mode) of a declared LogicalType. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    data class Value(
        @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
        val type: LogicalType,
        @field:JsonProperty("precision") @get:JsonProperty("precision") @param:JsonProperty("precision")
        val precision: Int? = null,
        @field:JsonProperty("scale") @get:JsonProperty("scale") @param:JsonProperty("scale")
        val scale: Int? = null,
    ) : TransformOutput {
        @get:JsonProperty("kind")
        override val kind: String get() = "value"
    }

    /** An object output (`value` mode) — written as-is to the Context (R4). */
    @JsonIgnoreProperties(ignoreUnknown = false)
    class Obj : TransformOutput {
        @get:JsonProperty("kind")
        override val kind: String get() = "object"

        override fun equals(other: Any?): Boolean = other is Obj

        override fun hashCode(): Int = kind.hashCode()
    }
}

/**
 * The contract block (record §2.2): the mode, the declared inputs, the declared output, and
 * whether the function partitions rows into a `rejects` half (legal only with a table output).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class TransformContract(
    @field:JsonProperty("mode") @get:JsonProperty("mode") @param:JsonProperty("mode")
    val mode: TransformMode,
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, TransformInput>,
    @field:JsonProperty("output") @get:JsonProperty("output") @param:JsonProperty("output")
    val output: TransformOutput,
    @field:JsonProperty("rejects") @get:JsonProperty("rejects") @param:JsonProperty("rejects")
    val rejects: Boolean = false,
)

/**
 * One invariant (record §2.2): a JSONata expression over `{ rows, rejects, inputs }` that must
 * evaluate to boolean `true` on every test case and every real execution.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class TransformInvariant(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("expr") @get:JsonProperty("expr") @param:JsonProperty("expr")
    val expr: String,
    @field:JsonProperty("message") @get:JsonProperty("message") @param:JsonProperty("message")
    val message: String,
)

/**
 * One test case's input object (record §2.2, §3.2): mirrors production exactly (R2) — in `row`
 * mode `rows` is the batch and `inputs` holds the value inputs only (the table input is NOT
 * listed; the runner builds `inputs.<table>` from `rows` for the invariants). `meta` is
 * optional — the runner supplies `{ node_id: "test", context_key: null }` — and [now] pins the
 * instant `$now()`/`$millis()` return for a case that reads the clock (absent → the clock
 * builtins refuse, 7a's A.3 choice).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class TransformTestInput(
    @field:JsonProperty("rows") @get:JsonProperty("rows") @param:JsonProperty("rows")
    val rows: List<Map<String, Any?>>? = null,
    @field:JsonProperty("inputs") @get:JsonProperty("inputs") @param:JsonProperty("inputs")
    val inputs: Map<String, Any?>? = null,
    @field:JsonProperty("meta") @get:JsonProperty("meta") @param:JsonProperty("meta")
    val meta: Map<String, Any?>? = null,
    @field:JsonProperty("now") @get:JsonProperty("now") @param:JsonProperty("now")
    val now: String? = null,
)

/**
 * One test case's expectation (record §2.2): exactly one of [output] (the function's return
 * value, compared after canonicalisation — D-T12, no tolerances) or [refusal] (the code the run
 * must refuse with — a type-gate refusal or an engine refusal; an unexpected success is a
 * failure too). [output] is bound as a tree so a JSON-null expectation is distinguishable from
 * an absent one.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
data class TransformTestExpect(
    @field:JsonProperty("output") @get:JsonProperty("output") @param:JsonProperty("output")
    val output: JsonNode? = null,
    @field:JsonProperty("refusal") @get:JsonProperty("refusal") @param:JsonProperty("refusal")
    val refusal: String? = null,
)

/** One test case (record §2.2): a name, its input object, its expectation. */
@JsonIgnoreProperties(ignoreUnknown = false)
data class TransformTestCase(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("input") @get:JsonProperty("input") @param:JsonProperty("input")
    val input: TransformTestInput,
    @field:JsonProperty("expect") @get:JsonProperty("expect") @param:JsonProperty("expect")
    val expect: TransformTestExpect,
)

/**
 * The blocks' own mapper and the (de)serialization the repository's jsonb columns use.
 *
 * Distinct from [TemplateJson]'s mapper for one reason: the wire mapper relaxes unknown keys
 * at the DTO boundary, and these blocks must NOT relax them anywhere (see the file's KDoc).
 * The mapper writes compact JSON; the database's `jsonb` normalizes key order, and the hash
 * (`TemplateRepository.TEMPLATE_HASH_EXPR`) is computed in Postgres over the stored jsonb —
 * never in Kotlin — so two spellings of the same value cannot hash differently on the two
 * sides of a comparison.
 */
object TransformBlocks {
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()

    /** Serializes one contract block to the stored jsonb text, or null through. */
    fun writeContract(contract: TransformContract?): String? = contract?.let { mapper.writeValueAsString(it) }

    /** Serializes the invariants block; an empty list serializes as `[]` (never null). */
    fun writeInvariants(invariants: List<TransformInvariant>?): String? = invariants?.let { mapper.writeValueAsString(it) }

    /** Serializes the tests block. */
    fun writeTests(tests: List<TransformTestCase>?): String? = tests?.let { mapper.writeValueAsString(it) }

    /** Reads one stored `contract_json` back. */
    fun readContract(json: String?): TransformContract? = json?.let { mapper.readValue(it, TransformContract::class.java) }

    /** Reads one stored `invariants_json` back. */
    fun readInvariants(json: String?): List<TransformInvariant>? =
        json?.let {
            mapper.readValue(it, Array<TransformInvariant>::class.java).toList()
        }

    /** Reads one stored `tests_json` back. */
    fun readTests(json: String?): List<TransformTestCase>? =
        json?.let { mapper.readValue(it, Array<TransformTestCase>::class.java).toList() }
}
