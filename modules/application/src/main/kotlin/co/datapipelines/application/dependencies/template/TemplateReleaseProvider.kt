package co.datapipelines.application.dependencies.template

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.IncomingArms
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.dependencies.Reference
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import java.util.UUID

/**
 * The template family's native provider (#873 G2; record §6 template → template `IMPORT`, §23.3): a template version
 * resolves by its id (the pin key) and exact version through ONE batch read ([TemplateRepository.findReleaseVersions]),
 * and references one library per `imports[i]` occurrence at field path `imports[i]` — two imports of one library version
 * are two edges to one node (A02). The library-type rule stays in `LibraryResolver` (§23.3 item 3), so no constraint
 * rides the edge. Replaces G1's adapted template source in the registry (brief §A.2: one descriptor per family).
 */
class TemplateReleaseProvider(
    private val templates: TemplateRepository,
    ceiling: StatementCeiling,
    incoming: IncomingArms? = null,
) : ArtifactReleaseProvider<Template>(ReleaseFamily.TEMPLATE, ceiling, incoming) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<Template>> =
        templates.findReleaseVersions(workspaceId, keys).map { row ->
            val template = row.template
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, template.id, template.version),
                entityUuid = row.entityUuid,
                name = template.id,
                status = template.status,
                bodyHash = template.bodyHash,
                currentVersion = row.currentVersion,
                body = template,
            )
        }

    /**
     * One `IMPORT` reference per `imports[i]` occurrence, carrying that occurrence's declared alias verbatim as provenance
     * (#873 R2, record §6 "Preserve alias/path as provenance", §23.3 item 1): a library imported twice under two aliases
     * is two edges, each with its own alias. The alias is the author's own, already bounded by the save validator's
     * `IMPORT_ALIAS` rule; it is never hashed (`tree_hash` v1 unchanged) and never rendered.
     */
    override fun references(stored: StoredVersion<Template>): List<Reference> =
        importsOf(stored.identity.workspaceId, stored.body).zip(stored.body.imports) { (target, path), import ->
            reference(target, path, alias = import.alias)
        }

    companion object {
        /** The field path of the [index]th import — the release form the `tree_hash` covers. */
        fun importPath(index: Int): String = "imports[$index]"

        /** Every import occurrence of [template]: the exact library version and its field path, in import order. */
        fun importsOf(
            workspaceId: UUID,
            template: Template,
        ): List<Pair<ReleaseNodeIdentity, String>> =
            template.imports.mapIndexed { index, import ->
                ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, import.id, import.version) to importPath(index)
            }
    }
}
