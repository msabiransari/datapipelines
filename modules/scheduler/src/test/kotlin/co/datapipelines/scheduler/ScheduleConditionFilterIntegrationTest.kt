package co.datapipelines.scheduler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #641 — the explorer's condition filter (rest-api §20.1 `condition`) over the real V38 table. The
 * SQL predicate is written beside [Schedule.condition], not derived from it, so the guard is the
 * invariant that ties them: for every word, the filtered read lists exactly the unfiltered read's
 * rows whose getter says that word — over [ScheduleRepository.listLive] and its keyset twin
 * [ScheduleRepository.listLiveAfter] (one window, then a walk of two), and through
 * [ScheduleService.list] with the lens narrowed (the filter reads admitted rows only).
 *
 * The fixture holds one schedule per condition plus one that is paused AND blocked, the row a
 * paused clause without its `blocked_at IS NULL` term would wrongly list.
 */
class ScheduleConditionFilterIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
        val enabled = h.create(h.request(name = "ops/a_enabled", payload = FakeExecutor.payload("a")))
        val paused = h.create(h.request(name = "ops/b_paused", payload = FakeExecutor.payload("a")))
        val blocked = h.create(h.request(name = "ops/c_blocked", payload = FakeExecutor.payload("b")))
        val both = h.create(h.request(name = "ops/d_paused_blocked", payload = FakeExecutor.payload("a")))
        h.create(h.request(name = "ops/e_enabled", payload = FakeExecutor.payload("b")))
        h.service.pause(SchedulerTestDb.WORKSPACE, paused.id, SchedulerTestDb.CREATOR)
        h.service.pause(SchedulerTestDb.WORKSPACE, both.id, SchedulerTestDb.CREATOR)
        h.schedules.block(blocked.id, "target_not_found", UUID.randomUUID(), h.clock.instant()) shouldBe true
        h.schedules.block(both.id, "target_not_found", UUID.randomUUID(), h.clock.instant()) shouldBe true
        // The fixture is what the invariant needs: every condition present, blocked twice (once paused too).
        h.schedules.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0).map { it.condition } shouldContainExactly
            listOf("enabled", "paused", "blocked", "blocked", "enabled")
        enabled.condition shouldBe "enabled"
    }

    @Test
    fun `each word lists exactly the rows whose condition is that word - the offset read`() {
        val all = h.schedules.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0)
        ScheduleCondition.entries.forEach { c ->
            h.schedules.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, condition = c).map { it.name } shouldContainExactly
                all.filter { it.condition == c.wire }.map { it.name }
        }
        h.schedules
            .listLive(
                SchedulerTestDb.WORKSPACE,
                "ops",
                LIMIT,
                0,
                condition = ScheduleCondition.BLOCKED,
            ).map { it.name } shouldContainExactly
            listOf("ops/c_blocked", "ops/d_paused_blocked")
        h.schedules.listLive(SchedulerTestDb.OTHER_WORKSPACE, null, LIMIT, 0, condition = ScheduleCondition.ENABLED) shouldBe emptyList()
    }

    @Test
    fun `each word lists exactly the rows whose condition is that word - the keyset read, one window and a walk of two`() {
        val all = h.schedules.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0)
        ScheduleCondition.entries.forEach { c ->
            val expected = all.filter { it.condition == c.wire }.map { it.name }
            h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, null, LIMIT, condition = c).map { it.name } shouldContainExactly
                expected
            val first = h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, null, 1, condition = c)
            val rest = h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, first.last().name, LIMIT, condition = c)
            (first + rest).map { it.name } shouldContainExactly expected
        }
    }

    @Test
    fun `the service filters before the lens - a narrowed reader sees the admitted rows of that condition only`() {
        ScheduleCondition.entries.forEach { c ->
            h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE, c.wire).map { it.name } shouldContainExactly
                h.service
                    .list(
                        SchedulerTestDb.WORKSPACE,
                        null,
                        LIMIT,
                        0,
                        TargetViewer.EVERYONE,
                    ).filter { it.condition == c.wire }
                    .map { it.name }
        }
        h.executor.lens = setOf("job:a")
        val admitted = h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, NarrowedViewer)
        ScheduleCondition.entries.forEach { c ->
            h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, NarrowedViewer, c.wire).map { it.name } shouldContainExactly
                admitted.filter { it.condition == c.wire }.map { it.name }
        }
        h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, NarrowedViewer, "blocked").map { it.name } shouldContainExactly
            listOf("ops/d_paused_blocked")
        // An empty word is no filter, as an absent one is.
        h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE, "").size shouldBe 5
    }

    @Test
    fun `a word outside the three is the request_invalid refusal naming the field and the words`() {
        listOf("nope", "BLOCKED", "Paused", " enabled").forEach { raw ->
            val refused =
                shouldThrow<ScheduleException> { h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE, raw) }
            refused.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
            refused.details shouldBe mapOf("field" to "condition", "expected" to "one of enabled, paused, blocked")
        }
    }

    private companion object {
        const val LIMIT = 50
    }
}
