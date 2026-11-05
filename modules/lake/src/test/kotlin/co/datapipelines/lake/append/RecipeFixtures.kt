package co.datapipelines.lake.append

import co.datapipelines.lake.delivery.DeliveryFormat
import co.datapipelines.lake.delivery.DeliveryKey
import co.datapipelines.lake.delivery.DeliveryManifest
import co.datapipelines.lake.delivery.DeliveryObject
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import java.time.Instant
import java.util.UUID

internal object RecipeFixtures {
    val mapper = PipelineJson.objectMapper()
    val document = RecipeDocument(RecipeRedactor { emptyMap() })

    fun publication(redact: Boolean = true): RecipePublication {
        val pipeline =
            Pipeline(
                schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
                name = "test/recipe",
                displayName = "Recipe",
                description = "Recipe test",
                settings = PipelineSettings(),
                parameters =
                    mapOf(
                        "token" to
                            Parameter(
                                LogicalType.STRING,
                                default = TextNode("default-secret-799"),
                                redactInRecipe = redact,
                            ),
                    ),
                nodes = emptyList(),
            )
        val file = DeliveryObject("landing/feed/batch/rows.csv", 10, "etag799")
        return RecipePublication(
            delivery =
                DeliveryManifest(
                    DeliveryKey("feed", "batch"),
                    listOf(file, DeliveryObject("landing/feed/batch/_done", 0, "done")),
                    listOf(file),
                    DeliveryFormat.Csv,
                    "a".repeat(64),
                    10,
                ),
            pipeline =
                RecipePipeline(
                    id = UUID.randomUUID(),
                    version = 3,
                    draft = true,
                    bodyHash = "b".repeat(64),
                    pipeline = pipeline,
                    executedBody = mapper.valueToTree<JsonNode>(pipeline),
                    resolvedParameters = mapOf("token" to TextNode("resolved-secret-799")),
                    templates = listOf(RecipeReference("test/query", 2, "c".repeat(64))),
                    parameterSets = listOf(RecipeReference("test/inputs", 4, "d".repeat(64))),
                ),
            appliedDefinitionVersion = 2,
            appliedDefinitionBody = mapper.readTree("{\"fields\":[{\"id\":1,\"name\":\"id\",\"type\":\"long\"}]}"),
            operationId = UUID.randomUUID(),
            worker = "worker799",
            recordedAt = Instant.parse("2026-11-05T00:00:00Z"),
            restoreEpoch = UUID.randomUUID(),
        )
    }
}
