package co.datapipelines.lake.maintenance.cleanup

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.MaintenanceRunRow
import co.datapipelines.lake.maintenance.plan.ProfileMaintenanceGate
import co.datapipelines.lake.profiles.LakePauseKind
import co.datapipelines.lake.profiles.LakeProfileRepository
import co.datapipelines.lake.profiles.LakeTablePauses
import co.datapipelines.logcapture.capturingLogEvents
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Error
import java.time.Duration

/**
 * Brief 962 D (C14, C14a) on Postgres and MinIO: the `cleanup` part through 959's real planner, 957's scheduler seam
 * and the production worker; every row of D2a, each followed by the NEXT real plan, which finds the cleanup due unless
 * the part ended `done` or `nothing to do` in a `finished` run.
 */
class CleanupWiringIntegrationTest {
    private val h = CleanupHarness()
    private val later = LaterPart()
    private val partLog = LoggerFactory.getLogger(CleanupPart::class.java) as Logger

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() = h.close()

    @Test
    fun `C14 a cleanup due by its interval runs through the planner and the scheduler, and a paused one changes nothing`() {
        val t = h.table("c14")
        val snapshots = h.keptAndExpirable(t, "c14_k")
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        val interval =
            h.profiles
                .forTable(t.id)
                .cleanupIntervalHours
                .toLong()
        h.cleaned(t, hoursAgo = interval + 1)
        val worker = h.worker(gate = ProfileMaintenanceGate(h.m.named))
        val pauses = LakeTablePauses(LakeProfileRepository(h.m.defs.named), h.m.defs.tx, h.m.audit) { h.m.dbNow() }
        pauses.set(t.id, LakePauseKind.CLEANUP, h.m.dbNow() + Duration.ofDays(1), h.m.defs.actor)

        val enqueuedWhilePaused = h.throughScheduler(t, worker)
        worker.run(t.id.toString()) // a task enqueued before the pause began reaches the worker all the same

        val paused = h.m.rows(t).last { it.kind == "maintenance" && it.partsJson.contains("paused_until") }
        cleanupPart(paused)["outcome"] shouldBe MaintenanceOutcome.PAUSED_UNTIL.wire
        h.tags(t) shouldBe emptySet()
        h.snapshotIds(t) shouldBe listOf(snapshots.expired, snapshots.kept, snapshots.current)
        h.exists(orphan) shouldBe true

        pauses.clear(t.id, LakePauseKind.CLEANUP, h.m.defs.actor)
        val enqueued = h.throughScheduler(t, worker)

        enqueued shouldBe true
        val row = h.m.rows(t).maxBy { it.startedAt }
        row.state shouldBe "finished"
        val part = cleanupPart(row)
        part["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        part["tags_created"] shouldBe 2
        part["snapshots_expired"] shouldBe 1
        part["files"] shouldBe 1 // the orphan: the expired snapshot's own files stay reached through the metadata log (958)
        h.exists(orphan) shouldBe false
        h.tags(t).size shouldBe 2
        println(
            "(962 C14) paused: enqueued=$enqueuedWhilePaused worker outcome=${cleanupPart(paused)["outcome"]} tags=0 orphan kept; " +
                "unpaused through the scheduler: enqueued=$enqueued outcome=${part["outcome"]} deleted=${part["files"]} expected=1",
        )
    }

    @Test
    fun `C14a (i) tags and an expiry committed, then the removal stops - failed, both stay, one stopped event, due again`() {
        val t = h.table("c14a_i")
        val snapshots = h.keptAndExpirable(t, "c14a_i_k")
        h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.faults.onDelete = { index, _, _ -> if (index == 0) throw SdkClientException.create("injected: batch failed") else null }

        val events = capturingLogEvents(partLog) { h.throughScheduler(t, h.worker(parts = parts())) shouldBe true }
        h.faults.onDelete = null
        val dueAgain = h.planned(t)
        val row = h.m.rows(t).single()
        val stopped = events.map { it.formattedMessage }.filter { "event=lake.cleanup.stopped" in it }
        println(
            "(962 C14a i) row=${row.state} ${row.errorCode} tags=${h.tags(
                t,
            ).size} later ran=${later.ran.get()} due again=$dueAgain event: $stopped",
        )

