package co.datapipelines.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.logging.StandardStackTracePrinter

/**
 * The stack-trace printer's redact-then-bound order (#337-b F3). The printer's output bound is
 * 8192 characters of the RAW rendered trace; a quoted secret that starts before that cutoff and
 * ends after it loses its closing quote under truncate-then-scrub, so no pattern matches and the
 * visible prefix survives. Each case places a synthetic quoted secret at a chosen offset from the
 * exact cutoff and proves — with a non-vacuity check on the RAW text — that the plant really
 * straddles it before asserting nothing survives.
 */
class RedactingStackTracePrinterTest {
    private val cutoff = 8192
    private val header = "java.lang.IllegalStateException: "
    private val secretBody = "planted-secret-cutoff-" + "z".repeat(300)
    private val marker = "\n... (stack trace truncated)"

    @Test
    fun `an assignment secret at every offset around the cutoff leaves no prefix or suffix`() {
        listOf(-400, -100, -11, -5, 0, 50).forEach { offset ->
            assertBoundedAndClean("password=\"$secretBody\"", offset)
        }
    }

    @Test
    fun `a JSON pair secret at every offset around the cutoff leaves no prefix or suffix`() {
        listOf(-400, -100, -14, -5, 0, 50).forEach { offset ->
            assertBoundedAndClean("{\"password\":\"$secretBody\"}", offset)
        }
    }

    @Test
    fun `a secret with an escaped quote crossing the cutoff leaves no prefix or suffix`() {
        assertBoundedAndClean("password=\"planted-secret-head\\\"" + secretBody + "\"", -100)
    }

    @Test
    fun `the same secret in a short exception is redacted without truncation`() {
        // No frames: a test runner's own stack is deep enough to pass the bound by itself, which
        // would make "no truncation" untestable for a secret this size.
        val exception =
            IllegalStateException("connect failed password=\"$secretBody\" and {\"secret\":\"$secretBody\"}")
                .apply { stackTrace = emptyArray() }
        val printed = print(exception)

        printed shouldContain "password=***"
        printed shouldContain "{\"secret\":\"***\"}"
        printed shouldNotContain "planted-secret-"
        printed shouldNotContain "stack trace truncated"
    }

    @Test
    fun `a trace past the bound is cut to the bound and carries the marker`() {
        val printed = print(IllegalStateException("x".repeat(cutoff * 2)))

        printed shouldEndWith marker
        printed.length shouldBeLessThanOrEqual cutoff + marker.length
    }

    private fun assertBoundedAndClean(
        plant: String,
        offsetFromCutoff: Int,
    ) {
        val padding = "x".repeat(cutoff + offsetFromCutoff - header.length)
        val message = padding + plant + " " + "y".repeat(1_500)
        val throwable = IllegalStateException(message)

        val raw = StandardStackTracePrinter.rootFirst().printStackTraceToString(throwable)
        val plantStart = raw.indexOf(plant)
        withClue("non-vacuity: the plant starts at cutoff${if (offsetFromCutoff >= 0) "+" else ""}$offsetFromCutoff of the RAW trace") {
            plantStart shouldBe cutoff + offsetFromCutoff
        }
        if (offsetFromCutoff < 0 && plantStart + plant.length > cutoff) {
            withClue("non-vacuity: this case straddles the cutoff") { (plantStart < cutoff) shouldBe true }
        }

        val printed = print(throwable)

        withClue("offset $offsetFromCutoff: no fragment of the secret survives\n${printed.takeLast(600)}") {
            SyntheticPlants.SECRET_MARKERS.forEach { printed shouldNotContain it }
            printed shouldNotContain "zzzzzzzz"
        }
        withClue("offset $offsetFromCutoff: the output is bounded and marked") {
            printed shouldEndWith marker
            printed.length shouldBeLessThanOrEqual cutoff + marker.length
        }
    }

    private fun print(throwable: Throwable): String =
        StringBuilder().also { RedactingStackTracePrinter().printStackTrace(throwable, it) }.toString()
}
