package co.datapipelines.web.schedules

import co.datapipelines.application.LakeAppendAuthorization
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.scheduler.ScheduleErrorCodes
import co.datapipelines.scheduler.ScheduleException
import co.datapipelines.scheduler.ScheduleRequest
import java.time.Instant

/** Extra authority for saving pipeline schedules; launch itself runs with the system actor's own grant. */
class ScheduleLakeAppendAuthorization(
    private val pipelines: PipelineRepository,
    private val pipelineService: PipelineService,
) {
    /** Uses the same exact-pin payload rules as the pipeline job's save validation (#786 D.1): the PINNED version's body decides. */
    @Suppress("ThrowsCount") // absent target and absent body are distinct save refusals
    fun check(
        principal: AuthenticatedPrincipal,
        request: ScheduleRequest,
    ) {
        if (request.executor != PipelineJobExecutor.EXECUTOR_ID) return
        val workspace = principal.requireWorkspace().id
        val (name, version) = PipelineJobExecutor.target(request.payload)
        val record =
            pipelines.findByNameAnyStatus(workspace, name)
                ?: throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_FOUND, "No pipeline named '$name' in this workspace.")
        val executable =
            pipelineService.findExecutable(workspace, ReadLens.Everything, record, version)
                ?: throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_FOUND, "Pipeline '$name' version $version has no stored body.")
        LakeAppendAuthorization.grant(principal, executable.writesManagedLake, Instant.now())
    }
}
