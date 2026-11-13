package co.datapipelines.application.dependencies.parameterset

import co.datapipelines.application.dependencies.ArtifactReleaseProvider
import co.datapipelines.application.dependencies.IncomingArms
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.StoredVersion
import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.dependencies.Reference
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.templates.TemplateRepository
import java.util.UUID

/**
 * A parameter-set version as its provider resolves it: the stored [set] body, and the dialect wire token each
 * datasource-bearing selector's pinned template declares, by parameter index (#873 §F.2) — the `datasource_dialect`
 * requirement of its `PARAMETER_SOURCE_DATASOURCE` edge. An index is absent when its selector names no datasource, pins
 * no template, or pins a version that is not stored.
 */
data class ParameterSetReleaseBody(
    val set: ParameterSetBody,
    val selectorDialects: Map<Int, String> = emptyMap(),
)

/**
 * The parameter-set family's native provider (#873 G2; record §6 parameter set → template `PARAMETER_SOURCE_TEMPLATE`,
 * parameter set → datasource `PARAMETER_SOURCE_DATASOURCE`): a set version resolves by its name and exact version through
 * ONE batch read ([ParameterSetRepository.findVersionsByName]), and references, in parameter order, the selector template
 * of every parameter whose source names one, at `parameters[i].source.template` (the path the set's restore and release
 * refusals already spell), then every selector datasource at `parameters[i].source.datasource` ([DatasourceEdges]).
 *
 * The dialect a datasource edge requires is `PinRules`' rule — the selector's template runs against that datasource, so
 * the datasource must speak the template's dialect — read from the pinned template versions' stored rows in at most ONE
 * more batch statement per read ([TemplateRepository.findReleaseVersions]), issued only when a read set has a selector
 * that pins both a template and a datasource. The selector is never executed, the datasource never described.
 */
class ParameterSetReleaseProvider(
    private val sets: ParameterSetRepository,
    private val templates: TemplateRepository,
    ceiling: StatementCeiling,
    incoming: IncomingArms? = null,
) : ArtifactReleaseProvider<ParameterSetReleaseBody>(ReleaseFamily.PARAMETER_SET, ceiling, incoming) {
    override fun read(
        workspaceId: UUID,
        keys: List<Pair<String, Int>>,
    ): List<StoredVersion<ParameterSetReleaseBody>> {
        val stored = sets.findVersionsByName(workspaceId, keys)
        val dialects = dialectsOf(workspaceId, stored.map { it.body })
        return stored.map { version ->
            StoredVersion(
                identity = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PARAMETER_SET, version.record.name, version.detail.version),
                entityUuid = version.record.id,
                name = version.record.name,
                status = version.detail.status,
                bodyHash = version.detail.bodyHash,
                currentVersion = version.record.currentVersion,
                body = ParameterSetReleaseBody(version.body, selectorDialects(version.body, dialects)),
            )
        }
    }

    override fun references(stored: StoredVersion<ParameterSetReleaseBody>): List<Reference> {
        val workspaceId = stored.identity.workspaceId
        val body = stored.body
        return templatePins(workspaceId, body.set).map { (target, path) -> reference(target, path) } +
            DatasourceEdges.ofParameterSet(workspaceId, body.set, body.selectorDialects::get)
    }

    /** The dialect of every template a datasource-bearing selector of [bodies] pins, keyed `(id, version)`: one statement, or none. */
    private fun dialectsOf(
        workspaceId: UUID,
        bodies: List<ParameterSetBody>,
    ): Map<Pair<String, Int>, String> {
        val wanted =
            bodies
                .flatMap { body -> body.parameters.mapNotNull { it.source?.takeIf { source -> source.datasource != null }?.template } }
                .map { it.id to it.version }
                .distinct()
        if (wanted.isEmpty()) return emptyMap()
        return templates
            .findReleaseVersions(workspaceId, wanted)
            .mapNotNull { row -> row.template.dialect?.let { (row.template.id to row.template.version) to it.wire } }
            .toMap()
    }

    companion object {
        /** Every selector-template occurrence of [body]: the exact template version and its field path, in parameter order. */
        fun templatePins(
            workspaceId: UUID,
            body: ParameterSetBody,
        ): List<Pair<ReleaseNodeIdentity, String>> =
            body.parameters.withIndex().mapNotNull { (index, parameter) ->
                parameter.source?.template?.let { template ->
                    ReleaseNodeIdentity(workspaceId, ReleaseFamily.TEMPLATE, template.id, template.version) to
                        "parameters[$index].source.template"
                }
            }

        /** The dialect each datasource-bearing selector of [body] requires, by parameter index, from [dialects]. */
        fun selectorDialects(
            body: ParameterSetBody,
            dialects: Map<Pair<String, Int>, String>,
        ): Map<Int, String> =
            body.parameters
                .withIndex()
                .mapNotNull { (index, parameter) ->
                    val source = parameter.source?.takeIf { it.datasource != null } ?: return@mapNotNull null
                    val template = source.template ?: return@mapNotNull null
                    dialects[template.id to template.version]?.let { index to it }
                }.toMap()
    }
}
