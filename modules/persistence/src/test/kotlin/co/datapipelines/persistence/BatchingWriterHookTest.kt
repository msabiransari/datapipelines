package co.datapipelines.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.persistence.BatchingWriterTest.Item
import co.datapipelines.persistence.BatchingWriterTest.RecordingHooks
import co.datapipelines.persistence.BatchingWriterTest.RecordingSink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A metrics hook that throws cannot hurt the writer (#393). [BatchingHooks] says hooks must not
 * throw, and nothing enforced it: a throw from `onCommitted` stranded the claimed entry, a throw
 * from `onBatch` was read as the STORE's failure and re-wrote a committed batch, a throw from
 * `onFailure` inside the poison retry ended the writer thread. Every call now goes through one
 * guard that logs `persistence.hook_failed` and lets the item's outcome stand.
 *
 * Each case names the production change that turns it red; the lane's evidence records every one
 * going red on the base and on its plant, and green on the tip. The sink and the recording hooks
 * are [BatchingWriterTest]'s — one recording sink for the whole contract.
 */
@Timeout(60)
class BatchingWriterHookTest {
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
        hooks: BatchingHooks,
        queueMaxEvents: Int = QUEUE_MAX_EVENTS,
    ): BatchingWriter<Item> =
        BatchingWriter("test", BatchingConfig(writers = 1, lingerMillis = 0, queueMaxEvents = queueMaxEvents), sink, hooks)
            .also { closeables += it }

    @Test
    fun `a throwing commit hook cannot strand a batch of one - the caller is answered, the item written once, the capacity freed`() {
        // Red on the base: finish() flipped the entry's one-completion gate, onCommitted threw before
        // release()/complete(), commit() read the throw as a STORE failure (failSingle), whose
        // finish() is a no-op on an entry already claimed — so the caller waits forever on a row the
        // store holds, and its capacity is never freed. Red again with onCommitted called bare.
        val sink = RecordingSink()
        val hooks = ThrowingHooks(throwOnCommitted = true)
        val w = writer(sink, hooks, queueMaxEvents = 1) // one slot: a leaked slot would send the second record to the direct path
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
        // Red on the base: onBatch threw AFTER sink.write(batch), commit() read it as the batch's
        // store failure and retryAsSingles wrote all N items AGAIN (the sink holds each twice).
        val sink = RecordingSink()
        val hooks = ThrowingHooks(throwOnBatchOfAtLeast = GROUP)
        val w = writer(sink, hooks)
        val outcomes = recordGrouped(w, sink, GROUP)
        outcomes.values.toSet() shouldBe setOf(Outcome.Committed)
        sink.committed().map { it.seq }.sorted() shouldBe (listOf(-1) + (0 until GROUP))
        sink.batches().map { it.size } shouldContainExactly listOf(1, GROUP)
        hooks.inner.batchRetries.shouldBeEmpty()
        logged().none { it.contains("event=persistence.batch_retried") } shouldBe true
        logged().count { it.contains("event=persistence.hook_failed") } shouldBe 1
    }

    @Test
    fun `a throwing failure hook in a poison retry cannot end the writer thread - the poison is skipped, the rest commit`() {
        // Red on the base: onFailure threw inside retryAsSingles' catch, escaped commit() to run()'s
        // catch (Throwable) — the batch is failed, persistence.writer_died is logged and the thread
        // ENDS; the items behind the poison row come back Failed, not Committed.
        val sink = RecordingSink(poison = { it.seq == POISON_SEQ })
        val w = writer(sink, ThrowingHooks(throwOnFailure = true))
        val outcomes = recordGrouped(w, sink, GROUP)
        outcomes.getValue(POISON_SEQ).shouldBeInstanceOf<Outcome.Failed>().kind shouldBe "poison"
        outcomes.filterKeys { it != POISON_SEQ }.values.toSet() shouldBe setOf(Outcome.Committed)
        sink.committed().map { it.seq }.sorted() shouldBe (listOf(-1) + (0 until GROUP).filter { it != POISON_SEQ })
        w.isWriterAlive(0) shouldBe true
        logged().none { it.contains("event=persistence.writer_died") } shouldBe true
        recordWithin(w, Item("later", 0)) shouldBe Outcome.Committed
        sink.directWrites().shouldBeEmpty() // the writer committed it — not the caller's fallback after a dead thread
    }

    @Test
    fun `a throwing hook is logged once per interval, by hook and exception class, never by its message`() {
        // Red if the guard logs every throw (two lines), logs nothing, attaches the Throwable or
        // renders the message (a hook could carry anything).
        val sink = RecordingSink()
        val w = writer(sink, ThrowingHooks(throwOnCommitted = true))
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
     * it, then the gate opens and they commit as ONE batch. Answers each item's outcome, keyed by seq
     * (the first item is -1).
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEN_SECONDS)
        while (w.queueDepth() < count + 1 && System.nanoTime() < deadline) Thread.sleep(2)
        w.queueDepth() shouldBe count + 1
        sink.release()
        all.await(TEN_SECONDS, TimeUnit.SECONDS) shouldBe true
        first.join(TimeUnit.SECONDS.toMillis(TEN_SECONDS))
        producers.shutdown()
        return outcomes.toMap()
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
        const val GROUP = 5
        const val POISON_SEQ = 2
        const val QUEUE_MAX_EVENTS = 1_000
        const val BOUND_SECONDS = 5L
        const val TEN_SECONDS = 10L
        const val PLANTED_MESSAGE = "planted-hook-message-must-not-be-logged"
    }
}
