package co.datapipelines.lake.delivery

import ch.qos.logback.classic.Logger
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.append.LakeAppendAutoConfiguration
import co.datapipelines.lake.append.LakeAppendHeartbeat
import co.datapipelines.lake.append.LakeAppendRuntime
import co.datapipelines.lake.definitions.LakeApplyState
import co.datapipelines.lake.settle.LakeMetadataRead
import co.datapipelines.lake.settle.LakeMetadataReads
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.lake.settle.SettleBudget
import co.datapipelines.lake.settle.SettleDeferCause
import co.datapipelines.pipeline.DatasourceRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.PlatformTransactionManager
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The metadata read's other ends (record §8b, D4): the sweep's line counts each deferral by its cause, a failed read
 * refuses no successor, and the thread is destroyed with the context, after the scheduler has stopped.
 */
class LakeMetadataReadsIntegrationTest {
    private val h = DeliverySettlementHarness()
    private val lines = SnapshotListAppender()
    private val settleLog = LoggerFactory.getLogger(LakeSettlementSweep::class.java.packageName) as Logger

    @BeforeEach
    fun clean() {
        h.reset()
        settleLog.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        settleLog.detachAppender(lines)
        h.close()
    }

    @Test
    fun `a table its spent budget stopped counts under the budget cause alone`() {
        h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson())
        var checks = 0

        h.stallableSweep(io, budgets = { h.budget { ++checks >= VERIFICATION_GATE } }).runOnce()

