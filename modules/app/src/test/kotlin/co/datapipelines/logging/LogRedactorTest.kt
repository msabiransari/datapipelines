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
        // Collected, not fail-fast: a red run names EVERY plant that leaks, not the first.
        val failures =
            SyntheticPlants.REDACTED.mapNotNull { plant ->
                val out = LogRedactor.scrubText(plant.raw)
                val leaked = SyntheticPlants.SECRET_MARKERS.filter { out.contains(it) }
                if (out == plant.scrubbed && leaked.isEmpty()) null else "${plant.label}: ${plant.raw} -> $out (leaked $leaked)"
            }
        withClue("plants that did not scrub exactly:\n${failures.joinToString("\n")}") { failures.isEmpty() shouldBe true }
    }

    @Test
    fun `the overlapping plants are a non-vacuous part of the corpus`() {
        // Non-vacuity floor for F5: the corpus must keep carrying both directions of nesting.
        (SyntheticPlants.OVERLAPPING.size >= 10) shouldBe true
        SyntheticPlants.REDACTED.containsAll(SyntheticPlants.OVERLAPPING) shouldBe true
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
        // pair of quotes is one match, so 25,000 masks come out.
        val hostile = "password=\"".repeat(50_000)
        LogRedactor.scrubText(hostile) shouldBe "password=***".repeat(25_000)
    }

    // The delivered pattern threw StackOverflowError on a few-KB a_a_a_ token (one recursion per
    // group iteration) and ran ~15 s on a 50 KB plain word before an equals sign (a prefix retried
    // from every start position). Each shape below is a plain string a log message can carry, and
    // each is its own test so a regression names the shape.

    @Test
    fun `a long underscore-segmented token neither overflows the stack nor changes`() {
        val token = "a_".repeat(50_000) + "z=1"
        assertScrubbed(token, token)
    }

    @Test
    fun `a long plain word before an equals sign is scrubbed in linear time`() {
        val token = "a".repeat(60_000) + "=1"
        assertScrubbed(token, token)
    }

    @Test
    fun `a long quoted body with escapes is one match`() {
        // Pairs of backslashes keep the closing quote real: the body is 1.2 million characters.
        assertScrubbed("password=\"" + "ab\\\\".repeat(300_000) + "\"", "password=***")
    }

    @Test
    fun `a long compound suffix on a sensitive key is masked without overflowing`() {
        assertScrubbed("password_" + "a_".repeat(50_000) + "=x", "password_" + "a_".repeat(50_000) + "=***")
    }

    // #337-c F6: a failed match must not rescan its suffix from every later key start. The bound is
    // a COUNT of scan steps (RedactionScanner.work), not a timing: it is the same on every machine
    // and a quadratic scanner fails it by orders of magnitude. Each family is a plain string a log
    // message can carry; each is checked for (a) unchanged output where the text holds no secret,
    // (b) an absolute steps-per-character ceiling, and (c) linear growth when the input doubles.

    @Test
    fun `failed matches are linear - repeated sensitive prefixes that never reach an equals sign`() {
        assertLinear("password_ repeated, no value") { n -> "password_".repeat(n) + "z:" }
    }

    @Test
    fun `failed matches are linear - every sensitive word glued into one token`() {
        assertLinear("all six words glued") { n ->
            "password_secret_api_key_authorization_jdbc_url_encryption_key_".repeat(n) + "z:"
        }
    }

    @Test
    fun `failed matches are linear - mixed case and dotted and hyphenated repeats`() {
        assertLinear("mixed case repeats") { n -> "Password.Secret-Api_Key_".repeat(n) + "end" }
    }

    @Test
    fun `failed matches are linear - a sensitive word with nothing after the blanks`() {
        assertLinear("word then blanks then a non-equals") { n -> "password_x".repeat(n) + " ".repeat(n) + "z" }
    }

    @Test
    fun `failed matches are linear - equals signs followed by blanks then a stop character`() {
        assertLinear("equals then blanks then a delimiter") { n -> "password_x" + "password_".repeat(n) + "=" + " ".repeat(n) + ")" }
    }

    @Test
    fun `failed matches are linear - repeated keys each followed by a delimiter-only value`() {
        assertLinear("key equals delimiter repeated") { n -> "password=)".repeat(n) }
    }

    @Test
    fun `failed matches are linear - long json key candidates without a closing quote`() {
        assertLinear("json key run, no closing quote") { n -> "\"" + "password_".repeat(n) + ":" }
    }

    @Test
    fun `failed matches are linear - long json key candidates with a closing quote and no colon`() {
        assertLinear("json key run, closing quote, no colon") { n -> "\"" + "password_".repeat(n) + "\" z:" }
    }

    @Test
    fun `failed matches are linear - many short json key candidates`() {
        assertLinear("quoted keys repeated") { n -> "\"password\" ".repeat(n) + "z:" }
    }

    @Test
    fun `failed matches are linear - a json key followed by blanks and no colon`() {
        assertLinear("json key then blanks") { n -> "\"password\"" + " ".repeat(n) + "z" }
    }

    @Test
    fun `failed matches are linear - escaped and open quotes`() {
        assertLinear("escaped quotes") { n -> "\\\"".repeat(n) + ":" }
        assertLinear("open quotes") { n -> "\"".repeat(n) + ":" }
        assertLinear("escaped then open quotes after a key") { n -> "password=\\\"".repeat(n) + ":" }
    }

    @Test
    fun `matching work is linear too - many adjacent secrets and a long value`() {
        assertLinear("adjacent assignments") { n -> "password=x;".repeat(n) }
        assertLinear("adjacent json pairs") { n -> "{\"secret\":\"x\"}".repeat(n) }
        assertLinear("one long quoted value holding nested keys") { n -> "password=\"" + "secret=a\\\"".repeat(n) + "\"" }
    }

    @Test
    fun `the work counter is real - it grows with the input and is zero when there is nothing to scan`() {
        LogRedactor.scrubMeasured("no delimiter here").work shouldBe 0L
        val small = LogRedactor.scrubMeasured("password_".repeat(100) + "z:").work
        val large = LogRedactor.scrubMeasured("password_".repeat(400) + "z:").work
        (small > 100) shouldBe true
        (large > small) shouldBe true
    }

    private fun assertLinear(
        label: String,
        build: (Int) -> String,
    ) {
        val sizes = listOf(1_000, 2_000, 4_000, 8_000)
        val works =
            sizes.map { n ->
                val input = build(n)
                val measured = LogRedactor.scrubMeasured(input)
                withClue("$label n=$n: scan steps ${measured.work} over ${input.length} characters") {
                    (measured.work <= WORK_PER_CHARACTER_CEILING * input.length + WORK_SLACK) shouldBe true
                }
                measured.work
            }
        sizes.indices.drop(1).forEach { i ->
            withClue("$label: doubling the input must not more than double-and-a-bit the work (${works[i - 1]} -> ${works[i]})") {
                (works[i] <= works[i - 1] * GROWTH_CEILING + WORK_SLACK) shouldBe true
            }
        }
    }

    private companion object {
        /** Scan steps per input character the scanner may take; quadratic input blows through it by 100x. */
        const val WORK_PER_CHARACTER_CEILING = 24

        /** Doubling the input may multiply the work by at most this (linear is 2.0). */
        const val GROWTH_CEILING = 2.3

        const val WORK_SLACK = 64
    }

    private fun assertScrubbed(
        input: String,
        expected: String,
    ) {
        val started = System.nanoTime()
        val out = LogRedactor.scrubText(input)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        withClue("took ${elapsedMillis}ms") {
            (out == expected) shouldBe true
            (elapsedMillis < 5_000) shouldBe true
        }
    }
}
