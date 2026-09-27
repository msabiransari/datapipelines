package co.datapipelines.parameters

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Record §7: the grammar, its caps, the static rules and the runtime semantics — every §7 code reached by a fixture. */
class ExpressionTest {
    private val json = JsonNodeFactory.instance
    private val parser = ExpressionParser()

    private fun tree(text: String): JsonNode = ParameterSetJson.mapper.readTree(text)

    private fun parsed(text: String): Expr = parser.parse(tree(text)).shouldBeInstanceOf<ExpressionParse.Parsed>().expr

    private fun refused(
        node: JsonNode,
        with: ExpressionParser = parser,
    ): List<ExpressionProblem> = with.parse(node).shouldBeInstanceOf<ExpressionParse.Refused>().problems

    @Nested
    inner class Grammar {
        @Test
        fun `every operator parses, and the printer's canonical form parses back to the same tree`() {
            val expression =
                parsed(
                    """
                    { "op": "and", "args": [
                        { "op": "not", "arg": { "op": "is_null", "arg": { "ref": "country" } } },
                        { "op": "or", "args": [
                            { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } },
                            { "op": "neq", "left": { "ref": "country" }, "right": { "literal": "CAN" } },
                            { "op": "in", "left": { "ref": "state" }, "right": [ { "literal": "NY" }, { "literal": "NJ" } ] } ] },
                        { "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "west" } },
                        { "op": "not", "arg": { "op": "is_empty", "arg": { "ref": "regions" } } } ] }
                    """.trimIndent(),
                )
            val printed = ExpressionPrinter.print(expression)
            parser.parse(printed).shouldBeInstanceOf<ExpressionParse.Parsed>().expr shouldBe expression
            expression.refs().map { it.name }.distinct() shouldBe listOf("country", "state", "regions")
            printed["op"].asText() shouldBe "and"
        }

        @Test
        fun `a malformed tree is expression_invalid with the reason and the path of every problem`() {
            val problems =
                refused(
                    tree(
                        """
                        { "op": "and", "args": [
                            "country is null",
                            { "arg": { "ref": "x" } },
                            { "op": "xor", "args": [] },
                            { "op": "not", "arg": { "op": "is_null", "arg": { "ref": "x" } }, "extra": 1 },
                            { "op": "or", "args": [] },
                            { "op": "or", "args": { "op": "is_null" } },
                            { "op": "eq", "left": { "ref": "x", "as": "y" }, "right": { "value": 1 } },
                            { "op": "eq", "right": { "literal": 1 } },
                            { "op": "in", "left": { "ref": "x" }, "right": [] },
                            { "op": "is_null", "arg": { "ref": 5 } } ] }
                        """.trimIndent(),
                    ),
                )
            problems.map { it.code }.distinct() shouldBe listOf(ParameterErrorCodes.EXPRESSION_INVALID)
            problems.map { it.path to it.details["reason"] } shouldContainExactlyInAnyOrder
                listOf(
                    "args[0]" to "not_an_object",
                    "args[1].op" to "missing",
                    "args[2].op" to "unknown_op",
                    "args[3].extra" to "unknown_key",
                    "args[4].args" to "empty_args",
                    "args[5].args" to "wrong_type",
                    "args[6].left" to "ref_shape",
                    "args[6].right" to "literal_shape",
                    "args[7].left" to "missing",
                    "args[8].right" to "empty_args",
                    "args[9].arg.ref" to "wrong_type",
                )
        }
    }

    @Nested
    inner class Caps {
        private fun nots(
            depth: Int,
            leaf: JsonNode = json.objectNode().put("op", "is_null").set("arg", json.objectNode().put("ref", "x")),
        ): JsonNode {
            var node = leaf
            repeat(depth - 1) { node = json.objectNode().put("op", "not").set("arg", node) }
            return node
        }

        @Test
        fun `depth - the bound itself parses, one past it is expression_depth_exceeded`() {
            val four = ExpressionParser(maxDepth = 4, maxNodes = 128)
            four.parse(nots(4)).shouldBeInstanceOf<ExpressionParse.Parsed>()
            refused(nots(5), four).map { it.code } shouldBe listOf(ParameterErrorCodes.EXPRESSION_DEPTH_EXCEEDED)
        }

        @Test
        fun `a nesting of 100,000 is refused at the configured depth - the counter is the bound, never the host stack`() {
            val problems = refused(nots(100_000))
            problems.map { it.code } shouldBe listOf(ParameterErrorCodes.EXPRESSION_DEPTH_EXCEEDED)
            problems.single().path.count { it == '.' } shouldBe ParametersKey.MAX_EXPRESSION_DEPTH.default.toInt() - 1
        }

        @Test
        fun `operator nodes - the budget itself parses, one past it is expression_too_large, and the walk stops there`() {
            fun and(n: Int): ObjectNode =
                json.objectNode().put("op", "and").set(
                    "args",
                    json.arrayNode().addAll(
                        (1 until n).map { json.objectNode().put("op", "is_null").set<JsonNode>("arg", json.objectNode().put("ref", "x")) },
                    ),
                )
            val ten = ExpressionParser(maxDepth = 16, maxNodes = 10)
            ten.parse(and(10)).shouldBeInstanceOf<ExpressionParse.Parsed>()
            refused(and(11), ten).single().let {
                it.code shouldBe ParameterErrorCodes.EXPRESSION_TOO_LARGE
                it.details["reason"] shouldBe "nodes"
            }
        }

        @Test
        fun `an in list - 256 literals parse, 257 are expression_too_large`() {
            fun inList(n: Int): JsonNode =
                json
                    .objectNode()
                    .put("op", "in")
                    .set<ObjectNode>("left", json.objectNode().put("ref", "x"))
                    .set("right", json.arrayNode().addAll((1..n).map { json.objectNode().put("literal", "v$it") }))
            parser.parse(inList(256)).shouldBeInstanceOf<ExpressionParse.Parsed>()
            refused(inList(257)).single().let {
                it.code shouldBe ParameterErrorCodes.EXPRESSION_TOO_LARGE
                it.details["reason"] shouldBe "in_list"
            }
        }
    }

