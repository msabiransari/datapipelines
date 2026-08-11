package co.datapipelines.web.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The standing guard for the envelope wire shapes (rest-api.md §4): explicit `@JsonProperty`
 * names, not a naming strategy, decide the keys — this test is what proves it.
 */
class ApiEnvelopeSerializationTest {
    private val mapper: ObjectMapper =
        JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `success envelope serializes schema_version, correlation_id and data`() {
        val json = mapper.readTree(mapper.writeValueAsString(ApiResponse.of(mapOf("x" to 1))))
        json.has("schema_version").shouldBe(true)
        json.has("correlation_id").shouldBe(true)
        json.get("schema_version").asInt() shouldBe ApiResponse.SCHEMA_VERSION
        json.get("data").get("x").asInt() shouldBe 1
        // The camelCase forms must NOT leak beside the wire names.
        json.has("schemaVersion").shouldBe(false)
        json.has("correlationId").shouldBe(false)
    }

    @Test
    fun `error envelope serializes code, user_message, details and doc_url`() {
        val response =
            ApiErrorResponse.of(
                code = "pipeline.validation.cycle_detected",
                message = "technical",
                details = mapOf("cycle_path" to listOf("a", "b", "a")),
            )
        val error = mapper.readTree(mapper.writeValueAsString(response)).get("error")
        error.get("code").asText() shouldBe "pipeline.validation.cycle_detected"
        error.get("user_message").asText().isNotBlank() shouldBe true
        error.get("doc_url").asText() shouldBe "https://docs.datapipelines.co/errors/pipeline-validation-cycle-detected"
        error.get("details").get("cycle_path").size() shouldBe 3
    }

    @Test
    fun `pagination block serializes has_more derived`() {
        val page = Pagination.of(offset = 0, limit = 50, total = 237, pageSize = 50)
        val json = mapper.readTree(mapper.writeValueAsString(PagedData(listOf(1, 2), page)))
        val pagination = json.get("pagination")
        pagination.get("has_more").asBoolean() shouldBe true
        pagination.get("total").asLong() shouldBe 237L
        pagination.has("hasMore").shouldBe(false)
    }
}
