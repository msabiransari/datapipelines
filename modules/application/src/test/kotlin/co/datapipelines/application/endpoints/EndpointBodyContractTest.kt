package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.pipeline.TemplateRef
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #1040 — the request-body contract every authoring surface shows beside an exact pin (published-mutation-apis §3
 * items 2–3): the mode comes from the pinned version's `request_body` declaration, the readers are exactly the SCRIPT
 * nodes that bind `@request_body`, in pipeline order, and [EndpointBodyContract.wire] is the one spelling REST, MCP and
 * the promotion batch answer.
 */
class EndpointBodyContractTest {
    private val repository = mockk<PipelineRepository>()
    private val pipelines = mockk<PipelineService>()
    private val validator =
        EndpointPinValidator(
            pipelineRepository = repository,
            pipelines = pipelines,
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, 5),
            authoring = AuthoringGuard(true),
        )

    @Test
    fun `no declaration is mode none with no readers - optional and required follow the declaration`() {
        EndpointBodyContract.of(pipeline(null)) shouldBe EndpointBodyContract(EndpointBodyContract.Mode.NONE, emptyList())
        EndpointBodyContract.of(pipeline(RequestBodyDeclaration(required = false))).mode shouldBe EndpointBodyContract.Mode.OPTIONAL
        EndpointBodyContract.of(pipeline(RequestBodyDeclaration(required = true))).mode shouldBe EndpointBodyContract.Mode.REQUIRED
    }

    @Test
    fun `the readers are the SCRIPT nodes that bind the body, in pipeline order, each with its exact template version`() {
        val contract =
            EndpointBodyContract.of(
                pipeline(
                    RequestBodyDeclaration(required = true),
                    script("shape", "shop/shape.jsonata", 2, reads = true),
                    script("quiet", "shop/quiet.jsonata", 1, reads = false),
                    script("audit", "shop/audit.jsonata", 7, reads = true),
                ),
            )
        contract.readers shouldBe
            listOf(
                EndpointBodyContract.Reader("shape", "shop/shape.jsonata@2"),
                EndpointBodyContract.Reader("audit", "shop/audit.jsonata@7"),
            )
    }

    @Test
    fun `the wire spelling is mode plus node_id and template, and an unknown wire mode is never guessed at`() {
        EndpointBodyContract(
            EndpointBodyContract.Mode.REQUIRED,
            listOf(EndpointBodyContract.Reader("shape", "shop/shape.jsonata@2")),
        ).wire() shouldBe
            mapOf("mode" to "required", "readers" to listOf(mapOf("node_id" to "shape", "template" to "shop/shape.jsonata@2")))
        EndpointBodyContract.Mode.entries.forEach { EndpointBodyContract.Mode.ofWire(it.wire) shouldBe it }
        EndpointBodyContract.Mode.ofWire("streaming").shouldBeNull()
        EndpointBodyContract.Mode.ofWire(null).shouldBeNull()
    }

    @Test
    fun `the validator reads the contract of the exact pinned version, and null when the pipeline or version is gone`() {
        val record = PipelineRecord(ID, NAME, "Orders", "", ACTOR, 3, Instant.EPOCH, Instant.EPOCH)
        every { repository.findByName(WORKSPACE, NAME) } returns record
        every { pipelines.findExecutable(WORKSPACE, any(), record, 2) } returns
            PipelineService.ExecutablePipeline(
                record,
                2,
                "{}",
                pipeline(RequestBodyDeclaration(required = false), script("shape", "shop/shape.jsonata", 2, reads = true)),
            )
        every { pipelines.findExecutable(WORKSPACE, any(), record, 9) } returns null
        every { repository.findByName(WORKSPACE, "shop/orders/absent") } returns null

        validator.bodyContract(WORKSPACE, NAME, 2) shouldBe
            EndpointBodyContract(
                EndpointBodyContract.Mode.OPTIONAL,
                listOf(EndpointBodyContract.Reader("shape", "shop/shape.jsonata@2")),
            )
        validator.bodyContract(WORKSPACE, NAME, 9).shouldBeNull()
        validator.bodyContract(WORKSPACE, "shop/orders/absent", 1).shouldBeNull()
    }

    private fun script(
        id: String,
        template: String,
        version: Int,
        reads: Boolean,
    ) = Node(
        id = id,
        description = "",
        type = NodeType.SCRIPT,
        source = "",
        template = TemplateRef(template, version),
        output = NodeOutput.Caller,
        dependsOn = emptyList(),
        inputs = if (reads) mapOf("order" to TextNode(RequestBodyDeclaration.REFERENCE)) else mapOf("order" to TextNode("@other")),
    )

    private fun pipeline(
        declaration: RequestBodyDeclaration?,
        vararg nodes: Node,
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = NAME,
        displayName = "Orders",
        description = "",
        settings = PipelineSettings(),
        parameters = emptyMap(),
        nodes = nodes.toList().ifEmpty { listOf(script("shape", "shop/shape.jsonata", 1, reads = false)) },
        requestBody = declaration,
    )

    private companion object {
        const val NAME = "shop/orders/submit"
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ab01")
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ab02")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000ab03")
    }
}
