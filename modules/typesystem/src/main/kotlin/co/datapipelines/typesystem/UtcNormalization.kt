package co.datapipelines.typesystem

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * UTC normalization for timestamp-bearing values (type-system.md §8.4).
 *
 * Two rules, and they are the whole policy:
 *
 *  1. **Zoned values convert to UTC and drop their zone.** Canonical `TIMESTAMP` has no
 *     notion of a source zone (§2 principle 5) — federated joins across sources with
 *     different conventions are otherwise incoherent.
 *  2. **Naive values are treated as already UTC.** A source `TIMESTAMP WITHOUT TIME
 *     ZONE` carries no zone information, so no conversion is *possible*; assuming UTC is
 *     the documented convention, not a guess we could improve on.
 *
 * ## Sub-microsecond precision truncates, never rounds (§3.5)
 *
 * The wire format is exactly six fractional digits. A nanosecond-precision source value
 * is **truncated** toward the start of the second — rounding up could move a value into
 * the next second, and a timestamp that reads later than the event it records is a
 * worse lie than one that reads six digits shorter.
 *
 * ## What this object deliberately does NOT do
 *
 * It never consults the JVM default zone, so its behavior is identical on every
 * machine. The `-Duser.timezone=UTC` precondition in §8.4 is a *deployment*
 * requirement — it governs what JDBC hands us for zone-less reads, upstream of here.
 * Asserting it in code would be checking the wrong layer; the way to keep JDBC honest
 * is to read zone-aware values with `getObject(i, OffsetDateTime::class.java)` rather
 * than the zone-less `getTimestamp()` overloads.
 */
object UtcNormalization {
    /** The fixed fractional-second width of every egress timestamp and time (§3.5). */
    const val FRACTIONAL_DIGITS = 6

    /**
     * Normalizes a temporal value to a UTC [Instant] truncated to microseconds.
     *
     * Accepts the `java.time` types a JDBC 4.2 `getObject(index, type)` read produces.
     * `java.sql.Timestamp` is deliberately **not** accepted: every conversion it offers
     * resolves through the JVM default zone, which would make this function's result
     * machine-dependent and hide a §8.4 violation instead of surfacing it.
     *
     * @throws IllegalArgumentException for a type this function cannot normalize.
     */
    fun toUtcInstant(value: Any): Instant =
        when (value) {
            is Instant -> {
                value
            }

            is OffsetDateTime -> {
                value.toInstant()
            }

            is ZonedDateTime -> {
                value.toInstant()
            }

            // Naive: already UTC by the §8.4 convention.
            is LocalDateTime -> {
                value.toInstant(ZoneOffset.UTC)
            }

            else -> {
                throw IllegalArgumentException(
                    "Cannot normalize ${value.javaClass.name} to a UTC instant. Read timestamp columns " +
                        "with getObject(index, OffsetDateTime::class.java) or LocalDateTime, not getTimestamp().",
                )
            }
        }.truncatedTo(ChronoUnit.MICROS)

    /**
     * Normalizes a time-of-day value, truncated to microseconds.
     *
     * A zone-carrying source time (`timetz`, `time with time zone`) has its offset
     * **dropped**, not applied: canonical `TIME` has no zone, and §5.1/§5.6 both say the
     * zone information is discarded rather than used to shift the clock reading.
     */
    fun toLocalTime(value: Any): LocalTime =
        when (value) {
            is LocalTime -> {
                value
            }

            is OffsetTime -> {
                value.toLocalTime()
            }

            else -> {
                throw IllegalArgumentException(
                    "Cannot normalize ${value.javaClass.name} to a time of day. Read time columns with " +
                        "getObject(index, LocalTime::class.java), not getTime().",
                )
            }
        }.truncatedTo(ChronoUnit.MICROS)
}
