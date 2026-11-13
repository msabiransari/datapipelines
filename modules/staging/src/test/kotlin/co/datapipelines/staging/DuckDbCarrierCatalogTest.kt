package co.datapipelines.staging

import co.datapipelines.typesystem.jdbc.DuckDbAuthorSqlGate
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Engine-driven spelling checks for the shared [DuckDbAuthorSqlGate] on locked staging leases.
 * The datasources module pins the reviewed function catalog for both callers. These tests ask
 * the pinned engine which call forms run, and require the shared guard to refuse each one.
 * Neighbouring spellings must remain refused by the engine; a driver upgrade requires review.
 * The sweep stops at U+2FFFF and has a non-vacuity floor for each carrier form.
 */
class DuckDbCarrierCatalogTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    @Timeout(SWEEP_TIMEOUT_SECONDS)
    fun `every text the pinned engine runs as a carrier call is refused by the gate, code points U+0001 to U+2FFFF`() {
        EngineUnderTest(scratch).use { engine ->
            val forms =
                codePointsUpTo(SWEEP_LAST_CODE_POINT).flatMap { cp ->
                    listOf(CarrierForm("query", false, cp), CarrierForm("query", true, cp), CarrierForm("query_table", false, cp))
                }
            val started = System.nanoTime()
            val accepted = engine.accepted(forms, THREADS) { it.text }
            val wallMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            val bypasses = accepted.filter { passesGate(it.text) }.toSet()
            println("carrier sweep: statements=${forms.size} threads=$THREADS wall_ms=$wallMs accepted=${accepted.size}")
            accepted.forEach { println("  engine ran ${it.label}; gate ${if (it in bypasses) "PASSED IT (bypass)" else "refused"}") }

            val byCarrier = bypasses.groupingBy { if (it.quoted) "${it.carrier} quoted" else it.carrier }.eachCount()
            withClue("${bypasses.size} carrier text(s) the engine runs pass the gate $byCarrier: ${bypasses.map { it.label }}") {
                bypasses shouldBe emptySet()
            }
            accepted.count { it.carrier == "query" } shouldBeGreaterThanOrEqual MEASURED_QUERY_FORMS
            accepted.count { it.carrier == "query_table" } shouldBeGreaterThanOrEqual MEASURED_SEPARATORS
        }
    }

    @Test
    fun `the guarded calls run in their plain spelling, so a spelling the engine resolved would be seen`() {
        EngineUnderTest(scratch).use { engine ->
            GUARDED_CALLS.forEach { (name, args) ->
                withClue(name) { engine.runs("SELECT * FROM $name$args") shouldBe true }
            }
            engine.runs("SELECT * FROM disable_logging()") shouldBe true
        }
    }

    @Test
    fun `class A - a unicode-escape identifier spelling a guarded name stays refused by the engine`() {
        val forms =
            listOf(
                "SELECT * FROM U&\"\\0071uery\"('SELECT 1')",
                "SELECT * FROM U&\"\\0071uery_table\"('t')",
                "SELECT * FROM U&\"\\+000071uery\"('SELECT 1')",
                "SELECT * FROM U&\"!0071uery\" UESCAPE '!'('SELECT 1')",
                "SELECT * FROM U&\"truncate_duckdb_\\006Cogs\"()",
                "SELECT * FROM U&\"disable_\\006Cogging\"()",
                "SELECT * FROM U&\"enable_\\006Cogging\"(level = 'info', storage = 'memory')",
            )
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(forms, THREADS) { it }
            withClue("class A: the engine now resolves unicode-escape identifiers: $resolved") { resolved shouldBe emptyList() }
        }
    }

    @Test
    fun `class B - a letter variant of a guarded name stays refused by the engine`() {
        val variants = letterVariants()
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(variants, THREADS) { it.second }.map { it.first }
            println("class B: ${variants.size} letter variants of ${GUARDED_CALLS.size} names, resolved ${resolved.size}")
            withClue("class B: the engine resolves letter variants ${resolved.take(LISTED)}") { resolved shouldBe emptyList() }
        }
        variants.size shouldBeGreaterThanOrEqual ORCHESTRATOR_LETTER_VARIANTS
    }

    @Test
    fun `class D - a separator inside a guarded name stays refused by the engine`() {
        val forms = separatorsInsideNames()
        EngineUnderTest(scratch).use { engine ->
            val resolved = engine.accepted(forms, THREADS) { it.second }.map { it.first }
            println("class D: ${forms.size} forms, resolved ${resolved.size}")
            withClue("class D: the engine strips a separator inside a name ${resolved.take(LISTED)}") { resolved shouldBe emptyList() }
        }
        // Non-vacuity: the 18 non-ASCII separators the engine accepts are all inserted.
        forms.size shouldBeGreaterThanOrEqual MEASURED_NON_ASCII_SEPARATORS * GUARDED_CALLS.keys.sumOf { it.length - 1 }
    }

    private fun passesGate(sql: String): Boolean = runCatching { DuckDbAuthorSqlGate.check(sql) }.isSuccess

    private companion object {
        /** Separator sweep parallelism: duplicates of one root, at most eight. */
        val THREADS = minOf(MAX_SWEEP_THREADS, Runtime.getRuntime().availableProcessors())
        const val MAX_SWEEP_THREADS = 8
        const val SWEEP_TIMEOUT_SECONDS = 180L
        const val NANOS_PER_MILLI = 1_000_000L
        const val LISTED = 20

        /** Measured on 1.5.5.1 by the lander and the orchestrator (2026-11-03): 5 ASCII + 18 non-ASCII. */
        const val MEASURED_SEPARATORS = 23
        const val MEASURED_NON_ASCII_SEPARATORS = 18
        const val MEASURED_QUERY_FORMS = 2 * MEASURED_SEPARATORS

        /** The orchestrator's class-B count over four of these six names (2026-11-03 probe). */
        const val ORCHESTRATOR_LETTER_VARIANTS = 2_918
    }
}