    @Nested
    inner class StaticRules {
        private fun definition(
            name: String,
            type: LogicalType = LogicalType.STRING,
            cardinality: ParameterCardinality = ParameterCardinality.SINGLE,
            dependsOn: List<String> = emptyList(),
        ) = ParameterDefinition(
            name = name,
            label = name,
            type = type,
            kind = if (cardinality == ParameterCardinality.MULTI) ParameterKind.SELECT else ParameterKind.INPUT,
            cardinality = cardinality,
            dependsOn = dependsOn,
        )

        private val parents =
            listOf(
                definition("country"),
                definition("regions", cardinality = ParameterCardinality.MULTI),
                definition("amount", type = LogicalType.INTEGER),
                definition("since", type = LogicalType.DATE),
                definition("blob", type = LogicalType.BINARY),
            ).associateBy { it.name }
        private val carrier = definition("city", dependsOn = parents.keys.toList())
        private val set = parents + (carrier.name to carrier)

        private fun check(text: String): List<ExpressionProblem> = ExpressionChecker.check(parsed(text), carrier, set)

        @Test
        fun `a well-typed expression over the carrier's dependencies passes`() {
            check(
                """
                { "op": "and", "args": [
                  { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } },
                  { "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "west" } },
                  { "op": "in", "left": { "ref": "amount" }, "right": [ { "literal": 1 }, { "literal": 2 } ] },
                  { "op": "neq", "left": { "ref": "since" }, "right": { "literal": "2026-09-26" } },
                  { "op": "is_null", "arg": { "ref": "blob" } } ] }
                """.trimIndent(),
            ) shouldBe emptyList()
        }

        @Test
        fun `ref_undeclared - itself, and a parameter outside depends_on`() {
            check("""{ "op": "is_null", "arg": { "ref": "city" } }""").single().let {
                it.code shouldBe ParameterErrorCodes.REF_UNDECLARED
                it.details["reason"] shouldBe "self"
            }
            ExpressionChecker
                .check(parsed("""{ "op": "is_null", "arg": { "ref": "country" } }"""), definition("city"), set)
                .single()
                .details["reason"] shouldBe "not_a_dependency"
        }

        @Test
        fun `expression_cardinality - each operator against the other cardinality`() {
            val wrong =
                listOf(
                    """{ "op": "eq", "left": { "ref": "regions" }, "right": { "literal": "west" } }""",
                    """{ "op": "neq", "left": { "ref": "regions" }, "right": { "literal": "west" } }""",
                    """{ "op": "in", "left": { "ref": "regions" }, "right": [ { "literal": "west" } ] }""",
                    """{ "op": "is_null", "arg": { "ref": "regions" } }""",
                    """{ "op": "contains", "left": { "ref": "country" }, "right": { "literal": "USA" } }""",
                    """{ "op": "is_empty", "arg": { "ref": "country" } }""",
                )
            wrong.forEach { text -> check(text).map { it.code } shouldBe listOf(ParameterErrorCodes.EXPRESSION_CARDINALITY) }
        }

        @Test
        fun `a BINARY ref may only be tested with is_null`() {
            check("""{ "op": "eq", "left": { "ref": "blob" }, "right": { "literal": "AAAA" } }""").single().code shouldBe
                ParameterErrorCodes.EXPRESSION_TYPE_UNSUPPORTED
        }

        @Test
        fun `expression_literal_type - a literal the ref's type cannot hold, strictly (nothing trimmed)`() {
            listOf(
                """{ "op": "eq", "left": { "ref": "amount" }, "right": { "literal": "5" } }""",
                """{ "op": "eq", "left": { "ref": "since" }, "right": { "literal": "2026-13-01" } }""",
                """{ "op": "eq", "left": { "ref": "country" }, "right": { "literal": 5 } }""",
                """{ "op": "in", "left": { "ref": "amount" }, "right": [ { "literal": 1 }, { "literal": null } ] }""",
            ).forEach { text -> check(text).map { it.code } shouldBe listOf(ParameterErrorCodes.EXPRESSION_LITERAL_TYPE) }
        }
    }

