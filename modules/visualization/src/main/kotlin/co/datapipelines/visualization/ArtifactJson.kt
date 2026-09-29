package co.datapipelines.visualization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.LogicalType.Textual
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.databind.type.LogicalType.Boolean as JacksonBoolean

/**
 * The visualization and dashboard JSON binding — strict at every level (the `ParameterSetJson` shape: "a
 * misspelt key never silently disappears").
 *
 * Two layers refuse an unknown key, on purpose: the readers' pre-scan REPORTS every one as
 * `*.validation.body_invalid` with its path and never lets a document it refused reach binding; this mapper
 * THROWS on one — the backstop for every path that binds without a reader (a stored body read back, a transfer
 * payload after its lifecycle keys are stripped). Scalar coercion is off (`"5"` is not the integer 5).
 *
 * A JSON `null` at a schema level is ABSENT (the parameter-set rule — no key here means something different
 * when null): [KotlinFeature.NullIsSameAsDefault] applies the model's default, and the readers' pre-scan has
 * already refused a null where a value is required. Raw values — a renderer configuration, a fixture row, a
 * literal — keep their nulls: they are the author's data, not the schema.
 */
object ArtifactJson {
    /** The strict mapper — the ONLY one this module binds or writes visualization and dashboard JSON with. */
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().enable(KotlinFeature.NullIsSameAsDefault).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            // ALLOW_COERCION_OF_SCALARS does not reach a scalar-to-STRING bind: Jackson's StringDeserializer
            // takes a number or a boolean as text unless the coercion config refuses it — `"display_name": 5`
            // bound as "5" until these lines (L1a). The transfer path binds WITHOUT a pre-scan, so this is
            // where a wrong JSON type must stop.
            .withCoercionConfig(Textual) { config ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean).forEach {
                    config.setCoercion(it, CoercionAction.Fail)
                }
            }.withCoercionConfig(JacksonBoolean) { it.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail) }
            .build()

    /**
     * The stored form of [body] — `*_versions.body_json`. The database hashes the JSONB projection of exactly
     * this text (versioning §4.1's pipeline rule), so key order and whitespace never move a hash.
     */
    fun writeBody(body: Any): String = mapper.writeValueAsString(body)

    /** Binds a STORED visualization body (already validated when it was written). Throws on anything else. */
    fun readVisualization(json: String): VisualizationBody = mapper.readValue(json, VisualizationBody::class.java)

    /** Binds a STORED dashboard body. Throws on anything else. */
    fun readDashboard(json: String): DashboardBody = mapper.readValue(json, DashboardBody::class.java)
}
