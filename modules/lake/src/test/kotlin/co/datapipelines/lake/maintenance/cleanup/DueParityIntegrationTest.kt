package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.lake.maintenance.plan.MaintenancePartNames
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Brief 962 (an addition): each part re-checks after admission whether it is due (957 D6), and that check must agree with
 * 959's planner rule ([co.datapipelines.lake.maintenance.plan.MaintenanceDue], this deployment's grace) in every state
 * the record names, or a task would be enqueued for a part that then declines, or never enqueued for one that would run.
 */
class DueParityIntegrationTest {
    private val h = CleanupHarness()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `cleanup - never cleaned, cleaned longer ago than the interval, within it, and a failed run in between`() {
        val interval = h.profiles.forTable(h.table("probe").id).cleanupIntervalHours.toLong()
        val cases =
            mapOf<String, (MaintenanceTable) -> Unit>(
                "never cleaned" to {},
                "cleaned interval + 1 h ago" to { h.cleaned(it, interval + 1) },
                "cleaned 1 h ago" to { h.cleaned(it, 1) },
                "cleaned 1 h ago, then a failed run" to {
                    h.cleaned(it, 1)
                    h.runRow(it, "failed")
                },
            )
        val expected = listOf(true, true, false, false)
        cases.entries.forEachIndexed { i, (name, setup) ->
            val t = h.table("parity_c$i")
            h.append(t)
            setup(t)
            val planner = MaintenancePartNames.CLEANUP in requireNotNull(h.due(t)).due
            val part = h.cleanup().dueNow(h.run(t))
            withClue(name) {
                part shouldBe planner
                part shouldBe expected[i]
            }
            println("(962 parity cleanup) $name: planner=$planner part=$part")
        }
    }

    @Test
    fun `purge deletion - not finalised, not passed, inside the grace, past it, and stamped`() {
        val now = h.m.dbNow()
        val cases =
            listOf(
                Triple("not finalised", null, false),
                Triple("protected until 1 h ahead", now + Duration.ofHours(1), false),
                Triple("passed 1 h ago, inside the 2 h grace", now - Duration.ofHours(1), false),
                Triple("passed 3 h ago, past the grace", now - Duration.ofHours(3), true),
            )
        cases.forEachIndexed { i, (name, until, expected) ->
            val t = h.purged(h.table("parity_p$i").also { h.append(it) }, until)
            check(t, name, expected)
        }
        val stamped = h.purged(h.table("parity_stamped").also { h.append(it) }, now - Duration.ofHours(3))
        h.reads.stampPurgeDeleted(stamped.id) shouldBe true
        check(stamped, "stamped", expected = false)
    }

    private fun check(
        t: MaintenanceTable,
        name: String,
        expected: Boolean,
    ) {
        val planner = MaintenancePartNames.PURGE_DELETION in h.due(t)?.due.orEmpty()
        val part = h.purge().dueNow(h.run(t))
        withClue(name) {
            part shouldBe planner
            part shouldBe expected
        }
        println("(962 parity purge) $name: planner=$planner part=$part")
    }
}
