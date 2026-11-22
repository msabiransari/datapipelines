package co.datapipelines.web.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Instant

/**
 * #1043 — `datapipelines.endpoints.capture-window-seconds` and `capture-detail-retention-hours` (configuration.md
 * §3.22, §7): both bind as positive integers with the documented defaults, and zero, a negative value, an integer
 * overflow and a deadline past the representable range are each refused naming the key — by the binder and by the
 * raw-text rule the startup validator applies.
 */
class CaptureDurationsTest {
    private val now = Instant.parse("2026-11-22T00:00:00Z")

    @Test
    fun `absent keys bind the documented defaults - fifteen minutes and twenty-four hours`() {
        val bound = bind(emptyMap())

        bound.captureWindowSeconds shouldBe 900
        bound.captureDetailRetentionHours shouldBe 24
    }

    @Test
    fun `configured values bind through the dashed keys`() {
        val bound = bind(mapOf(CaptureDurations.WINDOW_KEY to "300", CaptureDurations.RETENTION_KEY to "4"))

        bound.captureWindowSeconds shouldBe 300
        bound.captureDetailRetentionHours shouldBe 4
    }

    @Test
    fun `zero, a negative value and an overflowing value are refused by the binder`() {
        listOf("0", "-5").forEach { bad ->
            listOf(CaptureDurations.WINDOW_KEY, CaptureDurations.RETENTION_KEY).forEach { key ->
                val refused = shouldThrow<BindException> { bind(mapOf(key to bad)) }
                generateSequence<Throwable>(refused) { it.cause }.joinToString { it.message.orEmpty() } shouldContain key
            }
        }
        // Past Int.MAX_VALUE the binder's own conversion refuses it; the validator names it (below).
        shouldThrow<BindException> { bind(mapOf(CaptureDurations.WINDOW_KEY to "99999999999")) }
    }

    @Test
    fun `the raw-text rule names each bad value's key`() {
        val window = CaptureDurations.WINDOW_KEY
        val retention = CaptureDurations.RETENTION_KEY
        CaptureDurations.problemOfText(window, "900", now).shouldBeNull()
        CaptureDurations.problemOfText(retention, "24", now).shouldBeNull()
        CaptureDurations.problemOfText(window, "0", now).shouldNotBeNull() shouldContain "$window (0) must be a positive integer"
        CaptureDurations.problemOfText(retention, "-5", now).shouldNotBeNull() shouldContain "$retention (-5) must be a positive integer"
        CaptureDurations.problemOfText(window, "abc", now).shouldNotBeNull() shouldContain "must be a positive integer of at most"
        CaptureDurations.problemOfText(window, "99999999999", now).shouldNotBeNull() shouldContain window
    }

    @Test
    fun `a retention whose expiry is past the representable range is refused - the window's largest value is not`() {
        // Int.MAX_VALUE hours is ~245,000 years: no admission could store that expiry.
        CaptureDurations.problemOfText(CaptureDurations.RETENTION_KEY, Int.MAX_VALUE.toString(), now).shouldNotBeNull() shouldContain "not representable"
        // Int.MAX_VALUE seconds is ~68 years: representable, so allowed.
        CaptureDurations.problemOfText(CaptureDurations.WINDOW_KEY, Int.MAX_VALUE.toString(), now).shouldBeNull()
        CaptureDurations.problems(Int.MAX_VALUE.toLong(), 1, now).shouldBeEmpty()
        shouldThrow<IllegalArgumentException> { EndpointsProperties(captureDetailRetentionHours = Int.MAX_VALUE) }.message.shouldNotBeNull() shouldContain
            CaptureDurations.RETENTION_KEY
    }

    @Test
    fun `the boundary - the last representable expiry is allowed, one hour past it is refused`() {
        val lastHours = java.time.Duration.between(now, CaptureDurations.MAX_DEADLINE).toHours()
        CaptureDurations.problems(1, lastHours, now).shouldBeEmpty()
        CaptureDurations.problems(1, lastHours + 1, now).single() shouldContain CaptureDurations.RETENTION_KEY
    }

    private fun bind(values: Map<String, String>): EndpointsProperties =
        Binder(MapConfigurationPropertySource(values))
            .bind("datapipelines.endpoints", Bindable.of(EndpointsProperties::class.java))
            .orElseGet { EndpointsProperties() }
}
