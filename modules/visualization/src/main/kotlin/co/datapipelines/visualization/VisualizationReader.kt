package co.datapipelines.visualization

import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode

/** The result of a reader: the bound document, or every shape problem at once. */
sealed interface ReadOutcome<out D> {
    data class Read<D>(
        val document: D,
    ) : ReadOutcome<D>

    data class Refused(
        val result: ValidationResult,
    ) : ReadOutcome<Nothing>
}

/**
 * The ONE way a visualization document gets in from JSON (the spec's §3: "a strict reader — the
 * `ParameterSetReader` shape").
 *
 * The tree is walked FIRST, every level, against an EXPLICIT key table per level, and every problem that
 * would stop it binding is reported with its path: an unknown key, a wrong JSON type or a missing key is
 * `visualization.validation.body_invalid` (`details.reason` `unknown_key` / `wrong_type` / `missing`); a field
 * with its own code owns its refusal — the name (`name_invalid`), an unknown renderer kind
 * (`renderer_unsupported`), an unknown column type (`input_contract_invalid`), an unknown assertion kind
 * (`test_case_invalid`). The four collection bounds (`datapipelines.visualization.*`) are checked BEFORE their
 * members are walked, and `config`'s byte size before anything reads it (`too_many` / `too_large`).
 *
 * Everything else — a rule that needs the whole document or another aggregate — is [VisualizationValidator]'s.
 * `name` is outside the body: it is removed from the tree before the bind, so a stored body never carries it.
 */
class VisualizationReader(
    private val config: VisualizationConfig = VisualizationConfig(),
) {
    /** Reads [tree] into a [VisualizationDocument], or reports every shape problem at once. */
    fun read(tree: JsonNode): ReadOutcome<VisualizationDocument> {
        val failures = ArtifactFailures(VisualizationErrorCodes.BODY_INVALID)
        VisualizationScan(config, failures).document(tree)
        if (!failures.isEmpty) return ReadOutcome.Refused(failures.toResult())
        return when (val bound = DocumentBinding.bind(tree, VisualizationBody::class.java, VisualizationErrorCodes.BODY_INVALID)) {
            is ReadOutcome.Read -> ReadOutcome.Read(VisualizationDocument(bound.document.first, bound.document.second))
            is ReadOutcome.Refused -> bound
        }
    }

    /** [read] that throws [ArtifactValidationException] instead of returning a refusal. */
    fun readOrThrow(tree: JsonNode): VisualizationDocument =
        when (val outcome = read(tree)) {
            is ReadOutcome.Read -> outcome.document
            is ReadOutcome.Refused -> throw ArtifactValidationException(outcome.result, VisualizationErrorCodes.BODY_INVALID)
        }

    companion object {
        /** The keys of each level — the binding's own properties, pinned equal by `VisualizationReaderTest`. */
        val DOCUMENT_KEYS: Set<String> =
            setOf(
                "name",
                "display_name",
                "description",
                "renderer",
                "inputs",
                "transform",
                "config",
                "bindings",
                "presentation",
                "tests",
            )
        val RENDERER_KEYS: Set<String> = setOf("kind", "version")
        val INPUT_KEYS: Set<String> = setOf("columns")
        val COLUMN_KEYS: Set<String> = setOf("name", "type", "nullable")
        val TRANSFORM_KEYS: Set<String> = setOf("template", "inputs")
        val PRESENTATION_KEYS: Set<String> = setOf("title", "tokens")
        val TESTS_KEYS: Set<String> = setOf("cases")
        val CASE_KEYS: Set<String> = setOf("name", "fixtures", "assertions")
        val ASSERTION_KEYS: Set<String> = setOf("kind", "equals", "text")

        /** Every logical type is allowed in an input contract (the spec's §3.1) — `NULL` included. */
        val COLUMN_TYPES: List<String> = LogicalType.entries.map { it.wire }
    }
}

