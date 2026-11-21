package co.datapipelines.application.compatibility

import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import java.util.UUID

/**
 * The report's inventory (pipeline-write-transactions §7: "current stored and scheduled
 * versions"): for every live workspace, each pipeline's CURRENT version, plus every version a live
 * pipeline schedule pins — the exact pin of payload schema 2 (`{"pipeline": <name>, "version":
 * <n>}`, #786), the only schema a live schedule runs. A version that is both current and pinned is
 * one candidate listing both sources. A schema-1 schedule (converted to an exact pin at startup)
 * or a pin the store does not hold is listed UNREADABLE, never skipped silently.
 *
 * Read-only: every call here is a metadata SELECT.
 */
class StoredCompatibilityCandidates(
    private val workspaces: WorkspaceRepository,
    private val pipelines: PipelineRepository,
    private val schedules: ScheduleRepository,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
) {
    /** Every candidate, by workspace name, pipeline name and version. */
    fun all(): List<CompatibilityCandidate> {
        val names = workspaces.findAll().associate { it.id to it.name }
        val found = linkedMapOf<Key, MutableList<String>>()
        val unreadable = mutableListOf<CompatibilityCandidate>()
        names.keys.forEach { workspaceId ->
            pipelines.findCurrentVersions(workspaceId).forEach { current ->
                found.getOrPut(Key(workspaceId, current.name, current.version)) { mutableListOf() } += SOURCE_CURRENT
            }
        }
        schedules.listLiveAtSchema(PIPELINE_EXECUTOR, PINNED_SCHEMA).filter { it.workspaceId in names }.forEach { schedule ->
            val pin = pinOf(schedule)
            if (pin == null) {
                unreadable += schedule.unreadable(names, "schedule payload names no exact pipeline version")
            } else {
                found.getOrPut(Key(schedule.workspaceId, pin.first, pin.second)) { mutableListOf() } += "schedule:${schedule.name}"
            }
        }
        schedules.listLiveAtSchema(PIPELINE_EXECUTOR, LEGACY_SCHEMA).filter { it.workspaceId in names }.forEach { schedule ->
            unreadable += schedule.unreadable(names, "schedule payload schema 1 is converted to an exact pin at startup")
        }
        val inspected = found.map { (key, sources) -> candidate(key, names.getValue(key.workspaceId), sources) }
        return (inspected + unreadable).sortedWith(compareBy({ it.workspace }, { it.pipelineName }, { it.version ?: 0 }))
    }

    /** The pinned child resolver the planner walks with: same workspace, exact version, stored body. */
    fun resolver(): PipelineResolver =
        PipelineResolver { workspaceId, name, version ->
            val record = pipelines.findByNameAnyStatus(workspaceId, name) ?: return@PipelineResolver null
            val body = pipelines.findVersionBody(workspaceId, record.id, version) ?: return@PipelineResolver null
            ResolvedPipeline(pipeline = deserializer.readOrThrow(body), entityDiscarded = false)
        }

    private fun candidate(
        key: Key,
        workspace: String,
        sources: List<String>,
    ): CompatibilityCandidate {
        val pipeline = read(key)
        return CompatibilityCandidate(
            workspaceId = key.workspaceId,
            workspace = workspace,
            pipelineName = key.pipeline,
            version = key.version,
            sources = sources,
            pipeline = pipeline,
            unreadable = if (pipeline == null) "stored body not found or not readable" else null,
        )
    }

    private fun read(key: Key): Pipeline? {
        val record = pipelines.findByNameAnyStatus(key.workspaceId, key.pipeline) ?: return null
        val body = pipelines.findVersionBody(key.workspaceId, record.id, key.version) ?: return null
        return runCatching { deserializer.readOrThrow(body) }.getOrNull()
    }

    private fun pinOf(schedule: Schedule): Pair<String, Int>? {
        val name =
            schedule.payload
                .path("pipeline")
                .takeIf { it.isTextual }
                ?.asText()
        val version =
            schedule.payload
                .path("version")
                .takeIf { it.isIntegralNumber }
                ?.asInt()
        return if (name != null && version != null && version >= 1) name to version else null
    }

    private fun Schedule.unreadable(
        names: Map<UUID, String>,
        why: String,
    ) = CompatibilityCandidate(
        workspaceId = workspaceId,
        workspace = names.getValue(workspaceId),
        pipelineName = payload.path("pipeline").takeIf { it.isTextual }?.asText() ?: "-",
        version = null,
        sources = listOf("schedule:$name"),
        pipeline = null,
        unreadable = why,
    )

    private data class Key(
        val workspaceId: UUID,
        val pipeline: String,
        val version: Int,
    )

    private companion object {
        /** The scheduler's pipeline executor id and its payload schemas (#786). */
        const val PIPELINE_EXECUTOR = "pipeline"
        const val PINNED_SCHEMA = 2
        const val LEGACY_SCHEMA = 1
        const val SOURCE_CURRENT = "current"
    }
}
