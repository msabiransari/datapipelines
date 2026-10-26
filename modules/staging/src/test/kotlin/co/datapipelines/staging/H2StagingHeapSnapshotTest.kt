package co.datapipelines.staging

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryUsage
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A heap resize must not turn two individually valid readings into a negative budget sample (#361). */
class H2StagingHeapSnapshotTest {
    @Test
    fun `stats reads used bytes from one snapshot while committed memory grows`() {
        val meter = growingHeap(USED_BYTES)
        val staging = staging(meter.bean)
        try {
            runBlocking { staging.stats() }.memoryUsedBytes shouldBe USED_BYTES
            meter.reads.get() shouldBe 1
        } finally {
            staging.close()
        }
    }

    @Test
    fun `growing committed memory cannot bypass the staging budget`() {
        val meter = growingHeap(USED_BYTES)
        val staging = staging(meter.bean)
        try {
            val refusal =
                SourceDb().use { source ->
                    shouldThrow<StagingMemoryLimitException> {
                        runBlocking { staging.stage(source.query("SELECT 1 AS id"), "over_budget", Dialect.H2) }
                    }
                }
            refusal.memoryUsedBytes shouldBe USED_BYTES
            refusal.maxMemoryMb shouldBe BUDGET_MB
        } finally {
            staging.close()
        }
    }

    @Test
    fun `a below-budget snapshot permits a real staged row`() {
        val meter = growingHeap(LOW_USED_BYTES)
        val staging = staging(meter.bean)
        try {
            SourceDb().use { source ->
                runBlocking { staging.stage(source.query("SELECT 1 AS id"), "within_budget", Dialect.H2) }.rowsStaged shouldBe 1L
            }
            runBlocking { staging.stats() }.memoryUsedBytes shouldBe LOW_USED_BYTES
        } finally {
            staging.close()
        }
    }

    private data class Meter(
        val bean: MemoryMXBean,
        val reads: AtomicInteger,
    )

    private fun growingHeap(usedBytes: Long): Meter {
        val reads = AtomicInteger()
        val bean = mockk<MemoryMXBean>()
        every { bean.heapMemoryUsage } answers {
            val shift = reads.getAndIncrement().coerceAtMost(MAX_SAMPLE_SHIFT)
            MemoryUsage(-1L, usedBytes, INITIAL_COMMITTED_BYTES * (1L shl shift), -1L)
        }
        return Meter(bean, reads)
    }

    /** Same admin-session harness as stagingOverConnections; production identity is covered by the privilege suite. */
    private fun staging(heap: MemoryMXBean): H2Staging {
        val id = UUID.randomUUID()
        val properties = H2StagingProperties(maxMemoryMb = BUDGET_MB)
        val open = { DriverManager.getConnection(stagingUrl(id, properties), "sa", "") }
        val pool = H2ConnectionPool(id, open(), open, properties.maxConnections)
        return H2Staging(id, pool, properties, heap)
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val BUDGET_MB = 1L
        const val USED_BYTES = 8 * MIB
        const val LOW_USED_BYTES = MIB / 2
        const val INITIAL_COMMITTED_BYTES = 16 * MIB
        const val MAX_SAMPLE_SHIFT = 20
    }
}
