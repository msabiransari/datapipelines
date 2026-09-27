package co.datapipelines.parameters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The failure collector is bounded (the 194b security pass, F1's sink): a body that produces
 * thousands of refusals — one per unknown key, one per over-deep child — answers a capped list
 * with one terminal marker, never a response hundreds of times the request.
 */
class ParameterSetFailuresTest {
    @Test
    fun `past the cap the list stops growing and ends with one body_invalid too_many_failures marker`() {
        val failures = ParameterSetFailures()
        repeat(2_000) { failures.add(ParameterErrorCodes.BODY_INVALID, "parameters[$it]", "unknown key", mapOf("reason" to "unknown_key")) }
        val result = failures.toResult().failures
        result.size shouldBe 1_001 // MAX_FAILURES (1,000) + the marker
        result.last().code shouldBe ParameterErrorCodes.BODY_INVALID
        result.last().details["reason"] shouldBe "too_many_failures"
        result.dropLast(1).all { it.details["reason"] == "unknown_key" } shouldBe true
    }
}
