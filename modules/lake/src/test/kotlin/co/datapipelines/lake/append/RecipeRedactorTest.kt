package co.datapipelines.lake.append

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class RecipeRedactorTest {
    private val mapper = PipelineJson.objectMapper()

    private fun pipeline(
        parameters: Map<String, Parameter>,
        nodes: List<Node> = emptyList(),
    ): Pipeline {
        val model =
            Pipeline(
                schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
                name = "test/recipe",
                displayName = "Recipe",
                description = "Recipe test",
                settings = PipelineSettings(),
                parameters = parameters,
                nodes = nodes,
            )
        return PipelineDeserializer().readOrThrow(PipelineSerializer().write(model))
    }

    private fun body(pipeline: Pipeline): JsonNode = mapper.valueToTree(pipeline)

    @Test
    fun `false default preserves values body and replay capability`() {
        val pipeline = pipeline(mapOf("token" to Parameter(LogicalType.STRING, default = TextNode("ordinary-default"))))
        val original = body(pipeline)
        val result = RecipeRedactor { emptyMap() }.redact(pipeline, original, mapOf("token" to TextNode("ordinary-value")))
        result.parameters.path("token").asText() shouldBe "ordinary-value"
        result.body shouldBe original
        result.exactReplayPossible shouldBe true
        result.redactedPaths shouldBe emptyList()
    }

    @Test
    fun `true removes resolved values defaults and repeated constraint values without changing execution inputs`() {
        val pipeline =
            pipeline(
                mapOf(
                    "token" to
                        Parameter(
                            type = LogicalType.STRING,
                            default = TextNode("default-token-799"),
                            constraints = ParameterConstraints(pattern = "default-token-799"),
                            redactInRecipe = true,
                        ),
                ),
            )
        val original = body(pipeline)
        val originalBytes = mapper.writeValueAsString(original)
        val values = mapOf("token" to TextNode("resolved-token-799"))
        val result = RecipeRedactor { emptyMap() }.redact(pipeline, original, values)
        val bytes = mapper.writeValueAsString(result)
        bytes shouldNotContain "resolved-token-799"
        bytes shouldNotContain "default-token-799"
        result.parameters.path("token").asText() shouldBe "<redacted>"
        result.body
            .path("parameters")
            .path("token")
            .path("default")
            .asText() shouldBe "<redacted>"
        result.exactReplayPossible shouldBe false
        mapper.writeValueAsString(original) shouldBe originalBytes
        values.getValue("token").asText() shouldBe "resolved-token-799"
    }

    @Test
    fun `only declared parameter values enter the recipe`() {
        val pipeline = pipeline(mapOf("value" to Parameter(LogicalType.STRING)))
        val values = mapOf("value" to TextNode("ordinary"), "not_declared" to TextNode("context-secret-799"))
        val result = RecipeRedactor { emptyMap() }.redact(pipeline, body(pipeline), values)
        mapper.writeValueAsString(result) shouldNotContain "context-secret-799"
        result.parameters.size() shouldBe 1
    }

    @Test
    fun `pinned child metadata protects literal overrides and preserves unmarked values`() {
        val node =
            Node(
                id = "child",
                description = "Child",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = null,
                dependsOn = emptyList(),
                pipeline = PipelineNodeRef("test/child", 1),
                parameters = mapOf("token" to TextNode("child-token-799"), "plain" to TextNode("ordinary")),
            )
        val pipeline = pipeline(emptyMap(), listOf(node))
        val redactor =
            RecipeRedactor { reference ->
                reference shouldBe PipelineNodeRef("test/child", 1)
                mapOf("token" to Parameter(LogicalType.STRING, redactInRecipe = true), "plain" to Parameter(LogicalType.STRING))
            }
        val result = redactor.redact(pipeline, body(pipeline), emptyMap())
        mapper.writeValueAsString(result) shouldNotContain "child-token-799"
        result.body
            .path("nodes")
            .path(0)
            .path("parameters")
            .path("plain")
            .asText() shouldBe "ordinary"
        result.exactReplayPossible shouldBe false
        shouldThrow<IllegalArgumentException> {
            RecipeRedactor { emptyMap() }.redact(pipeline, body(pipeline), emptyMap())
        }
    }

    @Test
    fun `missing resolved values or mismatched execution bodies fail closed`() {
        val pipeline = pipeline(mapOf("token" to Parameter(LogicalType.STRING)))
        val redactor = RecipeRedactor { emptyMap() }
        shouldThrow<IllegalArgumentException> { redactor.redact(pipeline, body(pipeline), emptyMap()) }
        shouldThrow<IllegalArgumentException> {
            redactor.redact(pipeline, mapper.createObjectNode(), mapOf("token" to TextNode("value")))
        }
    }
}
