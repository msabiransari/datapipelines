package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RecipeRedactionMetadataTest {
    private val mapper = PipelineJson.objectMapper()

    @Test
    fun `omitted metadata defaults false and preserves legacy bytes`() {
        val legacy = """{"type":"STRING","required":false}"""
        val parameter = mapper.readValue(legacy, Parameter::class.java)
        parameter.redactInRecipe shouldBe false
        mapper.writeValueAsString(parameter) shouldBe legacy
    }

    @Test
    fun `explicit false normalizes to the same legacy body`() {
        val explicit = """{"type":"STRING","required":false,"redact_in_recipe":false}"""
        val parameter = mapper.readValue(explicit, Parameter::class.java)
        parameter.redactInRecipe shouldBe false
        mapper.writeValueAsString(parameter) shouldBe """{"type":"STRING","required":false}"""
    }

    @Test
    fun `true round trips without changing the logical type`() {
        val explicit = """{"type":"STRING","required":false,"redact_in_recipe":true}"""
        val parameter = mapper.readValue(explicit, Parameter::class.java)
        parameter.redactInRecipe shouldBe true
        parameter.type shouldBe LogicalType.STRING
        mapper.writeValueAsString(parameter) shouldBe explicit
    }

    @Test
    fun `pipeline serialization retains the opt in`() {
        val pipeline = Fixtures.pipeline(parameters = mapOf("token" to Parameter(LogicalType.STRING, redactInRecipe = true)))
        val rebound = PipelineDeserializer().readOrThrow(PipelineSerializer().write(pipeline))
        rebound.parameters.getValue("token").redactInRecipe shouldBe true
    }
}
