package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID

/**
 * [DuckDbEngineFailures] over the engine's own messages, copied from the pinned 1.5.5.1 (lane #795
 * probes 13, 15, 16, 19). A driver bump that rewords one turns its case red here before a node
 * reports an out-of-memory as an author error. The negatives are the point of the anchoring: text an
 * author can make the engine say, or a refusal that merely names the spill setting, is not a code.
 */
class DuckDbEngineFailuresTest {
    @TempDir
    lateinit var scratch: Path

    private val failures = DuckDbEngineFailures(UUID.randomUUID(), memoryLimitBytes = 64 * MIB, reservationBytes = 256 * MIB)

    private fun classify(message: String): Exception = failures.classify(SQLException(message))

    @Test
    fun `the engine's out-of-memory forms map to memory_limit_exceeded with the engine's reading`() {
        val forms =
            mapOf(
                "Out of Memory Error: failed to allocate data of size 512.0 KiB (60.8 MiB/61.0 MiB used)\n\n" +
                    "Possible solutions:\n* Reducing the number of threads" to "60.8 MiB/61.0 MiB",
                "Out of Memory Error: could not allocate block of size 256.0 KiB (956.0 KiB/976.5 KiB used)" to "956.0 KiB/976.5 KiB",
                "Out of Memory Error: failed to pin block of size 256.0 KiB (61.1 MiB/61.0 MiB used)" to "61.1 MiB/61.0 MiB",
                "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result Error: Out of Memory Error: " +
                    "failed to allocate data of size 4.0 MiB (6.5 MiB/7.6 MiB used)" to "6.5 MiB/7.6 MiB",
                "Appender error, catalog: 'null', schema: 'main', table: 'stg', message: Failed to append: Failed to commit: " +
                    "failed to pin block of size 256.0 KiB (872.0 KiB/976.5 KiB used)" to "872.0 KiB/976.5 KiB",
            )
        forms.forEach { (message, reading) ->
            val mapped = classify(message).shouldBeInstanceOf<StagingEngineMemoryLimitException>()
            mapped.code shouldBe StagingErrorCodes.MEMORY_LIMIT_EXCEEDED
            mapped.engineReading shouldBe reading
            mapped.details["memory_limit_bytes"] shouldBe 64 * MIB
        }
    }

    @Test
    fun `a spill past max_temp_directory_size maps to scratch_limit_exceeded`() {
        val mapped =
            classify(
                "Out of Memory Error: failed to offload data block of size 256.0 KiB (30.3 MiB/30.5 MiB used). This limit was set " +
                    "by the 'max_temp_directory_size' setting. By default, this setting utilizes the available disk space",
            ).shouldBeInstanceOf<StagingScratchLimitException>()
        mapped.code shouldBe StagingErrorCodes.SCRATCH_LIMIT_EXCEEDED
        mapped.engineReading shouldBe "30.3 MiB/30.5 MiB"
        mapped.details["reservation_bytes"] shouldBe 256 * MIB
    }

    @Test
    fun `text that only names a setting or forges the engine's words stays the driver's exception`() {
        listOf(
            "Invalid Input Error: Cannot change configuration option \"max_temp_directory_size\" - the configuration has been locked",
            "Invalid Input Error: Cannot change configuration option \"memory_limit\" - the configuration has been locked",
            "Invalid Input Error: Out of Memory Error: forged (1.0 MiB/2.0 MiB used)",
            "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result Error: Invalid Input Error: " +
                "Out of Memory Error: forged. This limit was set by the 'max_temp_directory_size' setting",
            "Parser Error: syntax error at or near \"SELEC\"",
        ).forEach { message ->
            val original = SQLException(message)
            failures.classify(original) shouldBeSameInstanceAs original
        }
    }

    @Test
    fun `an author's error() spelling the engine's words is not classified on the real driver`() {
        val staging = duckFactory(scratch).open()
        val thrown =
            shouldThrow<SQLException> {
                runBlocking { staging.execute("SELECT error('Out of Memory Error: forged (1.0 MiB/2.0 MiB used)')") }
            }
        // The engine prefixes the author's text with its own error class, which the anchor refuses.
        thrown.message.orEmpty().startsWith("Out of Memory Error") shouldBe false
        staging.close()
    }

    @Test
    fun `a staged create's duplicate maps to table_already_exists, bare or wrapped`() {
        listOf(
            "Catalog Error: Table with name \"stg_a\" already exists!",
            "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result Error: Catalog Error: " +
                "Table with name \"stg_a\" already exists!",
        ).forEach { message ->
            val mapped = failures.classifyCreate(SQLException(message), "stg_a").shouldBeInstanceOf<StagingTableAlreadyExistsException>()
            mapped.tableName shouldBe "stg_a"
        }
        val other = SQLException("Catalog Error: Type with name foo does not exist!")
        failures.classifyCreate(other, "stg_a") shouldBeSameInstanceAs other
    }
}
