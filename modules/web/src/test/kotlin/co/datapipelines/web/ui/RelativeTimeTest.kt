package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant

/** 091 — the coarse half of a timestamp, rendered on the server so a test can read it. */
class RelativeTimeTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")

    @Test
    fun `an age is the largest whole unit that fits, singular-aware`() {
        assertAll(
            { RelativeTime.since(now.minusSeconds(20), now) shouldBe "just now" },
            { RelativeTime.since(now.minusSeconds(60), now) shouldBe "1 minute ago" },
            { RelativeTime.since(now.minusSeconds(600), now) shouldBe "10 minutes ago" },
            { RelativeTime.since(now.minusSeconds(3600), now) shouldBe "1 hour ago" },
            { RelativeTime.since(now.minusSeconds(7200), now) shouldBe "2 hours ago" },
            { RelativeTime.since(now.minusSeconds(86_400), now) shouldBe "1 day ago" },
            { RelativeTime.since(now.minusSeconds(86_400 * 12), now) shouldBe "12 days ago" },
        )
    }

    @Test
    fun `a horizon reads forward, and a past expiry reads as expired`() {
        assertAll(
            { RelativeTime.until(now.plusSeconds(30), now) shouldBe "in under a minute" },
            { RelativeTime.until(now.plusSeconds(3600), now) shouldBe "in 1 hour" },
            { RelativeTime.until(now.plusSeconds(86_400 * 84), now) shouldBe "in 84 days" },
            // The fact that matters about a past expiry is that it IS past, not how long ago.
            { RelativeTime.until(now.minusSeconds(1), now) shouldBe "expired" },
            { RelativeTime.until(now, now) shouldBe "expired" },
        )
    }

    @Test
    fun `a future instant in the past-facing form never reads as a negative age`() {
        // The only way to get one is clock skew between two instances, and "in -3 minutes"
        // would be a worse answer than rounding it to the present.
        RelativeTime.since(now.plusSeconds(180), now) shouldBe "just now"
    }

    @Test
    fun `the absolute form is UTC, explicitly, at minute precision`() {
        // Never a local rendering: the server's zone is nobody's, and a tooltip that silently
        // shifted by the deployment's TZ would make two operators read the same row differently.
        RelativeTime.absolute(Instant.parse("2026-12-01T00:00:00Z")) shouldBe "2026-12-01 00:00 UTC"
    }
}
