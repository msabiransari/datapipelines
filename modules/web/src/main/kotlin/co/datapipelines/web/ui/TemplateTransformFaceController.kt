package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TransformTestRunner
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.config.TransformProperties
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Duration
import java.util.UUID

/**
 * The transform editor face's partial routes (7d, #7; transform-nodes design §9.3/§9.4).
 *
 * - `GET  /partials/templates/transform-face` — the four panes (`template.read`); read-only
 *   for a non-author and for any version that is not the working DRAFT. The version select
 *   of a transform template swaps through it.
 * - `POST /partials/templates/transform-face/save` — **Save draft** (`template.update`): the
 *   four panes through 7b's write path — the deserializer and the `TemplateValidator` bean
 *   (the save gate runs the whole suite) then [TemplateDraftService], the service `PUT
 *   /api/v1/templates` uses — under the draft's `body_hash` precondition. A success re-renders
 *   the face over the stored draft (its new hash); a refusal lands in `#tf-result` only,
 *   naming its pane, and leaves the author's panes exactly as typed.
 * - `POST /partials/templates/transform-face/run-suite` — **Run suite** (`template.evaluate`,
 *   the row 7b's evaluate tool and route sit on): the panes as typed, through
 *   [TransformSuiteRun]; nothing is written.
 *
 * Every refusal is a 200 carrying the reason: htmx does not swap a 4xx body, and form-level
 * feedback belongs in the form (the 022 review F9 rule the editor's Edit follows).
 */
@Controller
class TemplateTransformFaceController(
    private val reads: TemplateService,
    private val lens: PromoterLens,
    private val validator: TemplateValidator,
    private val drafts: TemplateDraftService,
    runner: TransformTestRunner,
    transform: TransformProperties,
) {
    private val source = TemplateSourceModel(reads)
    private val suite = TransformSuiteRun(validator, runner, Duration.ofSeconds(transform.suiteTimeoutSeconds))

    @GetMapping("/partials/templates/transform-face")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun face(
        @RequestParam name: String,
        @RequestParam(required = false) version: Int?,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val filled = source.fill(model, workspaceId, lens.viewFor(principal).templates, name, version, RoleModel.roles(principal).canAuthor)
        RoleModel.stamp(model, principal)
        // One rule for the column: a name that is not a transform paints the source column.
        return filled.view
    }

    @Suppress("LongParameterList") // the four panes are four form fields; a holder would be the form again
    @PostMapping("/partials/templates/transform-face/save")
    @RequiredScope(Permission.TEMPLATE_UPDATE)
    fun save(
        @RequestParam name: String,
        @RequestParam bodyHash: String,
        @RequestParam body: String,
        @RequestParam contract: String,
        @RequestParam invariants: String,
        @RequestParam tests: String,
        model: Model,
        response: HttpServletResponse,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val refusals = write(workspaceId, principal.userId, name, bodyHash, TransformPanes(body, contract, invariants, tests))
        if (refusals.isNotEmpty()) return refused(model, response, refusals)
        source.fill(model, workspaceId, lens.viewFor(principal).templates, name, null, RoleModel.roles(principal).canAuthor)
        model.addAttribute("faceSaved", true)
        RoleModel.stamp(model, principal)
        return TransformFace.VIEW
    }

    @PostMapping("/partials/templates/transform-face/run-suite")
    @RequiredScope(Permission.TEMPLATE_EVALUATE)
    fun runSuite(
        @RequestParam name: String,
        @RequestParam body: String,
        @RequestParam contract: String,
        @RequestParam invariants: String,
        @RequestParam tests: String,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val working =
            reads.findWorking(workspaceId, lens.viewFor(principal).templates, name)?.takeIf { it.type.isTransform }
                ?: return result(model, refusals = listOf(notATransform(name)))
        return when (val bound = TransformFace.bind(working, TransformPanes(body, contract, invariants, tests))) {
            is TransformFace.Bound.Refused -> result(model, refusals = bound.refusals)
            is TransformFace.Bound.Draft -> result(model, suite = suite.run(workspaceId, bound.draft))
        }
    }

    /**
     * Save's write attempt — the panes bound over the working draft, 7b's gate, then the draft
     * service under [bodyHash] — answering the refusals, or none when the draft was written.
     */
    private fun write(
        workspaceId: UUID,
        actor: UUID,
        name: String,
        bodyHash: String,
        panes: TransformPanes,
    ): List<FaceRefusal> {
        val working =
            when (val found = editableWorking(workspaceId, name)) {
                is Working.Found -> found.template
                is Working.Refused -> return listOf(found.refusal)
            }
        val draft =
            when (val bound = TransformFace.bind(working, panes)) {
                is TransformFace.Bound.Refused -> return bound.refusals
                is TransformFace.Bound.Draft -> bound.draft
            }
        return try {
            validator.validateOrThrow(draft, workspaceId)
            drafts.write(workspaceId, name, draft, bodyHash, actor, WriteSurface.SESSION)
            emptyList()
        } catch (err: TemplateValidationException) {
            err.result.failures.map(TransformFace::refusalOf)
        } catch (err: DatapipelinesException) {
            listOf(FaceRefusal(null, err.code, err.message ?: err.code, detailOf(err.details)))
        }
    }

    /** The working version, when Save may write it: a transform template whose working version is its DRAFT. */
    private fun editableWorking(
        workspaceId: UUID,
        name: String,
    ): Working {
        val working =
            reads.findWorking(workspaceId, lens.viewFor(currentPrincipal()).templates, name)?.takeIf { it.type.isTransform }
        return when {
            working == null -> {
                Working.Refused(notATransform(name))
            }

            working.status != PipelineVersionStatus.DRAFT -> {
                Working.Refused(
                    FaceRefusal(
                        pane = null,
                        code = PipelineErrorCodes.Template.VERSION_NOT_DRAFT,
                        message = "'$name' has no draft to save into — press Edit to open one from v${working.version}.",
                        detail = null,
                    ),
                )
            }

            else -> {
                Working.Found(working)
            }
        }
    }

    private sealed interface Working {
        data class Found(
            val template: Template,
        ) : Working

        data class Refused(
            val refusal: FaceRefusal,
        ) : Working
    }

    private fun notATransform(name: String) =
        FaceRefusal(
            pane = null,
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "No transform template named '$name' in this workspace.",
            detail = null,
        )

    /** A save refusal: into `#tf-result` only, so the panes keep exactly what the author typed. */
    private fun refused(
        model: Model,
        response: HttpServletResponse,
        refusals: List<FaceRefusal>,
    ): String {
        refusals.forEach { log.info(TAG, CorrelationId.current(), it.code, it.pane ?: "-") }
        response.setHeader("HX-Retarget", "#tf-result")
        response.setHeader("HX-Reswap", "innerHTML")
        model.addAttribute("saveRefused", true)
        return result(model, refusals = refusals)
    }

    private fun result(
        model: Model,
        refusals: List<FaceRefusal> = emptyList(),
        suite: SuiteResult? = null,
    ): String {
        model.addAttribute("refusals", refusals)
        model.addAttribute("suite", suite)
        return TransformFace.RESULT_VIEW
    }

    private fun detailOf(details: Map<String, Any?>): String? =
        details.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { (key, value) -> "$key: $value" }

    private companion object {
        private val log = LoggerFactory.getLogger(TemplateTransformFaceController::class.java)
        private const val TAG = "Transform face refused — correlationId={}, code={}, pane={}"
    }
}
