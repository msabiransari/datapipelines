package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #1038 (published-mutation-apis §3.3: "at publish, verify that the pinned pipeline admits this input") — a GET
 * route cannot pin a pipeline whose `request_body` is required; an optional body and every mutation method pass.
 * The check sits in [EndpointPinValidator], so publish, draft save, release, current switch and promotion import —
 * every caller of `check` — refuse alike.
 */
class EndpointPinRequestBodyTest {
    private val repository = mockk<PipelineRepository>()
    private val pipelines = mockk<PipelineService>()
    private val validator =
        EndpointPinValidator(
            pipelineRepository = repository,
            pipelines = pipelines,
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, 5),
            authoring = AuthoringGuard(true),
        )
    private val parsed = EndpointPath.parse("/shop/v1/orders").getOrThrow()
    private val body = EndpointVersionBody(pipelineName = NAME, pipelineVersion = 1, timeoutSeconds = 30, description = "")

    private fun stub(declaration: RequestBodyDeclaration?) {
        val record = PipelineRecord(ID, NAME, "Orders", "", ACTOR, 1, Instant.EPOCH, Instant.EPOCH)
        every { repository.findByName(WORKSPACE, NAME) } returns record
        every { repository.findVersionDetail(WORKSPACE, ID, 1) } returns
            PipelineVersionDetail(ID, 1, PipelineVersionStatus.RELEASED, "hash", Instant.EPOCH, ACTOR)
        val pipeline =
            Pipeline(
                schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
                name = NAME,
                displayName = "Orders",
                description = "",
                settings = PipelineSettings(),
                parameters = emptyMap(),
                nodes =
                    listOf(
                        Node(
                            id = "shape",
                            description = "",
                            type = NodeType.SCRIPT,
                            source = "",
                            template = TemplateRef("shop/shape.jsonata", 1),
                            output = NodeOutput.Caller,
                            dependsOn = emptyList(),
                        ),
                    ),
                requestBody = declaration,
            )
        every { pipelines.findExecutable(WORKSPACE, any(), record, 1) } returns
            PipelineService.ExecutablePipeline(record, 1, "{}", pipeline)
    }

    private fun check(method: EndpointMethod) = validator.check(WORKSPACE, parsed, body, EndpointPinValidator.Admission.ELIGIBLE, method)

    @Test
    fun `a GET pin of a body-requiring pipeline is refused naming the pipeline and method, never a value`() {
        stub(RequestBodyDeclaration(required = true))
        val refused = shouldThrow<DatapipelinesException> { check(EndpointMethod.GET) }
        refused.code shouldBe PipelineErrorCodes.Endpoint.REQUEST_BODY_INCOMPATIBLE
        refused.details shouldBe mapOf("pipeline" to NAME, "method" to "GET")
    }

    @Test
    fun `an optional body on GET, and a required body on every mutation method, pass`() {
        stub(RequestBodyDeclaration(required = false))
        shouldNotThrowAny { check(EndpointMethod.GET) }
        stub(RequestBodyDeclaration(required = true))
        listOf(EndpointMethod.POST, EndpointMethod.PUT, EndpointMethod.DELETE).forEach { method ->
            withClue(method) { shouldNotThrowAny { check(method) } }
        }
        stub(null)
        shouldNotThrowAny { check(EndpointMethod.GET) }
    }

    private companion object {
        const val NAME = "shop/orders/submit"
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-00000000aa01")
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000aa02")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-00000000aa03")
    }
}
