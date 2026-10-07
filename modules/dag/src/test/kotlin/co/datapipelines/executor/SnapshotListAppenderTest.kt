package co.datapipelines.executor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Deterministic #391 guards, using private loggers and finite, joined producer work. */
class SnapshotListAppenderTest {
    private fun logger(): Logger =
        (LoggerFactory.getLogger("co.datapipelines.executor.snapshot.${UUID.randomUUID()}") as Logger).apply {
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
        val event = LoggingEvent(javaClass.name, logger, Level.WARN, "event={} value={}", failure, arrayOf("metadata", 7))
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
                val producer = worker.submit {
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

    private companion object {
        const val EVENTS = 32
        const val WAIT_SECONDS = 5L
    }
}
