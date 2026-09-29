package co.datapipelines.visualization

import co.datapipelines.pipeline.TransformContractView
import java.util.UUID

/**
 * The visualization save-time rules (the spec's §3.1) over a document the [VisualizationReader] bound — all of
 * them, every failure collected (§17.2), one code per rule:
 *
 * - the name (`name_invalid`) and the display text (`body_invalid`);
 * - `renderer_unsupported` — a reserved kind (`html`, `svg`), or a version that is not a major (`^[1-9][0-9]{0,3}$`);
 * - `input_contract_invalid` — no inputs, an input name outside the object grammar, an input with no columns,
 *   a column name that is blank, over 128 characters or carries a control character, a duplicate column;
 * - `transform_binding_invalid` — through [TemplateContractFacts]: the pin missing (or DISCARDED), not a
 *   transform, its contract's input names ≠ `transform.inputs`' keys, a value naming no input, a declared
 *   input's columns ≠ the named input's columns (by name, type and nullability — `details.column`), a
 *   visualization input the transform never reads; and, with no transform, more than one input. A DRAFT pin is
 *   accepted here and must be released at the visualization's release (the 142 cascade — §11.4);
 * - `config_schema_invalid` — the renderer's schema, through [RendererConfigValidator];
 * - `binding_unbound` — a binding path outside the [BindingPath] grammar or not resolving in `config`, or a
 *   column not in the transform's OUTPUT contract (or, with no transform, the one input's columns);
 * - `test_case_invalid` — a case without a name or a duplicate, fixtures that miss an input or name an unknown
 *   one, a row column outside the input's contract or a null where the column is not nullable, a case with no
 *   assertion, an assertion missing its argument or carrying one its kind does not take.
 *
 * Fixture VALUES are not judged here: the type gate judges them at the fixture run (§11.3 step 3, L4).
 */
