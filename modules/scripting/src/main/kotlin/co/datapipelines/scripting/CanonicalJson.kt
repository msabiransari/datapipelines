package co.datapipelines.scripting

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * The canonical JSON form (transform-nodes design §5.5): object keys sorted
 * (recursively), integers as integers, doubles in shortest round-trip form,
 * `BigDecimal` in plain notation (no exponent), strings escaped per JSON, arrays in
 * the order the function returned them. ONE implementation, here — the test runner's
 * exact-equality comparisons (D-T12) and the type gate's byte accounting both read it,
 * so two spellings of the same value cannot pass one check and fail another.
 *
 * [write] serialises with a single module-private `ObjectMapper` configured for exactly
 * the guarantees above; [equal] canonicalises both sides and compares the strings,
 * so key order and decimal spelling never decide an equality the form says is
 * structural.
 *
 * Not part of the [ScriptEngine] seam — this is the module's second public surface,
 * reused by 7b/7c for test-case diffs and value accounting.
 */
object CanonicalJson {
    @Suppress("DEPRECATION") // WRITE_BIGDECIMAL_AS_PLAIN: Jackson 2.21 deprecated the
    // SerializationFeature without shipping the JsonWriteFeature replacement; plain
    // decimal notation is the canonical form's rule (§5.5), so the flag is load-bearing.
    private val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true)
            .build()

    /**
     * The canonical string for one JSON-shaped value ([Map] with String keys,
     * [List], [String], [Number], [Boolean], null).
     *
     * A value the mapper cannot serialise is a CALLER bug here (the engine never
     * returns one), so Jackson's own [com.fasterxml.jackson.databind.JsonMappingException]
     * is the honest signal — this module does not wrap it.
     */
    fun write(value: Any?): String = mapper.writeValueAsString(value)

    /**
     * True when both values are the same under the canonical form. Key order and
     * decimal spelling never decide an equality (record §10.3: canonicalisation
     * ignores both): `{"a":1}` equals `{"a":1.0}`, and `12.5` equals `12.50` — a
     * number compared by VALUE, everything else compared structurally.
     */
    fun equal(
        a: Any?,
        b: Any?,
    ): Boolean = treesEqual(mapper.readTree(write(a)), mapper.readTree(write(b)))

    private fun treesEqual(
        x: com.fasterxml.jackson.databind.JsonNode,
        y: com.fasterxml.jackson.databind.JsonNode,
    ): Boolean =
        when {
            x.isNumber && y.isNumber -> {
                x.decimalValue().stripTrailingZeros() == y.decimalValue().stripTrailingZeros()
            }

            x.isNumber != y.isNumber -> {
                false
            }

            else -> {
                x == y
            }
        }
}
