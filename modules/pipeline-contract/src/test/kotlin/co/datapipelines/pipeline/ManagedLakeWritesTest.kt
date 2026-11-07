package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ManagedLakeWritesTest {
    private val workspace = UUID.randomUUID()
    private val children = mutableMapOf<PipelineNodeRef, Pipeline>()
    private val resolvedWorkspaces = mutableListOf<UUID>()
    private val facts =
        ManagedLakeWrites(
            DatasourceRegistry { name, scope ->
                scope shouldBe workspace
                DatasourceFacts(Dialect.LAKE, readonly = true, managedLake = name == "managed")
            },
            PipelineResolver { scope, name, version ->
                resolvedWorkspaces += scope
                children[PipelineNodeRef(name, version)]?.let { ResolvedPipeline(it, entityDiscarded = false) }
            },
            maxDepth = MAX_DEPTH,
        )

    @Test
    fun `only managed append is a direct write fact`() {
        facts.writes(Fixtures.pipeline(), workspace) shouldBe false
        facts.writes(append("external"), workspace) shouldBe false
        facts.writes(append("managed"), workspace) shouldBe true
    }

    @Test
    fun `a grandchild append is found through exact version pins and the same workspace`() {
        children[PipelineNodeRef("test/child", 2)] = reference("test/grandchild", 3)
        children[PipelineNodeRef("test/grandchild", 3)] = append("managed")
        facts.writes(reference("test/child", 2), workspace) shouldBe true
        resolvedWorkspaces shouldBe listOf(workspace, workspace)
    }

    @Test
    fun `a different child version does not affect the pinned read-only version`() {
        children[PipelineNodeRef("test/child", 1)] = append("managed")
        children[PipelineNodeRef("test/child", 2)] = Fixtures.pipeline()
        facts.writes(reference("test/child", 2), workspace) shouldBe false
    }

    @Test
    fun `a mutable child is re-read for each admission instead of trusting an older parent flag`() {
        val pin = PipelineNodeRef("test/child", 1)
        children[pin] = Fixtures.pipeline()
        val parent = reference(pin.name, pin.version)
        facts.writes(parent, workspace) shouldBe false
        children[pin] = append("managed")
        facts.writes(parent, workspace) shouldBe true
        children[pin] = Fixtures.pipeline()
        facts.writes(parent, workspace) shouldBe false
    }

    @Test
    fun `missing references fail closed instead of asserting read-only`() {
        shouldThrow<DatapipelinesException> { facts.writes(reference("test/missing", 1), workspace) }
            .code shouldBe PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND
    }

    @Test
    fun `a cycle terminates without recursive stack growth`() {
        children[PipelineNodeRef("test/a", 1)] = reference("test/b", 1)
        children[PipelineNodeRef("test/b", 1)] = reference("test/a", 1)
        facts.writes(reference("test/a", 1), workspace) shouldBe false
        resolvedWorkspaces.size shouldBe 2
    }

    @Test
    fun `a deep unresolved graph respects the configured depth bound`() {
        (1..MAX_DEPTH).forEach { depth -> children[PipelineNodeRef("test/child$depth", 1)] = reference("test/child${depth + 1}", 1) }
        shouldThrow<DatapipelinesException> { facts.writes(reference("test/child1", 1), workspace) }
            .code shouldBe PipelineErrorCodes.Validation.COMPOSITION_TOO_DEEP
    }

    private fun append(datasource: String): Pipeline =
        Fixtures.pipeline(
            nodes = listOf(Fixtures.node().copy(output = NodeOutput.Datasource(datasource, "sales.orders", WriteMode.APPEND))),
        )

    private fun reference(
        name: String,
        version: Int,
    ): Pipeline =
        Fixtures.pipeline(
            nodes = listOf(Fixtures.node(type = NodeType.PIPELINE).copy(pipeline = PipelineNodeRef(name, version))),
        )

    private companion object {
        const val MAX_DEPTH = 3
    }
}
