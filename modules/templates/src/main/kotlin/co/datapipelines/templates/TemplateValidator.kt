package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.ScriptSyntaxException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType

/**
 * Runs the templates.md §7 checks — the save-time gate (D2: nothing invalid ever stored).
 *
 * ## Parse-only, by design (§7.1)
 *
 * A template declares no parameters (D3), so at save time there is no context to render it
 * against and this validator **never renders**. It checks: the `id` shape, the body length cap,
 * the forbidden construct scan ([ForbiddenConstructScanner]), a Freemarker *parse* (syntax
 * only), the import-graph resolution ([LibraryResolver]), and — for a library — that the body is
 * definitions and nothing else ([LibraryBodyCheck]). The render-level check is the pipeline's
 * job, because only a pipeline knows the parameters (§7.2).
 *
 * ## One parse, three consumers
 *
 * The body is parsed **once** ([TemplateBodyParser]) and the resulting AST feeds the syntax
 * verdict, the §4.2 forbidden-construct scan and the `is_library` structure check. templates.md
 * §4.2 makes that normative: a scan that reads the source separately from the parser can be made
 * to disagree with it, and was — see [ForbiddenConstructScanner]. A body that does not parse has
 * no AST, so it yields `syntax_error` alone; it is rejected either way.
 *
 * ## Bounded before it is parsed
 *
 * The body is untrusted input arriving on a request thread, so its length is capped
 * ([maxBodyChars], `datapipelines.templates.max-body-chars`, configuration.md §3.9) **before**
 * any parsing or scanning happens. An over-cap body is rejected with
 * `template.validation.syntax_error` and never reaches the parser, which bounds both the parse
 * cost and the heap an adversarial save can command.
 *
 * ## Exhaustive
 *
 * Every check runs and every failure is collected, so an author fixing an LLM-generated
 * template sees the whole picture at once rather than one error per round-trip.
 */
