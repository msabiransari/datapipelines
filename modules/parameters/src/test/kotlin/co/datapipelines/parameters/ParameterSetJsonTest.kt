package co.datapipelines.parameters

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
}
