package co.datapipelines.web.ui

import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.TemplateVersionDetail
import org.springframework.ui.Model
import java.util.UUID

/**
 * The template editor's SOURCE COLUMN model — one rule for every route that paints the column:
 * the editor page, `GET /partials/templates/editor/source` (the version select), and the
 * transform face's routes (7d, transform-nodes design §9.3). It lived in
 * [TemplateEditorController] as `fillSource` until the face needed it too; moved unchanged, so
 * the face cannot compute a second, slightly different `readOnly`.
 *
 * 039 O14: the default is the WORKING version — the draft when one exists, else the current
 * release. Selecting a DIFFERENT entry (R5) shows that version READ-ONLY, with its badge and,
 * when RELEASED, who released it and when. The editable surface only ever carries the working
 * version, so no selection can make a RELEASED row the write target.
 *
 * 143 (T315): `readOnly` is the version rule ABOVE combined with the author capability — a
 * reader (viewer, or a promoter, who releases but does not edit) sees every version, the
 * working one included, in the read-only surface. [canAuthor] arrives from [RoleModel].
 *
 * 7d: a transform version (`jsonata` / `javascript`) additionally gets the face's model
 * ([TransformFace.fill]) — the four panes and the save precondition — and [Filled.isTransform]
 * tells the route which fragment paints the column.
 */
internal class TemplateSourceModel(
    private val reads: TemplateService,
) {
    /** What a fill read: the draft (the header's affordances need it) and the version displayed. */
    data class Filled(
        val draft: TemplateVersionDetail?,
        val displayed: Template?,
    ) {
        /** The column is the transform face, not the Freemarker source column (7d). */
        val isTransform: Boolean get() = displayed?.type?.isTransform == true

        /** The fragment that paints the column for this fill. */
        val view: String get() = if (isTransform) TransformFace.VIEW else SOURCE_VIEW
    }

    fun fill(
        model: Model,
        workspaceId: UUID,
        view: ReadLens,
        name: String,
        requested: Int?,
        canAuthor: Boolean,
    ): Filled {
        val draft = reads.findDraftDetail(workspaceId, view, name)
        val latest = reads.findLatest(workspaceId, view, name)
        val workingVersion = draft?.version ?: latest?.version
        val selectedVersion = requested ?: workingVersion
        val displayed = displayedOf(workspaceId, view, name, selectedVersion, latest)
        val readOnly = displayed != null && workingVersion != null && (displayed.version != workingVersion || !canAuthor)
        // `readOnly` proves `displayed` non-null; Kotlin's data-flow carries that here.
        val detail = if (readOnly) reads.findVersionDetail(workspaceId, view, name, displayed.version) else null
        model.addAttribute("template", displayed)
        model.addAttribute("templateName", name)
        model.addAttribute("selectedVersion", displayed?.version ?: selectedVersion)
        model.addAttribute("workingVersion", workingVersion)
        model.addAttribute("readOnly", readOnly)
        model.addAttribute("selectedStatus", (detail?.status ?: displayed?.status)?.name)
        model.addAttribute("releasedAt", detail?.releasedAt)
        model.addAttribute("releasedBy", detail?.releasedBy?.toString())
        val filled = Filled(draft, displayed)
        model.addAttribute("isTransform", filled.isTransform)
        if (displayed != null && filled.isTransform) TransformFace.fill(model, displayed, readOnly)
        return filled
    }

    /**
     * The version the column shows. A `version` naming no stored row (a hand-typed URL) falls
     * back to the current release rather than painting an EMPTY editable textarea, which would
     * be a lie about what the author is looking at.
     */
    private fun displayedOf(
        workspaceId: UUID,
        view: ReadLens,
        name: String,
        selectedVersion: Int?,
        latest: Template?,
    ): Template? =
        when {
            selectedVersion == null -> null
            latest != null && selectedVersion == latest.version -> latest
            else -> reads.findVersion(workspaceId, view, name, selectedVersion)
        } ?: latest

    companion object {
        /** The Freemarker (sql/html) source column. */
        const val SOURCE_VIEW = "partials/template-source"
    }
}
