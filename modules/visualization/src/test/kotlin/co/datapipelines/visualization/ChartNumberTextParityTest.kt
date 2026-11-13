package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Node's String(Number), read from IEEE bits, is independent of the JVM's digit-selection algorithm. */
class ChartNumberTextParityTest {
    @Test
    fun `every named boundary power-of-two neighbor and fixed-seed value agrees with Node`() {
        val corpus = ArtifactJson.mapper.readTree(File(ChartModelParityTest.PARITY_ROOT, "corpus.json"))
        val oracle = ArtifactJson.mapper.readTree(File(ChartModelParityTest.JS_RESULT)).path("text")
        val bits = corpus.path("text").map(JsonNode::asText)
        bits.size shouldBe TEXT_CASES
        bits.toSet().size shouldBe TEXT_CASES
        oracle.size() shouldBe TEXT_CASES
        corpus.path("text_random_count").asInt() shouldBe RANDOM_CASES
        corpus.path("text_seed").asText() shouldBe "xorshift64star/0x860c20261110"
        val differences = mutableListOf<String>()
        bits.forEach { hex ->
            val expected = oracle.get(hex)
            withClue(hex) { expected?.isTextual shouldBe true }
            val value = Double.fromBits(java.lang.Long.parseUnsignedLong(hex, HEX_RADIX))
            val actual = ChartValues.number(value)
            if (actual != expected.asText()) differences += "$hex: JVM=$actual Node=${expected.asText()}"
        }
        withClue("${bits.size} bit patterns compared; ${differences.size} differences") {
            differences.take(MAX_REPORTED_DIFFERENCES).shouldBeEmpty()
        }
    }

    companion object {
        // Named boundaries, all positive powers of two and both neighbors, plus 1,000 seeded finite values.
        // Pin the actual count, so deleting a case from the shared list cannot silently weaken both sides.
        private const val TEXT_CASES = 7301
        private const val RANDOM_CASES = 1000
        private const val HEX_RADIX = 16
        private const val MAX_REPORTED_DIFFERENCES = 20
    }
}
