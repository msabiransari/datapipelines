package co.datapipelines.application.endpoints

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A LEGACY endpoint row's echo (#286, #298): its stored path is cut at [EndpointPath.MAX_LENGTH]
 * on a code-point boundary. Only a database write can store such a row (every API write has held
 * the limit), so these are the rows an operator can least explain — and the echo is what they
 * see. (The row's `reason` is pinned against the same cut through the repository's real read,
 * in `EndpointPersistenceIntegrationTest`.)
 */
class LegacyEndpointEchoTest {
    @Test
    fun `the echo cuts on a code-point boundary - a supplementary character straddling the limit is dropped whole`() {
        // U+1F600 is two UTF-16 units; placed at index 199 its low surrogate is index 200.
        val stored = "/" + "a".repeat(EndpointPath.MAX_LENGTH - 2) + "\uD83D\uDE00" + "tail"
        val echo = EndpointPath.echoBounded(stored)
        echo.length shouldBe EndpointPath.MAX_LENGTH - 1
        withClue("no lone surrogate at the cut: ${echo.takeLast(2).map { it.code.toString(16) }}") {
            echo.none { Character.isSurrogate(it) } shouldBe true
        }
        // A path within the limit is echoed exactly, supplementary characters included.
        EndpointPath.echoBounded("/nyc/v1/\uD83D\uDE00") shouldBe "/nyc/v1/\uD83D\uDE00"
    }
}
