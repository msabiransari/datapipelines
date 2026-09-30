package co.datapipelines.application.dashboards

import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.visualization.DashboardRuntimeConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [RefreshAdmission] on a REAL [ExecutionSlots] — every assertion reads a counter (D53, spec §9.4, §18 premise 2):
 * a refresh takes its workspace place, its share of the dashboard execution cap and ALL its instance slots or none;
 * takes no per-user slot; waits at most `max-wait-seconds`; and everything it took comes back on [Admission.close].
 *
 * Falsified at birth: reserving the instance slots one at a time (keeping what it got) turns the "holds nothing"
 * case red; taking a per-user slot turns the viewer's-own-run case red.
 */
class RefreshAdmissionTest {
    private val workspace: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val viewer: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

    private fun config(
        perWorkspace: Int = 4,
        perRefresh: Int = 16,
        perInstance: Int = 40,
        waitSeconds: Int = 0,
    ) = DashboardRuntimeConfig(
        maxConcurrentRefreshesPerWorkspace = perWorkspace,
        maxExecutionsPerRefresh = perRefresh,
        maxConcurrentDashboardExecutionsPerInstance = perInstance,
        maxWaitSeconds = waitSeconds,
    )

    @Test
    fun `an admitted refresh holds its workspace place, its executions and its instance slots - and gives every one back`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config())

            val admitted = admission.admit(workspace, executions = 3).shouldNotBeNull()

            admission.refreshesIn(workspace) shouldBe 1
            admission.executionsReserved shouldBe 3
            slots.inFlight shouldBe 3
            admitted.reservation.shouldNotBeNull().remaining shouldBe 3
            admitted.close()
            admitted.close() // idempotent
            admission.refreshesIn(workspace) shouldBe 0
            admission.executionsReserved shouldBe 0
            slots.inFlight shouldBe 0
        }

    @Test
    fun `a refresh never takes a per-user slot - the viewer's own runs are not starved by it`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admitted = RefreshAdmission(slots, config()).admit(workspace, executions = 5).shouldNotBeNull()

            slots.trackedUsers shouldBe 0
            val own = slots.acquire(viewer) // the per-user cap is 1 — still free
            own.close()
            admitted.close()
        }

    @Test
    fun `the workspace place is a cap - a fifth refresh is refused at once with a zero wait, and nothing is held for it`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config(perWorkspace = 4))
            val held = (1..4).map { admission.admit(workspace, executions = 1).shouldNotBeNull() }

            admission.admit(workspace, executions = 1).shouldBeNull()

            admission.refreshesIn(workspace) shouldBe 4
            slots.inFlight shouldBe 4
            admission.admit(UUID.randomUUID(), executions = 1).shouldNotBeNull().close() // another workspace is not affected
            held.forEach(Admission::close)
        }

    @Test
    fun `the instance execution cap counts across workspaces`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config(perRefresh = 8, perInstance = 10))
            val first = admission.admit(workspace, executions = 8).shouldNotBeNull()

            admission.admit(UUID.randomUUID(), executions = 3).shouldBeNull() // 8 + 3 > 10

            admission.executionsReserved shouldBe 8
            first.close()
        }

    @Test
    fun `when the instance slots are full it holds NOTHING - not the workspace place, not the execution count`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 4)
            val blocker = slots.acquireInstanceOnly(3, java.time.Duration.ZERO).shouldNotBeNull()
            val admission = RefreshAdmission(slots, config())

            admission.admit(workspace, executions = 3).shouldBeNull() // 1 slot free, 3 asked

            admission.refreshesIn(workspace) shouldBe 0
            admission.executionsReserved shouldBe 0
            slots.inFlight shouldBe 3
            blocker.close()
        }

    @Test
    fun `it waits within the bound and is admitted when room frees`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 4)
            val blocker = slots.acquireInstanceOnly(4, java.time.Duration.ZERO).shouldNotBeNull()
            launch(Dispatchers.Default) {
                delay(150)
                blocker.close()
            }

            val admitted = RefreshAdmission(slots, config(waitSeconds = 10)).admit(workspace, executions = 2)

            admitted.shouldNotBeNull().close()
        }

    @Test
    fun `a refresh needing more executions than one refresh may run is never admissible`() =
        runBlocking<Unit> {
            RefreshAdmission(ExecutionSlots(1, 100), config(perRefresh = 4)).admit(workspace, executions = 5).shouldBeNull()
        }

    @Test
    fun `a refresh with no execution is admitted without a reservation but still holds its workspace place`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config())

            val admitted = admission.admit(workspace, executions = 0).shouldNotBeNull()

            admitted.reservation.shouldBeNull()
            admission.refreshesIn(workspace) shouldBe 1
            slots.inFlight shouldBe 0
            admitted.close()
            admission.refreshesIn(workspace) shouldBe 0
        }

    @Test
    fun `ten racing refreshes with a cap of four - exactly four are admitted`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config(perWorkspace = 4))

            val results = (1..10).map { async(Dispatchers.Default) { admission.admit(workspace, executions = 1) } }.awaitAll()

            results.count { it != null } shouldBe 4
            slots.inFlight shouldBe 4
            results.filterNotNull().forEach(Admission::close)
            slots.inFlight shouldBe 0
        }

    @Test
    fun `a negative execution count is a programming error`() {
        shouldThrow<IllegalArgumentException> { runBlocking { RefreshAdmission(ExecutionSlots(1, 1), config()).admit(workspace, -1) } }
    }
}
