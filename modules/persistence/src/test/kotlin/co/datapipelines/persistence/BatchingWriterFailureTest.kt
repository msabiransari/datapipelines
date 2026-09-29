package co.datapipelines.persistence

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a store's failure becomes (#266b; observability §3.4G, §9.2): the writer's log lines name a
 * failure by its CLASS and its SQLState — never by its message or its cause chain, because a store's
 * message can carry the row it refused (Postgres renders the failing row in DETAIL for a NOT NULL or
 * CHECK refusal, and a fragment of the JSON in CONTEXT for a JSONB parse refusal).
 *
 * The sink here throws exactly that: an exception whose message and whose cause's message both
 * carry [ROW_CONTENT], with a SQLState on the cause. Every line the writer logs is rendered whole —
 * the message AND any attached throwable, recursively — and must not contain it.
 *
 * And whose failure it is: a store failure is the item's [Outcome.Failed]; a failure the sink
 * claims for the caller ([BatchSink.propagates] — the audit log's non-store failures) is rethrown on
 * the caller's thread, from a batch or from the caller's own direct write, blocking or suspending.
 */
@Timeout(30)
class BatchingWriterFailureTest {
    private val writerLog = LoggerFactory.getLogger(BatchingWriter::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeEach
    fun listen() {
        logs.start()
        writerLog.addAppender(logs)
    }

    @AfterEach
    fun stop() {
        closeables.reversed().forEach { it.close() }
        writerLog.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `a batch of one that fails logs write_failed with the class and SQLState, never the store's message`() {
        // Red on the pre-266b line, which attached the Throwable: its message is the row.
        val w = writer(LeakySink())
        w.record("row-1").shouldBeInstanceOf<Outcome.Failed>()
        assertNoRowContent()
        val line = line("event=persistence.write_failed")
        line shouldContain "cause=StoreRefusal"
        line shouldContain "sql_state=$SQL_STATE"
    }

    @Test
    fun `a direct write that fails logs direct_write_failed with the class and SQLState, never the store's message`() {
        val w = writer(LeakySink())
        w.close() // stopped: every record is the caller's own direct write
        w.record("row-1").shouldBeInstanceOf<Outcome.Failed>()
        assertNoRowContent()
        val line = line("event=persistence.direct_write_failed")
        line shouldContain "cause=StoreRefusal"
        line shouldContain "sql_state=$SQL_STATE"
    }

    @Test
    fun `a batch retried as singles logs batch_retried with the class and SQLState, never the store's message`() {
        val sink = LeakySink(refuse = { it == "row-2" })
        val w = writer(sink)
        sink.blockNextWrite()
        val first = Thread { w.record("row-0") }.apply { start() }
        sink.awaitBlocked()
        val queued = listOf("row-1", "row-2", "row-3").map { item -> Thread { w.record(item) }.apply { start() } }
        awaitQueueDepth(w, 4)
        sink.release()
        (queued + first).forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }
        assertNoRowContent()
        val line = line("event=persistence.batch_retried")
        line shouldContain "cause=StoreRefusal"
        line shouldContain "sql_state=$SQL_STATE"
    }

    @Test
    fun `a failure with no SQLState anywhere in its chain says so, and a cyclic chain is not walked forever`() {
        val plain = IllegalArgumentException("no state $ROW_CONTENT")
        FailureShape.cause(plain) shouldBe "IllegalArgumentException"
        FailureShape.sqlState(plain) shouldBe FailureShape.NO_SQL_STATE
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        FailureShape.sqlState(a) shouldBe FailureShape.NO_SQL_STATE
        FailureShape.sqlState(RuntimeException("wrapper", SQLException("inner", "40001"))) shouldBe "40001"
        FailureShape.cause(object : RuntimeException("anonymous") {}) shouldContain "BatchingWriterFailureTest"
    }

    @Test
    fun `a failure the sink claims for the caller is rethrown on the caller's thread - from a batch and from the direct write`() {
        // Red while the writer ignores BatchSink.propagates: every failure became an outcome, so the
        // audit log's batched path swallowed what its INSERT used to throw into the request.
        val w = writer(ClaimingSink())
        shouldThrow<IllegalStateException> { w.record("bug") }.message shouldBe "not a store failure: bug"
        w.record("refused").shouldBeInstanceOf<Outcome.Failed>()
        w.close() // stopped: the caller's own direct write
        shouldThrow<IllegalStateException> { w.record("bug") }.message shouldBe "not a store failure: bug"
        w.record("refused").shouldBeInstanceOf<Outcome.Failed>()
        withClue("counted and logged like any failure before it is rethrown") {
            logs.list.count { it.formattedMessage.contains("cause=IllegalStateException") } shouldBe 2
        }
    }

    @Test
    fun `the suspending record rethrows a claimed failure too - from a batch and from the bounded direct write`() =
        runBlocking<Unit> {
            val direct = Executors.newSingleThreadExecutor().also { pool -> closeables += AutoCloseable { pool.shutdownNow() } }
            val w = writer(ClaimingSink())
            shouldThrow<IllegalStateException> { w.recordSuspending("bug", direct) }
            w.recordSuspending("refused", direct).shouldBeInstanceOf<Outcome.Failed>()
            w.close()
            shouldThrow<IllegalStateException> { w.recordSuspending("bug", direct) }
            w.recordSuspending("refused", direct).shouldBeInstanceOf<Outcome.Failed>()
        }

    private fun writer(sink: BatchSink<String>): BatchingWriter<String> =
        BatchingWriter("test", BatchingConfig(writers = 1, lingerMillis = 0), sink).also { closeables += it }

    private fun line(prefix: String): String {
        val matching = logs.list.filter { it.formattedMessage.startsWith(prefix) }
        withClue("the writer's lines: ${logs.list.map { it.formattedMessage }}") { matching.size shouldBe 1 }
        return matching.single().formattedMessage
    }

    private fun assertNoRowContent() {
        val rendered = logs.list.map { it.formattedMessage + " " + it.throwableProxy.render() }
        withClue("every line the writer logged, rendered whole: $rendered") {
            rendered.none { it.contains(ROW_CONTENT) } shouldBe true
        }
    }

    private fun IThrowableProxy?.render(): String =
        if (this == null) {
            ""
        } else {
            "$className: $message | " + cause.render() + suppressed.joinToString(" ") { it.render() }
        }

    private fun awaitQueueDepth(
        w: BatchingWriter<String>,
        depth: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (w.queueDepth() < depth && System.nanoTime() < deadline) Thread.sleep(2)
        w.queueDepth() shouldBe depth
    }

    /** A store whose refusal carries the row, the way Postgres's DETAIL does. */
    private class LeakySink(
        private val refuse: (String) -> Boolean = { true },
    ) : BatchSink<String> {
        /** The gate the NEXT write waits on, and the one a write is waiting on now. */
        @Volatile private var armed: CountDownLatch? = null

        @Volatile private var holding: CountDownLatch? = null
        private val blocked = CountDownLatch(1)

        fun blockNextWrite() {
            armed = CountDownLatch(1)
        }

        fun awaitBlocked() {
            blocked.await(10, TimeUnit.SECONDS) shouldBe true
        }

        fun release() {
            armed?.countDown()
            holding?.countDown()
        }

        override fun write(items: List<String>) {
            armed?.let {
                armed = null
                holding = it
                blocked.countDown()
                it.await()
            }
            items.firstOrNull(refuse)?.let { throw refusal(it) }
        }

        override fun writeOne(item: String) {
            if (refuse(item)) throw refusal(item)
        }

        override fun partitionKey(item: String): Any = "one"

        override fun sizeOf(item: String): Int = item.length

        override fun describe(item: String): String = item

        private fun refusal(item: String) =
            StoreRefusal(
                "ERROR: null value in column violates not-null constraint for $item ($ROW_CONTENT)",
                SQLException("Detail: Failing row contains ($ROW_CONTENT).", SQL_STATE),
            )
    }

    /** A store that throws a store failure for `refused` and something else for `bug` — and claims only the latter. */
    private class ClaimingSink : BatchSink<String> {
        override fun write(items: List<String>) = items.forEach(::writeOne)

        override fun writeOne(item: String) {
            when (item) {
                "bug" -> throw IllegalStateException("not a store failure: $item")
                "refused" -> throw StoreRefusal("refused", SQLException("refused", SQL_STATE))
            }
        }

        override fun partitionKey(item: String): Any = "one"

        override fun sizeOf(item: String): Int = item.length

        override fun describe(item: String): String = item

        override fun propagates(failure: Throwable): Boolean = failure !is StoreRefusal
    }

    private class StoreRefusal(
        message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)

    private companion object {
        const val ROW_CONTENT = "row-content-7f3a-must-not-be-logged"
        const val SQL_STATE = "23502"
    }
}
