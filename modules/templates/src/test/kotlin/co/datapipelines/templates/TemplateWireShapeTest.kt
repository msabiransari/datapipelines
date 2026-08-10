package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The template wire shape is **snake_case**, and it is frozen (templates.md §3.1, §11.1).
 *
 * The §3.1 JSON is quoted here verbatim rather than paraphrased: a paraphrase can drift into
 * whatever the code happens to accept, which is precisely the failure this guards. Both
 * directions are asserted, because a REST layer will do both — bind an inbound payload
 * ([TemplateDraft]) and write an outbound one ([Template]).
 *
 * The keys most at risk are exactly the ones the house rules call out: `display_name` and
 * `schema_version` hit Jackson's `^[a-z][A-Z]` Java-Beans trap, and `is_library` additionally
 * hits Kotlin's `is`-prefix getter rule (`isLibrary` → bean property `library`). Explicit
 * `@JsonProperty` on all three use-site targets is the only thing that pins them.
 */
class TemplateWireShapeTest {
    private val mapper = TemplateJson.objectMapper()

    @Test
    fun `the spec's section 3-1 payload binds to a draft`() {
        val draft = TemplateDeserializer().readOrThrow(SPEC_JSON)

        draft.schemaVersion shouldBe 1
        draft.id shouldBe "fetch_orders.sql"
        draft.engine shouldBe "freemarker"
        draft.dialect shouldBe Dialect.POSTGRES
        draft.displayName shouldBe "Fetch Orders in Date Range"
        draft.isLibrary shouldBe false
        draft.imports shouldContainExactly listOf(TemplateImport("lib_date_filters.sql", 1, "dates"))
    }

    private fun serializedKeys(): Set<String> =
        mapper
            .readTree(mapper.writeValueAsString(TEMPLATE))
            .fieldNames()
            .asSequence()
            .toSet()

    @Test
    fun `a template serializes with the section 3-1 key spellings`() {
        val keys = serializedKeys()

        listOf(
            "schema_version",
            "id",
            "version",
            "engine",
            "dialect",
            "display_name",
            "description",
            "imports",
            "body",
            "created_at",
            "created_by",
            "is_library",
        ).forEach { key -> withClue("missing wire key: $key") { keys.contains(key) shouldBe true } }
    }

    @Test
    fun `no camelCase spelling leaks into the wire shape`() {
        val keys = serializedKeys()

        listOf("schemaVersion", "displayName", "createdAt", "createdBy", "isLibrary", "library").forEach { key ->
            withClue("camelCase key leaked into the wire shape: $key") { keys.contains(key) shouldBe false }
        }
    }

    @Test
    fun `there is no params_schema anywhere in the wire shape (D3)`() {
        val serialized = mapper.writeValueAsString(TEMPLATE)
        serialized.contains("params_schema") shouldBe false
        serialized.contains("paramsSchema") shouldBe false
    }

    @Test
    fun `an out-of-catalog dialect is rejected as dialect_invalid, not as a Jackson failure`() {
        // §7's `dialect_invalid` is a verdict on a wire value: once bound there is no "DB2" left
        // to validate, so the pre-scan is the only place it can be raised as a 400 the author
        // can act on rather than an enum-coercion 500.
        val outcome = TemplateDeserializer().read(SPEC_JSON.replace("\"POSTGRES\"", "\"DB2\""))

        outcome
            .shouldBeInstanceOf<TemplateDeserializationOutcome.Rejected>()
            .result.codes shouldContainExactly listOf(PipelineErrorCodes.Template.DIALECT_INVALID)
    }

    @Test
    fun `a missing or non-textual dialect is dialect_invalid too, never a bind-time crash`() {
        listOf(
            SPEC_JSON.replace("\"dialect\": \"POSTGRES\",", ""),
            SPEC_JSON.replace("\"POSTGRES\"", "{\"nested\": \"POSTGRES\"}"),
            SPEC_JSON.replace("\"POSTGRES\"", "null"),
        ).forEach { json ->
            withClue("must be rejected, not thrown: $json") {
                TemplateDeserializer()
                    .read(json)
                    .shouldBeInstanceOf<TemplateDeserializationOutcome.Rejected>()
                    .result.codes shouldContainExactly listOf(PipelineErrorCodes.Template.DIALECT_INVALID)
            }
        }
    }

    @Test
    fun `the reflected dialect in the failure is bounded and sanitized`() {
        val hostile = "\"" + "Z".repeat(5_000) + "\""
        val outcome = TemplateDeserializer().read(SPEC_JSON.replace("\"POSTGRES\"", hostile))

        val failure =
            outcome
                .shouldBeInstanceOf<TemplateDeserializationOutcome.Rejected>()
                .result.failures
                .single()
        (failure.details["dialect"] as String).length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
    }

    @Test
    fun `optional fields fall back to their documented defaults`() {
        val minimal =
            """
            {
              "id": "minimal.sql",
              "dialect": "H2",
              "display_name": "Minimal",
              "description": "No engine, no imports, no is_library.",
              "body": "SELECT 1"
            }
            """.trimIndent()

        val draft = TemplateDeserializer().readOrThrow(minimal)

        draft.schemaVersion shouldBe Template.SUPPORTED_SCHEMA_VERSION
        draft.engine shouldBe Template.FREEMARKER_ENGINE
        draft.isLibrary shouldBe false
        draft.imports shouldContainExactly emptyList()
    }

    @Test
    fun `server-assigned fields in an inbound payload cannot be over-posted`() {
        // version / created_at / created_by are not fields on TemplateDraft at all, so a payload
        // carrying them binds without them — absent from the shape, not filtered after binding.
        val draft = TemplateDeserializer().readOrThrow(OVER_POSTED_JSON)
        draft.displayName shouldBe "Fetch Orders in Date Range"
    }

    private companion object {
        /** templates.md §3.1, verbatim. */
        val SPEC_JSON =
            """
            {
              "schema_version": 1,
              "id": "fetch_orders.sql",
              "version": 2,
              "engine": "freemarker",
              "dialect": "POSTGRES",
              "display_name": "Fetch Orders in Date Range",
              "description": "Pulls orders between start_date and end_date (DATE), with an include_cancelled (BOOLEAN) switch.",
              "imports": [
                {"id": "lib_date_filters.sql", "version": 1, "alias": "dates"}
              ],
              "body": "SELECT order_id FROM orders",
              "created_at": "2026-08-01T10:00:00Z",
              "created_by": "00000000-0000-0000-0000-000000000009",
              "is_library": false
            }
            """.trimIndent()

        val OVER_POSTED_JSON = SPEC_JSON

        val TEMPLATE =
            Template(
                id = "fetch_orders.sql",
                version = 2,
                dialect = Dialect.POSTGRES,
                displayName = "Fetch Orders in Date Range",
                description = "Pulls orders in a range.",
                imports = listOf(TemplateImport("lib_date_filters.sql", 1, "dates")),
                body = "SELECT 1",
                isLibrary = false,
                createdAt = Instant.parse("2026-08-01T10:00:00Z"),
                createdBy = UUID.fromString("00000000-0000-0000-0000-000000000009"),
            )
    }
}
