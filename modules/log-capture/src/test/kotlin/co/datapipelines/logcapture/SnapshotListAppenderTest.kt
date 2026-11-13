package co.datapipelines.logcapture

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic #391 guards, using private loggers and finite, joined producer work (the four cases the web
 * copy carried), plus the #362 writer-thread stress case and the root-logger variant `capturingLogs` (#756).
 */
class SnapshotListAppenderTest {
    private fun logger(): Logger =
        (LoggerFactory.getLogger("co.datapipelines.logcapture.snapshot.${UUID.randomUUID()}") as Logger).apply {
            isAdditive = false
            level = Level.INFO
        }

    @Test
    fun `a returned snapshot stays unchanged after another append`() {
        val logger = logger()
        val appender = SnapshotListAppender()
        appender.events() shouldBe emptyList()
        appender.messages() shouldBe emptyList()
        capturingLogEvents(logger, appender) {
            logger.info("event=first")
            val first = appender.events()
            val messages = appender.messages()
            logger.info("event=second")
            first.map { it.formattedMessage } shouldBe listOf("event=first")
            messages shouldBe listOf("event=first")
            appender.messages() shouldBe listOf("event=first", "event=second")
            // Kotlin's read-only List can still be mutated through its Java-compatible implementation.
            (first as MutableList<*>).clear()
            appender.messages() shouldBe listOf("event=first", "event=second")
        }
    }

    @Test
    fun `snapshots retain event identity level arguments and throwable metadata`() {
        val logger = logger()
        val appender = SnapshotListAppender()
        val failure = IllegalStateException("synthetic failure")
        val event = LoggingEvent(javaClass.name, logger, Level.WARN, "event={} value={}", failure, arrayOf<Any>("metadata", 7))
        capturingLogEvents(logger, appender) {
            appender.doAppend(event)
            val captured = appender.events().single()
            (captured === event) shouldBe true
            captured.level shouldBe Level.WARN
            captured.argumentArray.toList() shouldBe listOf("metadata", 7)
            captured.throwableProxy.className shouldBe IllegalStateException::class.java.name
            appender.messages() shouldBe listOf("event=metadata value=7")
        }
    }

