package co.datapipelines.pipeline

import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import co.datapipelines.pipeline.release.ReleaseFamily

/**
 * #877 §E.3 (iii) — the release coordinator's stand-ins for this module, which cannot see the coordinator (`web` composes
 * it over this module). Since the native entry point was retired, `PipelineService.release` ALWAYS runs through a
 * [PipelineReleaseCoordination]; the release CASES run through the real coordinator in `web`
 * (`PipelineReleaseCoordinatorCasesTest`), never through these.
 */
object ReleaseCoordinationDoubles {
    /** For a construction that never releases: a release is a bug in the test, and it says so loudly. */
    val REFUSING = PipelineReleaseCoordination { error("this test never releases a pipeline: it wired the refusing coordinator") }

    /**
     * For a test whose releases are SET-UP — a RELEASED version to discard, restore, read or run: the pipeline
     * participant's own seams in the coordinator's order (prepare outside, then the flip and the result), with no
     * dependency closure, no cascade and no audit row. It refuses with an [IllegalStateException], never a catalogued
     * code, a request carrying a plan or the alias and a body pinning a template [statuses] does not answer RELEASED:
     * those are the coordinator's decisions, and a test that needs one belongs beside the coordinator.
     */
    fun setUp(
        releases: PipelineReleaseService,
        statuses: TemplateVersionStatuses,
    ) = PipelineReleaseCoordination { request ->
        check(request.plan?.present != true && !request.releasePinnedTemplates) {
            "the set-up release double takes no plan and no alias; release through the coordinator instead"
        }
        val prepared = releases.prepare(request.workspaceId, request.pipelineId, request.actor, request.overrideChecksReason)
        PipelineDependencySource
            .pinsOf(request.workspaceId, prepared.pipeline)
            .filter { it.target.family == ReleaseFamily.TEMPLATE }
            .forEach { pin ->
                check(statuses.statusOf(request.workspaceId, pin.target.entityId, pin.target.version) == PipelineVersionStatus.RELEASED) {
                    "the set-up release double releases only a body whose template pins are RELEASED (${pin.path})"
                }
            }
        releases.finish(prepared, releases.flip(prepared, request.expectedHash, request.actor), emptyList())
    }
}
