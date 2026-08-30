package co.datapipelines.web.sse

import co.datapipelines.executor.ExecutorJson
import co.datapipelines.web.CapturingSseEmitter
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Regression pins for T36 (023's demo E2E): a pipeline declaring a DATE parameter
 * aborted at [SseEventLog.append] because (1) dag's mapper cannot serialize
 * `java.time` values and (2) the append catch was narrower than the class KDoc's
 * "never fails an execution" contract. Either fix alone unblocks; both are pinned.
 */
class SseJsonTest {
    @Test
    fun `resolved DATE and TIMESTAMP parameters serialize as ISO strings`() {
        val event =
            LoggedSseEvent(
                eventId = 1,
                eventName = "execution_started",
                payload =
                    mapOf(
                        "parameters" to
                            mapOf(
                                "start_date" to LocalDate.of(2024, 1, 1),
                                "cutoff" to LocalDateTime.of(2024, 6, 1, 12, 30),
                                "at" to Instant.parse("2024-01-01T00:00:00Z"),
                            ),
                    ),
            )

        val json = SseJson.mapper.writeValueAsString(event)

        json shouldContain "\"2024-01-01\""
        json shouldContain "2024-06-01T12:30"
        json shouldContain "2024-01-01T00:00:00Z"
        // Round-trip: the replay path reads the same shape back.
        SseJson.mapper.readValue(json, LoggedSseEvent::class.java).eventName shouldBe "execution_started"
    }

    @Test
    fun `append never propagates a serialization failure`() {
        val poison = mockk<ObjectMapper>()
        every { poison.writeValueAsString(any()) } throws
            object : JsonProcessingException("unserializable payload") {}
        // Strict mock: any Redis call would throw "no answer found" — and none may
        // happen, because serialization fails before Redis is touched.
        val log = SseEventLog(mockk<StringRedisTemplate>(), poison)

        // The KDoc contract: a debugging-log failure never fails the execution.
        log.append(UUID.randomUUID(), LoggedSseEvent(1, "execution_started", emptyMap()))
    }

    /**
     * The SSE WIRE encoder — T36's second path, and the nastiest of the four.
     *
     * `ExecutionStream.send` catches `IOException` as "the client vanished" and flips the stream
     * to disconnected. Jackson's `InvalidDefinitionException` IS an `IOException`
     * (JsonProcessingException → JacksonException → IOException), so encoding a `LocalDate` with
     * a jsr310-free mapper did not merely lose the frame: it looked exactly like a dropped
     * client, and cancel-on-disconnect then aborted a healthy execution ~30s later.
     *
     * Falsify by passing `ExecutorJson.mapper` here — the send returns false and no frame lands.
     */
    @Test
    fun `a DATE parameter on the wire is a frame, not a phantom disconnect`() {
        val emitter = CapturingSseEmitter()
        val stream = ExecutionStream(UUID.randomUUID(), UUID.randomUUID(), emitter, SseJson.mapper)

        val sent =
            stream.send(
                "execution_started",
                1,
                mapOf("parameters" to mapOf("start_date" to LocalDate.of(2024, 1, 1))),
            )

        sent shouldBe true
        stream.isConnected shouldBe true
        emitter.eventNames() shouldBe listOf("execution_started")

        // The other half, so this test cannot pass vacuously and the failure mode is documented:
        // the SAME payload through the jsr310-free mapper reports a DISCONNECT rather than an
        // encoding error — which is what made cancel-on-disconnect abort healthy executions.
        val broken = ExecutionStream(UUID.randomUUID(), UUID.randomUUID(), CapturingSseEmitter(), ExecutorJson.mapper)
        broken.send("execution_started", 1, mapOf("parameters" to mapOf("start_date" to LocalDate.of(2024, 1, 1)))) shouldBe false
        broken.isConnected shouldBe false
    }
}
