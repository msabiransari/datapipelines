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
    /** Uses the same current-pointer and payload rules as the pipeline job's save validation. */
    @Suppress("ThrowsCount") // absent target, absent current pointer and absent body are distinct save refusals
    fun check(
        principal: AuthenticatedPrincipal,
        request: ScheduleRequest,
    ) {
        if (request.executor != PipelineJobExecutor.EXECUTOR_ID) return
        val workspace = principal.requireWorkspace().id
        val name = PipelineJobExecutor.targetName(request.payload)
        val record =
            pipelines.findByNameAnyStatus(workspace, name)
                ?: throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_FOUND, "No pipeline named '$name' in this workspace.")
        val version =
            record.currentVersion
                ?: throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_RELEASED, "Pipeline '$name' has no current version to follow.")
        val executable =
            pipelineService.findExecutable(workspace, ReadLens.Everything, record, version)
                ?: throw ScheduleException(ScheduleErrorCodes.TARGET_NOT_FOUND, "Pipeline '$name' version $version has no stored body.")
        LakeAppendAuthorization.grant(principal, executable.writesManagedLake, Instant.now())
    }
}