class TemplateValidator(
    private val libraryResolver: LibraryResolver,
    private val maxBodyChars: Int = DEFAULT_MAX_BODY_CHARS,
    private val scriptEngines: Map<ScriptLanguage, ScriptEngine> =
        mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
) {
    /**
     * Runs §7 against [draft] and returns every failure. Imports resolve within
     * [workspaceId] (design 2026-08-16-workspaces §3 — cross-workspace references do not
     * exist in v1); no default, so validation without an explicit workspace does not compile.
     *
     * [trace] counts the work this call did — see [ValidationTrace] for why templates.md §12.3's
     * "bounded work on adversarial input" is asserted as a step count and not as a stopwatch
     * reading. Production callers pass nothing and nothing branches on it.
     */
    fun validate(
        draft: TemplateDraft,
        workspaceId: java.util.UUID,
        trace: ValidationTrace = ValidationTrace(),
    ): TemplateValidationResult {
        val failures = mutableListOf<TemplateValidationFailure>()

        if (draft.id != null && !isValidTemplateName(draft.id)) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.ID_INVALID,
                    message =
                        "Template id '${draft.id.truncateForError()}' must be a path of 2-10 '/'-separated segments, " +
                            "each [a-z0-9][a-z0-9_.-], at most 64 chars, 200 total — a folder is required " +
                            "(test/scratch, not scratch).",
                    // `reason` separates "you forgot the folder" from "you used a bad
                    // character" without an agent parsing the message (§4.1, 077).
                    details =
                        mapOf(
                            "id" to draft.id.truncateForError(),
                            "reason" to TemplateNameGrammar.refusalReason(draft.id),
                        ),
                )
        }

        addEngineFailure(draft, failures)
        addSchemaVersionFailure(draft, failures)
        addTypeDialectFailures(draft, failures)
        addTransformFieldFailures(draft, failures)
        addTransformBlockFailures(draft, failures)
        addHtmlEntityFailure(draft, failures)
        addBodyFailures(draft, failures, trace)
        libraryResolver.validate(workspaceId, draft.imports, failures, trace)

        return TemplateValidationResult(failures)
    }

    /** Runs §7 and throws [TemplateValidationException] if anything failed; returns [draft] otherwise. */
    fun validateOrThrow(
        draft: TemplateDraft,
        workspaceId: java.util.UUID,
    ): TemplateDraft {
        validate(draft, workspaceId).orThrow()
        return draft
    }

    /**
     * templates.md §3.2/§7: `engine` must match the template's type (transform-nodes design
     * §2.1) — `freemarker` iff `sql`/`html`, `none` iff `jsonata`/`javascript`. An unsupported
     * pairing is **rejected at save**, never stored. The rule is the type's
     * [TemplateTypeBehaviour]; the refusal text is its too, because the fix differs by type.
     *
     * The gap this closes is a silent one: without the check, a template declaring
     * `engine: "pebble"` would be stored happily and then rendered by [TemplateEngine] as
     * Freemarker — the author's Pebble syntax either erroring far downstream at execution time or,
     * worse, parsing as valid Freemarker and producing SQL they never wrote.
     */
    private fun addEngineFailure(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val behaviour = TemplateTypeBehaviour.of(draft.type ?: TemplateType.SQL)
        if (draft.engine == behaviour.engine) return
        failures += behaviour.engineRefusal(draft)
    }

    /**
     * templates.md §3.2/§7: `schema_version` is `1` in v1, and any other value is rejected at save.
     *
     * Reported rather than coerced. A payload claiming schema_version 2 was written against a
     * contract this server does not implement, and quietly reading it as v1 would bind fields by
     * position of hope — the author gets a catalog code instead.
     */
    private fun addSchemaVersionFailure(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        if (draft.schemaVersion == Template.SUPPORTED_SCHEMA_VERSION) return
        failures +=
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
                message =
                    "schema_version ${draft.schemaVersion} is not supported; " +
                        "v1 supports only ${Template.SUPPORTED_SCHEMA_VERSION}.",
                details =
                    mapOf(
                        "schema_version" to draft.schemaVersion,
                        "supported" to listOf(Template.SUPPORTED_SCHEMA_VERSION),
                    ),
            )
    }

    /**
     * The type/dialect consistency rules of 046 (template-hierarchy-design §5.1/§7) — the
     * application-level twin of the `chk_type_dialect` database invariant, so every write
     * surface (REST, MCP, import) enforces the same pair through this one validator.
     *
     * A null [TemplateDraft.type] means "not stated", which is only legal as the create-time
     * default of `sql` ([TemplateTypeRule] resolves it before any write); it is therefore
     * checked exactly like an explicit `sql`.
     *
     * `dialect_not_allowed` is deliberately a different code from `dialect_invalid` (§7): a
     * present dialect on an html template and an unknown dialect value are different failures
     * an author fixes differently, and each gets its own greppable code.
     */
    private fun addTypeDialectFailures(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val type = draft.type ?: TemplateType.SQL
        if (!TemplateTypeBehaviour.of(type).requiresDialect) {
            if (draft.dialect != null) {
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED,
                        message =
                            "A template of type '${type.wire}' declares no dialect, but the payload carries " +
                                "'${draft.dialect.wire}'.",
                        details = mapOf("type" to type.wire, "dialect" to draft.dialect.wire),
                    )
            }
        } else if (draft.dialect == null) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.DIALECT_INVALID,
                    message =
                        "Dialect is required unless the template's type is 'html'.",
                    details = mapOf("dialect" to null, "supported" to Dialect.entries.map { it.wire }),
                )
        }
    }

    /**
     * D-T9 / transform-nodes design §2.1 — a transform type has no Freemarker at all:
     * `imports` and `is_library` are refused (there is no Freemarker to import into), and a
     * body containing a Freemarker construct (`${`, `<#`, `<@`) is refused, all with
     * `template.validation.freemarker_forbidden`, the detail naming which.
     */
    private fun addTransformFieldFailures(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val type = draft.type ?: TemplateType.SQL
        if (TemplateTypeBehaviour.of(type).allowsFreemarker) return
        if (draft.imports.isNotEmpty()) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN,
                    message =
                        "A template of type '${type.wire}' declares no imports — there is no Freemarker " +
                            "to import into on a transform template.",
                    details = mapOf("rule" to "imports", "type" to type.wire),
                )
        }
        if (draft.isLibrary) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN,
                    message =
                        "A template of type '${type.wire}' cannot be a library — a library is a Freemarker " +
                            "macro collection, and a transform body is evaluated, never rendered.",
                    details = mapOf("rule" to "is_library", "type" to type.wire),
                )
        }
        val construct = FREEMARKER_CONSTRUCT.find(draft.body)
        if (construct != null) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.FREEMARKER_FORBIDDEN,
                    message =
                        "The body contains the Freemarker construct '${construct.value}' — a transform body is " +
                            "evaluated by the script engine, never rendered (D-T9).",
                    details = mapOf("rule" to "body", "type" to type.wire, "match" to construct.value),
                )
        }
    }

    /**
     * `template.validation.html_entity` — a body carrying `&lt;`/`&gt;`/`&amp;`/`&quot;`/`&#39;`
     * was HTML-escaped on its way here and can never be the SQL its author meant. Named so the
     * fix is one edit, not a full run ending in a driver syntax error (pipeline-3 audit).
     * Applies to `sql` templates only: an `html` template may legitimately emit entities, and a
     * transform body is not SQL.
     */
    private fun addHtmlEntityFailure(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        if (!TemplateTypeBehaviour.of(draft.type ?: TemplateType.SQL).scansHtmlEntities) return
        val match = HTML_ENTITY.find(draft.body) ?: return
        val line = draft.body.substring(0, match.range.first).count { it == '\n' } + 1
        failures +=
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.HTML_ENTITY,
                message =
                    "Body contains the HTML entity '${match.value}' at line $line — a SQL body was HTML-escaped " +
                        "between the author and the server. Send the operator itself.",
                details = mapOf("entity" to match.value, "line" to line),
            )
    }

    /**
     * The shared length cap, then the type's own body pipeline ([TemplateTypeBehaviour] —
     * the Freemarker scan/parse for `sql`/`html`, the engine compile for `jsonata`, the
     * round-two refusal for `javascript`).
     */
    private fun addBodyFailures(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
        trace: ValidationTrace,
    ) {
        if (draft.body.length > maxBodyChars) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.SYNTAX_ERROR,
                    message = "Body of ${draft.body.length} characters exceeds the limit of $maxBodyChars.",
                    details = mapOf("body_chars" to draft.body.length, "max_body_chars" to maxBodyChars),
                )
            // Deliberately no parse: the cap exists to keep an adversarial body away from the
            // parser, so honouring it must mean not parsing.
            return
        }

        failures += TemplateTypeBehaviour.of(draft.type ?: TemplateType.SQL).validateBody(draft, scriptEngines, trace)
    }

    /**
     * The transform blocks' model rules (transform-nodes design §2.2, in §8.1's save order —
     * the body parse is [addBodyFailures]', this is contract → invariants → tests; the suite
     * itself is [TransformTestRunner]'s, run by the service):
     *
     *  - `sql`/`html` carry no blocks (`template.blocks_not_allowed`); a transform type carries
     *    all three (the `chk_transform_blocks` twin, reported as `template.contract_invalid`
     *    with `blocks_missing`).
     *  - contract: at least one input (`inputs_empty`); input names are §6.1-shaped
     *    (`name_invalid`); `row` mode requires exactly one table input (`row_mode_inputs`);
     *    BINARY/NULL are refused as declared types (`type_unsupported`); precision/scale follow
     *    type-system.md §4 (`precision_scale_invalid`); mode and output.kind agree
     *    (`mode_output_mismatch`); `rejects` only with a table output (`rejects_without_table`).
     *  - invariants: every `expr` compiles through the JSONata engine (`invariant_invalid`).
     *  - tests: the mandatory empty case (`empty_case_missing`); a `row`-mode case lists no
     *    table under `inputs` (`row_case_lists_table`, R2); `expect` is exactly one of
     *    `output`/`refusal` (`expect_shape`).
     */
    private fun addTransformBlockFailures(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val type = draft.type ?: TemplateType.SQL
        if (!type.isTransform) {
            listOfNotNull(
                "contract".takeIf { draft.contract != null },
                "invariants".takeIf { draft.invariants != null },
                "tests".takeIf { draft.tests != null },
            ).forEach { block ->
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.BLOCKS_NOT_ALLOWED,
                        message =
                            "An '$block' block belongs to a transform template; type '${type.wire}' " +
                                "declares none (transform-nodes design §2.2).",
                        details = mapOf("block" to block, "type" to type.wire),
                    )
            }
            return
        }

        val contract = draft.contract
        if (contract == null || draft.invariants == null || draft.tests == null) {
            val missing =
                listOfNotNull(
                    "contract".takeIf { contract == null },
                    "invariants".takeIf { draft.invariants == null },
                    "tests".takeIf { draft.tests == null },
                )
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.CONTRACT_INVALID,
                    message =
                        "A transform template carries contract, invariants and tests; missing: " +
                            missing.joinToString(", ") + " (transform-nodes design §2.2).",
                    details = mapOf("rule" to "blocks_missing", "missing" to missing),
                )
            return
        }
        validateContract(contract, failures)
        validateInvariants(draft.invariants, failures)
        validateTests(contract, draft.tests, failures)
    }

    private fun contractFailure(
        rule: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ) = TemplateValidationFailure(
        code = PipelineErrorCodes.Template.CONTRACT_INVALID,
        message = message,
        details = mapOf("rule" to rule) + extra,
    )

    private fun validateContract(
        contract: TransformContract,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        if (contract.inputs.isEmpty()) {
            failures += contractFailure("inputs_empty", "A transform contract declares at least one input.")
        }
        contract.inputs.keys.filterNot { INPUT_NAME.matches(it) }.forEach { name ->
            failures +=
                contractFailure(
                    "name_invalid",
                    "Input name '$name' must match ${INPUT_NAME.pattern} (the §6.1 identifier rule).",
                    mapOf("input" to name),
                )
        }
        val tableInputs = contract.inputs.filterValues { it is TransformInput.Table }
        if (contract.mode == TransformMode.ROW && tableInputs.size != 1) {
            failures +=
                contractFailure(
                    "row_mode_inputs",
                    "A row-mode contract requires exactly one table input; found ${tableInputs.size} " +
                        "(${tableInputs.keys.sorted()}).",
                    mapOf("mode" to contract.mode.wire, "table_inputs" to tableInputs.keys.sorted()),
                )
        }
        contract.inputs.forEach { (name, input) -> validateDeclaredType("inputs.$name", input.typeOf(), input.precisionOf(), input.scaleOf(), failures) }
        validateDeclaredType("output", contract.output.typeOf(), contract.output.precisionOf(), contract.output.scaleOf(), failures)
        val outputColumns = (contract.output as? TransformOutput.Table)?.columns.orEmpty()
        outputColumns.forEach { column ->
            validateDeclaredType("output.${column.name}", column.type, column.precision, column.scale, failures)
        }
        contract.inputs.forEach { (name, input) ->
            (input as? TransformInput.Table)?.columns?.forEach { column ->
                validateDeclaredType("inputs.$name.${column.name}", column.type, column.precision, column.scale, failures)
            }
        }
        val modeOutputOk =
            when (contract.mode) {
                TransformMode.ROW, TransformMode.TABLE -> contract.output is TransformOutput.Table
                TransformMode.VALUE -> contract.output is TransformOutput.Value || contract.output is TransformOutput.Obj
            }
        if (!modeOutputOk) {
            failures +=
                contractFailure(
                    "mode_output_mismatch",
                    "Mode '${contract.mode.wire}' does not admit output kind '${contract.output.kind}' " +
                        "(row/table produce a table; value produces a value or an object).",
                    mapOf("mode" to contract.mode.wire, "output_kind" to contract.output.kind),
                )
        }
        if (contract.rejects && contract.output !is TransformOutput.Table) {
            failures +=
                contractFailure(
                    "rejects_without_table",
                    "rejects: true requires a table output — the rejected rows must have columns to be checked against.",
                    mapOf("output_kind" to contract.output.kind),
                )
        }
    }

    private fun TransformInput.typeOf(): LogicalType? = (this as? TransformInput.Value)?.type

    private fun TransformInput.precisionOf(): Int? = (this as? TransformInput.Value)?.precision

    private fun TransformInput.scaleOf(): Int? = (this as? TransformInput.Value)?.scale

    private fun TransformOutput.typeOf(): LogicalType? = (this as? TransformOutput.Value)?.type

    private fun TransformOutput.precisionOf(): Int? = (this as? TransformOutput.Value)?.precision

    private fun TransformOutput.scaleOf(): Int? = (this as? TransformOutput.Value)?.scale

    /** type-system.md §4 applied to a declared (not inferred) contract type. */
    private fun validateDeclaredType(
        path: String,
        type: LogicalType?,
        precision: Int?,
        scale: Int?,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        if (type == null) return // Jackson binding refused or the caller's own failure — not this rule's.
        if (type == LogicalType.BINARY || type == LogicalType.NULL) {
            failures +=
                contractFailure(
                    "type_unsupported",
                    "Declared type ${type.wire} at '$path' is not supported in a transform contract " +
                        "(transform-nodes design §6).",
                    mapOf("path" to path, "type" to type.wire),
                )
            return
        }
        val decimal = type == LogicalType.DECIMAL || type == LogicalType.BIGDECIMAL
        if (!decimal && (precision != null || scale != null)) {
            failures +=
                contractFailure(
                    "precision_scale_invalid",
                    "'$path' declares ${type.wire} but carries precision/scale, which only " +
                        "DECIMAL/BIGDECIMAL take (type-system.md §4).",
                    mapOf("path" to path, "type" to type.wire),
                )
            return
        }
        if (decimal) {
            if (scale != null && precision == null) {
                failures +=
                    contractFailure(
                        "precision_scale_invalid",
                        "'$path' declares a scale without a precision (type-system.md §4).",
                        mapOf("path" to path, "type" to type.wire),
                    )
            }
            if (type == LogicalType.BIGDECIMAL && precision != null && scale == null) {
                failures +=
                    contractFailure(
                        "precision_scale_invalid",
                        "'$path' declares BIGDECIMAL($precision) without a scale — BIGDECIMAL's scale is " +
                            "declared or the type means unbounded-and-unknown with both omitted (type-system.md §4).",
                        mapOf("path" to path, "type" to type.wire),
                    )
            }
            if (type == LogicalType.DECIMAL && precision != null && precision > 15) {
                failures +=
                    contractFailure(
                        "precision_scale_invalid",
                        "'$path' declares DECIMAL($precision) — past 15 digits the type is BIGDECIMAL " +
                            "(type-system.md §4).",
                        mapOf("path" to path, "type" to type.wire),
                    )
            }
            if (precision != null && precision < 1) {
                failures +=
                    contractFailure(
                        "precision_scale_invalid",
                        "'$path' declares precision $precision — the minimum is 1 (type-system.md §7.1).",
                        mapOf("path" to path, "type" to type.wire),
                    )
            }
            if (precision != null && scale != null && scale > precision) {
                failures +=
                    contractFailure(
                        "precision_scale_invalid",
                        "'$path' declares scale $scale above precision $precision.",
                        mapOf("path" to path, "type" to type.wire),
                    )
            }
        }
    }

    private fun validateInvariants(
        invariants: List<TransformInvariant>,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val engine = scriptEngines.getValue(ScriptLanguage.JSONATA)
        invariants.forEach { invariant ->
            try {
                engine.compile(invariant.expr)
            } catch (err: ScriptSyntaxException) {
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.INVARIANT_INVALID,
                        message =
                            "Invariant '${invariant.name}' does not compile: ${err.message ?: "syntax error"}",
                        details =
                            mapOf(
                                "invariant" to invariant.name,
                                "line" to err.line,
                                "column" to err.column,
                            ),
                    )
            }
        }
    }

    private fun validateTests(
        contract: TransformContract,
        tests: List<TransformTestCase>,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        val hasEmptyCase =
            tests.any { case ->
                val rowsEmpty = case.input.rows.isNullOrEmpty()
                val tablesEmpty =
                    when (contract.mode) {
                        // R2: in row mode the table rides `rows`; `inputs` lists no table.
                        TransformMode.ROW -> true
                        // Every declared table input is listed AND empty.
                        TransformMode.TABLE, TransformMode.VALUE ->
                            contract.inputs
                                .filterValues { it is TransformInput.Table }
                                .keys
                                .all { name -> (case.input.inputs?.get(name) as? List<*>)?.isEmpty() == true }
                    }
                rowsEmpty && tablesEmpty
            }
        if (tests.isEmpty() || !hasEmptyCase) {
            failures +=
                contractFailure(
                    "empty_case_missing",
                    "A transform declares a test case whose every table input and rows are empty — " +
                        "a transform with no test for zero rows does not save (transform-nodes design §2.2).",
                )
        }
        if (contract.mode == TransformMode.ROW) {
            val tableName = contract.inputs.filterValues { it is TransformInput.Table }.keys.singleOrNull()
            tests.forEach { case ->
                if (tableName != null && case.input.inputs?.containsKey(tableName) == true) {
                    failures +=
                        contractFailure(
                            "row_case_lists_table",
                            "Test case '${case.name}' lists the table input '$tableName' under inputs — in row " +
                                "mode the batch is `rows` and `inputs` holds value inputs only (R2).",
                            mapOf("case" to case.name, "input" to tableName),
                        )
                }
            }
        }
        tests.forEach { case ->
            val hasOutput = case.expect.output != null
            val hasRefusal = case.expect.refusal != null
            if (hasOutput == hasRefusal) {
                failures +=
                    contractFailure(
                        "expect_shape",
                        "Test case '${case.name}' declares ${if (hasOutput) "both output and refusal" else "neither output nor refusal"} — " +
                            "expect is exactly one of the two (D-T12).",
                        mapOf("case" to case.name),
                    )
            }
        }
    }

    companion object {
        /** The five entities a SQL body can only have acquired by being HTML-escaped. */
        private val HTML_ENTITY = Regex("&(lt|gt|amp|quot|#39);")

        /** D-T9's forbidden Freemarker constructs in a transform body: interpolation or a directive. */
        private val FREEMARKER_CONSTRUCT = Regex("""\$\{|<#|<@""")

        /** A transform contract's input name — pipeline-contract §6.1's identifier rule. */
        private val INPUT_NAME = Regex("[a-z_][a-z0-9_]*")

        /**
         * `datapipelines.templates.max-body-chars` (configuration.md §3.9) — mirrored here for
         * the code path and for tests that construct a validator directly, never as a second
         * definition of the key's default.
         */
        const val DEFAULT_MAX_BODY_CHARS = 262_144
    }
}
