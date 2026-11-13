package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class DeliveryStagingPreparationTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `dispatch reserves scratch without opening any database and unopened close releases it`() {
        val directory = scratch.resolve("not-created")
        val factory = duckFactory(directory)
        val dispatch = EngineDispatchingStagingFactory(H2StagingFactory(H2StagingProperties()), factory)
        val admission = dispatch.prepareDelivery(UUID.randomUUID(), RESERVATION)
        admission.reservedBytes shouldBe RESERVATION
        factory.budget.reservedBytes shouldBe RESERVATION
        Files.exists(directory) shouldBe false
        admission.close()
        admission.close()
        factory.budget.reservedBytes shouldBe 0L
        Files.exists(directory) shouldBe false
        shouldThrow<IllegalStateException> { admission.open() }
    }

    @Test
    fun `trusted initialization sees resource limits then authors receive a locked materialized database`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val admission = factory.prepareDelivery(id, RESERVATION)
        val staging =
            admission.open { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_setting('enable_external_access'), current_setting('lock_configuration')").use {
                        it.next() shouldBe true
                        it.getBoolean(1) shouldBe true
                        it.getBoolean(2) shouldBe false
                    }
                    statement.executeQuery("SELECT current_setting('memory_limit'), current_setting('max_temp_directory_size')").use {
                        it.next() shouldBe true
                        it.getString(1) shouldBe "256.0 MiB"
                        it.getString(2) shouldBe "64.0 MiB"
                    }
                    statement.execute("CREATE TABLE delivery AS SELECT 42 AS answer")
                }
            }
        try {
            admission.close()
            factory.budget.reservedBytes shouldBe RESERVATION
            staging.scalarText("SELECT answer FROM delivery") shouldBe "42"
            staging.scalarText("SELECT current_setting('lock_configuration')") shouldBe "true"
            staging.scalarText("SELECT current_setting('enable_external_access')") shouldBe "false"
            shouldThrow<IllegalStateException> { admission.open() }
        } finally {
            staging.close()
        }
        factory.budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    @Test
    fun `initializer SQL errors disclose no secret and close root files and reservation`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val root = AtomicReference<Connection>()
        val failure =
            shouldThrow<DatapipelinesException> {
                factory.prepareDelivery(id, RESERVATION).open {
                    root.set(it)
                    throw SQLException("WRITER_SECRET_SENTINEL_799")
                }
            }
        failure.code shouldBe StagingErrorCodes.CREATION_FAILED
        failure.message.orEmpty() shouldNotContain "WRITER_SECRET_SENTINEL_799"
        failure.cause shouldBe null
        root.get().isClosed shouldBe true
        factory.budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    @Test
    fun `typed initialization refusal retains its code while releasing admission`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val refusal = DatapipelinesException(StagingErrorCodes.SCRATCH_LIMIT_EXCEEDED, "Sanitized refusal")
        shouldThrow<DatapipelinesException> {
            factory.prepareDelivery(id, RESERVATION).open { throw refusal }
        } shouldBe refusal
        factory.budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    @Test
    fun `trusted initialization cannot hand out a database past its scratch reservation`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val files = DuckDbStagingFiles(scratch, id)
        val failure =
            shouldThrow<StagingScratchLimitException> {
                factory.prepareDelivery(id, MIB).open {
                    Files.createDirectories(files.tempDir)
                    Files.write(files.tempDir.resolve("trusted-input"), ByteArray((2 * MIB).toInt()))
                }
            }
        requireNotNull(failure.measuredBytes) shouldBeGreaterThan MIB
        factory.budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    @Test
    fun `initializer cancellation is preserved and releases all resources`() {
        val factory = duckFactory(scratch)
        val id = UUID.randomUUID()
        val cancelled = CancellationException("cancelled")
        shouldThrow<CancellationException> {
            factory.prepareDelivery(id, RESERVATION).open { throw cancelled }
        } shouldBe cancelled
        factory.budget.reservedBytes shouldBe 0L
        pathsOf(scratch, id).shouldBeEmpty()
    }

    private companion object {
        const val RESERVATION = 64 * MIB
    }
}
