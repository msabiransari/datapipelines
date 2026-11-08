package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #845 — pins [LakeHttpBounds.egressReaderEndByMillis], the one place the managed reader's egress bound lives. The
 * docs (lake.md §9, configuration.md §3.36, datasources.md §8C.10) quote this function and the shipped figure pinned
 * here, so a change to the formula or to `http_retries` turns this red before the docs can drift.
 */
class LakeHttpBoundsTest {
    @Test
    fun `845 - end-by is (http_retries + 1) x max-age + slack, with http_retries read from the SET the adapter emits`() {
        val retries =
            LakeHttpBounds.STATEMENTS
                .single { it.startsWith("SET http_retries = ") }
                .substringAfter("= ")
                .toLong()
        withClue("the derivation's retries are the adapter's SET, not DuckDB's default") {
            retries shouldBe LakeHttpBounds.HTTP_RETRIES.toLong()
        }
        val maxAgeSeconds = 35L
        LakeHttpBounds.egressReaderEndByMillis(maxAgeSeconds) shouldBe
            (retries + 1) * maxAgeSeconds * 1_000 + LakeHttpBounds.egressReaderSlackMillis()
    }

    @Test
    fun `845 - the slack is one cut allowance per attempt plus the retry sleeps`() {
        LakeHttpBounds.EGRESS_CUT_SLACK_MILLIS shouldBe 2_000L
        LakeHttpBounds.retrySleepsMillis() shouldBe 100L
        LakeHttpBounds.egressReaderSlackMillis() shouldBe 3 * 2_000L + 100L
    }

    @Test
    fun `845 - the shipped 960 s max-age bounds a trickled reader at 2 886 100 ms, about 48 minutes`() {
        val endBy = LakeHttpBounds.egressReaderEndByMillis(960)
        endBy shouldBe 2_886_100L
        withClue("the docs' 'about 48 minutes'") { (endBy / 60_000.0).toInt() shouldBe 48 }
    }

    @Test
    fun `845 - the measured ends fall after every cut and within end-by (probe A0b at 20 s, acceptance D1 at 35 s)`() {
        // a0b/OUTCOME.md: the reader ended at 63.1 s; d/green-1.xml D1: 108 110 ms.
        mapOf(20L to 63_100L, 35L to 108_110L).forEach { (maxAge, measured) ->
            withClue("max-age $maxAge s: measured $measured ms") {
                measured shouldBeGreaterThan (LakeHttpBounds.HTTP_RETRIES + 1L) * maxAge * 1_000
                measured shouldBeLessThanOrEqual LakeHttpBounds.egressReaderEndByMillis(maxAge)
            }
        }
    }

    @Test
    fun `845 - a non-positive max-age is refused`() {
        shouldThrow<IllegalArgumentException> { LakeHttpBounds.egressReaderEndByMillis(0) }
    }

    @Test
    fun `the silent-store request chain still sums to 90 100 ms after the sleeps were shared`() {
        LakeHttpBounds.requestChainMillis() shouldBe 90_100L
    }
}
