package co.datapipelines.application.dependencies.schedule

import co.datapipelines.application.dependencies.BoundedGraphRead
import co.datapipelines.application.dependencies.CeilingBound
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.PromotionCapability
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import co.datapipelines.dependencies.ScheduleCondition as GraphCondition
import co.datapipelines.scheduler.ScheduleCondition as SchedulerCondition

/**
 * The schedule family of the dependency graph (#874 G3; record §4 row `schedule`, §6 row "Schedule → pipeline
 * `EXECUTOR_TARGET`", §11.1): a revisioned workspace object, `NONE`/`NONE` capabilities, addressed by its id (a canonical
 * UUID as the ENTITY lookup key), with exactly one outgoing edge — the executor's target.
 */
object ScheduleDependencyFamily {
    val KEY: FamilyKey = FamilyKey.SCHEDULE

    /** Record §4: a schedule is neither released nor promoted. */
    val CAPABILITIES = Capabilities(ReleaseCapability.NONE, PromotionCapability.NONE)

    /** The node projection's first version; part of the internal fingerprint (§5.3). */
    const val EXTRACTOR_SCHEMA_VERSION = 1

    /**
     * The target address of a payload no extractor reads: the empty lookup key, which no pipeline name can be (the name
     * grammar needs two segments), so every pipeline provider answers it MISSING. G1's contract has no "unreadable"
     * selector and its pipeline provider answers only EXACT versions, so the selector is EXACT — the payload's own version
     * when that alone is a valid pin, otherwise 1. The node shows the payload's spelled name, never this key.
     */
    const val UNREADABLE_TARGET_KEY = ""

    /** The unreadable target's version when the payload spells no valid pin ([UNREADABLE_TARGET_KEY]). */
    const val UNREADABLE_TARGET_VERSION = 1

    /** The source path of an unknown executor's target: the whole payload (JSON Pointer `""`), since no field is known. */
    const val WHOLE_PAYLOAD = ""

    /**
     * The descriptor over [schedules] — a repository over [ceiling]'s own template in production, so the one statement a
     * batch sends is bounded (#786 R3) — and the extractors of the executors this deployment runs, by executor id.
     *
     * [extractors] is read ONCE, on first use ([extractorsOf] forces it): in production the extractors are executor
     * beans whose own dependencies reach the family registry this descriptor belongs to (pipeline executor → pipeline
     * service → release service → the registry), so an eager read is a circular bean reference and the context does not
     * boot. `DependencyGraphNonArtifactConfiguration` forces the read once every singleton exists, so a duplicate
     * executor id still fails the boot.
     */
    fun descriptor(
        schedules: ScheduleRepository,
        extractors: () -> List<ExecutorTargetExtractor>,
        ceiling: StatementCeiling,
    ): FamilyDescriptor<ScheduleGraphBody> =
        FamilyDescriptor(
            key = KEY,
            rootSelectors = setOf(SelectorKind.ENTITY),
            capabilities = CAPABILITIES,
            extractorSchemaVersion = EXTRACTOR_SCHEMA_VERSION,
            provider = ScheduleDependencyProvider(schedules, { byExecutor(extractors()) }, ceiling),
            safeSummary = FamilyDescriptor.NO_SAFE_SUMMARY,
        )

    /** [descriptor] over a fixed extractor list, checked at once (tests and callers with no bean cycle). */
    fun descriptor(
        schedules: ScheduleRepository,
        extractors: List<ExecutorTargetExtractor>,
        ceiling: StatementCeiling,
    ): FamilyDescriptor<ScheduleGraphBody> = descriptor(schedules, { extractors }, ceiling).also(::extractorsOf)

    /**
     * The extractors of [descriptor] (a schedule descriptor [descriptor] built), by executor id — resolving them on the
     * first call and refusing two extractors for one executor id.
     */
    fun extractorsOf(descriptor: FamilyDescriptor<*>): Map<String, ExecutorTargetExtractor> =
        requireNotNull(descriptor.provider as? ScheduleDependencyProvider) { "not a schedule family descriptor: ${descriptor.key}" }
            .extractors

    private fun byExecutor(extractors: List<ExecutorTargetExtractor>): Map<String, ExecutorTargetExtractor> {
        val byExecutor = extractors.associateBy { it.executorId }
        require(byExecutor.size == extractors.size) {
            "one executor-target extractor per executor id, got ${extractors.map { it.executorId }}"
        }
        return byExecutor
    }

    /** The canonical UUID [key] names, or null: a lookup key that is not a lower-case canonical UUID names no schedule. */
    fun scheduleId(key: String): UUID? = runCatching { UUID.fromString(key) }.getOrNull()?.takeIf { it.toString() == key }

    internal fun condition(of: SchedulerCondition): GraphCondition =
        when (of) {
            SchedulerCondition.ENABLED -> GraphCondition.ENABLED
            SchedulerCondition.PAUSED -> GraphCondition.PAUSED
            SchedulerCondition.BLOCKED -> GraphCondition.BLOCKED
        }
}

/**
 * What the graph keeps of one live schedule: the node's facts and what its target extraction reads — the executor id,
 * the payload schema and a private copy of the payload. Nothing else of the row: no parameters, no cron, no notification
 * recipients. [toString] prints neither the payload nor anything a node does not show.
 */
