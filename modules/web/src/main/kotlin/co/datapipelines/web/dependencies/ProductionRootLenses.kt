package co.datapipelines.web.dependencies

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.dependencies.Node
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.schedules.PrincipalTargetViewer
import java.util.UUID

/**
 * The seven lensed families' root checks (#875 G4; record §12 as amended by erratum (8)(b): the promoter lens decides
 * the ROOT only). Each asks the family's OWN lensed read — the one its detail GET makes — never a copy of its rule:
 *
 * | family | before the walk | after the walk (resolved root) |
 * |---|---|---|
 * | pipeline | `LensedView.pipelines.admits(name)` | `PipelineService.findRecord(ws, lens, id)` → `findVersion(ws, lens, record, v)` |
 * | template | `LensedView.templates.admits(name)` | `TemplateService.findVersion(ws, lens, name, v)` |
 * | parameter_set | `LensedView.parameterSets.admits(name)` | `ParameterSetService.findVersion(ws, lens, id, v)` |
 * | visualization | `LensedView.visualizations.admits(name)` | `VisualizationService.findVersion(ws, lens, id, v)` |
 * | dashboard | `LensedView.dashboards.admits(name)` | `DashboardService.findVersion(ws, lens, id, v)` |
 * | api_endpoint | `EndpointPublishService.get(principal, path)` | `EndpointVersionService.version(principal, id, v)` |
 * | schedule | `ScheduleService.get(ws, id, PrincipalTargetViewer(principal))` (its target admitted) | Same read and captured revision |
 *
 * The id-keyed version reads run after the walk because these families' version detail reads are keyed by the entity
 * id the walk resolves. Their admitted lifecycle and hash must match the captured node; schedules must match its
 * revision. A hidden or changed root's tree is discarded before projection, so a later edit cannot authorize an earlier
 * snapshot. Native reads remain the authority, including API drafts that their pinned-pipeline lens admits.
 * `EndpointPublishService.get`'s super-admin cross-workspace arm is unreachable here: a lensed principal is never a
 * super admin (`AuthenticatedPrincipal.isLensed`), and the walk itself stays in the caller's workspace.
 */
