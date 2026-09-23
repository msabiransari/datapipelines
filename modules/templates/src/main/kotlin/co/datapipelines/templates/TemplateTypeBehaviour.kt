package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.ScriptSyntaxException
import co.datapipelines.typesystem.Dialect
import freemarker.core.TemplateElement

/**
 * One behaviour per [TemplateType] (transform-nodes design §2.4): which `engine` value the type
 * requires, whether it declares a `dialect`, whether Freemarker exists for it at all, whether
 * the SQL-only HTML-entity trap applies, whether it renders, and how its body is validated at
 * save. The template services call the behaviour; no `when (type)` accumulates in a service.
 *
 * The SQL and HTML behaviours are the pre-7b conditionals moved, not rewritten — the refusal
 * golden ([TemplateRefusalGoldenTest]) proves their codes, messages and details are
 * byte-identical to what the branchy validator produced.
 */
sealed interface TemplateTypeBehaviour {
    /** The type this behaviour answers for. */
    val type: TemplateType

    /** The one legal `engine` value (transform-nodes design §2.1: freemarker iff sql/html, none iff a transform). */
    val engine: String

    /** True only for `sql` — the one type that declares a dialect. */
    val requiresDialect: Boolean

    /** True for `sql`/`html` — the types with Freemarker, hence imports and libraries (D-T9). */
    val allowsFreemarker: Boolean

    /** True only for `sql` — the entity trap is a SQL-body concern. */
    val scansHtmlEntities: Boolean

    /** True for `sql`/`html` — the types that render through Freemarker (record §2.4). */
    val renders: Boolean

    /**
     * The `engine_unsupported` refusal for this type — per-type because the fix differs: a
     * sql/html author picks the one engine v1 renders with; a transform author sets `none`.
     */
    fun engineRefusal(draft: TemplateDraft): TemplateValidationFailure

    /**
     * The type's body checks, run after the shared length cap. The Freemarker types share the
     * source scan / parse / AST scan / library-structure pipeline; a transform type compiles
     * through the scripting engine (or, for `javascript`, refuses until round two).
     */
    fun validateBody(
        draft: TemplateDraft,
        scriptEngines: Map<ScriptLanguage, ScriptEngine>,
        trace: ValidationTrace,
    ): List<TemplateValidationFailure>

    /** `sql` — the behaviour every pre-046 template has. */
    object Sql : TemplateTypeBehaviour, FreemarkerBodyValidation {
        override val type = TemplateType.SQL
        override val engine = Template.FREEMARKER_ENGINE
        override val requiresDialect = true
        override val allowsFreemarker = true
        override val scansHtmlEntities = true
        override val renders = true

        override fun engineRefusal(draft: TemplateDraft) = freemarkerEngineRefusal(draft)

        override fun validateBody(
            draft: TemplateDraft,
            scriptEngines: Map<ScriptLanguage, ScriptEngine>,
            trace: ValidationTrace,
        ) = super<FreemarkerBodyValidation>.validateBody(draft, scriptEngines, trace)
    }

    /** `html` — Freemarker with auto-escaping; no dialect, ever (046 §7). */
    object Html : TemplateTypeBehaviour, FreemarkerBodyValidation {
        override val type = TemplateType.HTML
        override val engine = Template.FREEMARKER_ENGINE
        override val requiresDialect = false
        override val allowsFreemarker = true
        override val scansHtmlEntities = false
        override val renders = true

        override fun engineRefusal(draft: TemplateDraft) = freemarkerEngineRefusal(draft)

        override fun validateBody(
            draft: TemplateDraft,
            scriptEngines: Map<ScriptLanguage, ScriptEngine>,
            trace: ValidationTrace,
        ) = super<FreemarkerBodyValidation>.validateBody(draft, scriptEngines, trace)
    }

    /** `jsonata` — the body is one expression, compiled at save through the scripting seam. */
    object Jsonata : TemplateTypeBehaviour {
        override val type = TemplateType.JSONATA
        override val engine = Template.NONE_ENGINE
        override val requiresDialect = false
        override val allowsFreemarker = false
        override val scansHtmlEntities = false
        override val renders = false

        override fun engineRefusal(draft: TemplateDraft) = transformEngineRefusal(draft)

