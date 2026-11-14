package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceTable
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import java.time.Duration

/**
 * Brief 962 C (C11–C13a) on Postgres and MinIO: the `purge_deletion` part of a purged table, dropped through the
 * catalog. It deletes nothing before the final revision's `protected_until` plus the grace; it stamps
 * `purge_deleted_at` only when 958 answers *finished*; *more to do* and every failure leave the stamp null, so the
 * next real plan enqueues the table again.
 */
class PurgeDeletionIntegrationTest {
    private val h = CleanupHarness()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C11 nothing goes before the final revision's protected_until plus the grace, then data, manifests and metadata go`() {
        val t = h.table("c11")
        repeat(2) { h.append(t) }
        val keep = h.recipe(t)
        val now = h.m.dbNow()
        val p = h.purged(t, until = null)
        h.store.age = CleanupHarness.OLD // every file older than the remover's grace: only the part's own check holds them
        val before = classes(p)
        (before.getValue(DATA) > 0 && before.getValue(MANIFESTS) > 0 && before.getValue(METADATA) > 0) shouldBe true

        // not finalised; finalised but not passed; passed but the grace not passed since (G = 2 h)
        listOf(null, now + Duration.ofHours(1), now - Duration.ofHours(1)).forEach { until ->
            h.finalUntil(p, until)
            h.purge().dueNow(h.run(p)) shouldBe false
            val held = h.purge().run(h.run(p))
            held.outcome shouldBe MaintenanceOutcome.NOTHING_TO_DO
            held.figures.files shouldBe 0
            classes(p) shouldBe before
            h.purgeDeletedAt(p) shouldBe null
            println("(962 C11) protected_until=$until: outcome=${held.outcome} deleted=${held.figures.files} expected=0")
        }

        h.finalUntil(p, now - CleanupHarness.OLD)
        h.purge().dueNow(h.run(p)) shouldBe true
        val ripe = h.purge().run(h.run(p))

        ripe.outcome shouldBe MaintenanceOutcome.DONE
        classes(p) shouldBe mapOf(DATA to 0, MANIFESTS to 0, METADATA to 0, RECIPES to 1)
        h.exists(keep) shouldBe true
        val expected = before.getValue(DATA) + before.getValue(MANIFESTS) + before.getValue(METADATA)
        ripe.figures.files shouldBe expected.toLong()
        println("(962 C11) past protected_until + grace: before=$before deleted=${ripe.figures.files} expected=$expected, recipe kept")
    }

    @Test
    fun `C12 a purged table holding a recipe ends finished - the recipe stays, stamped, the next plan enqueues nothing`() {
        val t = h.table("c12")
        h.append(t)
        val keep = h.recipe(t)
        val p = h.purged(t, until = h.m.dbNow() - LONG_AGO)
        h.store.age = CleanupHarness.OLD
        h.planned(p) shouldBe true // the guard below can fail: the plan does enqueue this table while it is not stamped

        h.worker().run(p.id.toString())

        val row = h.m.rows(p).single()
        row.state shouldBe "finished"
        row.kind shouldBe "purge"
        val part = h.parts(row).single()
        part["name"] shouldBe PurgeDeletionPart.NAME
        part["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        part["purge"] shouldBe "finished"
        part["purge_stamped"] shouldBe true
        part["deferred_deletions"] shouldBe 0
        h.exists(keep) shouldBe true
        h.purgeDeletedAt(p) shouldNotBe null
        h.listing(p).map { it.key } shouldBe listOf(keep)
        h.planned(p) shouldBe false
        println("(962 C12) parts=${row.partsJson} deleted=${part["files"]} stamped=${h.purgeDeletedAt(p)} next plan enqueues=false")
    }

    @Test
    fun `C13 a pass leaving a young key does not stamp and records it, the next plan enqueues it again, later it finishes`() {
        val t = h.table("c13")
        h.append(t)
        val p = h.purged(t, until = h.m.dbNow() - LONG_AGO)
        val young = h.orphan(p).also { h.store.young += it }
        h.store.age = CleanupHarness.OLD

        h.worker().run(p.id.toString())

        val first = h.parts(h.m.rows(p).single()).single()
        first["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        first["purge"] shouldBe "more_to_do"
        first["purge_stamped"] shouldBe false
        first["deferred_deletions"] shouldBe 1
        (first["left"] as Map<*, *>)["young"] shouldBe 1
        h.m
            .rows(p)
            .single()
            .figures.deferredDeletions shouldBe 1
        h.exists(young) shouldBe true
        h.purgeDeletedAt(p) shouldBe null
        h.planned(p) shouldBe true

        h.store.young.clear() // the key has aged

        h.worker().run(p.id.toString())

        val second = h.parts(h.m.rows(p).maxBy { it.startedAt }).single()
        second["purge"] shouldBe "finished"
        second["purge_stamped"] shouldBe true
        second["files"] shouldBe 1
        h.exists(young) shouldBe false
        h.purgeDeletedAt(p) shouldNotBe null
        h.planned(p) shouldBe false
        println(
            "(962 C13) pass1 deleted=${first["files"]} left=${first["left"]} deferred=${first["deferred_deletions"]} stamped=false " +
                "enqueued again=true; pass2 deleted=${second["files"]} expected=1 (the aged key) stamped=true",
        )
    }

    @Test
    fun `C13a a failed delete batch ends the run failed cleanup_incomplete, no stamp, enqueued again, then a clean pass stamps`() {
        val t = h.table("c13a")
        h.append(t)
        val p = h.purged(t, until = h.m.dbNow() - LONG_AGO)
        h.store.age = CleanupHarness.OLD
        h.faults.onDelete = { index, _, _ -> if (index == 0) throw SdkClientException.create("injected: batch failed") else null }

        runCatching { h.worker().run(p.id.toString()) }

        val failed = h.m.rows(p).single()
        failed.state shouldBe "failed"
        failed.errorCode shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLEANUP_INCOMPLETE
        h.purgeDeletedAt(p) shouldBe null
        h.planned(p) shouldBe true

        h.faults.onDelete = null
        h.worker().run(p.id.toString())

        val clean = h.m.rows(p).maxBy { it.startedAt }
        clean.state shouldBe "finished"
        h.parts(clean).single()["purge"] shouldBe "finished"
        h.purgeDeletedAt(p) shouldNotBe null
        h.listing(p) shouldBe emptyList()
        println(
            "(962 C13a) failed run error=${failed.errorCode} stamped=false enqueued again=true; clean pass deleted=" +
                "${h.parts(clean).single()["files"]} stamped=true",
        )
    }

    /** The table's keys by class: data files, manifests and manifest lists, metadata files, recipes. */
    private fun classes(t: MaintenanceTable): Map<String, Int> {
        val keys = h.listing(t).map { it.key }.filterNot { "/dp/clock/" in it }
        return mapOf(
            DATA to keys.count { "/data/" in it },
            MANIFESTS to keys.count { "/metadata/" in it && it.endsWith(".avro") },
            METADATA to keys.count { it.endsWith(".metadata.json") },
            RECIPES to keys.count { "/dp/recipes/" in it },
        )
    }

    private companion object {
        const val DATA = "data"
        const val MANIFESTS = "manifests"
        const val METADATA = "metadata"
        const val RECIPES = "recipes"

        /** Past the planner harness's 72 h grace too, so the real plan's due rule agrees with the part's. */
        val LONG_AGO: Duration = Duration.ofHours(100)
    }
}
