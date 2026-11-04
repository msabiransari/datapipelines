package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TempdbSettings
import co.datapipelines.staging.DuckDbStagingFactory
import co.datapipelines.staging.DuckDbStagingProperties
import co.datapipelines.staging.EngineDispatchingStagingFactory
import co.datapipelines.staging.H2StagingFactory
import co.datapipelines.staging.H2StagingProperties
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingFactory
import co.datapipelines.typesystem.StagingEngine
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name

/**
 * The create-then-assign window (#795 review F5; dag-executor.md §9 "Lifetime"): a staging database
 * the factory opens while the execution is being cancelled is still closed by the executor's
 * `finally`, on both engines.
 *
 * The window is forced, never raced: the factory blocks inside `create` on the executor's pool until
 * the test has cancelled the execution, then returns a real, open database. `withContext` hands that
 * result back "with a prompt cancellation guarantee" — the cancelled caller gets
 * `CancellationException` and the result is discarded (kotlinx.coroutines `withContext` KDoc, pinned
 * 1.8.1, the same text in the tests' 1.10.2) — so only a database recorded inside the block can reach
 * the cleanup.
 */
class StagingCreateCancelTest {
    @TempDir
    lateinit var scratch: Path

    @ParameterizedTest(name = "{0}")
    @EnumSource(StagingEngine::class)
    @Timeout(value = TEST_BOUND_SECONDS, unit = TimeUnit.SECONDS)
    fun `a staging opened while the execution is cancelled is closed, not leaked`(engine: StagingEngine) {
        val duckDb = DuckDbStagingFactory(duckProps())
        val factory = GatedFactory(EngineDispatchingStagingFactory(H2StagingFactory(H2StagingProperties()), duckDb))
        val nodes = listOf(Fixtures.node("read"))
        val request = Fixtures.request(Fixtures.pipeline(nodes, settings = PipelineSettings(tempdb = TempdbSettings(engine = engine))))

        try {
            ExecutorHarness(templateEngine = Fixtures.templateEngine(mapOf("read" to "SELECT 1 AS n")), stagingFactory = factory).use { h ->
                runBlocking {
                    // A dispatcher of its own, so this thread can wait on the latch while the execution runs.
                    val execution = launch(Dispatchers.Default) { h.executor.execute(request) }
                    if (!factory.entered.await(STEP_BOUND_SECONDS, TimeUnit.SECONDS)) fail("the executor never called create")
                    execution.cancel()
                    factory.release.countDown()
                    withTimeout(TimeUnit.SECONDS.toMillis(STEP_BOUND_SECONDS)) { execution.join() }
                }
            }

            val opened = factory.opened.get() ?: fail("create never returned a database")
            withClue("the database create returned to the cancelled execution was closed") { opened.closed.get() shouldBe true }
            if (engine == StagingEngine.DUCKDB) {
                withClue("its scratch reservation") { duckDb.budget.reservedBytes shouldBe 0L }
                withClue("its files") { filesOf(opened.executionId).shouldBeEmpty() }
            }
        } finally {
            // A red run leaked the database: close it here so the temporary directory can go.
            val leaked = factory.opened.get()
            if (leaked != null && !leaked.closed.get()) leaked.close()
        }
    }

    private fun duckProps() =
        DuckDbStagingProperties(
            scratchDir = scratch,
            memoryLimitBytes = MEMORY_LIMIT_BYTES,
            expansionFactor = EXPANSION_FACTOR,
            scratchCapacityBytes = MEMORY_LIMIT_BYTES * EXPANSION_FACTOR * 2,
            threads = 1,
        )

    private fun filesOf(executionId: UUID): List<String> =
        Files.list(scratch).use { s -> s.map { it.name }.filter { it.startsWith("exec_$executionId") }.toList() }

    /** Blocks inside [create] until [release]; records what it returned. */
    private class GatedFactory(
        private val real: StagingFactory,
    ) : StagingFactory {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = AtomicReference<RecordingStaging?>()

        override fun create(
            executionId: UUID,
            engine: StagingEngine,
        ): Staging {
            entered.countDown()
            check(release.await(STEP_BOUND_SECONDS, TimeUnit.SECONDS)) { "the test never released create" }
            return RecordingStaging(real.create(executionId, engine)).also { opened.set(it) }
        }
    }

    /** The real database, with its [close] observed. */
    private class RecordingStaging(
        private val real: Staging,
    ) : Staging by real {
        val closed = AtomicBoolean()

        override fun close() {
            closed.set(true)
            real.close()
        }
    }

    private companion object {
        const val TEST_BOUND_SECONDS = 60L
        const val STEP_BOUND_SECONDS = 20L
        const val MEMORY_LIMIT_BYTES = 64L shl 20
        const val EXPANSION_FACTOR = 4
    }
}
