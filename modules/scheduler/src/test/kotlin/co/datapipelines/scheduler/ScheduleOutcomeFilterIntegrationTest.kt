package co.datapipelines.scheduler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * #725 — the explorer's last-run filter (rest-api §20.1 `outcome`) over the real V38 tables:
 * [ScheduleConditionFilterIntegrationTest]'s twin. Each word lists the schedules whose NEWEST run
 * falls in that [LastRunOutcome] bucket — over [ScheduleRepository.listLive] and its keyset twin
 * [ScheduleRepository.listLiveAfter] (one window, then a walk of two), composed with a folder
 * prefix and a condition, and through [ScheduleService.list] with the lens narrowed (the filter
 * reads admitted rows only). The bucket-for-bucket equality with the home's count is
 * [LastRunOutcomeFilterInvariantIntegrationTest]'s.
 *
 * The fixture's newest runs differ from their older ones (a failure then a success is
 * `succeeded`), one schedule has no run, one `unknown` run is paused, and one failed schedule sits
 * in another folder.
 */
class ScheduleOutcomeFilterIntegrationTest {
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness()
        val ok = h.create(h.request(name = "ops/a_ok", payload = FakeExecutor.payload("a")))
        val failed = h.create(h.request(name = "ops/b_failed", payload = FakeExecutor.payload("b")))
        val running = h.create(h.request(name = "ops/c_running", payload = FakeExecutor.payload("a")))
        val skipped = h.create(h.request(name = "ops/d_skipped", payload = FakeExecutor.payload("a")))
        h.create(h.request(name = "ops/e_never", payload = FakeExecutor.payload("b")))
        val unknown = h.create(h.request(name = "ops/f_unknown", payload = FakeExecutor.payload("a")))
        val aborted = h.create(h.request(name = "fin/g_aborted", payload = FakeExecutor.payload("a")))
        ScheduleRunRows.insert(ok.id, RunState.FAILED, AT)
        ScheduleRunRows.insert(ok.id, RunState.SUCCEEDED, AT.plusSeconds(60))
        ScheduleRunRows.insert(failed.id, RunState.SUCCEEDED, AT)
        ScheduleRunRows.insert(failed.id, RunState.FAILED, AT.plusSeconds(60))
        ScheduleRunRows.insert(running.id, RunState.SUCCEEDED, AT)
        ScheduleRunRows.insert(running.id, RunState.RUNNING, AT.plusSeconds(60))
        ScheduleRunRows.insert(skipped.id, RunState.SKIPPED, AT)
        ScheduleRunRows.insert(unknown.id, RunState.UNKNOWN, AT)
        ScheduleRunRows.insert(aborted.id, RunState.ABORTED, AT)
        h.service.pause(SchedulerTestDb.WORKSPACE, unknown.id, SchedulerTestDb.CREATOR)
    }

    @Test
    fun `each word lists exactly its bucket's schedules - the offset read, composed with the prefix and the condition`() {
        LastRunOutcome.entries.forEach { o ->
            withClue(o.wire) {
                h.schedules.listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, outcome = o).map { it.name } shouldContainExactly
                    EXPECTED.getValue(o)
            }
        }
        h.schedules
            .listLive(SchedulerTestDb.WORKSPACE, "ops", LIMIT, 0, outcome = LastRunOutcome.FAILED)
            .map { it.name } shouldContainExactly
            listOf("ops/b_failed", "ops/f_unknown")
        h.schedules
            .listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, condition = ScheduleCondition.PAUSED, outcome = LastRunOutcome.FAILED)
            .map { it.name } shouldContainExactly listOf("ops/f_unknown")
        h.schedules
            .listLive(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, condition = ScheduleCondition.ENABLED, outcome = LastRunOutcome.FAILED)
            .map { it.name } shouldContainExactly listOf("fin/g_aborted", "ops/b_failed")
        h.schedules.listLive(SchedulerTestDb.OTHER_WORKSPACE, null, LIMIT, 0, outcome = LastRunOutcome.NEVER_RAN) shouldBe emptyList()
    }

    @Test
    fun `each word lists exactly its bucket's schedules - the keyset read, one window and a walk of two`() {
        LastRunOutcome.entries.forEach { o ->
            withClue(o.wire) {
                val expected = EXPECTED.getValue(o)
                h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, null, LIMIT, outcome = o).map { it.name } shouldContainExactly
                    expected
                val first = h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, null, 1, outcome = o)
                val rest = h.schedules.listLiveAfter(SchedulerTestDb.WORKSPACE, null, first.last().name, LIMIT, outcome = o)
                (first + rest).map { it.name } shouldContainExactly expected
            }
        }
    }

    @Test
    fun `the service filters before the lens - a narrowed reader sees the admitted rows of that bucket only`() {
        LastRunOutcome.entries.forEach { o ->
            names(TargetViewer.EVERYONE, outcome = o.wire) shouldContainExactly EXPECTED.getValue(o)
        }
        h.executor.lens = setOf("job:a")
        val admitted = names(NarrowedViewer).toSet()
        LastRunOutcome.entries.forEach { o ->
            withClue(o.wire) {
                names(NarrowedViewer, outcome = o.wire) shouldContainExactly EXPECTED.getValue(o).filter { it in admitted }
            }
        }
        names(NarrowedViewer, outcome = "failed") shouldContainExactly listOf("fin/g_aborted", "ops/f_unknown")
        names(NarrowedViewer, condition = "paused", outcome = "failed") shouldContainExactly listOf("ops/f_unknown")
        // An empty word is no filter, as an absent one is.
        h.executor.lens = null
        h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE, outcome = "").size shouldBe 7
    }

    @Test
    fun `a word outside the five is the request_invalid refusal naming the field and the words`() {
        listOf("nope", "FAILED", "Failed", " failed", "in_progress", "never_ran", "blocked").forEach { raw ->
            withClue("'$raw'") {
                val refused =
                    shouldThrow<ScheduleException> {
                        h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, TargetViewer.EVERYONE, outcome = raw)
                    }
                refused.code shouldBe ScheduleErrorCodes.REQUEST_INVALID
                refused.details shouldBe
                    mapOf("field" to "outcome", "expected" to "one of succeeded, failed, in-progress, did-not-run, never-ran")
            }
        }
    }

    /** The names [ScheduleService.list] answers [viewer] at the workspace root. */
    private fun names(
        viewer: TargetViewer,
        condition: String? = null,
        outcome: String? = null,
    ): List<String> = h.service.list(SchedulerTestDb.WORKSPACE, null, LIMIT, 0, viewer, condition, outcome).map { it.name }

    private companion object {
        const val LIMIT = 50
        val AT: Instant = Instant.parse("2026-09-25T11:00:00Z")

        /** Each bucket's schedules by name — read off the fixture's newest runs, not derived from the code under test. */
        val EXPECTED =
            mapOf(
                LastRunOutcome.SUCCEEDED to listOf("ops/a_ok"),
                LastRunOutcome.FAILED to listOf("fin/g_aborted", "ops/b_failed", "ops/f_unknown"),
                LastRunOutcome.IN_PROGRESS to listOf("ops/c_running"),
                LastRunOutcome.DID_NOT_RUN to listOf("ops/d_skipped"),
                LastRunOutcome.NEVER_RAN to listOf("ops/e_never"),
            )
    }
}
