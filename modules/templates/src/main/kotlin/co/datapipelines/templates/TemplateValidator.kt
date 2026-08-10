package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import freemarker.core.TemplateElement

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
) {
    /** Runs §7 against [draft] and returns every failure. */
    fun validate(draft: TemplateDraft): TemplateValidationResult {
        val failures = mutableListOf<TemplateValidationFailure>()

        if (draft.id != null && !TEMPLATE_ID.matches(draft.id)) {
            failures +=
                TemplateValidationFailure(
                    code = PipelineErrorCodes.Template.ID_INVALID,
                    message = "Template id '${draft.id.truncateForError()}' must match [a-z0-9_.-], length 1-100.",
                    details = mapOf("id" to draft.id.truncateForError()),
                )
        }

        addEngineFailure(draft, failures)
        addSchemaVersionFailure(draft, failures)
        addBodyFailures(draft, failures)
        libraryResolver.validate(draft.imports, failures)

        return TemplateValidationResult(failures)
    }

    /** Runs §7 and throws [TemplateValidationException] if anything failed; returns [draft] otherwise. */
    fun validateOrThrow(draft: TemplateDraft): TemplateDraft {
        validate(draft).orThrow()
        return draft
    }

    /**
     * templates.md §3.2/§7: v1 supports only `"freemarker"`, and an unsupported `engine` is
     * **rejected at save**, never stored.
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
        if (draft.engine == Template.FREEMARKER_ENGINE) return
        failures +=
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.ENGINE_UNSUPPORTED,
                message =
                    "Engine '${draft.engine.truncateForError()}' is not supported; " +
                        "v1 supports only '${Template.FREEMARKER_ENGINE}'.",
                details =
                    mapOf(
                        "engine" to draft.engine.truncateForError(),
                        "supported" to listOf(Template.FREEMARKER_ENGINE),
                    ),
            )
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
     * Every body-derived §7 check: the length cap, the source-level refusals, the parse, the
     * §4.2 AST scan and the `is_library` structure check — in that order, because each stage's
     * cost is only bounded once the previous one has passed.
     */
    @Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
    private fun addBodyFailures(
        draft: TemplateDraft,
        failures: MutableList<TemplateValidationFailure>,
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

        val sourceFindings = ForbiddenConstructScanner.scanSource(draft.body)
        if (sourceFindings.isNotEmpty()) {
            addDangerousConstructFailures(sourceFindings, failures)
            // Deliberately no parse, for the same reason as the length cap above. A source-level
            // refusal exists precisely because parsing the construct is itself the harm: a leading
            // `<#ftl attributes={…}>` evaluates its expressions AT PARSE TIME on this thread, so
            // reporting it and then parsing anyway would still burn the CPU the refusal exists to
            // save (measured ~1.6s from a 65-byte body). The body is already rejected; additional
            // parse diagnostics for it are not worth handing an attacker the parse.
            return
        }

        when (val parse = TemplateBodyParser.parse(draft.body)) {
            is BodyParse.SyntaxError -> {
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.SYNTAX_ERROR,
                        message = parse.message,
                        details = mapOf("line" to parse.line, "column" to parse.column),
                    )
            }

            is BodyParse.Parsed -> {
                val root: TemplateElement? = parse.template.rootTreeNode
                addDangerousConstructFailures(ForbiddenConstructScanner.scanAst(root), failures)
                addLibraryBodyFailure(draft, root, failures)
            }
        }
    }

    private fun addDangerousConstructFailures(
        findings: List<ForbiddenConstructScanner.Finding>,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        findings
            .distinctBy { it.construct }
            .forEach { finding ->
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT,
                        message = "Body uses the forbidden construct '${finding.construct}'.",
                        details = mapOf("construct" to finding.construct, "match" to finding.snippet),
                    )
            }
    }

    @Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
    private fun addLibraryBodyFailure(
        draft: TemplateDraft,
        root: TemplateElement?,
        failures: MutableList<TemplateValidationFailure>,
    ) {
        if (!draft.isLibrary) return
        val message =
            when (LibraryBodyCheck.validate(root)) {
                LibraryBodyCheck.Result.OK -> {
                    return
                }

                LibraryBodyCheck.Result.NO_MACROS -> {
                    "A library must define at least one <#macro> or <#function>."
                }

                LibraryBodyCheck.Result.OUTPUT_OUTSIDE_MACROS -> {
                    "A library must have no output outside its macro/function definitions."
                }
            }
        failures +=
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.IS_LIBRARY_WITHOUT_MACROS,
                message = message,
            )
    }

    companion object {
        /**
         * `datapipelines.templates.max-body-chars` (configuration.md §3.9) — mirrored here for
         * the code path and for tests that construct a validator directly, never as a second
         * definition of the key's default.
         */
        const val DEFAULT_MAX_BODY_CHARS = 262_144
    }
}
