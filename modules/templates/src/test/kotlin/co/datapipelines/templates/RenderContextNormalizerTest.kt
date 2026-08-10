package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateRef
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [RenderContextNormalizer] branch by branch, through a real render (templates.md §4.4).
 *
 * Every row of §4.4's table and every branch of the normalizer is executed here, because the
 * table's most load-bearing promise is one no other test was reaching: **a `TIMESTAMP` renders
 * as UTC with a `Z`**. A context carrying a `+05:30` `OffsetDateTime` that rendered with its own
 * offset would put a local-time literal into SQL that reads as UTC — a silently wrong result,
 * not an error. The `OffsetDateTime` / `ZonedDateTime` / `LocalDateTime` branches were entirely
 * unexecuted before, so nothing held that promise.
 *
 * Asserted through [TemplateEngine] rather than against the normalizer's return values: what the
 * contract promises is what `${var}` *renders*, and the number/boolean formats live in the
 * Freemarker configuration, not in the normalizer.
 */
class RenderContextNormalizerTest {
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun renderValue(
        value: Any?,
        body: String = "\${v}",
    ): String {
        val engine =
            TemplateEngine(
                InMemoryTemplateRegistry(listOf(TemplateFixtures.version("t.sql", body = body))),
                cacheSize = 10,
                renderTimeoutMs = 5_000,
                maxOutputChars = 100_000,
            ).also { engines += it }
        return engine
            .execute(TemplateRef("t.sql", 1), mapOf("v" to value))
            .shouldBeInstanceOf<RenderOutcome.Success>()
            .sql
    }

    @Test
    fun `a non-UTC OffsetDateTime renders as UTC with a Z`() {
        // The §4.4 promise, and the one that fails silently if broken.
        renderValue(OffsetDateTime.of(2026, 8, 5, 20, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))) shouldBe
            "2026-08-05T14:30:00Z"
    }

    @Test
    fun `a non-UTC ZonedDateTime renders as UTC with a Z`() {
        renderValue(ZonedDateTime.of(2026, 8, 5, 10, 30, 0, 0, ZoneId.of("America/New_York"))) shouldBe
            "2026-08-05T14:30:00Z"
    }

    @Test
    fun `a LocalDateTime is read as UTC, never as the server's zone`() {
        // No offset in the value, so one has to be chosen; §4.4's TIMESTAMP row is UTC. Reading
        // the JVM default here would make the same pipeline emit different SQL on two hosts.
        renderValue(LocalDateTime.of(2026, 8, 5, 14, 30, 0)) shouldBe "2026-08-05T14:30:00Z"
    }

    @Test
    fun `each remaining scalar row of the section 4-4 table renders as documented`() {
        mapOf(
            42 to "42",
            BigInteger("9223372036854775808") to "9223372036854775808",
            BigDecimal("12345.67") to "12345.67",
            3.141592653589793 to "3.141592653589793",
            true to "true",
            false to "false",
            "verbatim ' string" to "verbatim ' string",
            LocalDate.of(2026, 8, 5) to "2026-08-05",
            LocalTime.of(14, 30, 0) to "14:30:00",
            Instant.parse("2026-08-05T14:30:00Z") to "2026-08-05T14:30:00Z",
            byteArrayOf(1, 2, 3) to "AQID",
        ).forEach { (value, expected) ->
            withClue("${value.javaClass.simpleName} must render as $expected") {
                renderValue(value) shouldBe expected
            }
        }
    }

    @Test
    fun `BOOLEAN false renders as the word false, not as an empty string`() {
        // Freemarker's default booleanFormat is the sentinel "true,false" that *errors* on
        // ${bool}; a mis-set format renders false as "" and quietly deletes a SQL predicate.
        renderValue(false) shouldBe "false"
    }

    @Test
    fun `a collection is normalized element by element and is listable`() {
        renderValue(
            listOf(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-30T12:00:00Z")),
            body = "<#list v as d>'\${d}'<#sep>,</#sep></#list>",
        ) shouldBe "'2026-01-01T00:00:00Z','2026-06-30T12:00:00Z'"
    }

    @Test
    fun `a map is normalized value by value and its keys are addressable`() {
        renderValue(
            mapOf("day" to LocalDate.of(2026, 8, 5), "flag" to true, "n" to BigDecimal("1.50")),
            body = "\${v.day} \${v.flag?c} \${v.n}",
        ) shouldBe "2026-08-05 true 1.50"
    }

    @Test
    fun `a nested map inside a list is normalized all the way down`() {
        renderValue(
            listOf(mapOf("at" to Instant.parse("2026-08-05T14:30:00Z"))),
            body = "<#list v as row>\${row.at}</#list>",
        ) shouldBe "2026-08-05T14:30:00Z"
    }

    @Test
    fun `a null context value is preserved as null, not as the string null`() {
        // `?default` must see an absent value; normalizing null to "null" would make the
        // fallback unreachable and put the literal word into the SQL.
        renderValue(null, body = "\${(v)!\"fallback\"}") shouldBe "fallback"
    }

    @Test
    fun `an unrecognised type falls through to toString rather than reaching the wrapper`() {
        // The contract is canonical scalars and collections; the fallback exists so an unexpected
        // value can never be handed to a wrapper that might expose Java members.
        renderValue(ZoneOffset.UTC.rules) shouldBe ZoneOffset.UTC.rules.toString()
    }
}
