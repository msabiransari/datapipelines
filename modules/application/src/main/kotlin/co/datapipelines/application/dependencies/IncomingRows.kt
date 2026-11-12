package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.IncomingEdge
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.findAllIncoming
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import java.util.UUID

/**
 * The twelve inverse arms of record §14 by name (#877 §C) — what an adapter asks for, without naming a
 * `dependency-contract` type (the surfaces that hold an adapter, `mcp-server` among them, do not see that module).
 */
enum class InverseArm(
    internal val target: FamilyKey,
    internal val dependents: FamilyKey,
) {
    TEMPLATE_FROM_PIPELINE(FamilyKey.TEMPLATE, FamilyKey.PIPELINE),
    TEMPLATE_FROM_TEMPLATE(FamilyKey.TEMPLATE, FamilyKey.TEMPLATE),
    TEMPLATE_FROM_PARAMETER_SET(FamilyKey.TEMPLATE, FamilyKey.PARAMETER_SET),
    TEMPLATE_FROM_VISUALIZATION(FamilyKey.TEMPLATE, FamilyKey.VISUALIZATION),
    PIPELINE_FROM_PIPELINE(FamilyKey.PIPELINE, FamilyKey.PIPELINE),
    PIPELINE_FROM_DASHBOARD(FamilyKey.PIPELINE, FamilyKey.DASHBOARD),
    PIPELINE_FROM_API_ENDPOINT(FamilyKey.PIPELINE, FamilyKey.API_ENDPOINT),
    PIPELINE_FROM_SCHEDULE(FamilyKey.PIPELINE, FamilyKey.SCHEDULE),
    PARAMETER_SET_FROM_DASHBOARD(FamilyKey.PARAMETER_SET, FamilyKey.DASHBOARD),
    VISUALIZATION_FROM_DASHBOARD(FamilyKey.VISUALIZATION, FamilyKey.DASHBOARD),
    DATASOURCE_FROM_PIPELINE(FamilyKey.DATASOURCE, FamilyKey.PIPELINE),
    DATASOURCE_FROM_PARAMETER_SET(FamilyKey.DATASOURCE, FamilyKey.PARAMETER_SET),
}

/**
 * The adapters' one read (#877 §C.1): EVERY stored reference occurrence of one arm into [key] — at [version], or
 * entity-wide when null — in [workspaceId]'s dependents under [scope], as the native query's rows, in the inverse total
 * order. The production implementation is [RegistryIncomingRows] (`findIncoming` paged to exhaustion); a test passes a
 * lambda. The bound is per statement, never per answer: a caller always receives the complete list.
 */
fun interface IncomingRows {
    fun rows(
        workspaceId: UUID,
        arm: InverseArm,
        key: String,
        version: Int?,
        scope: InverseScope,
    ): List<InverseRow>
}

/**
 * [IncomingRows] over the production registry (`dependencyFamilyRegistry`): the target family's provider answers
 * `findIncoming`, paged to exhaustion by [findAllIncoming] — whose `Unsupported` is an [IllegalStateException] (§C.3, fail
 * closed: a guard never reads an unregistered arm as "nothing pins this", the verb rolls back). Each edge maps back to
 * the row the native query produced, field for field, so the domain mappers that build each port's answer read the same
 * values the domain scans read.
 *
 * The context: [Authority.System] (the adapters' answers are unlensed; each method above narrows as it always has, §C.2),
 * the transaction the caller is in or none ([MetadataSnapshot.READ_COMMITTED], [ReadIsolation.NONE] — an adapter never
 * opens one, `841-r1`), and an unbounded [Deadline]: the per-statement bound is the page LIMIT. The registry is read
 * through [registry] at the first question, never at construction: a guard port is injected into the very services
 * some family descriptors are built over, so an eager registry parameter would be a bean cycle.
 */
class RegistryIncomingRows(
    private val registry: () -> FamilyRegistry,
    private val purpose: Purpose = Purpose.LIFECYCLE_GUARD,
) : IncomingRows {
    /** Over a registry already built (a test, or a caller outside the context). */
    constructor(registry: FamilyRegistry) : this({ registry })

    override fun rows(
        workspaceId: UUID,
        arm: InverseArm,
        key: String,
        version: Int?,
        scope: InverseScope,
    ): List<InverseRow> {
        val provider = checkNotNull(registry()[arm.target]) { "no ${arm.target} family is registered" }.provider
        val owner = if (arm.target == FamilyKey.DATASOURCE) OwnerScope.Deployment else OwnerScope.Workspace(workspaceId)
        val target =
            if (version == null) {
                IncomingTarget.Entity(arm.target, owner, key)
            } else {
                IncomingTarget.ExactVersion(arm.target, owner, key, version)
            }
        val context =
            ResolveContext(workspaceId, MetadataSnapshot.READ_COMMITTED, purpose, Authority.System, Deadline.none(), ReadIsolation.NONE)
        return provider.findAllIncoming(IncomingQuery(target, arm.dependents, scope.incoming, null), context).map(::rowOf)
    }

    private fun rowOf(edge: IncomingEdge): InverseRow =
        InverseRow(
            dependentId = edge.dependent.stableEntityId,
            dependentName = edge.dependentName,
            dependentVersion = edge.dependentVersion,
            status = edge.dependentLifecycle?.let { PipelineVersionStatus.valueOf(it.name) },
            sourcePath = edge.sourcePath,
            storedVersion = edge.storedVersion,
            elementId = edge.elementId,
            followsCurrent = edge.followsCurrent,
        )

    private val InverseScope.incoming: IncomingScope
        get() =
            when (this) {
                InverseScope.WORKING -> IncomingScope.WORKING
                InverseScope.LIVE -> IncomingScope.LIVE
                InverseScope.ANY -> IncomingScope.ANY
            }
}
