package co.datapipelines.staging

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The configuration read-back a new DuckDB staging database must pass before it is handed out
 * (#795 round two, review F8; staging.md §8.5), driven against the pinned engine: a database with
 * one of the bounding SETs skipped, then locked, fails exactly that check.
 *
 * That the factory runs the read-back on every open is the falsification's other half (handback):
 * with one SET removed from the factory's list, every open in the module is `creation_failed`.
 */
class DuckDbStagingReadBackTest {
    @TempDir
    lateinit var scratch: Path

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["memory_limit", "max_temp_directory_size", "lock_configuration"])
    fun `a database with one bounding SET skipped fails exactly that check`(skipped: String) {
        failuresSkipping(skipped) shouldBe listOf(skipped)
    }

    @Test
    fun `external access left on fails its check`() {
        failuresSkipping("enable_external_access") shouldBe listOf("enable_external_access off")
    }

    @Test
    fun `a database with every SET applied passes every check, at a size that is no power of two`() {
        // 100,000,000 B reads back as 95.3 MiB: the comparison is the engine's own formatting.
        failuresSkipping(null, memoryLimitBytes = 100_000_000L, reservationBytes = 400_000_000L).shouldBeEmpty()
    }

    @Test
    fun `a database the factory opens meets the read-back from an author lease too`() {
        val factory = duckFactory(scratch)
        factory.open().use { staging ->
            val sql = DuckDbConfigurationReadBack.sql(factory.properties.memoryLimitBytes, factory.properties.defaultReservationBytes)
            val failed = runBlocking { staging.withQuery(sql, DuckDbConfigurationReadBack::failures) }
            withClue("the read-back over the factory's own database") { failed.shouldBeEmpty() }
        }
    }

    /**
     * Opens a raw database on the pinned driver, applies the factory's bounding SETs except
     * [skipped], locks it (unless the lock is the one skipped) and returns the read-back's failures.
     */
    private fun failuresSkipping(
        skipped: String?,
        memoryLimitBytes: Long = MEMORY_LIMIT_BYTES,
        reservationBytes: Long = RESERVATION_BYTES,
    ): List<String> {
        val settings =
            listOf(
                "memory_limit" to "SET memory_limit = '${memoryLimitBytes}B'",
                "temp_directory" to "SET temp_directory = '${scratch.resolve("readback.tmp")}'",
                "max_temp_directory_size" to "SET max_temp_directory_size = '${reservationBytes}B'",
                "enable_external_access" to "SET enable_external_access = false",
                "lock_configuration" to "SET lock_configuration = true",
            )
        require(skipped == null || settings.any { (name, _) -> name == skipped }) { "$skipped is not one of the SETs" }
        val applied = settings.filter { (name, _) -> name != skipped }
        val readBack = DuckDbConfigurationReadBack.sql(memoryLimitBytes, reservationBytes)
        return DriverManager.getConnection("jdbc:duckdb:${scratch.resolve("readback.duckdb")}").use { c ->
            c.createStatement().use { st -> applied.forEach { (_, sql) -> st.execute(sql) } }
            c.createStatement().use { st -> st.executeQuery(readBack).use(DuckDbConfigurationReadBack::failures) }
        }
    }

    private companion object {
        const val MEMORY_LIMIT_BYTES = 64L shl 20
        const val RESERVATION_BYTES = 256L shl 20
    }
}
