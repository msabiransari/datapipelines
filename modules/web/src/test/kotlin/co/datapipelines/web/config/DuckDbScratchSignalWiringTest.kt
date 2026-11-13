package co.datapipelines.web.config

import co.datapipelines.lake.LakeProperties
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingFactory
import co.datapipelines.staging.StagingPreparation
import co.datapipelines.typesystem.StagingEngine
import co.datapipelines.web.health.ScratchPathKind
import co.datapipelines.web.health.ScratchSpaceReader
import co.datapipelines.web.metrics.WebMetrics
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.actuate.health.Status
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * #806 sections B and C — the scratch signal over the PRODUCTION staging factory (`stagingFactoryFor`, the real
 * [co.datapipelines.staging.ScratchBudget]) and a controllable [ScratchSpaceReader]: each gauge follows the figure
 * it reads at every read, `NaN` stands for "unknown" and never `0`, the indicator's threshold is one default
 * reservation, and a context whose staging factory has no DuckDB delegate fails instead of degrading.
 */
class DuckDbScratchSignalWiringTest {
    @TempDir
    lateinit var scratch: Path

    private val configuration = WebSurfaceConfiguration()
    private val registry = SimpleMeterRegistry()

    /** A directory that exists, is writable, and whose store reports [usable] (or throws while [failing]). */
    private class ControllableReader : ScratchSpaceReader {
        @Volatile var usable = 1L shl 40

        @Volatile var failing = false

        override fun kind(path: Path): ScratchPathKind = ScratchPathKind.DIRECTORY

        override fun canWrite(directory: Path): Boolean = true

        override fun usableBytes(path: Path): Long = if (failing) throw IOException("file store unreadable") else usable
    }

    private val reader = ControllableReader()
    private val factory by lazy { stagingFactoryFor(StagingH2Properties(), LakeProperties.Admission(), scratch) }

    private fun bind() {
        WebMetrics(registry).bindScratch(duckDbFactoryOf(factory).budget, configuration.duckDbScratchSpace(factory, reader))
    }

    private fun gauge(name: String): Double = registry.get(name).gauge().value()

    @Test
    fun `reserved rises by a reservation and falls back on its release, and capacity is the aggregate`() {
        bind()
        gauge(WebMetrics.SCRATCH_CAPACITY) shouldBe (20L shl 30).toDouble()
        gauge(WebMetrics.SCRATCH_RESERVED) shouldBe 0.0
        val preparation: StagingPreparation = factory.prepareDelivery(UUID.randomUUID(), RESERVATION)
        gauge(WebMetrics.SCRATCH_RESERVED) shouldBe RESERVATION.toDouble()
        preparation.close()
        gauge(WebMetrics.SCRATCH_RESERVED) shouldBe 0.0
    }

    @Test
    fun `leftover is NaN before the sweep and the counted bytes after it - and is never added to reserved`() {
        Files.write(scratch.resolve("left-by-a-crash.bin"), ByteArray(LEFTOVER))
        bind()
        gauge(WebMetrics.SCRATCH_LEFTOVER).isNaN() shouldBe true
        factory.prepareDelivery(UUID.randomUUID(), RESERVATION).use {
            gauge(WebMetrics.SCRATCH_LEFTOVER) shouldBe LEFTOVER.toDouble()
            gauge(WebMetrics.SCRATCH_RESERVED) shouldBe RESERVATION.toDouble()
        }
        gauge(WebMetrics.SCRATCH_LEFTOVER) shouldBe LEFTOVER.toDouble()
    }

    @Test
    fun `leftover reads 0 after a sweep that counted nothing - told apart from not yet swept`() {
        bind()
        gauge(WebMetrics.SCRATCH_LEFTOVER).isNaN() shouldBe true
        factory.prepareDelivery(UUID.randomUUID(), RESERVATION).close()
        gauge(WebMetrics.SCRATCH_LEFTOVER) shouldBe 0.0
    }

    @Test
    fun `usable follows the reader at every read, is NaN while it throws, then the number again`() {
        bind()
        gauge(WebMetrics.SCRATCH_USABLE) shouldBe (1L shl 40).toDouble()
        reader.usable = 12_345L
        gauge(WebMetrics.SCRATCH_USABLE) shouldBe 12_345.0
        reader.failing = true
        gauge(WebMetrics.SCRATCH_USABLE).isNaN() shouldBe true
        reader.failing = false
        reader.usable = 67_890L
        gauge(WebMetrics.SCRATCH_USABLE) shouldBe 67_890.0
    }

    @Test
    fun `the four gauges carry no tag and count bytes`() {
        bind()
        listOf(WebMetrics.SCRATCH_CAPACITY, WebMetrics.SCRATCH_RESERVED, WebMetrics.SCRATCH_LEFTOVER, WebMetrics.SCRATCH_USABLE)
            .forEach { name ->
                val id = registry.get(name).gauge().id
                id.tags shouldBe emptyList()
                id.baseUnit shouldBe "bytes"
            }
    }

    @Test
    fun `the indicator bean watches the factory's scratch directory with one default reservation as its threshold`() {
        val space = configuration.duckDbScratchSpace(factory, reader)
        space.directory shouldBe scratch
        val indicator = configuration.duckDbScratchHealthIndicator(factory, space)
        reader.usable = DEFAULT_RESERVATION
        indicator.health().status shouldBe Status.UP
        reader.usable = DEFAULT_RESERVATION - 1
        indicator.health().status shouldBe Status.OUT_OF_SERVICE
    }

    @Test
    fun `the DuckDB factory is the dispatching factory's delegate, and a factory without one fails the wiring`() {
        duckDbFactoryOf(factory) shouldBeSameInstanceAs factory.duckDb
        duckDbFactoryOf(factory.duckDb) shouldBeSameInstanceAs factory.duckDb
        val foreign =
            object : StagingFactory {
                override fun create(
                    executionId: UUID,
                    engine: StagingEngine,
                ): Staging = error("not used")
            }
        shouldThrow<IllegalStateException> { configuration.duckDbScratchSpace(foreign, reader) }.message.orEmpty() shouldContain
            "needs the DuckDB staging factory"
    }

    private companion object {
        const val RESERVATION = 5L shl 20
        const val LEFTOVER = 1234

        /** `staging-memory-limit × staging-expansion-factor` at the shipped defaults (1 GiB × 3). */
        const val DEFAULT_RESERVATION = 3L shl 30
    }
}
