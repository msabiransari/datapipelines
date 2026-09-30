package co.datapipelines.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The pure scrubber against the shared plant corpus (#337-b F1, F2): every shape §9.2 names comes
 * out with the whole value masked and every delimiter kept, and the never-redacted and
 * non-matching keys come out untouched. The encoder-level and packaged-output layers replay the
 * same corpus through the real encoders.
 */
class LogRedactorTest {
    @Test
    fun `every redacted plant scrubs to exactly the expected text`() {
        SyntheticPlants.REDACTED.forEach { plant ->
            val out = LogRedactor.scrubText(plant.raw)
            withClue("${plant.label}: ${plant.raw}") { out shouldBe plant.scrubbed }
            SyntheticPlants.SECRET_MARKERS.forEach { marker ->
                withClue("${plant.label}: $marker must not survive") { out shouldNotContain marker }
            }
        }
    }

    @Test
    fun `every kept plant scrubs to itself`() {
        SyntheticPlants.KEPT.forEach { plant ->
            withClue(plant.label) { LogRedactor.scrubText(plant.raw) shouldBe plant.raw }
        }
    }

    @Test
    fun `the member matcher and the text matcher agree on case for every sensitive key`() {
        LogRedactor.SENSITIVE_KEYS.forEach { key ->
            listOf(key, key.uppercase(), key.replaceFirstChar { it.uppercase() }, "Db_${key.uppercase()}_V2").forEach { spelling ->
                withClue("member rule for $spelling") { LogRedactor.isSensitiveKey(spelling) shouldBe true }
                val json = LogRedactor.scrubText("""{"$spelling":"planted-secret-x"}""")
                withClue("text rule for $spelling: $json") { json shouldBe """{"$spelling":"***"}""" }
                val assignment = LogRedactor.scrubText("$spelling=planted-secret-x")
                withClue("assignment rule for $spelling: $assignment") { assignment shouldBe "$spelling=***" }
            }
        }
    }

    @Test
    fun `never-redacted keys are not sensitive in any spelling`() {
        LogRedactor.NEVER_REDACTED.forEach { key ->
            listOf(key, key.uppercase()).forEach { spelling ->
                withClue(spelling) { LogRedactor.isSensitiveKey(spelling) shouldBe false }
            }
        }
    }

    @Test
    fun `scrubbing is linear in the number of open quotes on one line`() {
        // A hostile line of 50,000 `password="` fragments must not backtrack quadratically: each
        // pair of quotes is one match, so 25,000 masks come out. A generous wall-clock bound keeps
        // this a smoke test rather than a benchmark.
        val hostile = "password=\"".repeat(50_000)
        val started = System.nanoTime()
        LogRedactor.scrubText(hostile) shouldBe "password=***".repeat(25_000)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        withClue("took ${elapsedMillis}ms") { (elapsedMillis < 5_000) shouldBe true }
    }
}
