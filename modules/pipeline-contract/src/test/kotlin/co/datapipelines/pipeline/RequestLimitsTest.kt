package co.datapipelines.pipeline

import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The request-constraints factory (#291): the ONE place a request-body mapper gets §13.21's
 * stated constraints. Read here off the artifacts it builds — the factory's own
 * `streamReadConstraints()` and a document one level past the bound — never off the source.
 */
class RequestLimitsTest {
    private fun deep(levels: Int) = "[".repeat(levels) + "]".repeat(levels)

    @Test
    fun `the factory carries the stated numbers`() {
        val constraints = RequestLimits.jsonFactory().streamReadConstraints()
        constraints.maxNestingDepth shouldBe RequestLimits.MAX_NESTING_DEPTH
        constraints.maxStringLength shouldBe RequestLimits.MAX_STRING_LENGTH
        constraints.maxNumberLength shouldBe RequestLimits.MAX_NUMBER_LENGTH
    }

    @Test
    fun `a request copy refuses one level past the bound and reads a document at it`() {
        val request = RequestLimits.requestMapper(JsonMapper.builder().build())
        val refusal = shouldThrow<StreamConstraintsException> { request.readTree(deep(RequestLimits.MAX_NESTING_DEPTH + 1)) }
        refusal.message shouldContain "nesting depth"
        request.readTree(deep(RequestLimits.MAX_NESTING_DEPTH)).isArray shouldBe true
    }

    @Test
    fun `the request copy keeps the domain mapper's configuration`() {
        // ExecutorJson reads floats as BigDecimal; a copy that dropped that would change every
        // execute parameter's number type under the caller.
        val domain = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
        RequestLimits.requestMapper(domain).readTree("1.25").isBigDecimal shouldBe true
    }

    @Test
    fun `the domain mapper itself is left alone - stored rows and values keep the library's own bounds`() {
        // A request limit is a statement about REQUEST bytes. The domain mappers also read stored
        // rows and transform values, where a JSON column 150 levels deep is legitimate data.
        val domain = JsonMapper.builder().build()
        RequestLimits.requestMapper(domain)
        domain.readTree(deep(RequestLimits.MAX_NESTING_DEPTH + 50)).isArray shouldBe true
    }
}