class VisualizationValidator(
    private val templates: TemplateContractFacts,
    private val renderers: RendererConfigValidator = RendererConfigValidators.default(),
) {
    /** Every rule over [document]; the document itself when it passes. */
    fun validate(
        workspaceId: UUID,
        document: VisualizationDocument,
    ): ArtifactValidation<VisualizationDocument> {
        val failures = ArtifactFailures(VisualizationErrorCodes.BODY_INVALID)
        val body = document.body
        DocumentRules.name(document.name, VisualizationErrorCodes.NAME_INVALID, failures)
        DocumentRules.texts(body.displayName, body.description, VisualizationErrorCodes.BODY_INVALID, failures)
        presentation(body, failures)
        renderer(body, failures)
        inputs(body, failures)
        val output = transform(workspaceId, body, failures)
        if (body.renderer.kind.renderable) config(body, failures)
        bindings(body, output, failures)
        tests(body, failures)
        val result = failures.toResult()
        return if (result.isValid) ArtifactValidation.Valid(document) else ArtifactValidation.Invalid(result)
    }

    private fun presentation(
        body: VisualizationBody,
        failures: ArtifactFailures,
    ) {
        val presentation = body.presentation ?: return
        if ((presentation.title?.length ?: 0) > DocumentRules.MAX_DISPLAY_NAME_CHARS) {
            DocumentRules.tooLong(
                "presentation.title",
                DocumentRules.MAX_DISPLAY_NAME_CHARS,
                VisualizationErrorCodes.BODY_INVALID,
                failures,
            )
        }
        presentation.tokens.forEach { (token, value) ->
            if (!DocumentRules.OBJECT_NAME.matches(token) || !DocumentRules.OBJECT_NAME.matches(value.replace('-', '_'))) {
                failures.add(
                    VisualizationErrorCodes.BODY_INVALID,
                    "presentation.tokens.$token",
                    "A presentation token and its value are theme-token names ([a-z][a-z0-9_-], 64 characters).",
                    mapOf("reason" to "grammar"),
                )
            }
        }
    }

    private fun renderer(
        body: VisualizationBody,
        failures: ArtifactFailures,
    ) {
        if (!body.renderer.kind.renderable) {
            failures.add(
                VisualizationErrorCodes.RENDERER_UNSUPPORTED,
                "renderer.kind",
                "Renderer '${body.renderer.kind.wire}' is reserved; round one renders ${RendererKind.entries.filter {
                    it.renderable
                }.map { it.wire }}.",
                mapOf("reason" to "reserved", "kind" to body.renderer.kind.wire),
            )
        }
        if (!RENDERER_VERSION.matches(body.renderer.version)) {
            failures.add(
                VisualizationErrorCodes.RENDERER_UNSUPPORTED,
                "renderer.version",
                "renderer.version is the MAJOR version the host must provide, e.g. \"4\".",
                mapOf("reason" to "version_invalid"),
            )
        }
    }

    private fun inputs(
        body: VisualizationBody,
        failures: ArtifactFailures,
    ) {
        if (body.inputs.isEmpty()) contractInvalid("inputs", "no_inputs", "A visualization declares at least one input.", failures)
        body.inputs.forEach { (name, input) ->
            val path = "inputs.$name"
            if (!DocumentRules.OBJECT_NAME.matches(
                    name,
                )
            ) {
                contractInvalid(path, "name_invalid", "An input name is [a-z][a-z0-9_], 64 characters.", failures)
            }
            if (input.columns.isEmpty()) contractInvalid("$path.columns", "no_columns", "An input declares at least one column.", failures)
            val seen = mutableSetOf<String>()
            input.columns.forEachIndexed { index, column ->
                val columnPath = "$path.columns[$index].name"
                if (column.name.isBlank() || column.name.length > MAX_COLUMN_CHARS || column.name.any { it.isISOControl() }) {
                    contractInvalid(
                        columnPath,
                        "column_name_invalid",
                        "A column name is 1–$MAX_COLUMN_CHARS characters, no control character.",
                        failures,
                    )
                }
                if (!seen.add(
                        column.name,
                    )
                ) {
                    contractInvalid(columnPath, "duplicate_column", "Two columns of one input share a name.", failures)
                }
            }
        }
    }

    /** The transform's rules; answers the columns a binding may name (null when they cannot be known). */
    private fun transform(
        workspaceId: UUID,
        body: VisualizationBody,
        failures: ArtifactFailures,
    ): Set<String>? {
        val transform = body.transform
        if (transform == null) {
            if (body.inputs.size > 1) {
                bindingInvalid("transform", "transform_required", "With more than one input a transform must combine them.", failures)
            }
            return body.inputs.values
                .singleOrNull()
                ?.columns
                ?.map { it.name }
                ?.toSet()
        }
        return when (val pin = templates.pinOf(workspaceId, transform.template)) {
            TemplatePin.NotFound -> {
                bindingInvalid(
                    "transform.template",
                    "template_not_found",
                    "No template '${transform.template.name.safeEcho()}' here.",
                    failures,
                )
                null
            }

            TemplatePin.VersionNotFound -> {
                bindingInvalid(
                    "transform.template",
                    "template_version_not_found",
                    "The template has no live version ${transform.template.version}.",
                    failures,
                )
                null
            }

            is TemplatePin.NotTransform -> {
                bindingInvalid(
                    "transform.template",
                    "not_a_transform",
                    "The pinned template is '${pin.type.wire}', not a transform.",
                    failures,
                )
                null
            }

            is TemplatePin.Transform -> {
                contractBinding(body, transform, pin.contract, failures)
                (pin.contract.output as? TransformContractView.Output.Table)?.columns?.map { it.name }?.toSet() ?: emptySet()
            }
        }
    }

    private fun contractBinding(
        body: VisualizationBody,
        transform: TransformBinding,
        contract: TransformContractView,
        failures: ArtifactFailures,
    ) {
        if (contract.inputs.keys != transform.inputs.keys) {
            bindingInvalid(
                "transform.inputs",
                "inputs_mismatch",
                "transform.inputs must name exactly the contract's inputs ${contract.inputs.keys.sorted()}.",
                failures,
                mapOf(
                    "expected" to contract.inputs.keys.sorted(),
                    "got" to
                        transform.inputs.keys
                            .sorted()
                            .map { it.safeEcho() },
                ),
            )
        }
        transform.inputs.forEach { (contractInput, visualizationInput) ->
            val path = "transform.inputs.$contractInput"
            val input = body.inputs[visualizationInput]
            val declared = contract.inputs[contractInput]
            when {
                input == null -> {
                    bindingInvalid(
                        path,
                        "input_unknown",
                        "'${visualizationInput.safeEcho()}' is not an input of this visualization.",
                        failures,
                    )
                }

                declared is TransformContractView.Input.Value -> {
                    bindingInvalid(
                        path,
                        "value_input",
                        "The contract's input is a single value; an input is rows.",
                        failures,
                    )
                }

                declared is TransformContractView.Input.Table -> {
                    columnsEqual(path, declared.columns, input, failures)
                }
            }
        }
        (body.inputs.keys - transform.inputs.values.toSet()).forEach { unused ->
            bindingInvalid(
                "inputs.$unused",
                "input_unused",
                "No transform input reads '$unused'; a dashboard would fetch it for nothing.",
                failures,
            )
        }
    }

    private fun columnsEqual(
        path: String,
        declared: List<TransformContractView.Column>,
        input: InputContract,
        failures: ArtifactFailures,
    ) {
        val ours = input.columns.associateBy { it.name }
        val theirs = declared.associateBy { it.name }
        (ours.keys + theirs.keys).sorted().forEach { column ->
            val b = theirs[column]
            val same = ours[column]?.let { a -> b != null && a.type == b.type && a.nullable == b.nullable } ?: false
            if (!same) {
                bindingInvalid(
                    path,
                    "column_mismatch",
                    "The contract's input and the visualization input differ at column '${column.safeEcho()}'.",
                    failures,
                    mapOf("column" to column.safeEcho(), "declared" to b?.let { "${it.type.wire}${if (it.nullable) "?" else ""}" }),
                )
            }
        }
    }

    private fun config(
        body: VisualizationBody,
        failures: ArtifactFailures,
    ) {
        renderers.validate(body.renderer.kind, body.config).forEach { problem ->
            failures.add(
                VisualizationErrorCodes.CONFIG_SCHEMA_INVALID,
                JsonScan.join("config", problem.path),
                problem.message,
                mapOf("reason" to problem.reason, "renderer" to body.renderer.kind.wire),
            )
        }
    }

    private fun bindings(
        body: VisualizationBody,
        output: Set<String>?,
        failures: ArtifactFailures,
    ) {
        body.bindings.forEach { (path, column) ->
            val at = "bindings.$path"
            val steps = BindingPath.parse(path)
            when {
                steps == null -> {
                    unbound(
                        at,
                        "path_invalid",
                        "'${path.safeEcho()}' is not a binding path (data[0].x, layout.title.text).",
                        failures,
                    )
                }

                !BindingPath.resolves(
                    body.config,
                    steps,
                ) -> {
                    unbound(at, "path_unresolved", "'${path.safeEcho()}' does not resolve inside config.", failures)
                }
            }
            if (output != null && column !in output) {
                failures.add(
                    VisualizationErrorCodes.BINDING_UNBOUND,
                    at,
                    "Column '${column.safeEcho()}' is not in the output contract.",
                    mapOf("reason" to "column_unknown", "column" to column.safeEcho()),
                )
            }
        }
    }

    private fun tests(
        body: VisualizationBody,
        failures: ArtifactFailures,
    ) {
        val names = mutableSetOf<String>()
        body.tests?.cases?.forEachIndexed { index, case ->
            val path = "tests.cases[$index]"
            when {
                case.name.isBlank() -> caseInvalid("$path.name", "name_missing", "A case has a name.", failures)
                !names.add(case.name) -> caseInvalid("$path.name", "duplicate_case", "Two cases share a name.", failures)
            }
            fixtures(body, case, path, failures)
            if (case.assertions.isEmpty()) caseInvalid("$path.assertions", "no_assertions", "A case asserts at least one thing.", failures)
            case.assertions.forEachIndexed { at, assertion -> assertion(assertion, "$path.assertions[$at]", failures) }
        }
    }

    private fun fixtures(
        body: VisualizationBody,
        case: TestCase,
        path: String,
        failures: ArtifactFailures,
    ) {
        (body.inputs.keys - case.fixtures.keys).forEach {
            caseInvalid("$path.fixtures.$it", "fixture_missing", "A case supplies rows for every input.", failures)
        }
        case.fixtures.forEach { (input, rows) ->
            val contract = body.inputs[input]
            if (contract == null) {
                caseInvalid("$path.fixtures.$input", "fixture_unknown", "'${input.safeEcho()}' is not an input.", failures)
                return@forEach
            }
            val columns = contract.columns.associateBy { it.name }
            rows.forEachIndexed { index, row ->
                val rowPath = "$path.fixtures.$input[$index]"
                row.fieldNames().forEach { column ->
                    if (column !in
                        columns
                    ) {
                        caseInvalid(JsonScan.join(rowPath, column), "column_unknown", "Not a column of the input.", failures)
                    }
                }
                columns.values.filter { !it.nullable && (row.get(it.name)?.isNull ?: true) }.forEach {
                    caseInvalid(JsonScan.join(rowPath, it.name), "null_not_allowed", "The column is not nullable.", failures)
                }
            }
        }
    }

    private fun assertion(
        assertion: Assertion,
        path: String,
        failures: ArtifactFailures,
    ) {
        val argument = assertion.kind.argument
        val equals = assertion.equals
        val text = assertion.text
        when {
            argument == AssertionKind.Argument.EQUALS && (equals == null || equals < 0) -> {
                caseInvalid("$path.equals", "argument_missing", "${assertion.kind.wire} takes equals ≥ 0.", failures)
            }

            argument == AssertionKind.Argument.TEXT && (text.isNullOrBlank() || text.length > MAX_ASSERTION_TEXT) -> {
                caseInvalid(
                    "$path.text",
                    "argument_missing",
                    "${assertion.kind.wire} takes a text of 1–$MAX_ASSERTION_TEXT characters.",
                    failures,
                )
            }
        }
        val strayEquals = argument != AssertionKind.Argument.EQUALS && equals != null
        val strayText = argument != AssertionKind.Argument.TEXT && text != null
        if (strayEquals || strayText) {
            caseInvalid(path, "argument_not_allowed", "${assertion.kind.wire} takes ${argument.name.lowercase()} only.", failures)
        }
    }

    private fun contractInvalid(
        path: String,
        reason: String,
        message: String,
        failures: ArtifactFailures,
    ) = failures.add(VisualizationErrorCodes.INPUT_CONTRACT_INVALID, path, message, mapOf("reason" to reason))

    @Suppress("LongParameterList") // the refusal's four parts and its optional facts
    private fun bindingInvalid(
        path: String,
        reason: String,
        message: String,
        failures: ArtifactFailures,
        details: Map<String, Any?> = emptyMap(),
    ) = failures.add(VisualizationErrorCodes.TRANSFORM_BINDING_INVALID, path, message, mapOf("reason" to reason) + details)

    private fun unbound(
        path: String,
        reason: String,
        message: String,
        failures: ArtifactFailures,
    ) = failures.add(VisualizationErrorCodes.BINDING_UNBOUND, path, message, mapOf("reason" to reason))

    private fun caseInvalid(
        path: String,
        reason: String,
        message: String,
        failures: ArtifactFailures,
    ) = failures.add(VisualizationErrorCodes.TEST_CASE_INVALID, path, message, mapOf("reason" to reason))

    private companion object {
        val RENDERER_VERSION = Regex("^[1-9][0-9]{0,3}$")
        const val MAX_COLUMN_CHARS = 128
        const val MAX_ASSERTION_TEXT = 200
    }
}
