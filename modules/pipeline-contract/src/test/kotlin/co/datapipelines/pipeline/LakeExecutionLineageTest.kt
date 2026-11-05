package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LakeExecutionLineageTest {
    @Test
    fun `two nodes sharing a draft still resolve separately and child node ids cannot resolve the parent`() {
        val root = LakeExecutionLineage()
        val child = LakeExecutionLineage(root)
        val nodes =
            listOf("first", "sibling").map {
                Node(it, "", NodeType.DQL, "tempdb", TemplateRef("test/query.sql", 1), NodeOutput.Caller, emptyList())
            }
        val pipeline = Pipeline(Pipeline.SUPPORTED_SCHEMA_VERSION, "test/lineage", "Lineage", "", PipelineSettings(), emptyMap(), nodes)
        root.templateUsed(LakeArtifactReference("test/query.sql", 1, "a".repeat(64)))
        root.nodeResolved("first")
        child.nodeResolved("sibling")
        root.unresolvedNodes(pipeline) shouldBe listOf("sibling")
        child.unresolvedNodes(pipeline) shouldBe listOf("first")
        root.nodeResolved("sibling")
        root.unresolvedNodes(pipeline) shouldBe emptyList()
    }

    @Test
    fun `actual child and grandchild inputs contribute their hashes to the parent recipe`() {
        val root = LakeExecutionLineage()
        val child = LakeExecutionLineage(root)
        val grandchild = LakeExecutionLineage(child)
        val first = LakeArtifactReference("lib/input", 1, "a".repeat(64))
        val changed = first.copy(bodyHash = "b".repeat(64))
        grandchild.templateUsed(first)
        child.templateUsed(changed)
        root.templateReferences() shouldBe listOf(first, changed)
        grandchild.templateReferences() shouldBe listOf(first)
        val ref = PipelineNodeRef("nested/pipeline", 2)
        grandchild.childPipelineUsed(ref, LakeExecutionMetadata("{}", "c".repeat(64), true), emptyMap())
        root.pipelineReferences() shouldBe listOf(LakeArtifactReference(ref.name, ref.version, "c".repeat(64)))
    }

    @Test
    fun `a later draft cannot remove an observed child parameter redaction opt in`() {
        val lineage = LakeExecutionLineage()
        val ref = PipelineNodeRef("nested/pipeline", 2)
        val sensitive = Parameter(LogicalType.STRING, redactInRecipe = true)
        val plain = Parameter(LogicalType.STRING)
        lineage.childPipelineUsed(ref, LakeExecutionMetadata("{}", "a".repeat(64), true), mapOf("token" to sensitive))
        lineage.childPipelineUsed(ref, LakeExecutionMetadata("{}", "b".repeat(64), true), mapOf("token" to plain))
        lineage.childParameters(ref).getValue("token").redactInRecipe shouldBe true
        plain.redactInRecipe shouldBe false
        lineage.pipelineReferences().map { it.bodyHash } shouldBe listOf("a".repeat(64), "b".repeat(64))
    }
}
