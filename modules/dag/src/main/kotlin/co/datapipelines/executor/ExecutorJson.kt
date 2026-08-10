package co.datapipelines.executor

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamWriteFeature
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.time.Instant

/**
 * The `dag` module's JSON mapper: result-store payloads, `node_stats_json`, `error_json`,
 * `parameters_json`, and the durable event payloads.
 *
 * Jackson is the project-wide JSON stack (module-structure §5.1), and the Kotlin module is
 * required rather than convenient: `ColumnSchema` and friends are Kotlin data classes with no
 * `@JsonCreator`, so nothing can reconstruct a stored schema without it.
 *
 * ## Two settings that are contract, not taste
 *
 *  - **`WRITE_BIGDECIMAL_AS_PLAIN`** — type-system §3.5 fixes `BIGDECIMAL` as a plain-decimal
 *    string and `DECIMAL` as a JSON number with its declared scale. Exponent notation would
 *    change the bytes a client receives.
 *  - **`USE_BIG_DECIMAL_FOR_FLOATS`** — reading a stored page back must not round-trip
 *    `12345.60` through a `Double` and hand the client `12345.6`. The default float binding
 *    silently rewrites exactly the values the type system went to the most trouble to pin down.
 *
 * `Instant` is (de)serialized by the tiny module below rather than by
 * `jackson-datatype-jsr310`: the artifact is not on this module's declared dependency list
 * (module-structure §5.6) and ISO-8601 `Instant` handling does not justify adding one.
 */
object ExecutorJson {
    /** The shared, thread-safe mapper. */
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            // `StreamWriteFeature`, not the same-named `SerializationFeature` constant: that one is
            // deprecated on the pinned Jackson (2.21.5) and `allWarningsAsErrors` makes using it a
            // build failure, not a warning.
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .addModule(kotlinModule())
            .addModule(
                SimpleModule("dag-instant")
                    .addSerializer(Instant::class.java, InstantSerializer)
                    .addDeserializer(Instant::class.java, InstantDeserializer),
            ).build()

    /** Serializes any value to a JSON string. */
    fun write(value: Any?): String = mapper.writeValueAsString(value)

    private object InstantSerializer : JsonSerializer<Instant>() {
        override fun serialize(
            value: Instant,
            gen: JsonGenerator,
            serializers: SerializerProvider,
        ) = gen.writeString(value.toString())
    }

    private object InstantDeserializer : JsonDeserializer<Instant>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): Instant = Instant.parse(parser.valueAsString)
    }
}
