package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #362 — the capture's reads are SNAPSHOTS: reader threads iterate a copy, so logger threads
 * appending in a tight loop cannot trip a `ConcurrentModificationException` and no event is torn.
 * The planted old shape (iterating the live `appender.list` while the writers run) went red with
 * exactly that exception; this is its permanent guard.
 */
class SnapshotListAppenderTest {
    @Test
    fun `reads snapshot while writer threads append - no ConcurrentModificationException and nothing lost`() {
        val appender = SnapshotListAppender().apply { start() }
        val logger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        logger.addAppender(appender)
        val stop = AtomicBoolean(false)
        val writers =
            (1..4).map {
                Thread {
                    while (!stop.get()) {
                        logger.info("event=falsification.writer thread=$it")
                    }
                }.apply { start() }
            }
        try {
            val deadline = System.currentTimeMillis() + 2_000
            var reads = 0
            while (System.currentTimeMillis() < deadline) {
                val snapshot = appender.messages()
                snapshot.forEach { line -> line.contains("event=falsification.writer") shouldBe true }
                reads++
            }
            (reads > 10) shouldBe true
        } finally {
            stop.set(true)
            writers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
            logger.detachAppender(appender)
            appender.stop()
        }
        appender.messages().isNotEmpty() shouldBe true
    }
}
