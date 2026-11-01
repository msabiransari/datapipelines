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
 * case red; taking a per-user slot turns the viewer's-own-run case red. #762: on the base code a refresh over the
 * executor ceiling answered only after the whole `max-wait-seconds` (measured 10.229 s against the 1 s assertion).
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

            val admitted = admission.admit(workspace, executions = 3).admitted()

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
            val admitted = RefreshAdmission(slots, config()).admit(workspace, executions = 5).admitted()

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
            val held = (1..4).map { admission.admit(workspace, executions = 1).admitted() }

            admission.admit(workspace, executions = 1) shouldBe AdmissionResult.Busy

            admission.refreshesIn(workspace) shouldBe 4
            slots.inFlight shouldBe 4
            admission.admit(UUID.randomUUID(), executions = 1).admitted().close() // another workspace is not affected
            held.forEach(Admission::close)
        }

    @Test
    fun `the instance execution cap counts across workspaces`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config(perRefresh = 8, perInstance = 10))
            val first = admission.admit(workspace, executions = 8).admitted()

            admission.admit(UUID.randomUUID(), executions = 3) shouldBe AdmissionResult.Busy // 8 + 3 > 10

            admission.executionsReserved shouldBe 8
            first.close()
        }

    @Test
    fun `the dashboard reservation cap can exceed live executor slots and still limit overlapping refreshes`() {
        runBlocking {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 2)
            val admission = RefreshAdmission(slots, config(perRefresh = 2, perInstance = 3))
            admission.admit(workspace, executions = 2).admitted().use { first ->
                first.reservation.shouldNotBeNull().next().close()
                slots.inFlight shouldBe 1
                admission.admit(workspace, executions = 1).admitted().use {
                    admission.executionsReserved shouldBe 3
                    slots.inFlight shouldBe 2
                    first.reservation.shouldNotBeNull().next().close()
                    slots.inFlight shouldBe 1
                    admission.admit(workspace, executions = 1) shouldBe AdmissionResult.Busy
                }
            }
            admission.executionsReserved shouldBe 0
            slots.inFlight shouldBe 0
        }
    }

    @Test
    fun `when the instance slots are full it holds NOTHING - not the workspace place, not the execution count`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 4)
            val blocker = slots.acquireInstanceOnly(3, java.time.Duration.ZERO).shouldNotBeNull()
            val admission = RefreshAdmission(slots, config())

            admission.admit(workspace, executions = 3) shouldBe AdmissionResult.Busy // 1 slot free, 3 asked

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

            admitted.admitted().close()
        }

    @Test
    fun `a refresh needing more executions than one refresh may run is never admissible - naming that key`() =
        runBlocking<Unit> {
            RefreshAdmission(ExecutionSlots(1, 100), config(perRefresh = 4)).admit(workspace, executions = 5) shouldBe
                AdmissionResult.NeverAdmissible(5, 4, PER_REFRESH_KEY)
        }

    @Test
    fun `a refresh over the executor ceiling is refused at once, not at the deadline - and holds nothing`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 3)
            val admission = RefreshAdmission(slots, config(waitSeconds = 10))
            val started = System.nanoTime()

            val result = admission.admit(workspace, executions = 4)

            ((System.nanoTime() - started) / NANOS_PER_MILLI < 1_000) shouldBe true // the bound is 10 s; it took microseconds
            result shouldBe AdmissionResult.NeverAdmissible(executions = 4, ceiling = 3, key = EXECUTOR_KEY)
            admission.refreshesIn(workspace) shouldBe 0
            admission.executionsReserved shouldBe 0
            slots.inFlight shouldBe 0
        }

    @Test
    fun `raising the executor ceiling to the refresh's executions admits it`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 4)

            val admitted = RefreshAdmission(slots, config(waitSeconds = 10)).admit(workspace, executions = 4).admitted()

            slots.inFlight shouldBe 4
            admitted.close()
        }

    @Test
    fun `the smaller ceiling is the one named - a tie names the per-refresh bound`() =
        runBlocking<Unit> {
            RefreshAdmission(ExecutionSlots(1, 3), config(perRefresh = 16)).admit(workspace, executions = 20) shouldBe
                AdmissionResult.NeverAdmissible(20, 3, EXECUTOR_KEY)
            RefreshAdmission(ExecutionSlots(1, 20), config(perRefresh = 16)).admit(workspace, executions = 25) shouldBe
                AdmissionResult.NeverAdmissible(25, 16, PER_REFRESH_KEY)
            RefreshAdmission(ExecutionSlots(1, 16), config(perRefresh = 16)).admit(workspace, executions = 17) shouldBe
                AdmissionResult.NeverAdmissible(17, 16, PER_REFRESH_KEY)
        }

    @Test
    fun `a busy instance still waits to the deadline and answers Busy - the 429 for a genuinely full instance is unchanged`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 4)
            val blocker = slots.acquireInstanceOnly(4, java.time.Duration.ZERO).shouldNotBeNull()
            val started = System.nanoTime()

            val result = RefreshAdmission(slots, config(waitSeconds = 1)).admit(workspace, executions = 2)

            result shouldBe AdmissionResult.Busy
            ((System.nanoTime() - started) / NANOS_PER_MILLI >= 1_000) shouldBe true // it waited the whole bound
            slots.inFlight shouldBe 4
            blocker.close()
        }

    @Test
    fun `a refresh with no execution is admitted without a reservation but still holds its workspace place`() =
        runBlocking<Unit> {
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 100)
            val admission = RefreshAdmission(slots, config())

            val admitted = admission.admit(workspace, executions = 0).admitted()

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

            results.count { it is AdmissionResult.Admitted } shouldBe 4
            slots.inFlight shouldBe 4
            results.filterIsInstance<AdmissionResult.Admitted>().forEach { it.admission.close() }
            slots.inFlight shouldBe 0
        }

    @Test
    fun `a negative execution count is a programming error`() {
        shouldThrow<IllegalArgumentException> { runBlocking { RefreshAdmission(ExecutionSlots(1, 1), config()).admit(workspace, -1) } }
    }

    /** The places of an [AdmissionResult.Admitted]; any other answer fails the case. */
    private fun AdmissionResult.admitted(): Admission = (this as? AdmissionResult.Admitted).shouldNotBeNull().admission

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val EXECUTOR_KEY = "datapipelines.executor.max-concurrent-executions-per-instance"
        const val PER_REFRESH_KEY = "datapipelines.dashboards.admission.max-executions-per-refresh"
    }
}
