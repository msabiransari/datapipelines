package co.datapipelines.typesystem

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The §8.4 UTC normalization rules, pinned with **explicit** zones on every case.
 *
 * §8.4 makes a UTC JVM (`-Duser.timezone=UTC`) a hard deployment precondition. A test
 * suite that leaned on it would pass on the deployment and quietly stop testing anything
 * on a developer laptop in Karachi — so nothing here reads the default zone, and one
 * test actively changes it to prove the result does not move.
 */
class UtcNormalizationTest {
    @Test
    fun `a zoned value converts to the same instant and drops its zone`() {
        val expected = Instant.parse("2026-08-05T19:30:00.123456Z")

        UtcNormalization.toUtcInstant(OffsetDateTime.parse("2026-08-05T14:30:00.123456-05:00")) shouldBe expected
        UtcNormalization.toUtcInstant(
            ZonedDateTime.parse("2026-08-05T14:30:00.123456-05:00[America/New_York]"),
        ) shouldBe expected
        UtcNormalization.toUtcInstant(
            OffsetDateTime.parse("2026-08-06T01:30:00.123456+06:00"),
        ) shouldBe expected
    }

    @Test
    fun `a naive value is treated as already UTC, never shifted`() {
        // §8.4: the source carries no zone, so no conversion is possible. Applying the
        // JVM's zone here is precisely the silent corruption the precondition guards.
        UtcNormalization.toUtcInstant(LocalDateTime.parse("2026-08-05T19:30:00.123456")) shouldBe
            Instant.parse("2026-08-05T19:30:00.123456Z")
    }

    @Test
    fun `normalization truncates to microseconds rather than rounding`() {
        UtcNormalization.toUtcInstant(Instant.parse("2026-08-05T19:30:00.123456999Z")) shouldBe
            Instant.parse("2026-08-05T19:30:00.123456Z")
        UtcNormalization.FRACTIONAL_DIGITS shouldBe 6
    }

    @Test
    fun `a time of day drops any offset instead of shifting the clock reading`() {
        // §5.1 / §5.6: "TZ info dropped". Canonical TIME has no zone, so shifting the
        // reading would invent a different time of day.
        UtcNormalization.toLocalTime(OffsetTime.parse("14:30:00.123456-05:00")) shouldBe
            LocalTime.parse("14:30:00.123456")
        UtcNormalization.toLocalTime(LocalTime.parse("14:30:00.123456789")) shouldBe
            LocalTime.parse("14:30:00.123456")
    }

    @Test
    @ResourceLock(Resources.TIME_ZONE)
    fun `results do not move when the JVM default zone is not UTC`() {
        // The behavioral proof that nothing here reads ZoneId.systemDefault(). If a future
        // change introduced a systemDefault() call, every assertion below would shift by
        // the offset and this test would fail on the developer's machine — which is where
        // it needs to fail, long before the deployment precondition can mask it.
        //
        // @ResourceLock(TIME_ZONE) because the default zone is JVM-global mutable state:
        // the try/finally restores it, but under parallel execution another test could
        // still observe the mutated zone mid-flight. Tests are sequential today; the lock
        // is what stops enabling parallelism later from turning this into a flake nobody
        // can reproduce.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Karachi")))

            UtcNormalization.toUtcInstant(LocalDateTime.parse("2026-08-05T19:30:00")) shouldBe
                Instant.parse("2026-08-05T19:30:00Z")
            UtcNormalization.toUtcInstant(OffsetDateTime.parse("2026-08-05T14:30:00-05:00")) shouldBe
                Instant.parse("2026-08-05T19:30:00Z")
            JsonEncoder.encode(
                LocalDateTime.parse("2026-08-05T19:30:00"),
                ColumnSchema("c", LogicalType.TIMESTAMP),
            ) shouldBe "2026-08-05T19:30:00.000000Z"
            JsonEncoder.encode(
                LocalTime.parse("14:30:00"),
                ColumnSchema("c", LogicalType.TIME),
            ) shouldBe "14:30:00.000000"
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a java-sql value is rejected with an actionable message`() {
        // Rejecting is the point: java.sql.Timestamp's conversions all route through the
        // default zone, so accepting it would reintroduce the machine dependence above.
        val thrown =
            shouldThrow<IllegalArgumentException> {
                UtcNormalization.toUtcInstant(java.sql.Timestamp.from(Instant.EPOCH))
            }

        thrown.message.orEmpty().contains("getObject") shouldBe true
    }
}