object ProductionRootLenses {
    @Suppress("LongParameterList") // one native service per lensed family
    fun of(
        pipelines: PipelineService,
        templates: TemplateService,
        parameterSets: ParameterSetService,
        visualizations: VisualizationService,
        dashboards: DashboardService,
        endpoints: EndpointPublishService,
        endpointVersions: EndpointVersionService,
        schedules: ScheduleService,
    ): DependencyFamilyTransport.ProductionRootLenses =
        DependencyFamilyTransport.ProductionRootLenses(
            pipeline =
                artifact({ it.pipelines }) { principal, lens, _, root, version ->
                    val workspace = principal.requireWorkspace().id
                    pipelines
                        .findRecord(workspace, lens, UUID.fromString(root.stableEntityId))
                        ?.let {
                            pipelines.findVersion(workspace, lens, it, version)
                        }?.version
                        ?.let { VersionState(it.status, it.bodyHash) }
                },
            template =
                artifact({ it.templates }) { principal, lens, request, _, version ->
                    templates
                        .findVersion(principal.requireWorkspace().id, lens, request.lookupKey, version)
                        ?.let { VersionState(it.status, it.bodyHash) }
                },
            parameterSet =
                artifact({ it.parameterSets }) { principal, lens, _, root, version ->
                    parameterSets
                        .findVersion(principal.requireWorkspace().id, lens, UUID.fromString(root.stableEntityId), version)
                        ?.detail
                        ?.let { VersionState(it.status, it.bodyHash) }
                },
            visualization =
                artifact({ it.visualizations }) { principal, lens, _, root, version ->
                    visualizations
                        .findVersion(principal.requireWorkspace().id, lens, UUID.fromString(root.stableEntityId), version)
                        ?.detail
                        ?.let { VersionState(it.status, it.bodyHash) }
                },
            dashboard =
                artifact({ it.dashboards }) { principal, lens, _, root, version ->
                    dashboards
                        .findVersion(principal.requireWorkspace().id, lens, UUID.fromString(root.stableEntityId), version)
                        ?.detail
                        ?.let { VersionState(it.status, it.bodyHash) }
                },
            // A visible API's older version may pin a pipeline the lens hides: its own version read refuses that version,
            // so the resolved half asks it — the entity read alone would admit every version of a visible API.
            apiEndpoint =
                object : RootLens.Lensed {
                    override fun admitsRequest(
                        principal: AuthenticatedPrincipal,
                        view: LensedView,
                        request: DependenciesRequest,
                    ) = endpoints.get(principal, request.lookupKey) != null

                    override fun admitsResolved(
                        principal: AuthenticatedPrincipal,
                        view: LensedView,
                        request: DependenciesRequest,
                        root: Node,
                    ): Boolean {
                        val identity = requireNotNull(root.identity) { "an API root is resolved" }
                        val version = requireNotNull(identity.artifactVersion ?: request.version) { "an API root resolves at a version" }
                        val admitted = endpointVersions.version(principal, UUID.fromString(identity.stableEntityId), version)?.version
                        return admitted != null && VersionState(admitted.status, admitted.bodyHash).matches(root)
                    }
                },
            schedule =
                object : RootLens.Lensed {
                    override fun admitsRequest(
                        principal: AuthenticatedPrincipal,
                        view: LensedView,
                        request: DependenciesRequest,
                    ) = scheduleRevision(schedules, principal, request) != null

                    override fun admitsResolved(
                        principal: AuthenticatedPrincipal,
                        view: LensedView,
                        request: DependenciesRequest,
                        root: Node,
                    ): Boolean {
                        val revision = (root.versionInfo as? VersionInfo.Revision)?.number ?: return false
                        return scheduleRevision(schedules, principal, request) == revision
                    }
                },
        )

    /** Correlation with the native admitted row, without copying that family's visibility predicate. */
    private data class VersionState(
        val status: PipelineVersionStatus,
        val bodyHash: String,
    ) {
        fun matches(root: Node): Boolean = root.lifecycle?.name == status.name && root.bodyHash == bodyHash
    }

    /** The version read of an artifact family's resolved root. */
    private fun interface VersionRead {
        fun admitted(
            principal: AuthenticatedPrincipal,
            lens: ReadLens,
            request: DependenciesRequest,
            root: ResolvedIdentity,
            version: Int,
        ): VersionState?
    }

    private fun artifact(
        arm: (LensedView) -> ReadLens,
        read: VersionRead,
    ): RootLens.Lensed =
        object : RootLens.Lensed {
            override fun admitsRequest(
                principal: AuthenticatedPrincipal,
                view: LensedView,
                request: DependenciesRequest,
            ) = arm(view).admits(request.lookupKey)

            override fun admitsResolved(
                principal: AuthenticatedPrincipal,
                view: LensedView,
                request: DependenciesRequest,
                root: Node,
            ): Boolean {
                val identity = requireNotNull(root.identity) { "an artifact root is resolved" }
                val version = requireNotNull(identity.artifactVersion ?: request.version) { "an artifact root resolves at a version" }
                return read.admitted(principal, arm(view), request, identity, version)?.matches(root) == true
            }
        }

    private fun scheduleRevision(
        schedules: ScheduleService,
        principal: AuthenticatedPrincipal,
        request: DependenciesRequest,
    ): Int? =
        try {
            schedules.get(principal.requireWorkspace().id, UUID.fromString(request.lookupKey), PrincipalTargetViewer(principal)).revision
        } catch (hidden: ScheduleException) {
            if (hidden.code != ScheduleErrorCodes.NOT_FOUND) throw hidden
            null
        }
}
