package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Drift guard: the §7.1 JSON Schema **in the spec document** versus what [ColumnSchema]
 * actually enforces.
 *
 * type-system.md §7.1 embeds a real JSON Schema, and it is the artifact third-party
 * clients generate their models from. Every other test in this module asserts against
 * values a developer typed into the test — so spec and code could drift apart and the
 * whole suite would stay green, because both sides of every comparison live in the same
 * repository under the same hand. This test reads the document, parses the schema out of
 * it, and drives its assertions **from the parsed values**: change §7.1's enum, its
 * `required` list, a `minimum`, or either `allOf` conditional, and this fails until the
 * code follows.
 *
 * Deliberately zero-dependency — the fenced block is located by text and parsed with the
 * Jackson already on the classpath. A JSON Schema validator would be a new dependency in
 * a module whose whole point is having none (module-structure §5.1).
 */
class ColumnSchemaSpecDriftTest {
    private val schema: JsonNode = parseColumnSchemaFromSpec()

    @Test
    fun `the required list matches the fields ColumnSchema cannot be built without`() {
        schema["required"].map { it.asText() } shouldContainExactly listOf("name", "type")

        // `name` and `type` are non-nullable constructor parameters, so "required" is
        // structural. What is checkable is that nothing ELSE became mandatory in the
        // spec without becoming mandatory here.
        shouldNotThrowAny { ColumnSchema("c", LogicalType.STRING) }
    }

    @Test
    fun `the type enum matches the 11 canonical wire values exactly`() {
        // §9.1 freezes these names. A value added to the spec's enum without a matching
        // LogicalType constant would silently deserialize as an error at runtime.
        schema["properties"]["type"]["enum"].map { it.asText() } shouldContainExactly
            LogicalType.entries.map { it.wire }
    }

    @Test
    fun `additionalProperties stays open, as §9-2's additive promise requires`() {
        // A closed schema would make every future optional field a breaking change —
        // and would contradict the normative clients-MUST-ignore-unknown-fields rule
        // that ColumnSchema implements with @JsonIgnoreProperties(ignoreUnknown = true).
        schema["additionalProperties"].asBoolean() shouldBe true
    }

    @Test
    fun `the declared minimums are the bounds the constructor enforces`() {
        val minNameLength = schema["properties"]["name"]["minLength"].asInt()
        val minPrecision = schema["properties"]["precision"]["minimum"].asInt()
        val minScale = schema["properties"]["scale"]["minimum"].asInt()

        withClue("name minLength=$minNameLength") {
            shouldThrow<IllegalArgumentException> { ColumnSchema("".padStart(minNameLength - 1), LogicalType.STRING) }
            shouldNotThrowAny { ColumnSchema("x".repeat(minNameLength), LogicalType.STRING) }
        }
        withClue("precision minimum=$minPrecision") {
            shouldThrow<IllegalArgumentException> {
                ColumnSchema("c", LogicalType.DECIMAL, precision = minPrecision - 1, scale = 0)
            }
            shouldNotThrowAny { ColumnSchema("c", LogicalType.DECIMAL, precision = minPrecision, scale = 0) }
        }
        withClue("scale minimum=$minScale") {
            shouldThrow<IllegalArgumentException> {
                ColumnSchema("c", LogicalType.BIGDECIMAL, precision = 20, scale = minScale - 1)
            }
            shouldNotThrowAny { ColumnSchema("c", LogicalType.BIGDECIMAL, precision = 20, scale = minScale) }
        }
    }

    @Test
    fun `both allOf conditionals are enforced by the constructor`() {
        // The spec says: if type = DECIMAL then precision is required; if type =
        // BIGDECIMAL then scale is required. Read the conditionals out of the document
        // rather than restating them, so a third or altered conditional fails here.
        val conditionals =
            schema["allOf"].associate { branch ->
                branch["if"]["properties"]["type"]["const"].asText() to
                    branch["then"]["required"].map { it.asText() }
            }

        conditionals shouldBe mapOf("DECIMAL" to listOf("precision"), "BIGDECIMAL" to listOf("scale"))

        conditionals.forEach { (typeName, required) ->
            val type = LogicalType.fromWire(typeName)
            required.forEach { field ->
                withClue("$typeName without $field must not construct") {
                    shouldThrow<IllegalArgumentException> { omitting(type, field) }
                }
            }
        }
    }

    /** Builds a descriptor of [type] with [omittedField] left out, everything else valid. */
    private fun omitting(
        type: LogicalType,
        omittedField: String,
    ): ColumnSchema =
        ColumnSchema(
            name = "c",
            type = type,
            precision = if (omittedField == "precision") null else 20,
            scale = if (omittedField == "scale") null else 2,
        )

    private companion object {
        private const val SECTION_HEADING = "### 7.1 Column descriptor (JSON Schema)"
        private const val FENCE = "```"

        /**
         * Locates `docs/type-system.md` by walking up from the test's working directory
         * (the module dir under Gradle), so the test does not encode how deep the module
         * sits in the tree.
         */
        fun specFile(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "docs/type-system.md")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            throw IllegalStateException(
                "docs/type-system.md not found walking up from ${File("").absolutePath}. " +
                    "This test reads the spec on purpose — see its KDoc.",
            )
        }

        fun parseColumnSchemaFromSpec(): JsonNode {
            val text = specFile().readText()
            val sectionStart = text.indexOf(SECTION_HEADING)
            check(sectionStart >= 0) { "Section heading '$SECTION_HEADING' not found in type-system.md" }

            val fenceStart = text.indexOf("${FENCE}json", sectionStart)
            check(fenceStart >= 0) { "No fenced json block after '$SECTION_HEADING'" }
            val bodyStart = text.indexOf('\n', fenceStart) + 1
            val bodyEnd = text.indexOf("\n$FENCE", bodyStart)
            check(bodyEnd > bodyStart) { "Unterminated fenced json block after '$SECTION_HEADING'" }

            return ObjectMapper().readTree(text.substring(bodyStart, bodyEnd))
        }
    }
}
