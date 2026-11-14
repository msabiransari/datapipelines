package co.datapipelines.lake.maintenance

import co.datapipelines.scheduler.SystemJobOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * **R13b of #957** (D3): the order of admission as recorded call traces. A recording slot, scratch, row store and parts
 * write one trace; each case asserts the exact sequence and the state at each call. No trace shows a table read before the
 * row exists: a declaration is handed a [MaintenanceTable], which holds no handle that can read Iceberg (asserted).
 *
 * The run's deadline checks read the catalog's clock, so the module's Postgres is used; nothing else is.
 */
class MaintenanceAdmissionOrderTest {
    private val h = MaintenanceHarness()
    private val trace = mutableListOf<String>()
    private val table = MaintenanceTable(UUID.randomUUID(), "maint", "orders", "applied")

    @AfterEach
    fun close() = h.close()

    @Test
    fun `a due part that declares scratch - declare, reserve, row, settle, due, run, close, scratch, slot`() {
        val store = RecordingStore()
        val outcome = worker(store, listOf(TracingPart("cleanup", scratch = 100, store = store))).run(table.id.toString())

        outcome shouldBe SystemJobOutcome.Done
        trace shouldContainExactly
            listOf(
                "slot.acquire",
                "declare:cleanup",
                "reserve:100",
                "admit:scratch=100",
                "settle(row=running)",
                "progress:",
                "due:cleanup(row=running,scratch=held)",
                "run:cleanup(row=running,scratch=held)",
                "progress:cleanup=done",
                "close:finished:cleanup=done",
                "scratch.release",
                "slot.release",
            )
    }

    @Test
    fun `no part due - the row is written, nothing to do, nothing reserved`() {
        val store = RecordingStore()
        worker(
            store,
            listOf(TracingPart("cleanup", scratch = 100, expectsDue = false, due = false, store = store)),
        ).run(table.id.toString())

        trace shouldContainExactly
            listOf(
                "slot.acquire",
                "declare:cleanup",
                "admit:scratch=0",
                "settle(row=running)",
                "progress:",
                "due:cleanup(row=running,scratch=none)",
                "progress:cleanup=nothing_to_do",
                "close:finished:cleanup=nothing_to_do",
                "slot.release",
            )
    }

    @Test
    fun `every declaration zero - reserve is never called`() {
        val store = RecordingStore()
        worker(
            store,
            listOf(TracingPart("cleanup", store = store), TracingPart("rewrite", order = 2, store = store)),
        ).run(table.id.toString())

        trace.filter { it.startsWith("reserve") || it.startsWith("scratch") } shouldContainExactly emptyList()
        trace.first { it.startsWith("admit") } shouldBe "admit:scratch=0"
        trace.last() shouldBe "slot.release"
    }

    @Test
    fun `scratch refused beside a scratch-free part - the scratch part is capacity and not run, the other runs`() {
        val store = RecordingStore()
        val parts = listOf(TracingPart("compaction", order = 3, scratch = 100, store = store), TracingPart("cleanup", store = store))

        worker(store, parts, grant = false).run(table.id.toString())

        trace shouldContainExactly
            listOf(
                "slot.acquire",
                "declare:cleanup",
                "declare:compaction",
                "reserve:100=refused",
                "admit:scratch=0",
                "settle(row=running)",
                "progress:",
                "due:cleanup(row=running,scratch=none)",
                "run:cleanup(row=running,scratch=none)",
                "progress:cleanup=done",
                "due:compaction(row=running,scratch=none)",
                "progress:cleanup=done,compaction=capacity",
                "close:finished:cleanup=done,compaction=capacity",
                "slot.release",
            )
    }

    @Test
    fun `a scratch part first due after declaration is capacity and never starts`() {
        val store = RecordingStore()
        val part = TracingPart("compaction", scratch = 100, expectsDue = false, due = true, store = store)

        worker(store, listOf(part)).run(table.id.toString()) shouldBe SystemJobOutcome.Done

        trace.filter { it.startsWith("reserve:") || it.startsWith("run:") } shouldContainExactly emptyList()
        trace.takeLast(3) shouldContainExactly
            listOf("progress:compaction=capacity", "close:finished:compaction=capacity", "slot.release")
    }

    @Test
    fun `an earlier live run - no row, the slot and the reservation released, the task again at that deadline`() {
        val live = Instant.parse("2026-11-14T05:00:00Z")
        val store = RecordingStore(earlierLive = live)

        val outcome = worker(store, listOf(TracingPart("cleanup", scratch = 100, store = store))).run(table.id.toString())

        outcome shouldBe SystemJobOutcome.AgainAt(live)
        trace shouldContainExactly
            listOf("slot.acquire", "declare:cleanup", "reserve:100", "admit:scratch=100=earlier_live", "scratch.release", "slot.release")
    }