    @Test
    fun `a throwing capture detaches and stops the appender`() {
        val logger = logger()
        val appender = SnapshotListAppender()
        val started = CountDownLatch(1)
        val release = Semaphore(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            shouldThrow<IllegalStateException> {
                capturingLogEvents(logger, appender) {
                    worker.submit {
                        started.countDown()
                        check(release.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) { "worker was not released" }
                    }
                    started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    logger.info("event=before_failure")
                    error("synthetic capture failure")
                }
            }.message shouldBe "synthetic capture failure"
        } finally {
            release.release()
            worker.shutdown()
            worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
        worker.isTerminated shouldBe true
        logger.isAttached(appender) shouldBe false
        appender.isStarted shouldBe false
        logger.info("event=after_failure")
        appender.messages() shouldBe listOf("event=before_failure")
    }

    @Test
    fun `finite producer and reader observe ordered independent snapshots`() {
        val logger = logger()
        val appender = SnapshotListAppender()
        val produced = Semaphore(0)
        val next = Semaphore(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            capturingLogEvents(logger, appender) {
                val producer =
                    worker.submit {
                        repeat(EVENTS) { index ->
                            logger.info("event=producer n={}", index)
                            produced.release()
                            check(next.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) { "reader did not release producer" }
                        }
                    }
                try {
                    repeat(EVENTS) { index ->
                        produced.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                        val snapshot = appender.events()
                        snapshot.map { it.formattedMessage } shouldBe (0..index).map { "event=producer n=$it" }
                        next.release()
                        // A writer may now append: iterating this history must still see the same prefix.
                        snapshot.map { it.formattedMessage } shouldBe (0..index).map { "event=producer n=$it" }
                    }
                    producer.get(WAIT_SECONDS, TimeUnit.SECONDS)
                } finally {
                    next.release(EVENTS)
                    worker.shutdownNow()
                    worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                }
            }
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
        appender.messages() shouldBe (0 until EVENTS).map { "event=producer n=$it" }
    }

    /**
     * #362 — the capture's reads are SNAPSHOTS: reader threads iterate a copy, so logger threads appending
     * in a tight loop cannot trip a `ConcurrentModificationException`, no event is torn and none is lost.
     * Iterating the live backing list while the writers run went red with exactly that exception.
     * #938 bounds each producer's work: a slow snapshot reader must not allow unbounded event retention.
     */
    @Test
    fun `reads snapshot while writer threads append - no ConcurrentModificationException and nothing lost`() {
        val logger = logger()
        val appender = SnapshotListAppender()
        val written = AtomicInteger()
        val started = CountDownLatch(WRITERS)
        val release = CountDownLatch(1)
        var prefix: List<ILoggingEvent> = emptyList()
        var reads = 0
        val captured =
            capturingLogEvents(logger, appender) {
                val writers =
                    (1..WRITERS).map { thread ->
                        Thread {
                            logger.info("event=stress.writer thread={}", thread)
                            written.incrementAndGet()
                            started.countDown()
                            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "snapshot reader did not release writers" }
                            repeat(STRESS_EVENTS_PER_WRITER - 1) {
                                logger.info("event=stress.writer thread={}", thread)
                                written.incrementAndGet()
                            }
                        }.apply { start() }
                    }
                try {
                    started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                    prefix = appender.events()
                    prefix.size shouldBe WRITERS
                    release.countDown()
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STRESS_MILLIS)
                    while (System.nanoTime() < deadline) {
                        appender.messages().forEach { line -> line.startsWith("event=stress.writer thread=") shouldBe true }
                        reads++
                    }
                } finally {
                    release.countDown()
                    writers.forEach { it.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) }
                }
                writers.none { it.isAlive } shouldBe true
            }
        (reads > MIN_READS) shouldBe true
        withClue("the snapshot taken before releasing producers remains the same prefix") { prefix.size shouldBe WRITERS }
        written.get() shouldBe WRITERS * STRESS_EVENTS_PER_WRITER
        withClue("every event a writer logged is captured") { captured.size shouldBe written.get() }
    }

    @Test
    fun `the root-logger capture formats messages, detaches on a throw and records again when reused`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val logger = LoggerFactory.getLogger("co.datapipelines.logcapture.root.${UUID.randomUUID()}") as Logger
        logger.level = Level.INFO
        val appender = SnapshotListAppender()
        capturingLogs(appender) { logger.info("event=root.first value={}", 1) }.own() shouldBe listOf("event=root.first value=1")
        shouldThrow<IllegalStateException> {
            capturingLogs(appender) {
                logger.info("event=root.before_failure")
                error("synthetic root capture failure")
            }
        }.message shouldBe "synthetic root capture failure"
        root.isAttached(appender) shouldBe false
        appender.isStarted shouldBe false
        logger.info("event=root.while_detached")
        // A SECOND capture on the same, now stopped, appender must still record: the entry start() is the pin.
        capturingLogs(appender) { logger.info("event=root.second") }.own() shouldBe
            listOf("event=root.first value=1", "event=root.before_failure", "event=root.second")
        root.isAttached(appender) shouldBe false
    }

    /** The root logger is the JVM's: this case reads only its own lines, so unrelated output cannot interleave. */
    private fun List<String>.own(): List<String> = filter { it.startsWith("event=root.") }

    private companion object {
        const val EVENTS = 32
        const val WAIT_SECONDS = 5L
        const val WRITERS = 4
        const val STRESS_EVENTS_PER_WRITER = 2_048
        const val STRESS_MILLIS = 2_000L
        const val MIN_READS = 10
    }
}
