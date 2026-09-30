package co.datapipelines.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.regex.Pattern

/**
 * The scanner against an independent statement of its grammar (#337-c): the two shapes of
 * observability.md §9.2 written as plain regexes, applied at EVERY start of the ORIGINAL text, the
 * resulting spans merged into regions, each region replaced by its earliest span's replacement.
 * That is the definition the scanner's memoized single pass claims to implement, so the two must
 * agree on every string — including the nested and overlapping shapes no hand-written plant
 * thought of. The regexes here are a reference, never the shipped mechanism: they are quadratic
 * and their `%replace` form is what F5 and F6 were about.
 *
 * It exists because the memo caches (shared run ends, shared quoted ends) are exactly the kind of
 * optimisation a fixed corpus cannot falsify: the first version of the quoted-value memo reused a
 * closing quote as if it were an escaped one, and only a replay over random fragment soup showed
 * 4,936 mismatches in 400,000 strings. The non-vacuity floors keep the generator honest.
 */
class RedactionScannerGrammarOracleTest {
    private val identifier = "(?i:(?:${LogRedactor.SENSITIVE_KEYS.joinToString("|")})(?:_[A-Za-z0-9_.-]*+)?)"
    private val doubleQuoted = "\"(?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n]?)*+\"?"
    private val singleQuoted = "'(?:[^'\\\\\\r\\n]|\\\\[^\\r\\n]?)*+'?"
    private val assignment = Pattern.compile("($identifier)[ \\t]*=[ \\t]*(?:$doubleQuoted|$singleQuoted|[^\\s\"',;)\\]}]+)")
    private val jsonPair =
        Pattern.compile("(\"[A-Za-z0-9_.-]*?$identifier\"[ \\t]*:[ \\t]*)(?:$doubleQuoted|[^\\s\"',;)\\]}\\[{]+)")

    private class Span(
        val start: Int,
        val end: Int,
        val replacement: String,
    )

    private val fragments =
        listOf(
            "password",
            "Password",
            "secret",
            "SECRET",
            "api_key",
            "Authorization",
            "jdbc_url",
            "encryption_key",
            "db_",
            "_v2",
            "_",
            "-",
            ".",
            "=",
            ":",
            " ",
            "  ",
            "\t",
            "\"",
            "\\\"",
            "\\\\",
            "\\",
            "'",
            ",",
            ";",
            ")",
            "]",
            "}",
            "{",
            "[",
            "x",
            "abc",
            "1",
            "\n",
            "\"password\":",
            "\"secret\": ",
            "password=",
            "secret=",
            "passwordless",
            "user_id",
            "\"password\": \"",
            "secret='",
            "\\\"secret\\\":",
            "=\"",
            ":\"",
        )

    @Test
    fun `the scanner equals the union of the grammar's spans on random fragment text`() {
        var redacted = 0
        var merged = 0
        val mismatches = mutableListOf<String>()
        listOf(337L, 20_260_930L).forEach { seed ->
            val random = Random(seed)
            repeat(CASES_PER_SEED) {
                val text = randomText(random)
                val expected = oracle(text)
                if (expected.text != LogRedactor.scrubText(text)) {
                    mismatches.add("in : $text\n  oracle : ${expected.text}\n  scanner: ${LogRedactor.scrubText(text)}")
                }
                if (expected.text != text) redacted++
                if (expected.mergedRegions > 0) merged++
            }
        }
        withClue("strings where the scanner disagrees with the grammar (first 5):\n${mismatches.take(5).joinToString("\n")}") {
            mismatches.size shouldBe 0
        }
        // Non-vacuity: a generator that never produced a secret, or never an overlap, proves nothing.
        withClue("strings the oracle redacted") { (redacted >= MIN_REDACTED) shouldBe true }
        withClue("strings with a merged (overlapping) region") { (merged >= MIN_MERGED) shouldBe true }
    }

    private fun randomText(random: Random): String {
        val builder = StringBuilder()
        repeat(2 + random.nextInt(MAX_FRAGMENTS)) {
            builder.append(fragments[random.nextInt(fragments.size)])
        }
        return builder.toString()
    }

    private class Expected(
        val text: String,
        val mergedRegions: Int,
    )

    private fun oracle(text: String): Expected {
        if (!text.contains('=') && !text.contains(':')) return Expected(text, 0)
        val spans = mutableListOf<Span>()
        for (start in text.indices) {
            spanAt(assignment, text, start, "=${LogRedactor.MASK}")?.let(spans::add)
            spanAt(jsonPair, text, start, "\"${LogRedactor.MASK}\"")?.let(spans::add)
        }
        if (spans.isEmpty()) return Expected(text, 0)
        spans.sortBy { it.start }
        val out = StringBuilder()
        var copied = 0
        var merged = 0
        var i = 0
        while (i < spans.size) {
            val first = spans[i]
            var end = first.end
            var j = i + 1
            while (j < spans.size && spans[j].start < end) {
                end = maxOf(end, spans[j].end)
                j++
            }
            if (j > i + 1) merged++
            out.append(text, copied, first.start).append(first.replacement)
            copied = end
            i = j
        }
        return Expected(out.append(text, copied, text.length).toString(), merged)
    }

    private fun spanAt(
        pattern: Pattern,
        text: String,
        start: Int,
        suffix: String,
    ): Span? {
        val matcher =
            pattern
                .matcher(text)
                .region(start, text.length)
                .useTransparentBounds(true)
                .useAnchoringBounds(false)
        return if (matcher.lookingAt()) Span(start, matcher.end(), matcher.group(1) + suffix) else null
    }

    private companion object {
        const val CASES_PER_SEED = 40_000
        const val MAX_FRAGMENTS = 16
        const val MIN_REDACTED = 10_000
        const val MIN_MERGED = 500
    }
}
