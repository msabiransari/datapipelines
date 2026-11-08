package co.datapipelines.lake.delivery

import ch.qos.logback.classic.Logger
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
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
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import javax.sql.DataSource

/**
 * The metadata read's other ends (record §8b, D4): the sweep's line counts each deferral by its cause, a failed read
 * refuses no successor, and the thread is destroyed with the context, after the scheduler has stopped. Lane 851 (#851):
 * an abandoned read stops itself at its next chunk from the store, whichever way the tick stopped waiting; a read blocked
 * on its socket sees the stop only when the store answers; the file read is capped; a healthy read is never cut.
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
        withClue("a refused read was never queued behind the stuck one: only this read ever reached the store") {
            io.streamsEntered.get() shouldBe 1
        }

        val ended = lines.messages().single { it.startsWith(ENDED) }
        ended shouldContain "table_id=${h.tableId}"
        ended shouldNotContain "cause=none"
        reads.stuck().shouldBeFalse()
    }

    @Test
    fun `an interrupted wait stops the read at its next byte and the read ends as abandoned`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.TRICKLE)
        val reads = reads(io)
        val answer = AtomicReference<LakeMetadataRead>()
        val waiter = Thread { answer.set(read(reads, h.budget { false })) }

        waiter.start()
        withClue("the read is trickling before the wait is interrupted") { awaitTrue { io.bytesServed.get() >= 2 }.shouldBeTrue() }
        waiter.interrupt()
        waiter.join(JOIN_MILLIS)
        answer.get() shouldBe LakeMetadataRead.Deferred(SettleDeferCause.BUDGET)
        val atInterrupt = io.bytesServed.get()

        withClue("the abandoned read closed its stream at its next byte") { awaitTrue { io.readsEnded.get() == 1 }.shouldBeTrue() }
        io.bytesServed.get() shouldBeLessThanOrEqual atInterrupt + 1
        awaitOver(reads).shouldBeTrue()
        endedLine() shouldContain "cause=abandoned sql_state=none"
    }

    @Test
    fun `a read blocked on its socket sees the stop only when the store answers, then ends at that answer`() {
        h.staleSemantics()
        val io = ScriptedFileIo(h.metadataJson(), ScriptedFileIo.Mode.STALL)
        val reads = reads(io)
        read(reads, SettleBudget.startingNow(h.leases(), Duration.ofMillis(SHORT_MILLIS))) shouldBe
            LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)

        Thread.sleep(SHORT_MILLIS)
        withClue("the stop is checked between reads, never inside one: the blocked read is still blocked") {
            io.readsEnded.get() shouldBe 0
            io.bytesServed.get() shouldBe 0
            reads.stuck().shouldBeTrue()
        }

        io.release()
        awaitOver(reads).shouldBeTrue()
        withClue("what the store answered was discarded; nothing was read after it") { io.streamsEntered.get() shouldBe 1 }
        endedLine() shouldContain "cause=abandoned"
    }

    @Test
    fun `a file one byte over the cap ends as a failure naming the cap and the next read is not refused`() {
        h.staleSemantics()
        val json = h.metadataJson()
        val io = ScriptedFileIo(json, padding = (CAP_BYTES - json.size + 1).toInt())
        val reads = reads(io)

        val failure = shouldThrow<IOException> { read(reads, h.budget { false }) }

        failure::class.simpleName shouldBe "MetadataFileTooLarge"
        failure.message shouldContain "$CAP_BYTES"
        failure.message shouldContain "00001.metadata.json"
        io.readsEnded.get() shouldBe 1
        reads.stuck().shouldBeFalse()
    }

    @Test
    fun `a file one byte under the cap parses`() {
        h.staleSemantics()
        val json = h.metadataJson()
        val io = ScriptedFileIo(json, padding = (CAP_BYTES - json.size - 1).toInt())

        read(reads(io), h.budget { false }) shouldBe LakeMetadataRead.Version(1)
        io.bytesServed.get() shouldBe CAP_BYTES - 1
    }

    @Test
    fun `a gzip file is read through the codec its name declares and the cap counts its inflated bytes`() {
        h.staleSemantics()
        pointAt("s3://${DeliverySettlementHarness.LAKE}/settle/orders/metadata/00002.gz.metadata.json")
        val json = h.metadataJson()

        read(reads(ScriptedFileIo(gzip(json, padding = 0))), h.budget { false }) shouldBe LakeMetadataRead.Version(1)

        val bomb = ScriptedFileIo(gzip(json, padding = CAP_BYTES))
        val failure = shouldThrow<IOException> { read(reads(bomb), h.budget { false }) }
        failure::class.simpleName shouldBe "MetadataFileTooLarge"
        withClue("the compressed file is small; only the inflated bytes pass the cap") {
            bomb.bytesServed.get() shouldBeLessThan CAP_BYTES / COMPRESSED_FRACTION
        }
    }

    @Test
    fun `a healthy file of several MiB read inside the budget parses whole`() {
        h.staleSemantics()
        val json = h.metadataJson()
        val io = ScriptedFileIo(json, padding = LARGE_FILE_BYTES)

        read(reads(io), SettleBudget.startingNow(h.leases(), SHORT_BUDGET)) shouldBe LakeMetadataRead.Version(1)
        io.bytesServed.get() shouldBe LARGE_FILE_BYTES.toLong() + json.size
    }

    @Test
    fun `a healthy read that outruns the budget ends at its next chunk and the next read starts again from byte 0`() {
        h.staleSemantics()
        val json = h.metadataJson()
        val io = ScriptedFileIo(json, ScriptedFileIo.Mode.PACED, padding = LARGE_FILE_BYTES)
        val reads = reads(io)

        read(reads, SettleBudget.startingNow(h.leases(), SHORT_BUDGET)) shouldBe LakeMetadataRead.Deferred(SettleDeferCause.STUCK_READ)
        val atAbandon = io.bytesServed.get()
        withClue("the paced read was under way and far from its end") {
            atAbandon shouldBeGreaterThan 0
            atAbandon shouldBeLessThan LARGE_FILE_BYTES.toLong()
        }
        withClue("it stopped at its next chunk") { awaitTrue { io.readsEnded.get() == 1 }.shouldBeTrue() }
        io.bytesServed.get() shouldBeLessThanOrEqual atAbandon + ScriptedFileIo.PACE_CHUNK
        awaitOver(reads).shouldBeTrue()
        endedLine() shouldContain "cause=abandoned"

        io.mode = ScriptedFileIo.Mode.HEALTHY
        read(reads, h.budget { false }) shouldBe LakeMetadataRead.Version(1)
        io.streamsEntered.get() shouldBe 2
        io.latestStreamBytes.get() shouldBe LARGE_FILE_BYTES.toLong() + json.size
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

    private fun awaitTrue(condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + Duration.ofMillis(STOP_MILLIS).toNanos()
        while (!condition() && System.nanoTime() < end) {
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private fun endedLine(): String = lines.messages().single { it.startsWith(ENDED) }

    /** The harness table's catalog row moved to [location], under the table's lease as every catalog write is. */
    private fun pointAt(location: String) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.orders")
        h.fencedConnection(lease).use { connection ->
            connection
                .prepareStatement(
                    "UPDATE iceberg_tables SET metadata_location = ? " +
                        "WHERE catalog_name = ? AND table_namespace = 'settle' AND table_name = 'orders'",
                ).use { statement ->
                    statement.setString(1, location)
                    statement.setString(2, DeliverySettlementHarness.LAKE)
                    statement.executeUpdate() shouldBe 1
                }
        }
        LakeTestDb.leases.release(lease.operationId)
    }

    /** [padding] spaces then [json], gzip-compressed: a file whose inflated size is far above its stored size. */
    private fun gzip(
        json: ByteArray,
        padding: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { zipped ->
            val spaces = ByteArray(GZIP_BLOCK) { ' '.code.toByte() }
            var left = padding
            while (left > 0) {
                val n = minOf(left, GZIP_BLOCK.toLong()).toInt()
                zipped.write(spaces, 0, n)
                left -= n
            }
            zipped.write(json)
        }
        return out.toByteArray()
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

        /** How long a stopped read may take to end: its next chunk is due within 100 ms on every scripted store. */
        const val STOP_MILLIS = 2_000L

        /** The cap docs/lake.md §8 states, 64 MiB, as a literal: `CatalogStoreTimeoutsTest` pins the source constant to it. */
        const val CAP_BYTES = 64L * 1024 * 1024

        /** Several MiB: 64 paced chunks, far past a one-second budget at one chunk per 100 ms. */
        const val LARGE_FILE_BYTES = 4 * 1024 * 1024
        const val GZIP_BLOCK = 64 * 1024

        /** Spaces compress about a thousandfold; a hundredth is a loose bound that still tells stored from inflated. */
        const val COMPRESSED_FRACTION = 100
        val SHORT_BUDGET: Duration = Duration.ofSeconds(1)
    }
}
