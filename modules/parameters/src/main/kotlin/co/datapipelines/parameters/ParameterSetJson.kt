package co.datapipelines.parameters

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.ParameterConstraints
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.LogicalType.Textual
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.databind.type.LogicalType.Boolean as JacksonBoolean

/**
 * The parameter-set JSON binding — strict at every level (record §3; lane B's security brief: "a
 * misspelt key never silently disappears").
 *
 * Two layers refuse an unknown key, on purpose:
 *  1. [ParameterSetReader]'s pre-scan, which REPORTS every one as `parameter.validation.body_invalid`
 *     with its path (exhaustive — the author sees them all), and never lets a document it refused
 *     reach binding.
 *  2. This mapper, which THROWS on one — the backstop for every path that binds without the reader
 *     (a stored body read back, an import payload a future caller forgets to pre-scan).
 *
 * `FAIL_ON_UNKNOWN_PROPERTIES` alone is not enough: [TemplateRef] (pipeline-contract) and
 * [ParameterConstraints] (typesystem) carry a class-level `@JsonIgnoreProperties(ignoreUnknown =
 * true)` for their own modules' reasons, and a class annotation beats a mapper feature. The
 * [StrictDeclaredKeys] mix-in overrides it for THIS mapper only, so neither module's own binding changes.
 * Scalar coercion is off (`"5"` is not the integer 5, `1` is not `true`) — the pre-scan reports a
 * wrong JSON type, and this mapper refuses one rather than guessing. That feature does not reach a
 * scalar-to-STRING bind, though (Jackson's `StringDeserializer` takes a number or a boolean as text
 * until the `Textual` coercion config refuses it — #319), so the `Textual`/`Boolean` coercion
 * configs and `ACCEPT_FLOAT_AS_INT` off are the mapper's own refusal of every wrong scalar type.
 */
object ParameterSetJson {
    /** The strict mapper — the ONLY one this module binds or writes parameter-set JSON with. */
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            // ALLOW_COERCION_OF_SCALARS does not reach a scalar-to-STRING bind: Jackson's StringDeserializer
            // takes a number or a boolean as text unless the coercion config refuses it — `"display_name": 5`
            // bound as "5" until these lines (#319). The transfer path binds WITHOUT a pre-scan, so this is
            // where a wrong JSON type must stop.
            .withCoercionConfig(Textual) { config ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean).forEach {
                    config.setCoercion(it, CoercionAction.Fail)
                }
            }.withCoercionConfig(JacksonBoolean) { it.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail) }
            .addMixIn(TemplateRef::class.java, StrictDeclaredKeys::class.java)
            .addMixIn(ParameterConstraints::class.java, StrictDeclaredKeys::class.java)
            .build()

    /**
     * The stored form of [body] — `parameter_set_versions.body_json`. The database hashes the JSONB
     * projection of exactly this text (record §8.1, versioning §4.1's pipeline rule), so key order
     * and whitespace never move a hash; what the reader and the validator canonicalised (a JSON null
     * default read as absent, an expression reprinted by its printer) is what is hashed.
     */
    fun writeBody(body: ParameterSetBody): String = mapper.writeValueAsString(body)

    /** Binds a STORED body (already validated when it was written). Throws on anything else. */
    fun readBody(json: String): ParameterSetBody = mapper.readValue(json, ParameterSetBody::class.java)

    /**
     * Overrides a target class's lenient `ignoreUnknown = true` for this mapper only, and admits only
     * its DECLARED keys: an un-annotated getter is not a property. [TemplateRef.key] (`"{id}@{version}"`,
     * a lookup helper) would otherwise be WRITTEN into every stored pin and then refused on the way
     * back in — and `@JsonIgnore` would be worse, since an ignored name is silently accepted on read.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
    private abstract class StrictDeclaredKeys
}
