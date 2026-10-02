package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * #333 — the template reader refuses a JSON number or boolean where the contract declares a STRING, and
 * never reflects the value, a long unknown key or Jackson's own text unclipped.
 *
 * `ALLOW_COERCION_OF_SCALARS` off never reaches a scalar-to-STRING bind (Jackson's `StringDeserializer` takes
 * a number or a boolean as text unless the `Textual` coercion config refuses it), so before this lane
 * `"body": 12` was a legal FreeMarker body "12" and `"display_name": 5` bound as "5". The refusal is the
 * family's existing bind-failure answer — `template.contract_invalid`, `details.rule` `wrong_type`, the path in
 * `details.path` (the spelling the editor's pane mapping reads) — and a 9-digit sentinel is asserted ABSENT from
 * the message and from every `details` value. `TemplateDraft`'s `ignoreUnknown = true` is deliberate (lifecycle
 * keys dropped on re-import), so an unknown TOP-LEVEL key still binds; the interior blocks stay strict.
 */
class TemplateDeserializerStrictnessTest {
    private val deserializer = TemplateDeserializer()

    @Test
    fun `a number where a string is declared is refused with its path, never bound as text`() {
        STRING_FIELDS.forEach { field ->
            withClue("a number at $field") {
                val rejected = rejectionOf(payload(field, SENTINEL.toString()))

                rejected.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
                rejected.details.keys shouldContainExactlyInAnyOrder setOf("rule", "field", "path")
                rejected.details["rule"] shouldBe "wrong_type"
                rejected.details["field"] shouldBe field
                (rejected.details["path"] as String) shouldContain "[\"$field\"]"
                rejected.message shouldNotContain SENTINEL.toString()
                rejected.details.values.forEach { (it as String) shouldNotContain SENTINEL.toString() }
            }
        }
    }

    @Test
    fun `a boolean where a string is declared is refused with its path, never bound as text`() {
        STRING_FIELDS.forEach { field ->
            withClue("a boolean at $field") {
                val rejected = rejectionOf(payload(field, "true"))

                rejected.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
                rejected.details["rule"] shouldBe "wrong_type"
                rejected.details["field"] shouldBe field
            }
        }
    }

    @Test
    fun `a float where a string is declared is refused too`() {
        val rejected = rejectionOf(payload("display_name", "1.5"))

        rejected.details["rule"] shouldBe "wrong_type"
        rejected.details["field"] shouldBe "display_name"
    }

    @Test
    fun `a string or a float where an integer is declared, a number where a boolean is declared, is refused`() {
        listOf(
            """{"schema_version":"1","display_name":"X","description":"Y","body":"b","dialect":"POSTGRES"}""" to "schema_version",
            """{"schema_version":1.5,"display_name":"X","description":"Y","body":"b","dialect":"POSTGRES"}""" to "schema_version",
            """{"is_library":1,"display_name":"X","description":"Y","body":"b","dialect":"POSTGRES"}""" to "is_library",
        ).forEach { (json, field) ->
            withClue("a wrong scalar at $field: $json") {
                val rejected = rejectionOf(json)

                rejected.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
                rejected.details["rule"] shouldBe "wrong_type"
                rejected.details["field"] shouldBe field
            }
        }
    }

    @Test
    fun `the same refusal reaches the typed-throw entry`() {
        val thrown =
            io.kotest.assertions.throwables.shouldThrow<TemplateValidationException> {
                deserializer.readOrThrow(payload("body", SENTINEL.toString()))
            }

        thrown.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
    }

    @Test
    fun `a well-typed payload and the lifecycle keys a re-import carries still bind`() {
        val outcome =
            deserializer.read(
                """{"id":"test/t.sql","display_name":"X","description":"Y","body":"SELECT 1","dialect":"POSTGRES",
                    "version":7,"created_at":"2026-08-01T10:00:00Z","created_by":"00000000-0000-0000-0000-000000000009",
                    "typo_key_is_dropped_by_design":1}""",
            )

        outcome.shouldBeInstanceOf<TemplateDeserializationOutcome.Parsed>()
        outcome.draft.body shouldBe "SELECT 1"
    }

    @Test
    fun `an unknown key inside a block is refused with the key and Jackson's text clipped, not echoed whole`() {
        val garbage = "k".repeat(GARBAGE_LENGTH)
        val rejected =
            rejectionOf(
                """{"type":"jsonata","display_name":"X","description":"Y","body":"rows",
                    "contract":{"mode":"row","inputs":{},"output":{"kind":"table","columns":[]},"$garbage":1}}""",
            )

        rejected.code shouldBe PipelineErrorCodes.Template.CONTRACT_INVALID
        rejected.details["rule"] shouldBe "unknown_field"
        rejected.message shouldNotContain garbage
        rejected.message shouldHaveMaxLength MAX_BIND_MESSAGE_LENGTH
        (rejected.details["field"] as String) shouldHaveMaxLength MAX_REFLECTED + 1
        (rejected.details["path"] as String) shouldHaveMaxLength MAX_REFLECTED + 1
    }

    private fun rejectionOf(json: String): TemplateValidationFailure {
        val outcome = deserializer.read(json)

        outcome.shouldBeInstanceOf<TemplateDeserializationOutcome.Rejected>()
        return outcome.result.failures.single()
    }

    /** A valid sql template payload with [field] replaced by the raw JSON [value]. */
    private fun payload(
        field: String,
        value: String,
    ): String {
        val fields =
            linkedMapOf(
                "id" to "\"test/t.sql\"",
                "engine" to "\"freemarker\"",
                "display_name" to "\"X\"",
                "description" to "\"Y\"",
                "body" to "\"SELECT 1\"",
                "dialect" to "\"POSTGRES\"",
            )
        fields[field] = value
        return fields.entries.joinToString(",", "{", "}") { (key, raw) -> "\"$key\":$raw" }
    }

    private companion object {
        /** A 9-digit value no refusal message or detail may contain. */
        const val SENTINEL = 987654321

        val STRING_FIELDS = listOf("id", "engine", "display_name", "description", "body")

        /** Longer than any reflected-text clip. */
        const val GARBAGE_LENGTH = 2_048

        /** `MAX_REFLECTED_PATH_LENGTH` — the clip the house applies to a reflected Jackson path or message. */
        const val MAX_REFLECTED = 160

        /** The fixed prose around the clipped Jackson text, plus the ellipsis, with headroom. */
        const val MAX_BIND_MESSAGE_LENGTH = MAX_REFLECTED + 160
    }
}