        override fun validateBody(
            draft: TemplateDraft,
            scriptEngines: Map<ScriptLanguage, ScriptEngine>,
            trace: ValidationTrace,
        ): List<TemplateValidationFailure> =
            try {
                scriptEngines.getValue(ScriptLanguage.JSONATA).compile(draft.body)
                emptyList()
            } catch (err: ScriptSyntaxException) {
                listOf(
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.SYNTAX_ERROR,
                        message = err.message ?: "the body does not parse",
                        details = mapOf("line" to err.line, "column" to err.column),
                    ),
                )
            }
    }

    /**
     * `javascript` — the type exists so the enum, the CHECKs and the docs are final; the body
     * is refused at save until round two's GraalJS isolate ships (record §2.1, §4.4).
     */
    object Javascript : TemplateTypeBehaviour {
        override val type = TemplateType.JAVASCRIPT
        override val engine = Template.NONE_ENGINE
        override val requiresDialect = false
        override val allowsFreemarker = false
        override val scansHtmlEntities = false
        override val renders = false

        override fun engineRefusal(draft: TemplateDraft) = transformEngineRefusal(draft)

        override fun validateBody(
            draft: TemplateDraft,
            scriptEngines: Map<ScriptLanguage, ScriptEngine>,
            trace: ValidationTrace,
        ): List<TemplateValidationFailure> =
            listOf(
                TemplateValidationFailure(
                    code = TransformCodes.JS_UNAVAILABLE,
                    message =
                        "A 'javascript' template cannot be saved yet: the GraalJS isolate engine ships in " +
                            "round two (transform-nodes design §4.4). Only 'jsonata' saves today.",
                    details = mapOf("type" to type.wire),
                ),
            )
    }

    /** The refusal texts the two kinds of type produce for a wrong `engine`. */
    companion object {
        /** The one registry lookup — the behaviour for [type]. */
        fun of(type: TemplateType): TemplateTypeBehaviour =
            when (type) {
                TemplateType.SQL -> Sql
                TemplateType.HTML -> Html
                TemplateType.JSONATA -> Jsonata
                TemplateType.JAVASCRIPT -> Javascript
            }

        private fun freemarkerEngineRefusal(draft: TemplateDraft) =
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

        private fun transformEngineRefusal(draft: TemplateDraft) =
            TemplateValidationFailure(
                code = PipelineErrorCodes.Template.ENGINE_UNSUPPORTED,
                message =
                    "Engine '${draft.engine.truncateForError()}' is not supported for type '${draft.type?.wire}'; " +
                        "a transform template's engine is '${Template.NONE_ENGINE}' — the body is evaluated, " +
                        "never rendered.",
                details =
                    mapOf(
                        "engine" to draft.engine.truncateForError(),
                        "type" to draft.type?.wire,
                        "supported" to listOf(Template.NONE_ENGINE),
                    ),
            )
    }
}

/**
 * The shared Freemarker body pipeline the `sql` and `html` behaviours run (validator §7.1):
 * the source-level refusals, the parse, the §4.2 AST scan and the `is_library` structure
 * check — in that order, because each stage's cost is only bounded once the previous one has
 * passed. The length cap is the validator's own and runs before this is consulted.
 */
private interface FreemarkerBodyValidation {
    fun validateBody(
        draft: TemplateDraft,
        scriptEngines: Map<ScriptLanguage, ScriptEngine>,
        trace: ValidationTrace,
    ): List<TemplateValidationFailure> {
        val failures = mutableListOf<TemplateValidationFailure>()
        val sourceFindings = ForbiddenConstructScanner.scanSource(draft.body)
        if (sourceFindings.isNotEmpty()) {
            addDangerousConstructFailures(sourceFindings, failures)
            // Deliberately no parse: a source-level refusal exists precisely because parsing the
            // construct is itself the harm (a leading `<#ftl attributes={…}>` evaluates its
            // expressions AT PARSE TIME on this thread), so reporting it and then parsing anyway
            // would still burn the CPU the refusal exists to save.
            return failures
        }

        when (val parse = TemplateBodyParser.parse(draft.body, trace)) {
            is BodyParse.SyntaxError -> {
                failures +=
                    TemplateValidationFailure(
                        code = PipelineErrorCodes.Template.SYNTAX_ERROR,
                        message = parse.message,
                        details = mapOf("line" to parse.line, "column" to parse.column),
                    )
            }

            is BodyParse.Parsed -> {
                @Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
                val root: TemplateElement? = parse.template.rootTreeNode
                addDangerousConstructFailures(ForbiddenConstructScanner.scanAst(root), failures)
                addLibraryBodyFailure(draft, root, failures)
            }
        }
        return failures
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
}
