package co.datapipelines.templates

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The `ObjectMapper` template JSON is bound with.
 *
 * A dedicated mapper, not a shared/global one, for the reason [PipelineJson][co.datapipelines.pipeline]
 * gives: every field of the wire shapes carries an explicit `@JsonProperty` on all three
 * use-site targets, and no naming strategy is set here, so nothing configured upstream can
 * rewrite `is_library` into `isLibrary`. This mapper serializes the `imports` array into the
 * `imports_json` JSONB column and reads it back.
 *
 * ## Timestamps
 *
 * `created_at` is an [Instant] and templates.md §3.1 spells it as an ISO 8601 UTC instant
 * (`2026-08-01T10:00:00Z`). Jackson refuses `java.time` types without a module, so this mapper
 * registers a two-method one rather than taking a dependency on `jackson-datatype-jsr310`
 * for a single field — and the explicit formatter also pins the *spelling*, which the JSR-310
 * module leaves to `WRITE_DATES_AS_TIMESTAMPS` (numeric epoch by default, ISO only once
 * disabled). One field, one format, no configuration to get wrong.
 */
object TemplateJson {
    fun objectMapper(): ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(instantModule())
            .build()

    private val MAPPER = objectMapper()

    /** Serializes an `imports` array to the compact JSON stored in `template_versions.imports_json`. */
    fun writeImports(imports: List<TemplateImport>): String = MAPPER.writeValueAsString(imports)

    /** Reads an `imports` array back from the stored JSONB text. */
    fun readImports(json: String): List<TemplateImport> = MAPPER.readValue(json)

    private fun instantModule(): SimpleModule =
        SimpleModule("templates-instant")
            .addSerializer(Instant::class.java, IsoInstantSerializer)
            .addDeserializer(Instant::class.java, IsoInstantDeserializer)

    private object IsoInstantSerializer : JsonSerializer<Instant>() {
        override fun serialize(
            value: Instant,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) = gen.writeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }

    private object IsoInstantDeserializer : JsonDeserializer<Instant>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): Instant = Instant.parse(parser.text)
    }
}
