package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * #818d (record §10 RULING 4, second amendment, item 7) — [ReaderTermination.SECONDS] is DERIVED from the HTTP bounds
 * the LAKE adapter sets, never typed independently of them: this test recomputes it from the [LakeHttpBounds]
 * constants with the engine's retry arithmetic written out here a second time (DuckDB v1.5.5
 * `HTTPUtil::RunRequestWithRetry`), and fails on any drift — a constant changed without the term, or the term
 * changed without the constants.
 */
class ReaderTerminationDerivationTest {
    /** The engine's loop, restated: `retries + 1` attempts, three timeouts each, a sleep before attempts 3..retries+1. */
    private fun chainMillis(
        timeoutSeconds: Long,
        retries: Int,
        waitMillis: Long,
        backoff: Int,
    ): Long {
        var sleeps = 0L
        var factor = 1L
        // One sleep before each of attempts 3..retries+1: retries - 1 of them.
        repeat((retries - 1).coerceAtLeast(0)) {
            sleeps += waitMillis * factor
            factor *= backoff
        }
        return (retries + 1L) * PHASES * timeoutSeconds * MILLIS + sleeps
    }

    @Test
    fun `reader-termination is the request chain the adapter's HTTP bounds allow, plus the hand-off margin`() {
        val chain =
            chainMillis(
                LakeHttpBounds.HTTP_TIMEOUT_SECONDS,
                LakeHttpBounds.HTTP_RETRIES,
                LakeHttpBounds.HTTP_RETRY_WAIT_MS,
                LakeHttpBounds.HTTP_RETRY_BACKOFF,
            )
        val derived = Math.ceilDiv(chain + ReaderTermination.HAND_OFF_MARGIN_MILLIS, MILLIS)

        assertAll(
            { chain shouldBe 90_100L },
            { LakeHttpBounds.requestChainMillis() shouldBe chain },
            { ReaderTermination.SECONDS shouldBe derived },
            { ReaderTermination.SECONDS shouldBe 92L },
        )
    }

    @Test
    fun `the engine's own defaults would allow a far longer chain - the bounds are what shortens it`() {
        // http_timeout 30, http_retries 3, http_retry_wait_ms 100, http_retry_backoff 4: the probe's defaults.
        // Counted as the term is (three timeouts per attempt): 4 × 3 × 30 s + 100 + 400 ms. A store that accepts and
        // then sends nothing costs ONE timeout per attempt: 4 × 30 s + 0.5 s, the figure the stalled-store test's
        // red run can observe; with the bounds it is 3 × 10 s + 0.1 s.
        assertAll(
            { chainMillis(DEFAULT_TIMEOUT, DEFAULT_RETRIES, DEFAULT_WAIT, DEFAULT_BACKOFF) shouldBe 360_500L },
            { LakeHttpBounds.requestChainMillis(DEFAULT_TIMEOUT, DEFAULT_RETRIES, DEFAULT_WAIT, DEFAULT_BACKOFF) shouldBe 360_500L },
            { (DEFAULT_RETRIES + 1L) * DEFAULT_TIMEOUT * MILLIS + 500L shouldBe 120_500L },
            { (LakeHttpBounds.HTTP_RETRIES + 1L) * LakeHttpBounds.HTTP_TIMEOUT_SECONDS * MILLIS + 100L shouldBe 30_100L },
        )
    }

    @Test
    fun `the four SETs carry exactly the constants the term is derived from`() {
        LakeHttpBounds.STATEMENTS shouldBe
            listOf(
                "SET http_timeout = ${LakeHttpBounds.HTTP_TIMEOUT_SECONDS}",
                "SET http_retries = ${LakeHttpBounds.HTTP_RETRIES}",
                "SET http_retry_wait_ms = ${LakeHttpBounds.HTTP_RETRY_WAIT_MS}",
                "SET http_retry_backoff = ${LakeHttpBounds.HTTP_RETRY_BACKOFF}",
            )
    }

    private companion object {
        const val PHASES = 3
        const val MILLIS = 1_000L
        const val DEFAULT_TIMEOUT = 30L
        const val DEFAULT_RETRIES = 3
        const val DEFAULT_WAIT = 100L
        const val DEFAULT_BACKOFF = 4
    }
}
