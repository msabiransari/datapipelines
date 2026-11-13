package co.datapipelines.lake.append

import co.datapipelines.pipeline.LakeArtifactReference
import co.datapipelines.pipeline.LakeExecutionMetadata
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.TemplateRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat

/** A sibling's future draft contents cannot be certified by an earlier target's immutable recipe. */
class RecipeProvenanceIntegrationTest {
    @Test
    fun `an early recipe freezes unresolved sibling nodes and a later complete observation becomes eligible`() {
        val h = AppendPublicationHarness()
        h.reset()
        h.withRequest { request, _ ->
            val execution = execution(request)
            val lineage = execution.lineage
            lineage.templateUsed(REFERENCE)
            lineage.nodeResolved("first")
            h.withTable { table ->
                val assembler = AppendRecipeAssembler(h.h.definitions, h.deliveries, h.leases)
                val early = assembler.build(request.copy(execution = execution), table)
                val earlyJson = RecipeFixtures.document.build(early)
                earlyJson.path("unresolved_nodes").map { it.asText() } shouldBe listOf("sibling")
                earlyJson.path("exact_replay_possible").asBoolean() shouldBe false
                lineage.nodeResolved("sibling")
                RecipeFixtures.document.build(early) shouldBe earlyJson
                val complete = assembler.build(request.copy(execution = execution), table)
                RecipeFixtures.document
                    .build(complete)
                    .path("exact_replay_possible")
                    .asBoolean() shouldBe true
            }
        }
    }

    @Test
    fun `a sibling resolving at the snapshot boundary cannot produce an eligible recipe missing its hash`() {
        val h = AppendPublicationHarness()
        h.reset()
        h.withRequest { request, _ ->
            val execution = execution(request)
            execution.lineage.templateUsed(REFERENCE)
            execution.lineage.nodeResolved("first")
            val nodes = execution.pipeline.nodes
            val boundary =
                object : AbstractList<Node>() {
                    override val size = nodes.size

                    override fun get(index: Int): Node {
                        // Deterministic interleaving: the sibling finishes when completeness is first inspected.
                        execution.lineage.templateUsed(SIBLING)
                        execution.lineage.nodeResolved("sibling")
                        return nodes[index]
                    }
                }
            val observed = execution.copy(pipeline = execution.pipeline.copy(nodes = boundary))
            h.withTable { table ->
                val publication =
                    AppendRecipeAssembler(h.h.definitions, h.deliveries, h.leases)
                        .build(request.copy(execution = observed), table)
                publication.pipeline.unresolvedNodes shouldBe emptyList()
                publication.pipeline.templates
                    .map { it.bodyHash }
                    .toSet() shouldBe setOf(REFERENCE.bodyHash, SIBLING.bodyHash)
                RecipeFixtures.document
                    .build(publication)
                    .path("exact_replay_possible")
                    .asBoolean() shouldBe true
            }
        }
    }

    private fun execution(request: AppendPublicationInput): co.datapipelines.pipeline.LakeExecutionInput {
        val nodes =
            listOf("first", "sibling").map {
                Node(it, "", NodeType.DQL, "tempdb", TemplateRef(REFERENCE.name, 1), NodeOutput.Caller, emptyList())
            }
        val pipeline = request.execution.pipeline.copy(parameters = emptyMap(), nodes = nodes)
        val body = RecipeFixtures.mapper.writeValueAsString(pipeline)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        return request.execution.copy(pipeline = pipeline, parameters = emptyMap(), metadata = LakeExecutionMetadata(body, hash, true))
    }

    private companion object {
        val REFERENCE = LakeArtifactReference("test/query.sql", 1, "a".repeat(64))
        val SIBLING = REFERENCE.copy(bodyHash = "b".repeat(64))
    }
}
