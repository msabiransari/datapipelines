package co.datapipelines.web.requestlimits

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.LogicalType.Textual
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.math.BigInteger
import com.fasterxml.jackson.databind.type.LogicalType.Boolean as JacksonBoolean

/**
 * How a route that takes a typed request DTO reads it (#333, pipeline-contract §13.21).
 *
 * Spring binds an `@RequestBody` DTO in its message converter, with Boot's mapper: a JSON number or
 * boolean bound into a declared `String` becomes its text (`"name": 12` is the key named "12";
 * `ALLOW_COERCION_OF_SCALARS` off never reaches a scalar-to-String bind), a string into an integer
 * is parsed, and an unknown key is dropped. The bind happens before any handler line, so a route
 * that wants it refused owns the read: it takes the body as a [JsonNode] (the converter parses
 * it under the stated constraints) and binds it here, through [MAPPER].
 *
 * [MAPPER] is `RequestLimits.requestMapper` over the lines `ParameterSetJson` and `ArtifactJson`
 * carry - `ALLOW_COERCION_OF_SCALARS` and `ACCEPT_FLOAT_AS_INT` off, `Textual` failing the
 * Integer/Float/Boolean shapes, `Boolean` failing Integer - with `FAIL_ON_UNKNOWN_PROPERTIES` on.
 * A DTO that is a cross-version wire (`PromotionWire.Batch`) says so itself with
 * `@JsonIgnoreProperties(ignoreUnknown = true)`, which beats the mapper feature: only the coercion
 * half applies to it.
 *
 * A failure is [ApiException] `pipeline.execution.invalid_parameter_type` (400, the field-shape
 * refusal the key, endpoint and template routes already answer with), `details` `reason`
 * (`wrong_type`, `unknown_key` or `missing`), `path`, and for a wrong type `expected`. The message
 * is built from the path and the expected shape - Jackson's own text QUOTES the value - and the
 * path is clipped and sanitised, because an unknown key is the caller's text.
 */
object StrictRequestBodies {
    const val REASON_WRONG_TYPE = "wrong_type"
    const val REASON_UNKNOWN_KEY = "unknown_key"
    const val REASON_MISSING = "missing"

    /** The longest path echoed back: the house's reflected-path budget. */
    const val MAX_REFLECTED_PATH_LENGTH = 160

    private const val CONTROL_REPLACEMENT = '�'

    /** Built once: a mapper is thread-safe after configuration, and building one per request is the cost the bean avoids. */
    val MAPPER: ObjectMapper =
        RequestLimits.requestMapper(
            JsonMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .addModule(JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(Textual) { config ->
                    listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean).forEach {
                        config.setCoercion(it, CoercionAction.Fail)
                    }
                }.withCoercionConfig(JacksonBoolean) { it.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail) }
                .build(),
        )

    /** [body] bound to [type], or the 400 [ApiException] naming the path that does not bind. */
    fun <T : Any> bind(
        body: JsonNode,
        type: Class<T>,
    ): T =
        try {
            MAPPER.treeToValue(body, type)
        } catch (
            @Suppress("SwallowedException") err: MismatchedInputException,
        ) {
            throw refusal(err)
        }

    /** [bind] for a Kotlin type argument: `StrictRequestBodies.bind<CreateApiKeyRequest>(body)`. */
    inline fun <reified T : Any> bind(body: JsonNode): T = bind(body, T::class.java)

    private fun refusal(err: MismatchedInputException): ApiException {
        val path = pathOf(err)
        val (reason, message) =
            when (err) {
                is UnrecognizedPropertyException -> REASON_UNKNOWN_KEY to "'$path' is not a field of this request."
                is InvalidNullException -> REASON_MISSING to "'$path' is required and was absent or null."
                else -> REASON_WRONG_TYPE to "'${path.ifEmpty { "(request body)" }}' must be ${expectedShape(err.targetType)}."
            }
        val details =
            buildMap<String, Any?> {
                put("reason", reason)
                put("path", path)
                if (reason == REASON_WRONG_TYPE) put("expected", expectedShape(err.targetType))
            }
        return ApiException(PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE, message, details)
    }

    /** `nodes[0].path`-style spelling of Jackson's reference chain; an unknown key's own name is its last segment. */
    private fun pathOf(err: MismatchedInputException): String {
        val spelled =
            err.path.fold("") { acc, ref ->
                when {
                    ref.fieldName != null -> if (acc.isEmpty()) ref.fieldName else "$acc.${ref.fieldName}"
                    ref.index >= 0 -> "$acc[${ref.index}]"
                    else -> acc
                }
            }
        val clipped = if (spelled.length <= MAX_REFLECTED_PATH_LENGTH) spelled else spelled.take(MAX_REFLECTED_PATH_LENGTH) + "…"
        return buildString(clipped.length) {
            for (ch in clipped) append(if (ch.isISOControl()) CONTROL_REPLACEMENT else ch)
        }
    }

    /** What a declared type is called to a caller; never the value that failed. */
    private fun expectedShape(target: Class<*>?): String =
        when {
            target == null -> "a value of the declared type"
            CharSequence::class.java.isAssignableFrom(target) -> "a string"
            target == Boolean::class.javaObjectType || target == Boolean::class.javaPrimitiveType -> "a boolean"
            target in INTEGERS || target == BigInteger::class.java -> "an integer"
            target in DECIMALS || target == BigDecimal::class.java || target == Number::class.java -> "a number"
            target.isArray || Collection::class.java.isAssignableFrom(target) -> "an array"
            java.time.temporal.Temporal::class.java.isAssignableFrom(target) -> "an ISO-8601 timestamp"
            else -> "an object"
        }

    private val INTEGERS: Set<Class<*>> =
        setOf(Int::class, Long::class, Short::class, Byte::class).flatMap { listOf(it.javaPrimitiveType!!, it.javaObjectType) }.toSet()

    private val DECIMALS: Set<Class<*>> =
        setOf(Double::class, Float::class).flatMap { listOf(it.javaPrimitiveType!!, it.javaObjectType) }.toSet()
}
