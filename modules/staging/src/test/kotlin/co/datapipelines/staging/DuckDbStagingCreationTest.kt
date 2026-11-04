package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * What a DuckDB staging database's creation refuses, and what its refusal may say (#795 round two,
 * review F6–F8; staging.md §8.5).
 */
class DuckDbStagingCreationTest {
    @TempDir
    lateinit var scratch: Path

    @Test
    fun `a failed open says nothing of the scratch path to the caller, even when the driver's message does`() {
        val props = duckProps(scratch)
        val thrownByDriver = AtomicReference<String>()
        val leaky =
            DuckDbStagingFactory(props, ScratchBudget(props.scratchCapacityBytes)) { url, _ ->
                val message = "IO Error: Cannot open database \"${url.removePrefix("jdbc:duckdb:")}\": Permission denied"
                thrownByDriver.set(message)
                throw SQLException(message)
            }
        val executionId = UUID.randomUUID()

        val thrown = shouldThrow<DatapipelinesException> { leaky.create(executionId, StagingEngine.DUCKDB) }

        // Non-vacuous: the driver's own message did carry the path.
        thrownByDriver.get() shouldContain scratch.toString()
        thrown.code shouldBe StagingErrorCodes.CREATION_FAILED
        thrown.message shouldContain executionId.toString()
        // Everything the executor transports to the caller: the message, the details and every
        // message along the cause chain (057, error-detail: full).
        callerVisibleText(thrown) shouldNotContain scratch.toString()
        leaky.budget.reservedBytes shouldBe 0L
    }

    private fun callerVisibleText(e: DatapipelinesException): String =
        generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { "${it.javaClass.name}: ${it.message}" } + " | " + e.details
}
