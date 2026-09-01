package co.datapipelines.web.config

import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.CancellationFlags
import co.datapipelines.executor.CancellationHandle
import co.datapipelines.executor.CancellationRegistry
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutorConfig
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * The drain's contract is the ORDER (deployment.md §8.3.1): readiness must fail before the
 * first cancellation, or the load balancer keeps routing work into an instance that is
 * already cancelling it. These tests pin that order, the flush wait, and its bound.
 */
class ExecutionDrainLifecycleTest {
    @Test
    fun `readiness flips before the first cancel, and stop waits for the registry to empty`() {
        val calls = mutableListOf<String>()
        val publisher = recordingPublisher(calls)
        val registry = fakeRegistry(calls, liveAfterCancel = 0)
        val lifecycle = lifecycle(registry, publisher)

        lifecycle.start()
        lifecycle.isRunning shouldBe true
        lifecycle.stop()

        // The B2 order assertion: REFUSING_TRAFFIC strictly precedes the first cancelAll.
        calls.first() shouldBe "readiness"
        calls shouldBe listOf("readiness", "cancelAll")
        publisher.events
            .filterIsInstance<AvailabilityChangeEvent<*>>()
            .single()
            .state shouldBe
            ReadinessState.REFUSING_TRAFFIC
        registry.cancelReasons shouldBe listOf(AbortReason.SHUTDOWN)
        lifecycle.isRunning shouldBe false
    }

    @Test
    fun `the flush wait polls until the registry empties`() {
        val calls = mutableListOf<String>()
        // Two cancels to deregister fully: live 1 after the first, 0 after the second.
        val registry = fakeRegistry(calls, liveAfterCancel = 1, liveAfterSecondCancel = 0)
        val lifecycle = lifecycle(registry, recordingPublisher(calls), pollMillis = 10)

        lifecycle.start()
        lifecycle.stop()

        registry.cancelReasons.size shouldBeGreaterThanOrEqual 2
        registry.cancelReasons.toSet() shouldBe setOf(AbortReason.SHUTDOWN)
    }

    @Test
    fun `the flush wait is bounded when an execution never deregisters`() {
        val calls = mutableListOf<String>()
        val registry = fakeRegistry(calls, liveAfterCancel = 1, liveAfterSecondCancel = 1)
        val lifecycle = lifecycle(registry, recordingPublisher(calls), flushMillis = 200, pollMillis = 25)

        lifecycle.start()
        val started = System.nanoTime()
        lifecycle.stop()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        // Bounded: returns shortly after the flush deadline instead of hanging shutdown,
        // and the repeated cancel swept the wedged execution more than once.
        elapsedMillis shouldBeLessThan 5_000
        registry.cancelReasons.size shouldBeGreaterThanOrEqual 2
    }

    private fun lifecycle(
        registry: CancellationRegistry,
        publisher: ApplicationEventPublisher,
        flushMillis: Long = 1_000,
        pollMillis: Long = 25,
    ): ExecutionDrainLifecycle =
        ExecutionDrainLifecycle(
            cancellationService = ExecutionCancellationService(registry, NoopFlags, ExecutorConfig()),
            registry = registry,
            publisher = publisher,
            flushTimeoutMillis = flushMillis,
            pollIntervalMillis = pollMillis,
        )

    private fun recordingPublisher(calls: MutableList<String>) =
        object : ApplicationEventPublisher {
            val events = mutableListOf<Any>()

            override fun publishEvent(event: ApplicationEvent) = publishEvent(event as Any)

            override fun publishEvent(event: Any) {
                events += event
                if (event is AvailabilityChangeEvent<*>) calls += "readiness"
            }
        }

    /**
     * A registry whose live count drops as cancellations land — the drain has no other
     * observation of the executors' `finally` blocks running.
     */
    private fun fakeRegistry(
        calls: MutableList<String>,
        liveAfterCancel: Int,
        liveAfterSecondCancel: Int = liveAfterCancel,
    ) = object : CancellationRegistry {
        val cancelReasons = mutableListOf<AbortReason>()

        override val liveExecutions: Int
            get() =
                when (cancelReasons.size) {
                    0 -> 1
                    1 -> liveAfterCancel
                    else -> liveAfterSecondCancel
                }

        override fun register(executionId: UUID): CancellationHandle = throw UnsupportedOperationException("not used by the drain")

        override fun deregister(executionId: UUID) = Unit

        override fun cancel(
            executionId: UUID,
            reason: AbortReason,
        ): Boolean = false

        override fun cancelAll(reason: AbortReason) {
            calls += "cancelAll"
            cancelReasons += reason
        }
    }

    private object NoopFlags : CancellationFlags {
        override fun request(
            executionId: UUID,
            reason: AbortReason,
            ttlSeconds: Long,
        ) = Unit

        override fun read(executionId: UUID): AbortReason? = null

        override fun clear(executionId: UUID) = Unit
    }
}