        sweepLine() shouldContain "deferred_budget=1 deferred_stuck=0 deferred_failed=0"
        h.applyState() shouldBe LakeApplyState.APPLYING
    }

    @Test
    fun `a row whose conclusion keeps failing counts its table as failed`() {
        val row = h.backlog(1).single()
        // The attempt is already terminal, so the ledger's conclusion refuses and rolls back.
        h.jdbc.update("UPDATE lake_delivery_attempts SET outcome = 'FAILED', ended_at = now() WHERE operation_id = ?", row.operationId)

        h.sweep().runOnce()

        sweepLine() shouldContain "deferred_budget=0 deferred_stuck=0 deferred_failed=1"
    }

    @Test
    fun `a read the store fails counts as failed, keeps the row and refuses no successor`() {
        h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.FAIL)
        val sweep = h.stallableSweep(io)

        sweep.runOnce()

        sweepLine() shouldContain "deferred_budget=0 deferred_stuck=0 deferred_failed=1"
        lines.messages().single { it.startsWith("event=lake.settle.deferred ") } shouldContain "table_id=${h.tableId}"
        h.applyState() shouldBe LakeApplyState.APPLYING
        h.jdbc.queryForObject("SELECT count(*) FROM lake_leases", Int::class.java) shouldBe 0

        io.mode = ScriptedFileIo.Mode.HEALTHY
        sweep.runOnce()
        h.applyState() shouldBe LakeApplyState.APPLIED
    }

    @Test
    fun `a table the catalog does not know sends no read and throws, as the catalog's own load did`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson())
        val otherCatalog = LakeMetadataReads(h.named, "no_such_catalog") { io }

        shouldThrow<IllegalStateException> { read(otherCatalog, h.budget { false }) }

        io.streamsEntered.get() shouldBe 0
        otherCatalog.close()
    }

    @Test
    fun `an abandoned read that ends in failure reports its cause when a later check finds it over`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val reads = reads(io)

        read(reads, SettleBudget.startingNow(h.leases(), Duration.ofMillis(SHORT_MILLIS))) shouldBe
            LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
        reads.stuck().shouldBeTrue()
        lines.messages().none { it.startsWith(ENDED) }.shouldBeTrue()
        withClue("the class itself refuses a second read, whatever its caller checked") {
            read(reads, h.budget { false }) shouldBe LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
            io.streamsEntered.get() shouldBe 1
        }

        io.mode = ScriptedFileIo.Mode.FAIL
        io.release()
        withClue("the read's thread ends once the store answers") { awaitOver(reads).shouldBeTrue() }

        val ended = lines.messages().single { it.startsWith(ENDED) }
        ended shouldContain "table_id=${h.tableId}"
        ended shouldNotContain "cause=none"
        reads.stuck().shouldBeFalse()
    }

    @Test
    fun `closing stops an idle thread and closes the store client`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson())
        val reads = reads(io)
        read(reads, h.budget { false })
        val reader = requireNotNull(io.readerThread)

        reads.close()

        reader.join(JOIN_MILLIS)
        reader.isAlive.shouldBeFalse()
        io.closes.get() shouldBe 1
    }

    @Test
    fun `closing does not wait for a stuck read, whose daemon thread cannot hold the JVM`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val reads = reads(io)
        read(reads, SettleBudget.startingNow(h.leases(), Duration.ofMillis(SHORT_MILLIS)))
        val reader = requireNotNull(io.readerThread)

        val started = System.nanoTime()
        reads.close()

        Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeLessThan JOIN_MILLIS
        reader.isDaemon.shouldBeTrue()
        reader.isAlive.shouldBeTrue()
        io.release()
        reader.join(JOIN_MILLIS)
        reader.isAlive.shouldBeFalse()
    }

    @Test
    fun `a client never built is not built to be closed`() {
        val built = AtomicInteger()
        val reads =
            LakeMetadataReads(h.named, DeliverySettlementHarness.LAKE) {
                built.incrementAndGet()
                ScriptedFileIo(ByteArray(0))
            }

        reads.close()

        built.get() shouldBe 0
    }

    @Test
    fun `the context destroys the runtime's read after the lifecycle beans that run the tick have stopped`() {
        h.applyingWithEvidence()
        val io = ScriptedFileIo(h.metadataJson())
        val catalogs = mockk<LakeCatalogs>()
        every { catalogs.metadataFileIo() } returns io
        val closesAtSchedulerStop = AtomicInteger(NOT_STOPPED)

        contextRunner(catalogs, stopping = { closesAtSchedulerStop.set(io.closes.get()) }).run { context ->
            context.getBean(LakeAppendRuntime::class.java).sweep.runOnce()
            h.applyState() shouldBe LakeApplyState.APPLIED
            requireNotNull(io.readerThread).isAlive.shouldBeTrue()
        }

        closesAtSchedulerStop.get() shouldBe 0
        io.closes.get() shouldBe 1
        val reader = requireNotNull(io.readerThread)
        reader.join(JOIN_MILLIS)
        reader.isAlive.shouldBeFalse()
    }

    private fun contextRunner(
        catalogs: LakeCatalogs,
        stopping: () -> Unit,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withPropertyValues("${LakeProperties.PREFIX}.enabled=true")
            .withUserConfiguration(LakeAppendAutoConfiguration::class.java)
            .withBean(DataSource::class.java, { DelegatingDataSource(LakeTestDb.dataSource) })
            .withBean(PlatformTransactionManager::class.java, { DataSourceTransactionManager(LakeTestDb.dataSource) })
            .withBean(
                LakeProperties::class.java,
                {
                    LakeProperties(
                        enabled = true,
                        name = DeliverySettlementHarness.LAKE,
                        storage = LakeProperties.Storage(bucket = DeliverySettlementHarness.LAKE, region = REGION, pathStyle = true),
                    )
                },
            ).withBean(LakeCatalogs::class.java, { catalogs })
            .withBean(S3Client::class.java, { mockk(relaxUnitFun = true) })
            .withBean(LakeStagingStorage::class.java, { mockk() })
            .withBean(LakeAppendHeartbeat::class.java, { mockk() })
            .withBean(LakeProjectionInvalidation::class.java, { LakeProjectionInvalidation {} })
            .withBean(DatasourceRegistry::class.java, { mockk() })
            .withBean(AuditEventSink::class.java, { h.audit })
            .withBean(SmartLifecycle::class.java, { StoppingScheduler(stopping) })

    /** Stands where the scheduler that runs the tick stands: a lifecycle bean the context stops before it destroys. */
    private class StoppingScheduler(
        private val stopping: () -> Unit,
    ) : SmartLifecycle {
        @Volatile
        private var running = false

        override fun start() {
            running = true
        }

        override fun stop() {
            stopping()
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private fun reads(io: ScriptedFileIo) = LakeMetadataReads(h.named, DeliverySettlementHarness.LAKE) { io }

    private fun read(
        reads: LakeMetadataReads,
        budget: SettleBudget,
    ): LakeMetadataRead = reads.definitionVersion(h.tableId, "settle", "orders", budget)

    private fun awaitOver(reads: LakeMetadataReads): Boolean {
        val end = System.nanoTime() + Duration.ofMillis(JOIN_MILLIS).toNanos()
        while (reads.stuck() && System.nanoTime() < end) {
            Thread.sleep(POLL_MILLIS)
        }
        return !reads.stuck()
    }

    private fun sweepLine(): String = lines.messages().last { it.startsWith("event=lake.settle.sweep ") }

    private companion object {
        /** The tick's checks before verification's first, for a table with no deliveries; the enumeration is pinned elsewhere. */
        const val VERIFICATION_GATE = 8
        const val SHORT_MILLIS = 200L
        const val JOIN_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        const val NOT_STOPPED = -1
        const val REGION = "us-east-1"
        const val ENDED = "event=lake.settle.metadata_read_ended "
    }
}
