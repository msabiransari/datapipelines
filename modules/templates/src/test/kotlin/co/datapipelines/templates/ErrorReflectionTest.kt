package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Every value this module echoes into an error message, a `details` map, or a log line comes
 * from an untrusted template payload — so [truncateForError] is a security control, not
 * cosmetics (rules/03-security.md, rules/02-error-handling.md).
 *
 * Two attacks it stops:
 *  - **log forging** — a newline in a reflected value would let an author write a second,
 *    fabricated log record ("... level=INFO msg=all clear").
 *  - **response/log flooding** — a 5MB body reflected verbatim into every failure turns one
 *    rejected save into a memory and disk amplifier.
 */
class ErrorReflectionTest {
    @Test
    fun `control characters cannot forge a log record`() {
        val forged = "ok\n2026-08-09 INFO  fabricated-record".truncateForError()

        forged shouldNotContain "\n"
        forged shouldContain "fabricated-record" // kept, but inert: the newline is gone
    }

    @Test
    fun `every ISO control character is replaced, not just newlines`() {
        // The escape character is the one that matters most: an ANSI sequence smuggled into an
        // operator's console can rewrite what they see. NUL, BEL and DEL stand in for the rest.
        val sanitized = "a\u0000b\u0007c\u001Bd\u007Fe".truncateForError()

        listOf("\u0000", "\u0007", "\u001B", "\u007F").forEach { sanitized shouldNotContain it }
        sanitized shouldContain "a"
        sanitized shouldContain "e"
    }

    @Test
    fun `an oversized value is truncated to the declared cap`() {
        val truncated = "x".repeat(10_000).truncateForError()

        truncated.length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1 // + the ellipsis
    }

    @Test
    fun `a null reflects as the literal null, never as a crash`() {
        (null as String?).truncateForError() shouldBe "null"
    }

    @Test
    fun `a validation failure never echoes an unbounded body back to the caller`() {
        val hostileId = "A".repeat(5_000) + "\n"
        val result =
            TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })
                .validate(TemplateFixtures.draft(id = hostileId), java.util.UUID.randomUUID())

        result.codes shouldContain PipelineErrorCodes.Template.ID_INVALID
        val failure = result.failures.single { it.code == PipelineErrorCodes.Template.ID_INVALID }
        failure.message.length shouldBeLessThan BOUNDED_MESSAGE_CEILING
        (failure.details["id"] as String).length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
        failure.message shouldNotContain "\n"
    }

    private companion object {
        /** A rejected save's message stays a message — a few hundred chars, never the body. */
        const val BOUNDED_MESSAGE_CEILING = 300
    }
}
