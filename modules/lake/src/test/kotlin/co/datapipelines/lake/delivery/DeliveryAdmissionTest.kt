package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class DeliveryAdmissionTest {
    private val clock = Instant.parse("2026-11-05T00:00:00Z")
    private val timeout = Duration.ofHours(2)

    @Test
    fun `slot precedes scratch and a refused slot never calls its scratch factory`() {
        val admission = admission()
        val resource = Resource()
        val first =
            requireNotNull(
                admission.acquire { bytes ->
                    bytes shouldBe BYTES
                    resource
                },
            )
        first.deadline shouldBe clock.plus(timeout)
        var calls = 0
        admission.acquire {
            calls++
            Resource()
        } shouldBe null
        calls shouldBe 0
        first.close()
        first.close()
        resource.closed shouldBe 1
        requireNotNull(admission.acquire { Resource() }).close()
    }

    @Test
    fun `scratch refusal retains no slot and fixes no job deadline`() {
        var clockReads = 0
        val admission =
            DeliveryAdmission(1, BYTES, Duration.ZERO, timeout) {
                clockReads++
                clock.plus(it)
            }
        admission.acquire<Resource> { null } shouldBe null
        clockReads shouldBe 0
        requireNotNull(admission.acquire { Resource() }).close()
        clockReads shouldBe 1
    }

    @Test
    fun `a scratch factory failure releases its slot`() {
        val admission = admission()
        shouldThrow<IllegalStateException> { admission.acquire<Resource> { error("reservation failure") } }
        requireNotNull(admission.acquire { Resource() }).close()
    }

    @Test
    fun `clock failure after reservation releases scratch and the slot`() {
        var fail = true
        val admission =
            DeliveryAdmission(1, BYTES, Duration.ZERO, timeout) {
                if (fail) error("catalog clock unavailable") else clock.plus(it)
            }
        val resource = Resource()
        shouldThrow<IllegalStateException> { admission.acquire { resource } }
        resource.closed shouldBe 1
        fail = false
        requireNotNull(admission.acquire { Resource() }).close()
    }

    @Test
    fun `scratch close failure still releases its slot exactly once`() {
        val admission = admission()
        val admitted = requireNotNull(admission.acquire { AutoCloseable { error("close failure") } })
        shouldThrow<IllegalStateException> { admitted.close() }
        admitted.close()
        requireNotNull(admission.acquire { Resource() }).close()
    }

    @Test
    fun `delivery reservation uses the configured maximum and refuses overflow`() {
        val properties = LakeProperties.Admission(maxDeliveryBytes = DataSize.ofBytes(BYTES), stagingExpansionFactor = 4)
        DeliveryAdmission(properties) { clock.plus(it) }.scratchBytes shouldBe BYTES * 4
        shouldThrow<ArithmeticException> {
            DeliveryAdmission(LakeProperties.Admission(maxDeliveryBytes = DataSize.ofBytes(Long.MAX_VALUE), stagingExpansionFactor = 2)) {
                clock.plus(it)
            }
        }
    }

    @Test
    fun `interrupted admission touches no scratch and leaves its slot available`() {
        val admission = admission()
        var calls = 0
        Thread.currentThread().interrupt()
        try {
            shouldThrow<InterruptedException> {
                admission.acquire {
                    calls++
                    Resource()
                }
            }
        } finally {
            Thread.interrupted()
        }
        calls shouldBe 0
        requireNotNull(admission.acquire { Resource() }).close()
    }

    @Test
    fun `exhausted shared wait budget never tries a late scratch reservation`() {
        val admission = DeliveryAdmission(1, BYTES, Duration.ofMillis(1), timeout) { clock.plus(it) }
        var calls = 0
        admission.acquire<Resource> {
            calls++
            // This caller's own work consumes its budget; there is no other thread or race to win.
            TimeUnit.MILLISECONDS.sleep(RESERVATION_WORK_MILLIS)
            null
        } shouldBe null
        calls shouldBe 1
        requireNotNull(admission.acquire { Resource() }).close()
    }

    private fun admission(): DeliveryAdmission = DeliveryAdmission(1, BYTES, Duration.ZERO, timeout) { clock.plus(it) }

    private class Resource : AutoCloseable {
        var closed = 0

        override fun close() {
            closed++
        }
    }

    private companion object {
        const val BYTES = 1024L
        const val RESERVATION_WORK_MILLIS = 2L
    }
}
