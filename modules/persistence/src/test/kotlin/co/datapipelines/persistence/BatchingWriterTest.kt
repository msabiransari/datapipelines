package co.datapipelines.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The batching writer's contract (dag-executor §10, auth §10, #266 B.1), over ONE recording sink —
 * the primitive is generic, so one suite covers every store it fronts: the execution-event rows,
 * the replay log and the audit rows all reach it through the same [BatchSink] seam.
 *
 * Each case names the production change that would turn it red; the gates' falsification log
 * (#266 evidence) records every one of those reverts going red and coming back green.
 */
@Timeout(60) // a concurrency suite: a hang is a failure with a stack trace, not a stalled build
class BatchingWriterTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val writerLog = LoggerFactory.getLogger(BatchingWriter::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun listen() {
        logs.start()
        writerLog.addAppender(logs)
    }

    @AfterEach
    fun closeAll() {
        closeables.reversed().forEach { it.close() }
        writerLog.detachAppender(logs)
        logs.stop()
    }

    private fun writer(
        sink: RecordingSink,
        config: BatchingConfig = BatchingConfig(),
        hooks: BatchingHooks = RecordingHooks(),
    ): BatchingWriter<Item> = BatchingWriter("test", config, sink, hooks).also { closeables += it }

    @Test
    fun `per-key order holds under 8 writers and 1000 items`() {
        // Red if the partition stops being a function of the key: one key's items would reach two
        // writers, commit concurrently, and the recorded order per key would interleave. The items
        // are SUBMITTED, not awaited one by one — an awaited producer is sequential by
        // construction, so it could not see a shuffled partition at all.
        val sink = RecordingSink(writeDelayMillis = 1)
        val w = writer(sink, BatchingConfig(writers = WRITERS, lingerMillis = 0, batchMaxEvents = 16))
        val producers = Executors.newFixedThreadPool(KEYS)
        val done = CountDownLatch(KEYS)
        repeat(KEYS) { key ->
            producers.execute {
                repeat(ITEMS / KEYS) { seq -> w.submit(Item("k$key", seq)) shouldBe true }
                done.countDown()
            }
        }
        done.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        producers.shutdown()
        w.close()
        sink.committed().size shouldBe ITEMS
        sink.committed().groupBy { it.key }.forEach { (_, items) -> items.map { it.seq } shouldBe (0 until ITEMS / KEYS).toList() }
    }

    @Test
    fun `awaited records from concurrent producers all commit, each exactly once`() {
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = WRITERS, lingerMillis = 0))
        val producers = Executors.newFixedThreadPool(KEYS)
        val done = CountDownLatch(KEYS)
        repeat(KEYS) { key ->
            producers.execute {
                repeat(ITEMS / KEYS) { seq -> w.record(Item("k$key", seq)) shouldBe Outcome.Committed }
                done.countDown()
            }
        }
        done.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        producers.shutdown()
        sink.committed().size shouldBe ITEMS
        sink.committed().toSet().size shouldBe ITEMS
    }

    @Test
    fun `per-key order holds for items queued behind a commit and for items recorded concurrently`() {
        // The FIFO half of the order claim: 200 items of one key submitted while the writer is
        // blocked in a commit come out in submission order. Red if the queue stops being FIFO.
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0))
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        repeat(QUEUED) { w.submit(Item("k", it)) shouldBe true }
        sink.release()
        first.join()
        w.close()
        sink.committed().map { it.seq } shouldBe listOf(-1) + (0 until QUEUED).toList()
    }

    @Test
    fun `a sequential producer at idle is written at once - no linger`() {
        // Red if linger applies at idle — including the subtle idle: an awaiting producer's next
        // item lands between its previous commit's completion and the writer's next take, so a
        // "queue non-empty" test for load would linger on EVERY sequential write.
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = LONG_LINGER_MS))
        w.record(Item("warm", 0))
        val t0 = System.nanoTime()
        repeat(SEQUENTIAL) { w.record(Item("k", it)) shouldBe Outcome.Committed }
        val elapsedMs = (System.nanoTime() - t0) / NANOS_PER_MS
        elapsedMs shouldBeLessThan LONG_LINGER_MS / 5
        sink
            .batches()
            .drop(1)
            .map { it.size }
            .toSet() shouldBe setOf(1)
    }

    @Test
    fun `items arriving during a commit form the next batch - group commit`() {
        // Red if the writer stops draining everything queued at the moment it becomes free.
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0))
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", 0)) }.also { it.start() }
        sink.awaitBlocked()
        val producers = Executors.newFixedThreadPool(GROUP)
        val all = CountDownLatch(GROUP)
        repeat(GROUP) { i ->
            producers.execute {
                w.record(Item("p$i", i))
                all.countDown()
            }
        }
        awaitQueueDepth(w, GROUP + 1)
        sink.release()
        all.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        first.join()
        producers.shutdown()
        sink.batches().map { it.size } shouldContainExactly listOf(1, GROUP)
    }

    @Test
    fun `a batch never exceeds batch-max-events or batch-max-bytes`() {
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, batchMaxEvents = 7, batchMaxBytes = 250))
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        repeat(40) { w.submit(Item("k", it, bytes = 40)) shouldBe true }
        sink.release()
        first.join()
        w.close()
        sink.committed().size shouldBe 41
        sink.batches().forEach { batch ->
            batch.size shouldBeLessThanOrEqual 7
            if (batch.size > 1) batch.sumOf { it.bytes } shouldBeLessThanOrEqual 250
        }
    }

    @Test
    fun `under load the writer lingers for a fuller batch, bounded by linger-ms`() {
        // Busy mode: two items queued behind the previous commit, so the writer waits up to
        // linger-ms for more before flushing. Red if linger is ignored under load (the second batch would flush
        // with the backlog alone) — and the bound is asserted by the elapsed time.
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 300, batchMaxEvents = 1000))
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        w.submit(Item("k", 0)) shouldBe true
        w.submit(Item("k", 1)) shouldBe true
        sink.release()
        Thread.sleep(100)
        w.submit(Item("k", 2)) shouldBe true
        first.join()
        w.close()
        sink.batches().map { it.size } shouldContainExactly listOf(1, 3)
    }

    @Test
    fun `saturation - record waits for room, then writes directly on the caller's thread`() {
        // Red if the queue is unbounded: the record would enqueue and wait on a hung writer instead.
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = 3, recordMaxWaitMillis = 200), hooks)
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0)) }.start()
        sink.awaitBlocked()
        repeat(2) { w.submit(Item("k", it + 1)) shouldBe true }
        w.queueDepth() shouldBe 3
        val caller = Thread.currentThread().name
        val t0 = System.nanoTime()
        w.record(Item("k", 99)) shouldBe Outcome.Committed
        val waitedMs = (System.nanoTime() - t0) / NANOS_PER_MS
        (waitedMs >= 150) shouldBe true
        sink.directWrites().single().let { (item, thread) ->
            item.seq shouldBe 99
            thread shouldBe caller
        }
        hooks.fallbacks shouldContainExactly listOf(FallbackReason.SATURATED)
        sink.release()
    }

    @Test
    fun `saturation - submit refuses at once and counts the drop`() {
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = 2), hooks)
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0)) }.start()
        sink.awaitBlocked()
        w.submit(Item("k", 1)) shouldBe true
        w.submit(Item("k", 2)) shouldBe false
        hooks.dropped.get() shouldBe 1
        sink.release()
    }

    @Test
    fun `saturation by bytes - the byte bound refuses like the count bound`() {
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxBytes = 100))
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0, bytes = 60)) }.start()
        sink.awaitBlocked()
        w.submit(Item("k", 1, bytes = 30)) shouldBe true
        w.submit(Item("k", 2, bytes = 30)) shouldBe false
        w.queuedBytes() shouldBe 90
        sink.release()
    }

    @Test
    fun `a failed batch is retried as singles in order - the poison row is skipped, the rest commit`() {
        // Red if a failed batch fails all its items (no singles retry) or if the retry stops at the
        // poison row (the rows after it would be lost).
        val sink = RecordingSink(poison = { it.seq == 57 })
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, batchMaxEvents = 500), hooks)
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        val outcomes = Collections.synchronizedMap(mutableMapOf<Int, Outcome>())
        val producers = Executors.newFixedThreadPool(200)
        val all = CountDownLatch(200)
        repeat(200) { i ->
            producers.execute {
                outcomes[i] = w.record(Item("k$i", i))
                all.countDown()
            }
        }
        awaitQueueDepth(w, 201)
        sink.release()
        all.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        first.join()
        producers.shutdown()
        sink.committed().map { it.seq }.sorted() shouldBe (listOf(-1) + (0 until 200).filter { it != 57 })
        outcomes.getValue(57).shouldBeInstanceOf<Outcome.Failed>().kind shouldBe "poison"
        outcomes.filterKeys { it != 57 }.values.toSet() shouldBe setOf(Outcome.Committed)
        hooks.failures shouldContainExactly listOf("poison" to 1)
        hooks.batchRetries shouldContainExactly listOf(200)
    }

    @Test
    fun `a failed batch's singles retry keeps the batch's order`() {
        val sink = RecordingSink(poison = { it.seq == 3 })
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0))
        sink.blockNextWrite()
        val first = Thread { w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        repeat(8) { w.submit(Item("k", it)) }
        sink.release()
        first.join()
        w.close()
        sink.committed().map { it.seq } shouldBe listOf(-1, 0, 1, 2, 4, 5, 6, 7)
    }

    @Test
    fun `a sink that hangs - the bounded wait fires and the caller proceeds by the direct path`() {
        // Red if record waits on its queued item forever: it must claim it back and write it itself.
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 200), hooks)
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0)) }.start()
        sink.awaitBlocked()
        val t0 = System.nanoTime()
        w.record(Item("k", 1)) shouldBe Outcome.Committed
        ((System.nanoTime() - t0) / NANOS_PER_MS).toInt() shouldBeGreaterThan 150
        sink.directWrites().map { it.first.seq } shouldBe listOf(1)
        hooks.fallbacks shouldContainExactly listOf(FallbackReason.TIMEOUT)
        sink.release()
        w.close()
        // The claimed item is written exactly once — by its caller, never again by the writer.
        sink.committed().count { it.seq == 1 } shouldBe 1
    }

    @Test
    fun `an in-flight item is never written twice - a blocking caller waits for its batch's outcome`() {
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 100))
        sink.blockNextWrite()
        var outcome: Outcome? = null
        val caller = Thread { outcome = w.record(Item("k", 0)) }.also { it.start() }
        sink.awaitBlocked()
        Thread.sleep(300)
        caller.isAlive shouldBe true
        sink.release()
        caller.join()
        outcome shouldBe Outcome.Committed
        sink.directWrites().shouldBeEmpty()
        sink.committed().map { it.seq } shouldBe listOf(0)
    }

    @Test
    fun `the suspending record is bounded - an in-flight item stuck in a hung commit reports indeterminate`() =
        runBlocking<Unit> {
            // The emitter's path: dag-executor §10 runs the terminal emit under NonCancellable, so
            // the wait itself must end. Red if the suspending record waits on the hung batch forever.
            val sink = RecordingSink()
            val hooks = RecordingHooks()
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 150), hooks)
            sink.blockNextWrite()
            val direct = Executors.newSingleThreadExecutor().also { closeables += AutoCloseable { it.shutdownNow() } }
            val t0 = System.nanoTime()
            val outcome = w.recordSuspending(Item("k", 0), direct)
            val elapsedMs = (System.nanoTime() - t0) / NANOS_PER_MS
            outcome shouldBe Outcome.Indeterminate
            elapsedMs shouldBeLessThan 150L * 2 + 250
            hooks.failures shouldContainExactly listOf("indeterminate" to 1)
            sink.release()
            w.close()
            // The waiter gave up; the write did not: the batch landed once the sink came back.
            sink.committed().map { it.seq } shouldBe listOf(0)
        }

    @Test
    fun `the suspending record claims a queued item and writes it on the direct executor`() =
        runBlocking<Unit> {
            val sink = RecordingSink()
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 150))
            sink.blockNextWrite()
            Thread { w.record(Item("k", 0)) }.start()
            sink.awaitBlocked()
            val direct =
                Executors.newSingleThreadExecutor { r -> Thread(r, "direct-pool") }.also {
                    closeables +=
                        AutoCloseable { it.shutdownNow() }
                }
            w.recordSuspending(Item("k", 1), direct) shouldBe Outcome.Committed
            sink.directWrites().single().let { (item, thread) ->
                item.seq shouldBe 1
                thread shouldBe "direct-pool"
            }
            sink.release()
        }

    @Test
    fun `the suspending record abandons a direct write that cannot finish, and never runs one it abandoned before start`() =
        runBlocking<Unit> {
            val sink = RecordingSink(hangDirect = true)
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 100))
            sink.blockNextWrite()
            Thread { w.record(Item("k", 0)) }.start()
            sink.awaitBlocked()
            // One direct thread, taken by the first abandoned write: the second never starts.
            val direct = Executors.newSingleThreadExecutor().also { closeables += AutoCloseable { it.shutdownNow() } }
            val outcomes =
                listOf(
                    async(Dispatchers.Default) {
                        w.recordSuspending(Item("k", 1), direct)
                    },
                    async(Dispatchers.Default) { w.recordSuspending(Item("k", 2), direct) },
                ).awaitAll()
            outcomes.toSet() shouldBe setOf(Outcome.Indeterminate, Outcome.Failed("abandoned", null))
            sink.releaseDirect()
            sink.release()
            Thread.sleep(200)
            sink.directStarts.get() shouldBe 1
        }

    @Test
    fun `a timed-out suspending waiter does not cancel its item - the writer still completes it`() =
        runBlocking<Unit> {
            val sink = RecordingSink()
            val hooks = RecordingHooks()
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 100), hooks)
            sink.blockNextWrite()
            val direct = Executors.newSingleThreadExecutor().also { closeables += AutoCloseable { it.shutdownNow() } }
            w.recordSuspending(Item("k", 0), direct) shouldBe Outcome.Indeterminate
            sink.release()
            w.close()
            hooks.committed.get() shouldBe 1
        }

    @Test
    fun `stop drains every queued item before returning - nothing unflushed`() {
        // Red if stop abandons the queue: the submitted items would never reach the sink.
        val sink = RecordingSink(writeDelayMillis = 2)
        val w = writer(sink, BatchingConfig(writers = 2, lingerMillis = 0, batchMaxEvents = 50))
        repeat(QUEUED * 5) { w.submit(Item("k${it % 7}", it)) shouldBe true }
        w.close()
        sink.committed().size shouldBe QUEUED * 5
        w.queueDepth() shouldBe 0
    }

    @Test
    fun `stop with a hung sink gives up at shutdown-drain-ms and reports what it lost`() {
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, shutdownDrainMillis = 300), hooks)
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0)) }.start()
        sink.awaitBlocked()
        repeat(9) { w.submit(Item("k", it + 1)) }
        val t0 = System.nanoTime()
        val report = w.stop()
        ((System.nanoTime() - t0) / NANOS_PER_MS) shouldBeLessThan 300L + 500
        report.lost shouldBe 9
        report.inFlight shouldBe 1
        hooks.failures shouldContainExactly listOf("drain_lost" to 9)
        sink.release()
    }

    @Test
    fun `after stop, record writes directly and submit refuses`() {
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, hooks = hooks)
        w.close()
        w.record(Item("k", 1)) shouldBe Outcome.Committed
        w.submit(Item("k", 2)) shouldBe false
        sink.directWrites().map { it.first.seq } shouldBe listOf(1)
        hooks.fallbacks shouldContainExactly listOf(FallbackReason.STOPPED)
    }

    @Test
    fun `a direct write that fails is reported failed, never thrown into the caller`() {
        val sink = RecordingSink(poison = { true })
        val w = writer(sink)
        w.close()
        w.record(Item("k", 1)).shouldBeInstanceOf<Outcome.Failed>().kind shouldBe "poison"
    }

    @Test
    fun `the suspending record waits for room, then writes directly when the queue stays full`() =
        runBlocking<Unit> {
            val sink = RecordingSink()
            val hooks = RecordingHooks()
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = 1, recordMaxWaitMillis = 150), hooks)
            sink.blockNextWrite()
            Thread { w.record(Item("k", 0)) }.start()
            sink.awaitBlocked()
            val direct =
                Executors.newSingleThreadExecutor { r -> Thread(r, "direct-pool") }.also {
                    closeables +=
                        AutoCloseable { it.shutdownNow() }
                }
            w.recordSuspending(Item("k", 1), direct) shouldBe Outcome.Committed
            hooks.fallbacks shouldContainExactly listOf(FallbackReason.SATURATED)
            sink.directWrites().single().second shouldBe "direct-pool"
            sink.release()
        }

    @Test
    fun `a suspending record woken by freed room is queued, not written directly`() =
        runBlocking<Unit> {
            val sink = RecordingSink()
            val hooks = RecordingHooks()
            val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = 1, recordMaxWaitMillis = 2_000), hooks)
            sink.blockNextWrite()
            Thread { w.record(Item("k", 0)) }.start()
            sink.awaitBlocked()
            Thread {
                Thread.sleep(100)
                sink.release()
            }.start()
            val direct = Executors.newSingleThreadExecutor().also { closeables += AutoCloseable { it.shutdownNow() } }
            w.recordSuspending(Item("k", 1), direct) shouldBe Outcome.Committed
            hooks.fallbacks.shouldBeEmpty()
            sink.batches().flatten().map { it.seq } shouldBe listOf(0, 1)
        }

    @Test
    fun `a lone item the store refuses fails alone - no retry of a batch of one`() {
        val sink = RecordingSink(poison = { it.seq == 1 })
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), hooks)
        w.record(Item("k", 1)).shouldBeInstanceOf<Outcome.Failed>().kind shouldBe "poison"
        hooks.batchRetries.shouldBeEmpty()
        hooks.failures shouldContainExactly listOf("poison" to 1)
        w.record(Item("k", 2)) shouldBe Outcome.Committed
    }

    @Test
    fun `a writer thread killed by an Error fails its batch instead of stranding its callers`() {
        // An Error is not a store failure the singles retry can handle; the writer must still
        // complete every entry it took, and later items for that partition go the direct way.
        val sink = RecordingSink(error = { it.seq == 1 })
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), hooks)
        w
            .record(Item("k", 1))
            .shouldBeInstanceOf<Outcome.Failed>()
            .cause
            .shouldBeInstanceOf<AssertionError>()
        awaitCondition { !w.isWriterAlive(0) }
        w.record(Item("k", 2)) shouldBe Outcome.Committed
        hooks.fallbacks shouldContainExactly listOf(FallbackReason.STOPPED)
        w.submit(Item("k", 3)) shouldBe false
        hooks.dropped.get() shouldBe 1
    }

    @Test
    fun `an interrupted blocking caller takes its item back and writes it, keeping the interrupt`() {
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, recordMaxWaitMillis = 30_000))
        sink.blockNextWrite()
        Thread { w.record(Item("k", 0)) }.start()
        sink.awaitBlocked()
        var outcome: Outcome? = null
        var interrupted = false
        val caller =
            Thread {
                outcome = w.record(Item("k", 1))
                interrupted = Thread.currentThread().isInterrupted
            }.also { it.start() }
        awaitQueueDepth(w, 2)
        caller.interrupt()
        caller.join(TEN_SECONDS * 1_000)
        outcome shouldBe Outcome.Committed
        interrupted shouldBe true
        sink.directWrites().map { it.first.seq } shouldBe listOf(1)
        sink.release()
    }

    @Test
    fun `a store with only the required methods gets the defaults - write for the direct path, write_failed for a failure`() {
        val written = Collections.synchronizedList(mutableListOf<List<String>>())
        val minimal =
            object : BatchSink<String> {
                override fun write(items: List<String>) {
                    check(items != listOf("bad")) { "refused" }
                    written += items
                }

                override fun partitionKey(item: String): Any? = null

                override fun sizeOf(item: String): Int = item.length

                override fun describe(item: String): String = item
            }
        val w = BatchingWriter("minimal", BatchingConfig(), minimal).also { closeables += it }
        w.record("a") shouldBe Outcome.Committed
        w.record("bad").shouldBeInstanceOf<Outcome.Failed>().kind shouldBe FailureKinds.WRITE_FAILED
        w.close()
        w.record("b") shouldBe Outcome.Committed
        written shouldContainExactly listOf(listOf("a"), listOf("b"))
        BatchingHooks.NONE.run {
            onBatch(1, 1, 1)
            onBatchRetried(1)
            onCommitted(1)
            onFailure("k", 1)
            onFallback(FallbackReason.TIMEOUT)
            onDropped()
        }
    }

    @Test
    fun `hooks see every batch, every commit's lag and the queue depth`() {
        val sink = RecordingSink()
        val hooks = RecordingHooks()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), hooks)
        repeat(5) { w.record(Item("k", it, bytes = 10)) }
        hooks.batchSizes.sum() shouldBe 5
        hooks.batchBytes.sum() shouldBe 50L
        hooks.committed.get() shouldBe 5
        w.queueDepth() shouldBe 0
    }

    @Test
    fun `a commit's hook runs before the caller is released - the caller cannot return while the hook holds the gate`() {
        // #363, forced interleaving, not luck: the hook itself blocks on a latch, so the writer
        // thread sits INSIDE onCommitted with the gate held. On the old order (release → complete →
        // hook) the caller was let go BEFORE the hook ran and returned while the gate was held —
        // the CI red (committed=4 of 5). On the shipped order the hook precedes the answer, so a
        // return during the gate is impossible.
        val sink = RecordingSink()
        val hookStarted = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        val hooks =
            object : BatchingHooks {
                override fun onCommitted(lagNanos: Long) {
                    hookStarted.countDown()
                    releaseHook.await(TEN_SECONDS, TimeUnit.SECONDS)
                }
            }
        val w = BatchingWriter("test", BatchingConfig(writers = 1, lingerMillis = 0), sink, hooks).also { closeables += it }
        val callerReturned = AtomicBoolean(false)
        val caller =
            Thread {
                w.record(Item("k", 0)) shouldBe Outcome.Committed
                callerReturned.set(true)
            }.also { it.start() }
        hookStarted.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        // The hook is inside its gate; give the caller every chance to (wrongly) return.
        Thread.sleep(200)
        callerReturned.get() shouldBe false
        releaseHook.countDown()
        caller.join(TimeUnit.SECONDS.toMillis(TEN_SECONDS))
        callerReturned.get() shouldBe true
        sink.committed().map { it.seq } shouldBe listOf(0)
    }

    @Test
    fun `a throwing commit hook cannot strand a batch of one - the caller is answered, the item written once, the capacity freed`() {
        // #393. Red on the base: finish() flipped the entry's one-completion gate, onCommitted threw
        // before release()/complete(), commit() read the throw as a STORE failure (failSingle), whose
        // finish() is a no-op on an entry already claimed — so the caller waits forever on a row the
        // store holds, and its capacity is never freed. Red again with onCommitted called bare.
        val sink = RecordingSink()
        val hooks = ThrowingHooks(throwOnCommitted = true)
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = 1), hooks)
        recordWithin(w, Item("k", 0)) shouldBe Outcome.Committed
        sink.committed() shouldContainExactly listOf(Item("k", 0))
        w.queueDepth() shouldBe 0
        recordWithin(w, Item("k", 1)) shouldBe Outcome.Committed
        sink.committed().map { it.seq } shouldContainExactly listOf(0, 1)
        sink.directWrites().shouldBeEmpty() // both went through the writer: the freed slot, not the saturated fallback
        hooks.inner.committed.get() shouldBe 2
    }

    @Test
    fun `a throwing batch hook cannot re-write a committed batch - every item is stored exactly once`() {
        // #393. Red on the base: onBatch threw AFTER sink.write(batch), commit() read it as the
        // batch's store failure and retryAsSingles wrote all N items AGAIN (the sink holds each twice).
        val sink = RecordingSink()
        val hooks = ThrowingHooks(throwOnBatchOfAtLeast = GROUP_OF)
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), hooks)
        val outcomes = recordGrouped(w, sink, GROUP_OF)
        outcomes.values.toSet() shouldBe setOf(Outcome.Committed)
        sink.committed().map { it.seq }.sorted() shouldBe (listOf(-1) + (0 until GROUP_OF))
        sink.batches().map { it.size } shouldContainExactly listOf(1, GROUP_OF)
        hooks.inner.batchRetries.shouldBeEmpty()
        logged().none { it.contains("event=persistence.batch_retried") } shouldBe true
        logged().count { it.contains("event=persistence.hook_failed") } shouldBe 1
    }

    @Test
    fun `a throwing failure hook inside a poison retry cannot end the writer thread - the poison is skipped, the rest commit, a later record commits`() {
        // #393. Red on the base: onFailure threw inside retryAsSingles' catch, escaped commit() to
        // run()'s catch (Throwable) — the batch is failed, persistence.writer_died is logged and the
        // thread ENDS; the items behind the poison row come back Failed, not Committed.
        val sink = RecordingSink(poison = { it.seq == POISON_SEQ })
        val hooks = ThrowingHooks(throwOnFailure = true)
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), hooks)
        val outcomes = recordGrouped(w, sink, GROUP_OF)
        outcomes.getValue(POISON_SEQ).shouldBeInstanceOf<Outcome.Failed>().kind shouldBe "poison"
        outcomes.filterKeys { it != POISON_SEQ }.values.toSet() shouldBe setOf(Outcome.Committed)
        sink.committed().map { it.seq }.sorted() shouldBe (listOf(-1) + (0 until GROUP_OF).filter { it != POISON_SEQ })
        w.isWriterAlive(0) shouldBe true
        logged().none { it.contains("event=persistence.writer_died") } shouldBe true
        w.record(Item("later", 0)) shouldBe Outcome.Committed
        sink.directWrites().shouldBeEmpty() // the writer committed it — not the caller's fallback after a dead thread
    }

    @Test
    fun `a throwing hook is logged once per interval, by hook and exception class, never by its message`() {
        // #393. Red if the guard logs every throw (two lines), logs nothing, attaches the Throwable
        // or renders the message (a hook could carry anything).
        val sink = RecordingSink()
        val w = writer(sink, BatchingConfig(writers = 1, lingerMillis = 0), ThrowingHooks(throwOnCommitted = true))
        recordWithin(w, Item("k", 0)) shouldBe Outcome.Committed
        recordWithin(w, Item("k", 1)) shouldBe Outcome.Committed
        val events = logs.list.filter { it.formattedMessage.contains("event=persistence.hook_failed") }
        events.size shouldBe 1
        val event = events.single()
        event.level shouldBe Level.WARN
        event.formattedMessage shouldContain "writer=test hook=onCommitted cause=IllegalStateException"
        event.formattedMessage shouldNotContain PLANTED_MESSAGE
        event.throwableProxy shouldBe null
    }

    @Test
    fun `config refuses a bound that cannot hold`() {
        listOf(
            { BatchingConfig(batchMaxEvents = 0) },
            { BatchingConfig(batchMaxBytes = 0) },
            { BatchingConfig(lingerMillis = -1) },
            { BatchingConfig(queueMaxEvents = 0) },
            { BatchingConfig(queueMaxBytes = 0) },
            { BatchingConfig(recordMaxWaitMillis = 0) },
            { BatchingConfig(writers = 0) },
            { BatchingConfig(shutdownDrainMillis = -1) },
        ).forEach { build -> runCatching(build).isFailure shouldBe true }
    }

    /**
     * [BatchingWriter.record] on a daemon thread, answered within [BOUND_SECONDS] or null. A stranded
     * entry makes `record` wait on an uninterruptible join, so a test calling it bare would hang
     * instead of failing (an `@Timeout` interrupt does not free it).
     */
    private fun recordWithin(
        w: BatchingWriter<Item>,
        item: Item,
    ): Outcome? {
        val outcome = AtomicReference<Outcome?>(null)
        val caller = Thread { outcome.set(w.record(item)) }.also { it.isDaemon = true }
        caller.start()
        caller.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))
        return outcome.get()
    }

    private fun logged(): List<String> = logs.list.map { it.formattedMessage }

    /**
     * A grouped batch, forced: the first item holds the sink mid-write, [count] more queue up behind
     * it, then the gate opens and they commit as ONE batch. Answers each item's outcome, keyed by seq.
     */
    private fun recordGrouped(
        w: BatchingWriter<Item>,
        sink: RecordingSink,
        count: Int,
    ): Map<Int, Outcome> {
        val outcomes = Collections.synchronizedMap(mutableMapOf<Int, Outcome>())
        sink.blockNextWrite()
        val first = Thread { outcomes[-1] = w.record(Item("k", -1)) }.also { it.start() }
        sink.awaitBlocked()
        val producers = Executors.newFixedThreadPool(count)
        val all = CountDownLatch(count)
        repeat(count) { i ->
            producers.execute {
                outcomes[i] = w.record(Item("p$i", i))
                all.countDown()
            }
        }
        awaitQueueDepth(w, count + 1)
        sink.release()
        all.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        first.join(TimeUnit.SECONDS.toMillis(TEN_SECONDS))
        producers.shutdown()
        return outcomes.toMap()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEN_SECONDS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        condition() shouldBe true
    }

    private fun awaitQueueDepth(
        w: BatchingWriter<Item>,
        depth: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEN_SECONDS)
        while (w.queueDepth() < depth && System.nanoTime() < deadline) Thread.sleep(2)
        w.queueDepth() shouldBe depth
    }

    /** One recorded thing: a key (the partition), a sequence number per key, a size. */
    data class Item(
        val key: String,
        val seq: Int,
        val bytes: Int = 1,
    )

    /**
     * A real in-memory sink (not a mock — the contract is "you must CALL me", and a strict double
     * would make a missing write unobservable): it records every batch and every direct write
     * with the thread that performed it, can be blocked mid-write on demand, and refuses items
     * [poison] names — the stand-in for a row the column refuses.
     */
    class RecordingSink(
        private val poison: (Item) -> Boolean = { false },
        private val error: (Item) -> Boolean = { false },
        private val writeDelayMillis: Long = 0,
        private val hangDirect: Boolean = false,
    ) : BatchSink<Item> {
        private val batches = Collections.synchronizedList(mutableListOf<List<Item>>())
        private val direct = Collections.synchronizedList(mutableListOf<Pair<Item, String>>())
        private val committed = Collections.synchronizedList(mutableListOf<Item>())

        /** The gate the NEXT write will wait on; taken atomically so exactly one write waits on it. */
        private val armed = AtomicReference<CountDownLatch?>(null)

        /** The gate a write is waiting on right now. */
        @Volatile private var holding: CountDownLatch? = null

        @Volatile private var blocked = CountDownLatch(1)
        private val directGate = CountDownLatch(1)
        val directStarts = AtomicInteger()

        fun blockNextWrite() {
            blocked = CountDownLatch(1)
            armed.set(CountDownLatch(1))
        }

        fun awaitBlocked() {
            blocked.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        }

        fun release() {
            armed.getAndSet(null)?.countDown()
            holding?.countDown()
        }

        fun releaseDirect() {
            directGate.countDown()
        }

        fun batches(): List<List<Item>> = synchronized(batches) { batches.toList() }

        fun directWrites(): List<Pair<Item, String>> = synchronized(direct) { direct.toList() }

        fun committed(): List<Item> = synchronized(committed) { committed.toList() }

        override fun write(items: List<Item>) {
            armed.getAndSet(null)?.let { g ->
                holding = g
                blocked.countDown()
                g.await()
            }
            if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
            if (items.any(error)) throw AssertionError("not a store failure")
            if (items.any(poison)) throw Refused()
            batches += items
            committed += items
        }

        override fun writeOne(item: Item) {
            directStarts.incrementAndGet()
            if (hangDirect) directGate.await()
            if (poison(item)) throw Refused()
            direct += item to Thread.currentThread().name
            committed += item
        }

        override fun partitionKey(item: Item): Any = item.key

        override fun sizeOf(item: Item): Int = item.bytes

        override fun describe(item: Item): String = "${item.key}#${item.seq}"

        override fun classify(failure: Throwable): String = if (failure is Refused) "poison" else "write_failed"
    }

    /** The recording sink's stand-in for a row the store refuses. */
    class Refused : RuntimeException("refused")

    /** Hooks recorded as values, so a test asserts what the metrics would have read. */
    class RecordingHooks : BatchingHooks {
        val batchSizes = Collections.synchronizedList(mutableListOf<Int>())
        val batchBytes = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val fallbacks = Collections.synchronizedList(mutableListOf<FallbackReason>())
        val batchRetries = Collections.synchronizedList(mutableListOf<Int>())
        val dropped = AtomicInteger()
        val committed = AtomicInteger()

        override fun onBatch(
            size: Int,
            bytes: Long,
            durationNanos: Long,
        ) {
            batchSizes += size
            batchBytes += bytes
        }

        override fun onBatchRetried(size: Int) {
            batchRetries += size
        }

        override fun onCommitted(lagNanos: Long) {
            committed.incrementAndGet()
        }

        override fun onFailure(
            kind: String,
            count: Int,
        ) {
            failures += kind to count
        }

        override fun onFallback(reason: FallbackReason) {
            fallbacks += reason
        }

        override fun onDropped() {
            dropped.incrementAndGet()
        }
    }

    /** Recording hooks that throw where told: what a metrics binding must never do, and a writer must survive. */
    class ThrowingHooks(
        val inner: RecordingHooks = RecordingHooks(),
        private val throwOnCommitted: Boolean = false,
        private val throwOnBatchOfAtLeast: Int = Int.MAX_VALUE,
        private val throwOnFailure: Boolean = false,
    ) : BatchingHooks by inner {
        override fun onCommitted(lagNanos: Long) {
            inner.onCommitted(lagNanos)
            if (throwOnCommitted) throw IllegalStateException(PLANTED_MESSAGE)
        }

        override fun onBatch(
            size: Int,
            bytes: Long,
            durationNanos: Long,
        ) {
            inner.onBatch(size, bytes, durationNanos)
            if (size >= throwOnBatchOfAtLeast) throw IllegalStateException(PLANTED_MESSAGE)
        }

        override fun onFailure(
            kind: String,
            count: Int,
        ) {
            inner.onFailure(kind, count)
            if (throwOnFailure) throw IllegalStateException(PLANTED_MESSAGE)
        }
    }

    private companion object {
        const val GROUP_OF = 5
        const val POISON_SEQ = 2
        const val BOUND_SECONDS = 5L
        const val PLANTED_MESSAGE = "planted-hook-message-must-not-be-logged"
        const val WRITERS = 8
        const val KEYS = 20
        const val ITEMS = 1000
        const val QUEUED = 200
        const val GROUP = 50
        const val SEQUENTIAL = 10
        const val LONG_LINGER_MS = 1_000L
        const val TEN_SECONDS = 10L
        const val NANOS_PER_MS = 1_000_000L
    }
}
