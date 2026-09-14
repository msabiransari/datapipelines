package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one wiring decision of [MailConfiguration] — which sender backs the port — and the
 * pool's shape: bounded, daemon, caller-runs on overflow (never discard; the KDoc says why).
 */
class MailConfigurationTest {
    private val configuration = MailConfiguration()

    @Test
    fun `mail configured wires the transport, mail off wires the no-op`() {
        configuration.mailSender(MailProperties(host = "smtp.example.com", from = "dp@example.com")).shouldBeInstanceOf<SpringMailSender>()
        configuration.mailSender(MailProperties()).shouldBeInstanceOf<NoopMailSender>()
        configuration.mailSender(MailProperties(host = "smtp.example.com")).shouldBeInstanceOf<NoopMailSender>()
    }

    @Test
    fun `the pool is two daemon threads over a bounded queue, and overflow runs on the caller`() {
        val executor = configuration.mailExecutor() as ThreadPoolExecutor
        try {
            executor.corePoolSize shouldBe 2
            executor.maximumPoolSize shouldBe 2
            executor.queue.remainingCapacity() shouldBe 100
            executor.rejectedExecutionHandler.shouldBeInstanceOf<ThreadPoolExecutor.CallerRunsPolicy>()

            val ran = CountDownLatch(1)
            var daemon = false
            var name = ""
            executor.execute {
                daemon = Thread.currentThread().isDaemon
                name = Thread.currentThread().name
                ran.countDown()
            }
            ran.await(5, TimeUnit.SECONDS) shouldBe true
            daemon shouldBe true
            name.startsWith("mail-") shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }
}
