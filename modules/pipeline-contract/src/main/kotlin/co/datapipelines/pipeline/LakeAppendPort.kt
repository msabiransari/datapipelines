package co.datapipelines.pipeline

import com.fasterxml.jackson.databind.JsonNode
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Exact stored execution body and lifecycle, captured with its hash in one entry-point read. */
data class LakeExecutionMetadata(
    val bodyJson: String,
    val bodyHash: String,
    val draft: Boolean,
) {
    companion object {
        /** Production executable snapshots carry all three fields; legacy non-lake fixtures may omit them. */
        fun from(execution: PipelineService.ExecutablePipeline): LakeExecutionMetadata? {
            val hash = execution.bodyHash ?: return null
            val status = execution.status ?: return null
            return LakeExecutionMetadata(execution.bodyJson, hash, status == PipelineVersionStatus.DRAFT)
        }
    }
}

/** One artifact version and the content actually used; a draft's version alone is not its identity. */
data class LakeArtifactReference(
    val name: String,
    val version: Int,
    val bodyHash: String,
)

/** Concurrent node renders record their actual root and imported-template hashes here. */
class LakeExecutionLineage(
    private val parent: LakeExecutionLineage? = null,
) {
    private val templates = ConcurrentHashMap.newKeySet<LakeArtifactReference>()
    private val pipelines = ConcurrentHashMap.newKeySet<LakeArtifactReference>()
    private val childDeclarations = ConcurrentHashMap<Pair<String, Int>, ConcurrentHashMap<String, Parameter>>()

    /** Called at the actual source resolution, without another lookup of a mutable draft. */
    fun templateUsed(reference: LakeArtifactReference) {
        templates += reference
        parent?.templateUsed(reference)
    }

    /** Stable recipe ordering, without discarding two different contents used under one draft version. */
    fun templateReferences(): List<LakeArtifactReference> =
        templates.sortedWith(compareBy(LakeArtifactReference::name, LakeArtifactReference::version, LakeArtifactReference::bodyHash))

    /** Child inputs use the exact body that was loaded, including its recipe-redaction declarations. */
    fun childPipelineUsed(
        reference: PipelineNodeRef,
        metadata: LakeExecutionMetadata,
        parameters: Map<String, Parameter>,
    ) {
        pipelines += LakeArtifactReference(reference.name, reference.version, metadata.bodyHash)
        val declarations = childDeclarations.computeIfAbsent(reference.name to reference.version) { ConcurrentHashMap() }
        parameters.forEach { (name, parameter) ->
            declarations.merge(name, parameter) { previous, current ->
                current.copy(redactInRecipe = previous.redactInRecipe || current.redactInRecipe)
            }
        }
        parent?.childPipelineUsed(reference, metadata, parameters)
    }

    /** Copies of actual declarations; any opt-in observed under a mutable draft is preserved. */
    fun childParameters(reference: PipelineNodeRef): Map<String, Parameter> =
        childDeclarations[reference.name to reference.version]?.toMap().orEmpty()

    /** Pinned child bodies are recipe inputs just as root bodies and templates are. */
    fun pipelineReferences(): List<LakeArtifactReference> =
        pipelines.sortedWith(compareBy(LakeArtifactReference::name, LakeArtifactReference::version, LakeArtifactReference::bodyHash))
}

/** Inputs fixed before admission; resolved parameter values use the canonical parameter wire encoding. */
data class LakeExecutionInput(
    val executionId: UUID,
    val pipelineId: UUID,
    val pipelineVersion: Int,
    val workspaceId: UUID,
    val pipeline: Pipeline,
    val metadata: LakeExecutionMetadata?,
    val parameters: Map<String, JsonNode>,
    val grant: LakeAppendGrant?,
    val lineage: LakeExecutionLineage,
)

/** Scratch remains typed in the producer's module; this port introduces no dependency on staging or dag. */
data class LakePreparedExecution<T : AutoCloseable>(
    val reservation: T,
    val session: LakeAppendSession,
)

/**
 * The managed append boundary. Lake implements this contract; dag owns its staging and node adapters.
 * Admission reserves scratch and a slot before listing, and returns ownership before staging is opened.
 */
interface LakeAppendPort {
    val datasourceName: String

    fun <T : AutoCloseable> prepare(
        execution: LakeExecutionInput,
        delivery: DeliveryRef,
        context: Map<String, Any?>,
        reserve: (Long) -> T?,
    ): LakePreparedExecution<T>
}

/** One execution's delivery and its target attempts; close after staging in NonCancellable cleanup. */
interface LakeAppendSession : AutoCloseable {
    val datasourceName: String
    val deadline: Instant

    /** DP's trusted initializer only, before the staging root's external-access/configuration lock. */
    fun initialize(
        connection: Connection,
        statements: LakeStatementExecution,
    )

    /** Re-list after the delivery node's SQL and before releasing its dependants. */
    fun readCompleted()

    /** Native rows have already been materialized once; never re-execute author SQL in this method. */
    fun append(
        target: NodeOutput.Datasource,
        input: LakeAppendInput,
        checkActive: () -> Unit,
    ): Long

    /** A catalogued failure code only, never an exception message or secret-bearing cause. */
    fun failed(code: String?)

    override fun close()
}

/** Registers trusted initialization SQL with the executor's cancellation machinery without a dag dependency. */
fun interface LakeStatementExecution {
    fun execute(
        statement: Statement,
        action: () -> Unit,
    )

    companion object {
        /** Component callers without an execution handle; production supplies its registered statement adapter. */
        val DIRECT = LakeStatementExecution { _, action -> action() }
    }
}