/** One walk over one visualization document; [document] is the whole API. */
private class VisualizationScan(
    private val config: VisualizationConfig,
    private val failures: ArtifactFailures,
) {
    private val scan = JsonScan(failures, VisualizationErrorCodes.BODY_INVALID)

    fun document(tree: JsonNode) {
        if (!tree.isObject) {
            scan.wrongType("", "object", tree)
            return
        }
        scan.unknownKeys(tree, VisualizationReader.DOCUMENT_KEYS, "")
        scan.requiredText(tree, "name", "name", VisualizationErrorCodes.NAME_INVALID)
        scan.requiredText(tree, "display_name", "display_name")
        scan.optionalText(tree, "description", "description")
        scan.objectAt(tree, "renderer", "renderer", required = true)?.let(::renderer)
        scan.objectAt(tree, "inputs", "inputs", required = true)?.let(::inputs)
        scan.objectAt(tree, "transform", "transform", required = false)?.let(::transform)
        scan.objectAt(tree, "config", "config", required = true)?.let(::config)
        scan.objectAt(tree, "bindings", "bindings", required = false)?.let(::bindings)
        scan.objectAt(tree, "presentation", "presentation", required = false)?.let(::presentation)
        scan.objectAt(tree, "tests", "tests", required = false)?.let(::tests)
    }

    private fun renderer(node: JsonNode) {
        scan.unknownKeys(node, VisualizationReader.RENDERER_KEYS, "renderer")
        scan.literal(
            node,
            "kind",
            "renderer.kind",
            RendererKind.WIRE_VALUES,
            code = VisualizationErrorCodes.RENDERER_UNSUPPORTED,
            required = true,
            unknownReason = "unknown_kind",
        )
        scan.requiredText(node, "version", "renderer.version")
    }

    private fun inputs(node: JsonNode) {
        node.properties().forEach { (name, input) ->
            val path = "inputs.$name"
            if (!input.isObject) {
                scan.wrongType(path, "object", input)
                return@forEach
            }
            scan.unknownKeys(input, VisualizationReader.INPUT_KEYS, path)
            scan.arrayAt(input, "columns", "$path.columns", required = true)?.forEachIndexed { index, column ->
                column(column, "$path.columns[$index]")
            }
        }
    }

    private fun column(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            scan.wrongType(path, "object", node)
            return
        }
        scan.unknownKeys(node, VisualizationReader.COLUMN_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        scan.literal(
            node,
            "type",
            "$path.type",
            VisualizationReader.COLUMN_TYPES,
            code = VisualizationErrorCodes.INPUT_CONTRACT_INVALID,
            required = true,
            unknownReason = "unknown_type",
        )
        scan.optionalBoolean(node, "nullable", "$path.nullable")
    }

    private fun transform(node: JsonNode) {
        scan.unknownKeys(node, VisualizationReader.TRANSFORM_KEYS, "transform")
        scan.present(node, "template")?.let { scan.ref(it, "transform.template") } ?: scan.missing("transform.template")
        scan.objectAt(node, "inputs", "transform.inputs", required = true)?.let { scan.stringValues(it, "transform.inputs") }
    }

    private fun config(node: JsonNode) {
        if (!scan.depthWithin(node, "config", JsonScan.MAX_RAW_DEPTH)) return
        val bytes = ArtifactJson.mapper.writeValueAsBytes(node).size
        if (bytes > config.maxConfigBytes) {
            failures.add(
                VisualizationErrorCodes.BODY_INVALID,
                "config",
                "config is $bytes bytes; at most ${config.maxConfigBytes} (${VisualizationKey.MAX_CONFIG_BYTES.path}).",
                mapOf(
                    "reason" to JsonScan.REASON_TOO_LARGE,
                    "bytes" to bytes,
                    "max" to config.maxConfigBytes,
                    "config_key" to VisualizationKey.MAX_CONFIG_BYTES.path,
                ),
            )
        }
    }

    private fun bindings(node: JsonNode) {
        if (node.size() > config.maxBindingsPerVisualization) {
            scan.tooMany("bindings", node.size(), VisualizationKey.MAX_BINDINGS_PER_VISUALIZATION, config.maxBindingsPerVisualization)
            return
        }
        scan.stringValues(node, "bindings")
    }

    private fun presentation(node: JsonNode) {
        scan.unknownKeys(node, VisualizationReader.PRESENTATION_KEYS, "presentation")
        scan.optionalText(node, "title", "presentation.title")
        scan.objectAt(node, "tokens", "presentation.tokens", required = false)?.let { scan.stringValues(it, "presentation.tokens") }
    }

    private fun tests(node: JsonNode) {
        scan.unknownKeys(node, VisualizationReader.TESTS_KEYS, "tests")
        val cases = scan.arrayAt(node, "cases", "tests.cases", required = false) ?: return
        if (cases.size() > config.maxCasesPerVisualization) {
            scan.tooMany("tests.cases", cases.size(), VisualizationKey.MAX_CASES_PER_VISUALIZATION, config.maxCasesPerVisualization)
            return
        }
        cases.forEachIndexed { index, case -> case(case, "tests.cases[$index]") }
    }

    private fun case(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            scan.wrongType(path, "object", node)
            return
        }
        scan.unknownKeys(node, VisualizationReader.CASE_KEYS, path)
        scan.requiredText(node, "name", "$path.name")
        scan.objectAt(node, "fixtures", "$path.fixtures", required = true)?.let { fixtures(it, "$path.fixtures") }
        scan.arrayAt(node, "assertions", "$path.assertions", required = true)?.forEachIndexed { index, assertion ->
            assertion(assertion, "$path.assertions[$index]")
        }
    }

    private fun fixtures(
        node: JsonNode,
        path: String,
    ) {
        val arrays = node.properties().filter { (_, rows) -> rows.isArray }
        val rows = arrays.sumOf { (_, rows) -> rows.size() }
        if (rows > config.maxFixtureRowsPerCase) {
            scan.tooMany(path, rows, VisualizationKey.MAX_FIXTURE_ROWS_PER_CASE, config.maxFixtureRowsPerCase)
            return
        }
        node.properties().forEach { (input, fixture) ->
            if (!fixture.isArray) {
                scan.wrongType("$path.$input", "array", fixture)
                return@forEach
            }
            fixture.forEachIndexed { index, row -> row(row, "$path.$input[$index]") }
        }
    }

    /** A fixture row: an object whose values are SCALARS — a column's value is never a structure. */
    private fun row(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            scan.wrongType(path, "object", node)
            return
        }
        node.properties().forEach { (column, value) ->
            if (value.isContainerNode) scan.wrongType(JsonScan.join(path, column), "scalar", value)
        }
    }

    private fun assertion(
        node: JsonNode,
        path: String,
    ) {
        if (!node.isObject) {
            scan.wrongType(path, "object", node)
            return
        }
        scan.unknownKeys(node, VisualizationReader.ASSERTION_KEYS, path)
        scan.literal(
            node,
            "kind",
            "$path.kind",
            AssertionKind.WIRE_VALUES,
            code = VisualizationErrorCodes.TEST_CASE_INVALID,
            required = true,
            unknownReason = "unknown_kind",
        )
        scan.optionalInt(node, "equals", "$path.equals")
        scan.optionalText(node, "text", "$path.text")
    }
}
