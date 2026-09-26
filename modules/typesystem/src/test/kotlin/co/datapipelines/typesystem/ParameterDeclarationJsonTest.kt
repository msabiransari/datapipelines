package co.datapipelines.typesystem

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The declaration model's WIRE shape — the generated artifact, not the source (MISTAKES: a
 * reflection-fed contract fails silently). `min_length`/`max_length` are snake_case on the wire
 * and camelCase in Kotlin, the exact shape the Jackson naming trap mis-spells; `cardinality`
 * travels as its catalogued wire value.
 */
class ParameterDeclarationJsonTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `constraints bind from and serialise to their snake_case keys, absent keys omitted`() {
        val constraints: ParameterConstraints =
            mapper.readValue("""{"min": 0, "max": "9", "min_length": 1, "max_length": 3, "pattern": "[a-z]+"}""")

        constraints.min?.intValue() shouldBe 0
        constraints.max?.asText() shouldBe "9"
        constraints.minLength shouldBe 1
        constraints.maxLength shouldBe 3
        constraints.pattern shouldBe "[a-z]+"
        mapper
            .readTree(mapper.writeValueAsString(constraints))
            .fieldNames()
            .asSequence()
            .toList() shouldBe
            listOf("min", "max", "min_length", "max_length", "pattern")
        mapper.writeValueAsString(ParameterConstraints(minLength = 2)) shouldBe """{"min_length":2}"""
    }

    @Test
    fun `cardinality travels as its wire value and refuses anything else`() {
        mapper.writeValueAsString(ParameterCardinality.MULTI) shouldBe "\"MULTI\""
        mapper.readValue<ParameterCardinality>("\"SINGLE\"") shouldBe ParameterCardinality.SINGLE
        ParameterCardinality.WIRE_VALUES shouldBe listOf("SINGLE", "MULTI")
        ParameterCardinality.fromWireOrNull("TRIPLE") shouldBe null
        shouldThrow<IllegalArgumentException> { ParameterCardinality.fromWire("single") }
    }

    @Test
    fun `the limits refuse a non-positive budget and a negative default length`() {
        shouldThrow<IllegalArgumentException> { ParameterValueLimits(maxRegexReads = 0) }
        shouldThrow<IllegalArgumentException> { ParameterValueLimits(defaultMaxLength = -1) }
        ParameterValueLimits().maxRegexReads shouldBe 100_000L
        ParameterValueLimits().defaultMaxLength shouldBe null
    }

    @Test
    fun `a reflected reason is clipped at 64 characters and has its control characters replaced`() {
        "x".repeat(100).truncateForError() shouldBe "x".repeat(MAX_REFLECTED_VALUE_LENGTH) + "…"
        "a\nb\u001bc".truncateForError() shouldBe "a�b�c"
        (null as String?).truncateForError() shouldBe "null"
    }
}