        dueAgain shouldBe true // the next plan finds the cleanup due: not postponed by an interval
        row.state shouldBe "failed"
        row.errorCode shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLEANUP_INCOMPLETE
        h.tags(t).size shouldBe 2
        h.snapshotIds(t) shouldBe listOf(snapshots.kept, snapshots.current)
        stopped.size shouldBe 1
        stopped.single() shouldContain "reason=delete_failed"
        stopped.single() shouldContain "tags_created=2"
        stopped.single() shouldContain "snapshots_expired=1"
        later.ran.get() shouldBe 0
    }

    @Test
    fun `C14a (ii) a complete pass with a young key left - done, and the interval resets`() {
        val t = h.table("c14a_ii")
        h.append(t)
        val aged = h.orphan(t)
        val young = h.orphan(t).also { h.store.young += it }
        h.store.age = CleanupHarness.OLD
        h.planned(t) shouldBe true // never cleaned: due before the run, so the check below can fail

        h.worker(parts = parts()).run(t.id.toString())

        val row = h.m.rows(t).single()
        row.state shouldBe "finished"
        val part = cleanupPart(row)
        part["outcome"] shouldBe MaintenanceOutcome.DONE.wire
        part["left_young"] shouldBe 1
        h.exists(aged) shouldBe false
        h.exists(young) shouldBe true
        later.ran.get() shouldBe 1
        h.planned(t) shouldBe false
        val left = part.filterKeys { it.startsWith("left_") }
        println("(962 C14a ii) outcome=${part["outcome"]} deleted=${part["files"]} expected=1 left=$left due again=false")
    }

    @Test
    fun `C14a (iii) a skew refusal - failed clock_skew, nothing deleted, due again`() {
        val t = h.table("c14a_iii")
        h.append(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.removerClock.shift(Duration.ofSeconds(SKEW_S))

        h.worker(parts = parts()).run(t.id.toString())

        val row = h.m.rows(t).single()
        row.state shouldBe "failed"
        row.errorCode shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLOCK_SKEW
        h.exists(orphan) shouldBe true
        later.ran.get() shouldBe 0
        h.planned(t) shouldBe true
        println("(962 C14a iii) row=${row.state} ${row.errorCode} deleted=0 expected=0 due again=true")
    }

    @Test
    fun `C14a (iv) the mode changes between two batches - failed cleanup_incomplete reason mode, due again`() {
        val t = h.table("c14a_iv")
        h.append(t)
        h.orphans(t, TWO_BATCHES)
        h.store.age = CleanupHarness.OLD
        h.faults.afterDelete = { index -> if (index == 0) h.setMode("restoring") }

        val events = capturingLogEvents(partLog) { h.worker(parts = parts()).run(t.id.toString()) }

        val row = h.m.rows(t).single()
        row.state shouldBe "failed"
        row.errorCode shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLEANUP_INCOMPLETE
        val stopped = events.map { it.formattedMessage }.single { "event=lake.cleanup.stopped" in it }
        stopped shouldContain "reason=mode"
        stopped shouldContain "not_attempted=1"
        h.setMode("normal")
        h.planned(t) shouldBe true
        println("(962 C14a iv) row=${row.state} ${row.errorCode} deleted=${countOf(stopped, "deleted")} expected=1000 event: $stopped")
    }

    @Test
    fun `C14a (v) the lease is lost between two batches - the part ends deadline, later parts not attempted, due again`() {
        val t = h.table("c14a_v")
        h.append(t)
        h.orphans(t, TWO_BATCHES)
        h.store.age = CleanupHarness.OLD
        h.faults.afterDelete = { index -> if (index == 0) h.loseTableLease(t) }

        h.worker(parts = parts()).run(t.id.toString())

        val row = h.m.rows(t).single()
        row.state shouldBe "finished"
        cleanupPart(row)["outcome"] shouldBe MaintenanceOutcome.DEADLINE.wire
        val rewrite = h.parts(row).single { it["name"] == later.name }
        rewrite["outcome"] shouldBe MaintenanceOutcome.DEADLINE.wire
        rewrite["attempted"] shouldBe false
        later.ran.get() shouldBe 0
        h.planned(t) shouldBe true
        println(
            "(962 C14a v) part=${cleanupPart(
                row,
            )["outcome"]} deleted=${cleanupPart(row)["files"]} expected=1000 later=$rewrite due again=true",
        )
    }

    @Test
    fun `C14a (vi) one key refused in a pass that otherwise completed - failed cleanup_incomplete, due again`() {
        val t = h.table("c14a_vi")
        h.append(t)
        val refused = h.orphan(t)
        val other = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        h.faults.onDelete = { index, request, real -> if (index == 0) refuseOne(request, real, refused) else null }

        h.worker(parts = parts()).run(t.id.toString())

        val row = h.m.rows(t).single()
        row.state shouldBe "failed"
        row.errorCode shouldBe PipelineErrorCodes.Lake.MAINTENANCE_CLEANUP_INCOMPLETE
        h.exists(refused) shouldBe true
        h.exists(other) shouldBe false
        h.faults.onDelete = null
        h.planned(t) shouldBe true
        println("(962 C14a vi) row=${row.state} ${row.errorCode} refused kept, other deleted (1 of 1 expected) due again=true")
    }

    private fun parts() = listOf(h.cleanup(), later, h.purge())

    private fun cleanupPart(row: MaintenanceRunRow): Map<String, Any?> = h.parts(row).single { it["name"] == CleanupPart.NAME }

    private fun countOf(
        line: String,
        key: String,
    ): String = Regex("\\b$key=(\\d+)").find(line)?.groupValues?.get(1) ?: "?"

    /** The first batch: every key confirmed except [refused], which comes back as a per-key error (958 V14a's shape). */
    private fun refuseOne(
        request: DeleteObjectsRequest,
        real: software.amazon.awssdk.services.s3.S3Client,
        refused: String,
    ): DeleteObjectsResponse {
        val requested = request.delete().objects().map { it.key() }
        val rest = requested - refused
        val confirmed =
            if (rest.isEmpty()) {
                emptyList()
            } else {
                real
                    .deleteObjects(
                        DeleteObjectsRequest
                            .builder()
                            .bucket(request.bucket())
                            .delete(Delete.builder().objects(rest.map { ObjectIdentifier.builder().key(it).build() }).build())
                            .build(),
                    ).deleted()
            }
        val errors =
            requested.filter { it == refused }.map {
                S3Error
                    .builder()
                    .key(it)
                    .code("AccessDenied")
                    .message("injected")
                    .build()
            }
        return DeleteObjectsResponse
            .builder()
            .deleted(confirmed)
            .errors(errors)
            .build()
    }

    private companion object {
        /** Past the probe's bound (3600 s + the statement timeout): the remover refuses for skew. */
        const val SKEW_S = 3665L

        /** One more than 958's batch of 1,000 keys: a check runs between the two batches. */
        const val TWO_BATCHES = 1001
    }
}
