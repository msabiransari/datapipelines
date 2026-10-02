package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * #167 — the probe's timeout ceiling is the executor's node statement timeout, per dialect, and
 * the result reports the timeout the statement ran under.
 *
 * Observed by the ENGINE, never modelled: H2 turns a statement's `queryTimeout` into the
 * session's `QUERY_TIMEOUT` (milliseconds), and the probe's own SELECT reads it back from
 * `INFORMATION_SCHEMA.SETTINGS` on the same lease — so every case asserts the number the driver
 * was handed AND the number the payload reports, exactly, never "at most the ceiling".
 */
class SqlProbeTimeoutCeilingTest {
    private val registry = mockk<DatasourceRegistry>()

    private fun wireDatasource(): Datasource {
        val datasource = Fixtures.h2(name = "h2-ceiling", jdbcUrl = "jdbc:h2:mem:sqlprobe_ceiling")
        every { registry.poolFor(datasource) } returns JdbcUrlPool(datasource.jdbcUrl, datasource.name)
        return datasource
    }

    /** The session's statement timeout in SECONDS, as H2 holds it while the probe's statement runs. */
    private fun observedTimeoutSeconds(rows: QueryRows): Int =
        (
            rows.rows
                .single()
                .values
                .single() as String
        ).toInt() / MILLIS

    /** Probes the H2 datasource asking for [requested] seconds; answers (engine-observed, reported). */
    private fun probed(
        probe: SqlProbe,
        requested: Int,
    ): Pair<Int, Int> {
        val result = probe.probe(wireDatasource(), SESSION_TIMEOUT_SQL, timeoutSeconds = requested)
        return observedTimeoutSeconds(result.rows) to result.timeoutSeconds
    }

    @Test
    fun `a request under the ceiling reaches the statement unchanged`() {
        probed(SqlProbe(registry, nodeQueryTimeoutSeconds = 60), requested = 45) shouldBe (45 to 45)
    }

    @Test
    fun `a request over the ceiling clamps to the node's statement timeout`() {
        probed(SqlProbe(registry, nodeQueryTimeoutSeconds = 60), requested = 90) shouldBe (60 to 60)
    }

    @Test
    fun `a request below one clamps up to one second`() {
        probed(SqlProbe(registry, nodeQueryTimeoutSeconds = 60), requested = 0) shouldBe (1 to 1)
    }

    @Test
    fun `the dialect's own node timeout is the ceiling when the operator set one`() {
        val lower = SqlProbe(registry, nodeQueryTimeoutSeconds = 60, nodeQueryTimeoutSecondsByDialect = mapOf(Dialect.H2 to 20))
        // The shipped shape: LAKE raised to 180 must not lift any other dialect's ceiling.
        val otherDialect = SqlProbe(registry, nodeQueryTimeoutSeconds = 60, nodeQueryTimeoutSecondsByDialect = mapOf(Dialect.LAKE to 180))

        assertAll(
            { probed(lower, requested = 45) shouldBe (20 to 20) },
            { probed(otherDialect, requested = 90) shouldBe (60 to 60) },
            { lower.maxTimeoutSecondsFor(Dialect.H2) shouldBe 20 },
            { otherDialect.maxTimeoutSecondsFor(Dialect.LAKE) shouldBe 180 },
            { otherDialect.maxTimeoutSecondsFor(Dialect.POSTGRES) shouldBe 60 },
        )
    }

    @Test
    fun `the argument bound is the highest ceiling any dialect gets`() {
        assertAll(
            { SqlProbe(registry, nodeQueryTimeoutSeconds = 60).maxTimeoutSeconds shouldBe 60 },
            { SqlProbe(registry, 60, mapOf(Dialect.LAKE to 180)).maxTimeoutSeconds shouldBe 180 },
            { SqlProbe(registry, 60, mapOf(Dialect.H2 to 20)).maxTimeoutSeconds shouldBe 60 },
        )
    }

    @Test
    fun `the scratch twin clamps to the H2 ceiling and reports it`() {
        val probe = SqlProbe(registry, nodeQueryTimeoutSeconds = 60, nodeQueryTimeoutSecondsByDialect = mapOf(Dialect.H2 to 50))

        val under = probe.probeScratch(SESSION_TIMEOUT_SQL, timeoutSeconds = 45).shouldBeInstanceOf<ScratchProbeOutcome.Rows>().result
        val over = probe.probeScratch(SESSION_TIMEOUT_SQL, timeoutSeconds = 90).shouldBeInstanceOf<ScratchProbeOutcome.Rows>().result

        assertAll(
            { (observedTimeoutSeconds(under.rows) to under.timeoutSeconds) shouldBe (45 to 45) },
            { (observedTimeoutSeconds(over.rows) to over.timeoutSeconds) shouldBe (50 to 50) },
        )
    }

    @Test
    fun `a non-positive ceiling is refused at construction`() {
        assertAll(
            { shouldThrow<IllegalArgumentException> { SqlProbe(registry, nodeQueryTimeoutSeconds = 0) } },
            { shouldThrow<IllegalArgumentException> { SqlProbe(registry, 60, mapOf(Dialect.LAKE to 0)) } },
        )
    }

    private companion object {
        const val SESSION_TIMEOUT_SQL = "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'QUERY_TIMEOUT'"
        const val MILLIS = 1000
    }
}
