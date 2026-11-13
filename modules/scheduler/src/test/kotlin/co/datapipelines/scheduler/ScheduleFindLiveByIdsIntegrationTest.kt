package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #874 G3 — [ScheduleRepository.findLiveByIds], the schedule family's graph batch read (dependency-graph record §7.1),
 * over the real table: ONE statement returns exactly the live rows of the named workspace among the ids, and an id
 * that is deleted, another workspace's or absent is simply not returned — the provider answers those MISSING.
 */
class ScheduleFindLiveByIdsIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
    }

    @Test
    fun `the batch read returns the live rows of the workspace among the ids and nothing else`() {
        val first = h.create(h.request(name = "ops/first"))
        val second = h.create(h.request(name = "ops/second"))
        val unrequested = h.create(h.request(name = "ops/unrequested"))
        val deleted = h.create(h.request(name = "ops/deleted"))
        h.schedules.softDelete(SchedulerTestDb.WORKSPACE, deleted.id, deleted.revision, SchedulerTestDb.CREATOR, h.clock.instant()) shouldBe
            true
        val elsewhere =
            h.service.create(SchedulerTestDb.OTHER_WORKSPACE, SchedulerTestDb.CREATOR, h.request(name = "ops/elsewhere"), null).value
        val requested = listOf(first.id, second.id, deleted.id, elsewhere.id, UUID.randomUUID())

        val rows = h.schedules.findLiveByIds(SchedulerTestDb.WORKSPACE, requested)

        rows.map { it.id } shouldContainExactlyInAnyOrder listOf(first.id, second.id)
        rows.single { it.id == first.id } shouldBe h.schedules.findLive(SchedulerTestDb.WORKSPACE, first.id)
        h.schedules.findLiveByIds(SchedulerTestDb.OTHER_WORKSPACE, requested).map { it.id } shouldBe listOf(elsewhere.id)
        h.schedules.findLiveByIds(SchedulerTestDb.WORKSPACE, listOf(unrequested.id, unrequested.id)).map { it.id } shouldBe
            listOf(unrequested.id)
    }

    @Test
    fun `an empty id list returns nothing`() {
        h.create(h.request(name = "ops/any"))

        h.schedules.findLiveByIds(SchedulerTestDb.WORKSPACE, emptyList()).shouldBeEmpty()
    }
}
