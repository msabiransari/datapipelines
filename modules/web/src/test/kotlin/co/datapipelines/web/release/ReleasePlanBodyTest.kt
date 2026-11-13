package co.datapipelines.web.release

import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.pipeline.release.CheckOverride
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The release route's optional body (record §6): no body, a blank body and `{}` are no plan, so a legacy request is
 * unchanged; the three plan fields parse typed; anything else is refused `plan_invalid` with a field locator and never
 * the submitted value; an oversized body is `dependency_limit` naming the wire bound only; bytes that cannot be read — not
 * JSON, or nested past §13.21's bound — are the family's `malformed_json` 400 (#291), never `plan_invalid` or a 500.
 */
class ReleasePlanBodyTest {
    private val bounds = ReleaseBounds()

    private fun parse(
        body: String?,
        limits: ReleaseBounds = bounds,
    ) = ReleasePlanBody.parse(body, ReleaseFamily.PIPELINE, limits, ApiErrors::malformedPipelineBody)

    private fun refused(body: String): DatapipelinesException = shouldThrow<DatapipelinesException> { parse(body) }

    /** A plan object whose `tree_hash` is arrays nested so the whole body is [depth] levels deep. */
    private fun nestedTo(depth: Int): String = "{\"tree_hash\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}"

    @Test
    fun `no body, a blank body and an empty object carry no plan`() {
        parse(null) shouldBe null
        parse("  ") shouldBe null
        parse("{}") shouldBe ReleasePlanRequest()
        parse("{}")?.present shouldBe false
    }

    @Test
    fun `the three plan fields parse typed`() {
        val plan =
            parse(
                """{"tree_hash":"v1:${"a".repeat(64)}","include_dependencies":["n1","n2"],""" +
                    """"check_overrides":[{"node_id":"n2","reason":"known red upstream"}]}""",
            )

        plan shouldBe
            ReleasePlanRequest("v1:${"a".repeat(64)}", listOf("n1", "n2"), listOf(CheckOverride("n2", "known red upstream")))
        plan?.present shouldBe true
    }

    @Test
    fun `a field the plan does not have is refused, never ignored - statuses, families and orders are the server's`() {
        listOf("""{"status":"RELEASED"}""", """{"tree_hash":"x","order":["n1"]}""", """{"family":"template"}""").forEach { body ->
            withClue(body) {
                val error = refused(body)
                error.code shouldBe "pipeline.release.plan_invalid"
                error.details["field"] shouldBe "body"
            }
        }
    }

    @Test
    fun `wrong shapes name the field locator and never echo the submitted value`() {
        val cases =
            mapOf(
                "[1,2]" to "body",
                """{"tree_hash":7}""" to "tree_hash",
                """{"include_dependencies":"n1"}""" to "include_dependencies",
                """{"include_dependencies":["n1",{"evil":"value-xyz"}]}""" to "include_dependencies[1]",
                """{"check_overrides":[{"node_id":"n1"}]}""" to "check_overrides[0].reason",
                """{"check_overrides":[{"node_id":"n1","reason":"r","extra":"value-xyz"}]}""" to "check_overrides[0]",
                """{"check_overrides":["value-xyz"]}""" to "check_overrides[0]",
            )
        cases.forEach { (body, field) ->
            withClue(body) {
                val error = refused(body)
                error.code shouldBe "pipeline.release.plan_invalid"
                error.details["field"] shouldBe field
                error.message.orEmpty() shouldNotContain "value-xyz"
                error.details.toString() shouldNotContain "value-xyz"
            }
        }
    }

    @Test
    fun `unreadable bytes are the family's malformed_json 400 - not JSON, truncated, or nested past the bound`() {
        val pastTheBound = nestedTo(RequestLimits.MAX_NESTING_DEPTH + 1)
        listOf("not json at all", "{\"tree_hash\": ", pastTheBound).forEach { body ->
            withClue(body.take(40)) {
                val error = refused(body)
                error.code shouldBe "pipeline.validation.schema_version_unsupported"
                error.details[ApiErrors.REASON] shouldBe ApiErrors.MALFORMED_JSON
            }
        }
        refused(pastTheBound).message.orEmpty() shouldContain "nesting depth"
    }

    @Test
    fun `a body nested exactly at the bound is read, and its wrong shape is plan_invalid`() {
        val atTheBound = nestedTo(RequestLimits.MAX_NESTING_DEPTH)

        val error = refused(atTheBound)

        error.code shouldBe "pipeline.release.plan_invalid"
        error.details["field"] shouldBe "tree_hash"
    }

    @Test
    fun `a body over the wire bound is dependency_limit naming the bound only, refused before it is parsed`() {
        val tight = ReleaseBounds(maxWireBytes = 32)

        val error = shouldThrow<DatapipelinesException> { parse("""{"include_dependencies":["n1","n2","n3","n4"]}""", tight) }

        error.code shouldBe "pipeline.release.dependency_limit"
        error.details shouldBe mapOf("limit" to "wire_bytes", "maximum" to 32)
        // Exactly at the bound is admitted.
        parse("""{"include_dependencies":["n1"]}""".padEnd(32), tight)?.includeDependencies shouldBe listOf("n1")
    }
}