class ScheduleGraphBody internal constructor(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val revision: Int,
    val condition: GraphCondition,
    val executorId: String,
    val payloadSchemaVersion: Int,
    payload: JsonNode,
) {
    private val payloadCopy: JsonNode = payload.deepCopy()

    /** A fresh copy each call: the body stays as it was loaded whatever a reader does with what it receives. */
    val payload: JsonNode get() = payloadCopy.deepCopy()

    override fun toString(): String = "ScheduleGraphBody(id=$id, name=$name, revision=$revision, condition=${condition.wire})"

    internal companion object {
        fun of(schedule: Schedule): ScheduleGraphBody =
            ScheduleGraphBody(
                id = schedule.id,
                workspaceId = schedule.workspaceId,
                name = schedule.name,
                revision = schedule.revision,
                condition = ScheduleDependencyFamily.condition(SchedulerCondition.of(schedule.enabled, schedule.blocked)),
                executorId = schedule.executorId,
                payloadSchemaVersion = schedule.payloadSchemaVersion,
                payload = schedule.payload,
            )
    }
}

/**
 * Resolves schedule ENTITY requests by id in ONE bounded statement per workspace in the call ([BoundedGraphRead]; a walk
 * carries one workspace, so one statement per call) and answers every request once: a returned row is a node; a deleted,
 * another workspace's, absent or malformed id is [Outcome.Missing]; a non-ENTITY selector is [Outcome.Unsupported].
 *
 * Node: `name`, `version = revision`, `lifecycle = null`, `operational = schedule(condition)`; identity = the id in its
 * workspace. [outgoing] is EXACTLY one [Relationship.EXECUTOR_TARGET] reference, read by the executor's
 * [ExecutorTargetExtractor] from the loaded payload: a pipeline at its selector, or — for a payload nobody can read —
 * the unreadable address ([ScheduleDependencyFamily.UNREADABLE_TARGET_KEY]) that resolves MISSING with the walk's
 * MISSING diagnostic. Never the payload, parameters or recipients.
 */
internal class ScheduleDependencyProvider(
    private val schedules: ScheduleRepository,
    extractors: () -> Map<String, ExecutorTargetExtractor>,
    override val ceiling: StatementCeiling,
) : DependencyProvider<ScheduleGraphBody>,
    CeilingBound {
    /** The executors' target extractors by executor id, read on first use ([ScheduleDependencyFamily.descriptor]). */
    val extractors: Map<String, ExecutorTargetExtractor> by lazy(extractors)

    override fun resolveMany(
        requests: List<ObjectRequest>,
        context: ResolveContext,
    ): Map<ObjectRequest, Outcome<ScheduleGraphBody>> {
        val entity = requests.filter { it.family == ScheduleDependencyFamily.KEY && it.selector == Selector.Entity }
        val keyed = entity.mapNotNull { request -> ScheduleDependencyFamily.scheduleId(request.address.lookupKey)?.let { request to it } }
        val found: (ObjectRequest, UUID) -> Schedule? =
            if (keyed.isEmpty()) {
                { _, _ -> null }
            } else {
                BoundedGraphRead.run(ScheduleDependencyFamily.KEY, requests, context, ceiling) { read(keyed) }
            }
        val ids = keyed.toMap()
        return requests.associateWith { request ->
            when {
                request !in entity -> Outcome.Unsupported
                else -> ids[request]?.let { id -> found(request, id) }?.let(::resolved) ?: Outcome.Missing
            }
        }
    }

    /** One statement per lookup workspace among [keyed]; the answer looks a request's row up by workspace and id. */
    private fun read(keyed: List<Pair<ObjectRequest, UUID>>): (ObjectRequest, UUID) -> Schedule? {
        val byWorkspace =
            keyed.groupBy({ it.first.address.lookupScope.workspaceId }, { it.second }).mapValues { (workspaceId, ids) ->
                schedules.findLiveByIds(workspaceId, ids.distinct()).associateBy { it.id }
            }
        return { request, id -> byWorkspace[request.address.lookupScope.workspaceId]?.get(id) }
    }

    private fun resolved(schedule: Schedule): Outcome.Resolved<ScheduleGraphBody> {
        val body = ScheduleGraphBody.of(schedule)
        return Outcome.Resolved(
            identity =
                ResolvedIdentity(
                    family = ScheduleDependencyFamily.KEY,
                    ownerScope = OwnerScope.Workspace(body.workspaceId),
                    stableEntityId = body.id.toString(),
                    artifactVersion = null,
                ),
            facts =
                NodeFacts(
                    name = body.name,
                    versionInfo = VersionInfo.Revision(body.revision),
                    lifecycle = null,
                    operational = Operational.Schedule(body.condition),
                ),
            body = body,
        )
    }

    override fun outgoing(resolved: Outcome.Resolved<ScheduleGraphBody>): List<Reference> {
        val body = resolved.body
        val target =
            extractors[body.executorId]?.targetOf(body.payloadSchemaVersion, body.payload)
                ?: ExecutorTarget.Unreadable(spelledName = null, spelledVersion = null, sourcePath = ScheduleDependencyFamily.WHOLE_PAYLOAD)
        val (key, selector, spelled) =
            when (target) {
                is ExecutorTarget.Pipeline -> {
                    Triple(target.name, target.selector, target.name)
                }

                is ExecutorTarget.Unreadable -> {
                    Triple(
                        ScheduleDependencyFamily.UNREADABLE_TARGET_KEY,
                        Selector.ExactVersion(target.spelledVersion ?: ScheduleDependencyFamily.UNREADABLE_TARGET_VERSION),
                        target.spelledName,
                    )
                }
            }
        return listOf(
            Reference(
                request = ObjectRequest(ObjectAddress(FamilyKey.PIPELINE, LookupScope(body.workspaceId), key), selector),
                relationship = Relationship.EXECUTOR_TARGET,
                sourcePath = target.sourcePath,
                spelledName = spelled,
            ),
        )
    }
}