    @Nested
    inner class Runtime {
        private val types =
            mapOf(
                "country" to LogicalType.STRING,
                "regions" to LogicalType.STRING,
                "rate" to LogicalType.DECIMAL,
                "since" to LogicalType.DATE,
            )

        private fun holds(
            text: String,
            values: Map<String, Any?>,
        ): Boolean = ExpressionEvaluator.evaluate(parsed(text), values, types)

        @Test
        fun `a null expression is false`() {
            ExpressionEvaluator.evaluate(null, emptyMap(), types) shouldBe false
        }

        @Test
        fun `is_null and is_empty are the only absence tests - every compare on an absent ref is false, neq included`() {
            val none = mapOf("country" to null, "regions" to emptyList<String>())
            holds("""{ "op": "is_null", "arg": { "ref": "country" } }""", none) shouldBe true
            holds("""{ "op": "is_empty", "arg": { "ref": "regions" } }""", none) shouldBe true
            holds("""{ "op": "is_empty", "arg": { "ref": "regions" } }""", mapOf("regions" to null)) shouldBe true
            holds("""{ "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } }""", none) shouldBe false
            holds("""{ "op": "neq", "left": { "ref": "country" }, "right": { "literal": "USA" } }""", none) shouldBe false
            holds("""{ "op": "in", "left": { "ref": "country" }, "right": [ { "literal": "USA" } ] }""", none) shouldBe false
            holds("""{ "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "west" } }""", none) shouldBe false
        }

        @Test
        fun `SINGLE compares - eq, neq and in on the canonical value, strings exact`() {
            val usa = mapOf("country" to "USA")
            holds("""{ "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } }""", usa) shouldBe true
            holds("""{ "op": "eq", "left": { "ref": "country" }, "right": { "literal": "usa" } }""", usa) shouldBe false
            holds("""{ "op": "eq", "left": { "ref": "country" }, "right": { "literal": " USA" } }""", usa) shouldBe false
            holds("""{ "op": "neq", "left": { "ref": "country" }, "right": { "literal": "CAN" } }""", usa) shouldBe true
            holds("""{ "op": "in", "left": { "ref": "country" }, "right": [ { "literal": "CAN" }, { "literal": "USA" } ] }""", usa) shouldBe
                true
            holds("""{ "op": "in", "left": { "ref": "country" }, "right": [ { "literal": "CAN" } ] }""", usa) shouldBe false
        }

        @Test
        fun `equality is the canonical value's - 1_0 equals 1_00, a date its date`() {
            holds(
                """{ "op": "eq", "left": { "ref": "rate" }, "right": { "literal": 1.00 } }""",
                mapOf("rate" to BigDecimal("1.0")),
            ) shouldBe
                true
            holds(
                """{ "op": "eq", "left": { "ref": "since" }, "right": { "literal": "2026-09-26" } }""",
                mapOf("since" to LocalDate.of(2026, 9, 26)),
            ) shouldBe
                true
        }

        @Test
        fun `MULTI - contains a member, and not is_empty`() {
            val west = mapOf("regions" to listOf("west", "east"))
            holds("""{ "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "west" } }""", west) shouldBe true
            holds("""{ "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "north" } }""", west) shouldBe false
            holds("""{ "op": "not", "arg": { "op": "is_empty", "arg": { "ref": "regions" } } }""", west) shouldBe true
        }

        @Test
        fun `and, or and not combine - every argument evaluated`() {
            val v = mapOf("country" to "USA", "regions" to listOf("west"))
            holds(
                """{ "op": "and", "args": [ { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "USA" } },
                    { "op": "contains", "left": { "ref": "regions" }, "right": { "literal": "west" } } ] }""",
                v,
            ) shouldBe true
            holds(
                """{ "op": "or", "args": [ { "op": "eq", "left": { "ref": "country" }, "right": { "literal": "CAN" } },
                    { "op": "is_null", "arg": { "ref": "country" } } ] }""",
                v,
            ) shouldBe false
            holds("""{ "op": "not", "arg": { "op": "is_null", "arg": { "ref": "country" } } }""", v) shouldBe true
        }

        @Test
        fun `a literal that never passed the save-time check fails loudly at evaluate`() {
            shouldThrow<IllegalStateException> {
                holds("""{ "op": "eq", "left": { "ref": "rate" }, "right": { "literal": "x" } }""", mapOf("rate" to BigDecimal.ONE))
            }
        }
    }
}
