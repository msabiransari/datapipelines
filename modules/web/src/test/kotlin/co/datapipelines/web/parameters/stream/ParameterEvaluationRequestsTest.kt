package co.datapipelines.web.parameters.stream

import co.datapipelines.web.api.ApiErrorCatalog
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.parameters.ParameterSetsController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * The observed evaluation's body reader (spec §4.1, rest-api §21.5) — every refusal names `details.path` and a
 * `details.reason`, never a value; the pre-parse bound is the ordinary evaluate's own number.
 */
class ParameterEvaluationRequestsTest {
    private val evaluationId = "1b4e28ba-2fa1-41d2-883f-0016d3cca427"
    private val instanceId = "6f9619ff-8b86-4011-b42d-00cf4fc964ff"

    private fun body(
        version: String? = "4",
        selections: String? = """{"country":"USA"}""",
        evaluation: String? = "\"$evaluationId\"",
        instance: String? = "\"$instanceId\"",
    ): String =
        listOfNotNull(
            version?.let { "\"version\":$it" },
            selections?.let { "\"selections\":$it" },
            evaluation?.let { "\"evaluation_id\":$it" },
            instance?.let { "\"instance_id\":$it" },
        ).joinToString(",", "{", "}")

    private fun refusal(body: String): Pair<String, String> {
        val error = shouldThrow<ApiException> { ParameterEvaluationRequests.read(body) }
        error.code shouldBe "parameter.validation.body_invalid"
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.BAD_REQUEST
        return error.details["path"] as String to error.details["reason"] as String
    }

    @Test
    fun `a well-formed body reads whole - the version, the selections and both ids`() {
        val request = ParameterEvaluationRequests.read(body())

        request.version shouldBe 4
        request.selections.keys shouldBe setOf("country")
        request.selections.getValue("country")?.asText() shouldBe "USA"
        request.evaluationId shouldBe UUID.fromString(evaluationId)
        request.instanceId shouldBe UUID.fromString(instanceId)
    }

    @Test
    fun `absent or null selections are the first render's empty map`() {
        ParameterEvaluationRequests.read(body(selections = null)).selections shouldBe emptyMap()
        ParameterEvaluationRequests.read(body(selections = "null")).selections shouldBe emptyMap()
    }

    @Test
    fun `version is REQUIRED - missing, null and non-integer are refused naming the field`() {
        refusal(body(version = null)) shouldBe ("version" to "missing")
        refusal(body(version = "null")) shouldBe ("version" to "missing")
        refusal(body(version = "\"4\"")) shouldBe ("version" to "wrong_type")
        refusal(body(version = "4.5")) shouldBe ("version" to "wrong_type")
    }

    @Test
    fun `a present non-object selections is refused - never read as nothing chosen`() {
        refusal(body(selections = """["country=USA"]""")) shouldBe ("selections" to "wrong_type")
    }

    @Test
    fun `the evaluation id must be a canonical v4 UUID - missing, non-text, malformed, v1 and non-canonical are refused`() {
        refusal(body(evaluation = null)) shouldBe ("evaluation_id" to "missing")
        refusal(body(evaluation = "7")) shouldBe ("evaluation_id" to "wrong_type")
        refusal(body(evaluation = "\"not-a-uuid\"")) shouldBe ("evaluation_id" to "malformed")
        refusal(body(evaluation = "\"c232ab00-9414-11ec-b3c8-9f6bdeced846\"")) shouldBe ("evaluation_id" to "malformed")
        refusal(body(evaluation = "\"${evaluationId.uppercase()}\"")) shouldBe ("evaluation_id" to "malformed")
    }

    @Test
    fun `the instance id follows the same rule`() {
        refusal(body(instance = null)) shouldBe ("instance_id" to "missing")
        refusal(body(instance = "true")) shouldBe ("instance_id" to "wrong_type")
        refusal(body(instance = "\"${instanceId.uppercase()}\"")) shouldBe ("instance_id" to "malformed")
    }

    @Test
    fun `a root that is not an object and a body that is not JSON are the family's 400`() {
        refusal("[1,2]") shouldBe ("$" to "wrong_type")
        shouldThrow<ApiException> { ParameterEvaluationRequests.read("{not json") }.code shouldBe "parameter.validation.body_invalid"
    }

    @Test
    fun `a refusal never echoes the offending value`() {
        val error = shouldThrow<ApiException> { ParameterEvaluationRequests.read(body(evaluation = "\"secret-looking-value\"")) }

        (error.message ?: "").contains("secret-looking-value") shouldBe false
        error.details.values.any { it.toString().contains("secret-looking-value") } shouldBe false
    }

    @Test
    fun `a body over the bound is the platform 413 before its JSON is parsed`() {
        val oversized = "{" + " ".repeat(ParameterEvaluationRequests.MAX_EVALUATE_REQUEST_BYTES) + "not json"

        val error = shouldThrow<ApiException> { ParameterEvaluationRequests.read(oversized) }

        error.code shouldBe "request.body_too_large"
        ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.PAYLOAD_TOO_LARGE
        error.details["limit_bytes"] shouldBe ParameterEvaluationRequests.MAX_EVALUATE_REQUEST_BYTES
    }

    @Test
    fun `the pre-parse bound is the ordinary evaluate's own number - one bound, never a third`() {
        val ordinary =
            ParameterSetsController::class.java
                .getDeclaredField("MAX_EVALUATE_REQUEST_BYTES")
                .apply { isAccessible = true }
                .getInt(null)

        withClue("ParameterSetsController.MAX_EVALUATE_REQUEST_BYTES moved; the observed route must move with it") {
            ParameterEvaluationRequests.MAX_EVALUATE_REQUEST_BYTES shouldBe ordinary
        }
    }
}
