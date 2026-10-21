package co.datapipelines.datasources

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The one jdbc-form rule both bind paths share (#339): the lease's selector binds and the SQL
 * probe's parameters. The question this suite answers is "does the helper change anything but an
 * `Instant`'s Java form?" — an `Instant` must come back as the same instant at offset `Z`, and every
 * other canonical value must come back as the very object it was given, so what is bound never moves.
 * The wire effect (a `timestamptz` bind on real Postgres) is pinned by `SelectorRunnerIntegrationTest`
 * and `SqlProbePostgresIntegrationTest`, one per path.
 */
class JdbcBindFormsTest {
    @Test
    fun `an Instant becomes the same instant as an OffsetDateTime at UTC`() {
        val instant = Instant.parse("2026-10-20T20:31:07.123456Z")

        val form = jdbcForm(instant)

        val offsetDateTime = form.shouldBeInstanceOf<OffsetDateTime>()
        assertAll(
            { offsetDateTime.offset shouldBe ZoneOffset.UTC },
            { offsetDateTime.toInstant() shouldBe instant },
        )
    }

    @Test
    fun `every other canonical value comes back as the same reference`() {
        val values: List<Any> =
            listOf(
                "text",
                BigDecimal("12.50"),
                BigInteger("123456789012345678901234567890"),
                true,
                42,
                byteArrayOf(1, 2, 3),
                LocalDate.parse("2026-10-20"),
                LocalTime.parse("20:31:07"),
                LocalDateTime.parse("2026-10-20T20:31:07"),
            )

        assertSoftly {
            values.forEach { value ->
                withClue(value::class.simpleName) { jdbcForm(value) shouldBeSameInstanceAs value }
            }
        }
    }

    @Test
    fun `null stays null`() {
        jdbcForm(null) shouldBe null
    }
}