    @Test
    fun `a part that throws - the row is failed and both releases still happen`() {
        val store = RecordingStore()
        worker(store, listOf(TracingPart("cleanup", scratch = 100, store = store, throws = true))).run(table.id.toString())

        trace.takeLast(4) shouldContainExactly
            listOf("run:cleanup(row=running,scratch=held)", "close:failed:cleanup=failed", "scratch.release", "slot.release")
    }

    @Test
    fun `a declaration is handed nothing that can read the table`() {
        val fields =
            MaintenanceTable::class.java.declaredFields.filterNot {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers)
            }
        fields.map { it.type.name } shouldContainExactly
            listOf("java.util.UUID", "java.lang.String", "java.lang.String", "java.lang.String")
    }

    private var scratchHeld = false

    private fun worker(
        store: RecordingStore,
        parts: List<MaintenancePart>,
        grant: Boolean = true,
    ): MaintenanceWorker {
        val slots =
            object : MaintenanceSlots {
                override fun tryAcquire(): Boolean {
                    trace += "slot.acquire"
                    return true
                }

                override fun release() {
                    trace += "slot.release"
                }
            }
        val scratch =
            MaintenanceScratch { bytes ->
                if (grant) {
                    trace += "reserve:$bytes"
                    scratchHeld = true
                    AutoCloseable {
                        scratchHeld = false
                        trace += "scratch.release"
                    }
                } else {
                    trace += "reserve:$bytes=refused"
                    null
                }
            }
        val settlement =
            MaintenanceSettlement {
                trace += "settle(row=${store.state})"
                SettlementRecord(0, 0, leftUnsettled = false, deferred = false)
            }
        return h.worker(parts = parts, slots = slots, scratch = scratch, settlement = settlement, store = store)
    }

    private fun summary(parts: List<PartRecord>) = parts.joinToString(",") { "${it.name}=${it.outcome.wire}" }

    /** The run row store, recording; the row is `running` from the admission until the close. */
    private inner class RecordingStore(
        private val earlierLive: Instant? = null,
    ) : MaintenanceRunStore {
        var state = "absent"

        override fun catalogNormal() = true

        override fun findTable(tableId: UUID) = table.takeIf { it.id == tableId }

        override fun admit(
            runId: UUID,
            tableId: UUID,
            kind: MaintenanceRunKind,
            timeout: Duration,
            scratchReservedBytes: Long,
        ): Admission {
            if (earlierLive != null) {
                trace += "admit:scratch=$scratchReservedBytes=earlier_live"
                return Admission.EarlierRunLive(earlierLive)
            }
            trace += "admit:scratch=$scratchReservedBytes"
            state = "running"
            return Admission.Admitted(runId, h.dbNow().plus(timeout), emptyList())
        }

        override fun recordProgress(
            runId: UUID,
            parts: List<PartRecord>,
            settlement: SettlementRecord?,
        ): Boolean {
            check(state == "running") { "progress recorded on a row that is $state" }
            trace += "progress:${summary(parts)}"
            return true
        }

        override fun close(
            runId: UUID,
            state: MaintenanceRunState,
            parts: List<PartRecord>,
            settlement: SettlementRecord?,
            errorCode: String?,
            errorMessage: String?,
        ): Boolean {
            trace += "close:${state.wire}:${summary(parts)}"
            this.state = state.wire
            return true
        }
    }

    /** A part that records its calls and the row's and the scratch's state at each. */
    private inner class TracingPart(
        override val name: String,
        override val order: Int = 1,
        private val scratch: Long = 0,
        private val expectsDue: Boolean = true,
        private val due: Boolean = true,
        private val store: RecordingStore,
        private val throws: Boolean = false,
    ) : MaintenancePart {
        override fun declare(table: MaintenanceTable): MaintenanceDeclaration {
            check(store.state == "absent") { "declare after the row exists" }
            trace += "declare:$name"
            return MaintenanceDeclaration(expectsDue, scratch)
        }

        override fun dueNow(run: MaintenanceRun): Boolean {
            trace += "due:$name(${states()})"
            return due
        }

        override fun run(run: MaintenanceRun): PartResult {
            trace += "run:$name(${states()})"
            check(!throws) { "part $name throws" }
            return PartResult(MaintenanceOutcome.DONE)
        }

        private fun states() = "row=${store.state},scratch=${if (scratchHeld) "held" else "none"}"
    }
}
