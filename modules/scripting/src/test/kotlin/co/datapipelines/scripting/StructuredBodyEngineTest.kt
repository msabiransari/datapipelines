package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A decoded structured body reaches both transformer languages intact (#1038; requirement §3.3,
 * §9): root identity (an array stays an array, a singleton array included, `{}` and `[]` stay
 * empty containers), member and element order, nesting, nulls, Unicode and the exact numbers the
 * decoder produced. The body crosses as `inputs.<name>` of the production input object.
 */
class StructuredBodyEngineTest {
    private val engines = listOf(ScriptingTestSupport.engine, ScriptingTestSupport.javaScript)

    private fun body(json: String): StructuredBody =
        (StructuredBodyDecoder().decode(json.toByteArray()) as StructuredBodyDecoding.Decoded).body

    private fun inputObject(body: StructuredBody): Map<String, Any?> =
        mapOf("inputs" to mapOf("order" to body.value), "meta" to mapOf("node_id" to "t"))

    private fun run(
        engine: ScriptEngine,
        programs: Map<ScriptLanguage, String>,
        body: StructuredBody,
    ): Any? = engine.evaluate(engine.compile(programs.getValue(engine.type)), inputObject(body), DEFAULT_LIMITS)

    private val identity =
        mapOf(
            ScriptLanguage.JSONATA to "inputs.order",
            ScriptLanguage.JAVASCRIPT to "return input.inputs.order;",
        )

    private val rootKind =
        mapOf(
            ScriptLanguage.JSONATA to "\$type(inputs.order)",
            ScriptLanguage.JAVASCRIPT to "const v = input.inputs.order; return Array.isArray(v) ? 'array' : typeof v;",
        )

    private val shapes =
        listOf(
            """{"customer":{"name":"Zoë 名前 😀","vip":null},"lines":[{"sku":"A","qty":2},{"sku":"B","note":null,"tags":[]}],"meta":{}}""",
            """[{"a":1},{"b":"x","c":[1,{"d":null}]}]""",
            """[{"only":true}]""",
            "{}",
            "[]",
        )

    @Test
    fun `every shape round-trips through both engines with its root kind`() {
        engines.forEach { engine ->
            shapes.forEach { json ->
                val decoded = body(json)
                withClue("${engine.type} $json") {
                    CanonicalJson.equal(run(engine, identity, decoded), decoded.value) shouldBe true
                    run(engine, rootKind, decoded) shouldBe if (json.startsWith("[")) "array" else "object"
                }
            }
        }
    }

    @Test
    fun `a transformer in each language extracts rows from an order object and from a heterogeneous root array`() {
        val extract =
            mapOf(
                ScriptLanguage.JSONATA to
                    "(\$o := inputs.order; \$items := \$type(\$o) = \"array\" ? \$o[\$exists(sku)] : \$o.lines; " +
                    "[\$items.{\"sku\": sku, \"qty\": qty}])",
                ScriptLanguage.JAVASCRIPT to
                    "const o = input.inputs.order; const items = Array.isArray(o) ? o.filter(x => 'sku' in x) : (o.lines || []); " +
                    "return items.map(x => ({ sku: x.sku, qty: x.qty }));",
            )
        val cases =
            mapOf(
                """{"customer":{"name":"Zoë"},"lines":[{"sku":"A","qty":2},{"sku":"B","qty":1,"unknown":true}]}""" to
                    listOf(mapOf("sku" to "A", "qty" to 2), mapOf("sku" to "B", "qty" to 1)),
                """[{"sku":"A","qty":3},{"kind":"comment"},{"sku":"C","qty":4}]""" to
                    listOf(mapOf("sku" to "A", "qty" to 3), mapOf("sku" to "C", "qty" to 4)),
                """[{"sku":"S","qty":1}]""" to listOf(mapOf("sku" to "S", "qty" to 1)),
                "[]" to emptyList(),
                "{}" to emptyList(),
            )
        engines.forEach { engine ->
            cases.forEach { (json, rows) ->
                withClue("${engine.type} $json") { CanonicalJson.equal(run(engine, extract, body(json)), rows) shouldBe true }
            }
        }
    }

    @Test
    fun `member order and element order arrive as sent`() {
        val decoded = body("""{"zeta":1,"alpha":2,"mid":3,"rows":[{"n":3},{"n":1},{"n":2}]}""")
        val keys =
            mapOf(
                ScriptLanguage.JSONATA to "\$keys(inputs.order)",
                ScriptLanguage.JAVASCRIPT to "return Object.keys(input.inputs.order);",
            )
        val order =
            mapOf(
                ScriptLanguage.JSONATA to "[inputs.order.rows.n]",
                ScriptLanguage.JAVASCRIPT to "return input.inputs.order.rows.map(r => r.n);",
            )
        engines.forEach { engine ->
            withClue(engine.type) {
                run(engine, keys, decoded) shouldBe listOf("zeta", "alpha", "mid", "rows")
                CanonicalJson.equal(run(engine, order, decoded), listOf(3, 1, 2)) shouldBe true
            }
        }
    }

    @Test
    fun `a string identifier passes through unchanged and JavaScript Number conversion shows the precision limit`() {
        val decoded = body("""{"id":"9007199254740993","price":12.50}""")
        val passThrough =
            mapOf(
                ScriptLanguage.JSONATA to "inputs.order.id",
                ScriptLanguage.JAVASCRIPT to "return input.inputs.order.id;",
            )
        engines.forEach { engine ->
            withClue(engine.type) { run(engine, passThrough, decoded) shouldBe "9007199254740993" }
        }
        val asNumber = mapOf(ScriptLanguage.JAVASCRIPT to "return String(Number(input.inputs.order.id));")
        run(ScriptingTestSupport.javaScript, asNumber, decoded) shouldBe "9007199254740992"
        // The existing number rule (#8): an integer NUMBER past 2^53 reaches JavaScript as its decimal string.
        val unsafe = body("""{"n":9007199254740993}""")
        val kind = mapOf(ScriptLanguage.JAVASCRIPT to "const n = input.inputs.order.n; return typeof n + ':' + n;")
        run(ScriptingTestSupport.javaScript, kind, unsafe) shouldBe "string:9007199254740993"
    }

    @Test
    fun `an exact decimal and a safe integer keep their values in both engines`() {
        val decoded = body("""{"price":12.50,"qty":9007199254740991,"neg":-0.125}""")
        engines.forEach { engine ->
            withClue(engine.type) {
                CanonicalJson.equal(
                    run(engine, identity, decoded),
                    mapOf(
                        "price" to java.math.BigDecimal("12.5"),
                        "qty" to 9_007_199_254_740_991L,
                        "neg" to java.math.BigDecimal("-0.125"),
                    ),
                ) shouldBe true
            }
        }
    }

    @Test
    fun `an absent body is undefined to the transformer, distinct from the empty containers`() {
        val absent = mapOf("inputs" to emptyMap<String, Any?>(), "meta" to mapOf("node_id" to "t"))
        val probe =
            mapOf(
                ScriptLanguage.JSONATA to "\$exists(inputs.order) ? 'present' : 'absent'",
                ScriptLanguage.JAVASCRIPT to "return input.inputs.order === undefined ? 'absent' : 'present';",
            )
        engines.forEach { engine ->
            withClue(engine.type) {
                engine.evaluate(engine.compile(probe.getValue(engine.type)), absent, DEFAULT_LIMITS) shouldBe "absent"
                run(engine, probe, body("{}")) shouldBe "present"
                run(engine, probe, body("[]")) shouldBe "present"
            }
        }
    }
}
