package co.datapipelines.web.sse

import co.datapipelines.executor.ExecutorJson
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

/**
 * [SseEventLog.replay]'s order is the CODE's, not the store's (#266b, the security pass's observation
 * 5). The batched append keeps one execution's entries in order on the shared Redis connection — but
 * an emitter's direct fallback that outlived its bound (`indeterminate`) keeps running on the
 * persistence pool while the next event's entry is batched, so the list's order rests on two writes
 * reaching Redis in the order they were issued. The durable record orders by `event_id` in SQL; the
 * replay now does too, after keeping the first copy of each id.
 */
class SseEventLogTest {
    private val ops = mockk<ListOperations<String, String>>()
    private val redis = mockk<StringRedisTemplate>().also { every { it.opsForList() } returns ops }
    private val log = SseEventLog(redis, ExecutorJson.mapper)

    @Test
    fun `replay serves the stored stream in event-id order, each id once, whatever order the list holds`() {
        // Red while replay only deduplicated: the list's order was served as it stood.
        val id = UUID.randomUUID()
        every { ops.range("dp:events:$id", 0, -1) } returns listOf(entry(1), entry(3), entry(2, "late copy"), entry(2), entry(4), entry(3, "resend"))
        val replayed = log.replay(id).shouldNotBeNull()
        replayed.map { it.eventId } shouldBe listOf(1, 2, 3, 4)
        // The FIRST stored copy of an id is the one served — the dedup's rule, unchanged by the sort.
        replayed.single { it.eventId == 2 }.payload["note"] shouldBe "late copy"
        replayed.single { it.eventId == 3 }.payload["note"] shouldBe null
    }

    private fun entry(
        eventId: Int,
        note: String? = null,
    ): String = ExecutorJson.mapper.writeValueAsString(LoggedSseEvent(eventId, "node_started", note?.let { mapOf("note" to it) } ?: emptyMap()))
}
