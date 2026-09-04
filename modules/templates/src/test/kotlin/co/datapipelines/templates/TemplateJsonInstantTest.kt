package co.datapipelines.templates

import com.fasterxml.jackson.databind.JsonMappingException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [TemplateJson]'s ISO-instant binding — the "one field, one format, no configuration to
 * get wrong" rule for every Instant the template surface stores (`created_at`, version
 * bodies). Strict both ways: serialize is always `ISO_INSTANT` (Z-suffixed), and the reader
 * accepts ONLY that form — epoch millis, offset forms and dates never bind, so a drifted
 * writer upstream fails loudly here instead of corrupting a stored version.
 */
class TemplateJsonInstantTest {
    private val mapper = TemplateJson.objectMapper()

    private data class Holder(
        val at: Instant,
    )

    @Test
    fun `an instant serializes to the ISO-8601 Z form`() {
        mapper.writeValueAsString(Holder(Instant.parse("2026-09-03T12:00:00Z"))) shouldBe
            """{"at":"2026-09-03T12:00:00Z"}"""
    }

    @Test
    fun `round-trip preserves the instant exactly - nanos included`() {
        val instant = Instant.parse("2026-09-03T12:00:00.123456789Z")

        mapper.readValue(mapper.writeValueAsString(Holder(instant)), Holder::class.java).at shouldBe instant
    }

    @Test
    fun `the reader accepts only the Z form - epoch millis never bind`() {
        shouldThrow<JsonMappingException> {
            mapper.readValue("""{"at":1760000000000}""", Holder::class.java)
        }
    }

    @Test
    fun `an offset datetime binds to its UTC equivalent - ISO_INSTANT's leniency, recorded`() {
        // DateTimeFormatter.ISO_INSTANT accepts offsets when parsing; the pinned JDK keeps
        // that. Recorded as the binding's actual semantic rather than asserted away.
        mapper.readValue("""{"at":"2026-09-03T08:00:00-04:00"}""", Holder::class.java).at shouldBe
            Instant.parse("2026-09-03T12:00:00Z")
    }

    @Test
    fun `the reader accepts only the Z form - a bare date never binds`() {
        shouldThrow<JsonMappingException> {
            mapper.readValue("""{"at":"2026-09-03"}""", Holder::class.java)
        }
    }
}
