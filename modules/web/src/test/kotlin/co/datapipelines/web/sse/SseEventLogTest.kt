package co.datapipelines.web.sse

import co.datapipelines.executor.ExecutorJson
import co.datapipelines.web.TestRedis
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
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
        every { ops.range("dp:events:$id", 0, -1) } returns
            listOf(entry(1), entry(3), entry(2, "late copy"), entry(2), entry(4), entry(3, "resend"))
        val replayed = log.replay(id).loggedEvents()
        replayed.map { it.eventId } shouldBe listOf(1, 2, 3, 4)
        // The FIRST stored copy of an id is the one served — the dedup's rule, unchanged by the sort.
        replayed.single { it.eventId == 2 }.payload["note"] shouldBe "late copy"
        replayed.single { it.eventId == 3 }.payload["note"] shouldBe null
    }

    /** #487 — the store's fault is its own answer, carrying the cause; before, it was the expiry's null. */
    @Test
    fun `a read Redis does not answer is Unavailable with its cause, never Absent`() {
        val id = UUID.randomUUID()
        val timeout = QueryTimeoutException("Redis command timed out")
        every { ops.range("dp:events:$id", 0, -1) } throws timeout
        log.replay(id).shouldBeInstanceOf<ReplayRead.Unavailable>().cause shouldBe timeout
    }

    @Test
    fun `an empty list and a missing key are both Absent`() {
        val empty = UUID.randomUUID()
        val missing = UUID.randomUUID()
        every { ops.range("dp:events:$empty", 0, -1) } returns emptyList()
        every { ops.range("dp:events:$missing", 0, -1) } returns null
        log.replay(empty) shouldBe ReplayRead.Absent
        log.replay(missing) shouldBe ReplayRead.Absent
    }

    /**
     * #487 B, against a real server: a log is written, the server goes away under the live
     * connection (the production shape — Lettuce reconnects in the background and the command
     * waits out the 2 s command timeout, #482/#488), and the read of that log is [ReplayRead.Unavailable]
     * inside about the timeout — never [ReplayRead.Absent], which §10.3 would serve as `410`. The
     * disposable container, never the shared one.
     */
    @Test
    fun `a stopped Redis reads as Unavailable within about the command timeout, an unknown key on a live one as Absent`() {
        val disposable = TestRedis.disposable()
        try {
            val real = SseEventLog(disposable.template, ExecutorJson.mapper)
            val id = UUID.randomUUID()
            real.appendAll(listOf(real.entry(id, LoggedSseEvent(1, "execution_started", emptyMap()))))
            real.replay(id).loggedEvents().map { it.eventId } shouldBe listOf(1)
            real.replay(UUID.randomUUID()) shouldBe ReplayRead.Absent

            disposable.stopServer()
            val startedAt = System.nanoTime()
            val read = real.replay(id)
            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

            read.shouldBeInstanceOf<ReplayRead.Unavailable>()
            elapsedMs shouldBeLessThan TestRedis.COMMAND_TIMEOUT.toMillis() + FAULT_SLACK_MS
            println("#487: stopped Redis — the replay read answered Unavailable in $elapsedMs ms (${read.cause::class.simpleName})")
        } finally {
            disposable.close()
        }
    }

    private fun entry(
        eventId: Int,
        note: String? = null,
    ): String =
        ExecutorJson.mapper.writeValueAsString(
            LoggedSseEvent(
                eventId,
                "node_started",
                note?.let { mapOf("note" to it) } ?: emptyMap(),
            ),
        )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Past the 2 s command timeout: container stop latency and the reconnect's first attempt. */
        const val FAULT_SLACK_MS = 1_500L
    }
}
