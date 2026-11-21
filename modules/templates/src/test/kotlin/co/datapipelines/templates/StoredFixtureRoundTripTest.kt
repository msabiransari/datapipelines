package co.datapipelines.templates

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * A body fixture keeps its occurrence information, member order and exact numbers through the stored
 * `tests_json` (#1038, review C2). The oracle is the fixture's METADATA — [FixtureInputs.occurrences],
 * [FixtureInputs.isRepeated], [FixtureInputs.exactText] — never map equality, which ignores all three.
 *
 * [jsonb] stands in for Postgres `jsonb`: it keeps the last copy of a repeated member and reorders
 * members by (length, bytes), at every depth, and keeps decimals exactly. The real database is
 * the oracle in the integration suite; this stand-in models the property under test.
 */
class StoredFixtureRoundTripTest {
    private val bodyContract =
        ScriptContract(
            mode = ScriptMode.VALUE,
            inputs = mapOf("order" to ScriptInput.Body()),
            output = ScriptOutput.Obj(),
        )

    private val valueContract =
        ScriptContract(
            mode = ScriptMode.VALUE,
            inputs = mapOf("order" to ScriptInput.Value(type = co.datapipelines.typesystem.LogicalType.STRING)),
            output = ScriptOutput.Obj(),
        )

    private fun bind(inputsJson: String): ScriptTestCase =
        ScriptBlocks.mapper.readValue(
            """{"name":"c","input":{"inputs":$inputsJson},"expect":{"output":{"x":1}}}""",
            ScriptTestCase::class.java,
        )

    private fun inputsOf(case: ScriptTestCase): FixtureInputs = case.input.inputs.shouldBeInstanceOf<FixtureInputs>()

    private fun stored(
        case: ScriptTestCase,
        contract: ScriptContract? = bodyContract,
    ): String = jsonb(requireNotNull(ScriptBlocks.writeTests(listOf(case), contract)))

    private fun readBack(
        case: ScriptTestCase,
        contract: ScriptContract? = bodyContract,
    ): FixtureInputs = inputsOf(requireNotNull(ScriptBlocks.readTests(stored(case, contract))).single())

    @Test
    fun `a repeated body input name survives the stored round trip as two occurrences`() {
        val read = readBack(bind("""{"order":{"a":1},"order":{"a":2}}"""))
        read.isRepeated("order") shouldBe true
        read.occurrences("order").shouldContainExactly("""{"a":1}""", """{"a":2}""")
    }

    @Test
    fun `a nested duplicate inside a body input survives the stored round trip`() {
        val read = readBack(bind("""{"order":{"a":1,"a":2}}"""))
        read.isRepeated("order") shouldBe false
        read.exactText("order") shouldBe """{"a":1,"a":2}"""
    }

    @Test
    fun `member order and exact decimals of a body input survive the stored round trip`() {
        val text = """{"zeta":0.123456789012345678901234567890,"alpha":[3,2,1],"mid":{"z":1,"b":2.50}}"""
        val read = readBack(bind("""{"order":$text}"""))
        read.exactText("order") shouldBe text
        // the ordinary view is the author's order too, so every reader of the map sees it
        (read["order"] as Map<*, *>).keys.toList() shouldBe listOf("zeta", "alpha", "mid")
    }

    @Test
    fun `an unread fixture re-stores and re-reads to the same metadata`() {
        val first = bind("""{"order":{"b":1,"a":2},"order":{"c":3}}""")
        val again = requireNotNull(ScriptBlocks.readTests(stored(first))).single()
        val read = readBack(again)
        read.occurrences("order").shouldContainExactly("""{"b":1,"a":2}""", """{"c":3}""")
        stored(again) shouldBe stored(first)
    }

    @Test
    fun `the stored text changes with the order of a body fixture, so the body hash does`() {
        stored(bind("""{"order":{"b":1,"a":2}}""")) shouldBe stored(bind("""{"order":{"b":1,"a":2}}"""))
        (stored(bind("""{"order":{"b":1,"a":2}}""")) == stored(bind("""{"order":{"a":2,"b":1}}"""))) shouldBe false
    }

    @Test
    fun `a contract with no body input stores exactly what it always did`() {
        val case = bind("""{"order":"x","n":0.50}""")
        val text = requireNotNull(ScriptBlocks.writeTests(listOf(case), valueContract))
        text shouldNotContain FixtureInputs.ENVELOPE_KEY
        text shouldBe
            """[{"name":"c","input":{"rows":null,"inputs":{"order":"x","n":0.50},"meta":null,"now":null},""" +
            """"expect":{"output":{"x":1},"refusal":null}}]"""
        requireNotNull(ScriptBlocks.writeTests(listOf(case), null)) shouldBe text
    }

    @Test
    fun `a legacy stored fixture with no envelope reads as it always did`() {
        val legacy = """[{"name":"c","input":{"inputs":{"order":{"a":1}}},"expect":{"output":{"x":1}}}]"""
        val read = inputsOf(requireNotNull(ScriptBlocks.readTests(legacy)).single())
        read.occurrences("order").shouldContainExactly("""{"a":1}""")
        read.isRepeated("order") shouldBe false
    }

    @Test
    fun `table and value inputs are not enveloped and keep their ordinary reading`() {
        val case = bind("""{"order":{"b":1,"a":2},"other":"v","other":"w"}""")
        val text = requireNotNull(ScriptBlocks.writeTests(listOf(case), bodyContract))
        text shouldContain """"${FixtureInputs.ENVELOPE_KEY}":{"order":["{\"b\":1,\"a\":2}"]}"""
        text shouldNotContain "other\\\""
        val read = readBack(case)
        read["other"] shouldBe "w"
        read.occurrences("other").shouldContainExactly(""""w"""")
    }

    @Test
    fun `the wire form writes every occurrence in the order read, and binds back to the same metadata`() {
        val case = bind("""{"order":{"b":1},"order":{"a":0.10},"z":1}""")
        val wire = ScriptBlocks.mapper.writeValueAsString(case)
        wire shouldContain """"inputs":{"order":{"b":1},"order":{"a":0.10},"z":1}"""
        val back = inputsOf(ScriptBlocks.mapper.readValue(wire, ScriptTestCase::class.java))
        back.occurrences("order").shouldContainExactly("""{"b":1}""", """{"a":0.10}""")
    }

    @Test
    fun `the reserved member is an ordinary unknown input on the wire and is read only in storage mode`() {
        val forged = bind("""{"order":{"a":1},"${FixtureInputs.ENVELOPE_KEY}":{"order":["{\"x\":9}"]}}""")
        val read = inputsOf(forged)
        read.keys.toList().shouldContainExactly("order", FixtureInputs.ENVELOPE_KEY)
        read.exactText("order") shouldBe """{"a":1}"""
        read.isRepeated("order") shouldBe false
    }

    @Test
    fun `a stored envelope that is not names to string lists is refused when read`() {
        val envelope = """"${FixtureInputs.ENVELOPE_KEY}":{"order":[1]}"""
        val broken = """[{"name":"c","input":{"inputs":{"order":{},$envelope}},"expect":{"output":{}}}]"""
        shouldThrow<com.fasterxml.jackson.databind.JsonMappingException> { ScriptBlocks.readTests(broken) }
    }

    @Test
    fun `a read shape keeps a repeated input name and a nested duplicate that a tree would collapse`() {
        val case = bind("""{"order":{"a":1,"a":2},"order":{"b":3}}""")
        val node = TemplateJson.testsNode(listOf(case))
        node.isPojo shouldBe true
        val text = TemplateJson.objectMapper().writeValueAsString(node)
        text shouldContain """"inputs":{"order":{"a":1,"a":2},"order":{"b":3}}"""
    }

    @Test
    fun `a read shape with no repeated member is an ordinary tree that keeps every digit and trailing zero`() {
        val node = TemplateJson.testsNode(listOf(bind("""{"order":{"b":0.123456789012345678901234567890,"a":2.50}}""")))
        node.isArray shouldBe true
        TemplateJson.objectMapper().writeValueAsString(node) shouldContain
            """"order":{"b":0.123456789012345678901234567890,"a":2.50}"""
    }

    /** A stand-in for Postgres `jsonb`: last copy of a repeated member, members ordered by (length, bytes), decimals kept. */
    private fun jsonb(text: String): String {
        val mapper: ObjectMapper =
            JsonMapper
                .builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
                .build()
        return mapper.writeValueAsString(normalize(mapper.readTree(text), mapper))
    }

    private fun normalize(
        node: JsonNode,
        mapper: ObjectMapper,
    ): JsonNode =
        when (node) {
            is ObjectNode -> {
                mapper.createObjectNode().also { out ->
                    node
                        .properties()
                        .sortedWith(compareBy({ it.key.length }, { it.key }))
                        .forEach { out.set<JsonNode>(it.key, normalize(it.value, mapper)) }
                }
            }

            is ArrayNode -> {
                mapper.createArrayNode().also { out -> node.forEach { out.add(normalize(it, mapper)) } }
            }

            else -> {
                node
            }
        }
}
