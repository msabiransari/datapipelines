package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.RequestLimits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** [RequestBodies] (#291): a `String` body read under the stated constraints, a read failure the family's 400. */
class RequestBodiesTest {
    private val requestMapper = RequestLimits.requestMapper(PipelineJson.objectMapper())

    private fun deep(levels: Int) = "[".repeat(levels) + "]".repeat(levels)

    @Test
    fun `a body nested past the bound is the family's malformed-body 400, naming the depth`() {
        val refusal =
            shouldThrow<ApiException> {
                RequestBodies.readTree(requestMapper, deep(RequestLimits.MAX_NESTING_DEPTH + 1), ApiErrors::malformedPipelineBody)
            }
        refusal.code shouldBe "pipeline.validation.schema_version_unsupported"
        refusal.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON
        refusal.message shouldContain "nesting depth"
    }

    @Test
    fun `a body that is not json is the same 400`() {
        shouldThrow<ApiException> { RequestBodies.readTree(requestMapper, "{\"a\": ", ApiErrors::malformedTemplateBody) }
            .code shouldBe "template.validation.schema_version_unsupported"
    }

    @Test
    fun `a body at the bound is read`() {
        RequestBodies.readTree(requestMapper, deep(RequestLimits.MAX_NESTING_DEPTH), ApiErrors::malformedPipelineBody).isArray shouldBe true
    }
}
