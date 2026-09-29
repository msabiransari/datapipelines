package co.datapipelines.parameters

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The strict mapper is the backstop behind the reader: a body bound WITHOUT the pre-scan (a stored
 * body read back, a future caller) still cannot drop a misspelt key — including inside the two
 * foreign types whose own class annotation says `ignoreUnknown = true` (the mix-in's reason).
 */
class ParameterSetJsonTest {
    @Test
    fun `a body round-trips through the stored form unchanged, derived getters never written`() {
        val body = ParameterSetReader().readOrThrow(ParameterSetFixtures.fullSet()).body
        val stored = ParameterSetJson.writeBody(body)
        ParameterSetJson.readBody(stored) shouldBe body
        stored shouldNotContain "\"declaration\""
        stored shouldNotContain "\"kind\":\"template\""
        stored shouldNotContain "\"name\":\"acme/"
    }

    @Test
    fun `binding refuses an unknown key at every level - set, parameter, source, option, pin, constraints, presentation`() {
        val paths =
            listOf<(ObjectNode) -> ObjectNode>(
                { it },
                { it.p(0) },
                { it.p(0)["source"] as ObjectNode },
                { (it.p(0)["source"]["constants"] as com.fasterxml.jackson.databind.node.ArrayNode)[0] as ObjectNode },
                { it.p(1)["source"]["template"] as ObjectNode },
                { it.p(2)["constraints"] as ObjectNode },
                { it.p(2)["presentation"] as ObjectNode },
                { it.p(2)["presentation"]["format"] as ObjectNode },
            )
        paths.forEachIndexed { index, at ->
            val body = ParameterSetReader.withoutNulls(ParameterSetFixtures.fullSet()) as ObjectNode
            body.remove("name")
            at(body).put("misspelt_$index", 1)
            val thrown = shouldThrow<UnrecognizedPropertyException> { ParameterSetJson.readBody(body.toString()) }
            thrown.propertyName shouldBe "misspelt_$index"
        }
    }

    private fun ObjectNode.p(index: Int): ObjectNode =
        (this["parameters"] as com.fasterxml.jackson.databind.node.ArrayNode)[index] as ObjectNode

    /**
     * The KDoc's scalar-coercion claim, pinned at the mapper itself (the #319 gap): `ALLOW_COERCION_OF_SCALARS`
     * governs text → number/boolean, never scalar → STRING — Jackson's `StringDeserializer` takes a JSON
     * number or boolean as text until the `Textual` coercion config refuses it. These bind WITHOUT the
     * pre-scan (a stored body read back, a transfer payload), so the mapper is where the refusal must happen.
     */
    @Test
    fun `a number or a boolean where a string is declared is refused, never bound as text`() {
        val base = ParameterSetReader.withoutNulls(ParameterSetFixtures.fullSet()) as ObjectNode
        base.remove("name")
        listOf(
            "display_name" to base.deepCopy().put("display_name", 5),
            "display_name" to base.deepCopy().put("display_name", 1.5),
            "description" to base.deepCopy().put("description", true),
        ).forEach { (path, document) ->
            val thrown = shouldThrow<MismatchedInputException> { ParameterSetJson.readBody(document.toString()) }
            thrown.pathReference() shouldBe path
            thrown.originalMessage shouldContain "Cannot coerce"
        }
    }

    @Test
    fun `a text or a float where an integer is declared, a number where a boolean is declared, is refused`() {
        val base = ParameterSetReader.withoutNulls(ParameterSetFixtures.fullSet()) as ObjectNode
        base.remove("name")
        listOf(
            "parameters[2].precision" to base.deepCopy().also { it.p(2).put("precision", "12") },
            "parameters[2].precision" to base.deepCopy().also { it.p(2).put("precision", 1.5) },
            "parameters[1].source.template.version" to base.deepCopy().also { it.p(1).sourceTemplate().put("version", 3.0) },
            "parameters[0].required" to base.deepCopy().also { it.p(0).put("required", 1) },
        ).forEach { (path, document) ->
            val thrown = shouldThrow<MismatchedInputException> { ParameterSetJson.readBody(document.toString()) }
            thrown.pathReference() shouldBe path
            thrown.originalMessage shouldContain "Cannot coerce"
        }
    }

    private fun ObjectNode.sourceTemplate(): ObjectNode = (get("source") as ObjectNode).get("template") as ObjectNode

    /** The binding's reference chain in the reader's own spelling: `parameters[0].source.template.version`. */
    private fun MismatchedInputException.pathReference(): String =
        path.fold("") { acc, ref ->
            when {
                ref.fieldName == null -> "$acc[${ref.index}]"
                acc.isEmpty() -> ref.fieldName
                else -> "$acc.${ref.fieldName}"
            }
        }
}
