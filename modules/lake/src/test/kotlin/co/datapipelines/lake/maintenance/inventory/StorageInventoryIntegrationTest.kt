package co.datapipelines.lake.maintenance.inventory

import ch.qos.logback.classic.Logger
import co.datapipelines.lake.maintenance.MaintenanceOutcome
import co.datapipelines.lake.maintenance.cleanup.CleanupClockSkew
import co.datapipelines.lake.maintenance.cleanup.CleanupHarness
import co.datapipelines.lake.maintenance.cleanup.ObserverOutcome
import co.datapipelines.lake.maintenance.cleanup.TWO_DAY_KEEP
import co.datapipelines.lake.maintenance.plan.PlanFigures
import co.datapipelines.lake.maintenance.plan.PlanFiguresReader
import co.datapipelines.logcapture.capturingLogEvents
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Duration

/**
 * Brief 964 B (B5–B8, B8a; record row K5) on Postgres and MinIO: the production cleanup (962) hands its listing and walk
 * to the production observer, which stores six rows; every row is checked against what the fixture built, and the
 * total against the test's OWN listing (never the remover's) taken right after the cleanup.
 */
class StorageInventoryIntegrationTest {
    private val f = InventoryFixture()
    private val h: CleanupHarness = f.h
    private val log = LoggerFactory.getLogger(StorageInventoryObserver::class.java) as Logger

    @BeforeEach
    fun reset() {
        h.reset()
        f.observations.clear()
    }

    @AfterEach
    fun close() = h.close()

    @Test
    fun `B5 B7 K5 the fixture - every row equals its expected value, the rows add up, the remover deletes no keep-held file`() {
        val t = h.table("k5")
        val now = h.m.dbNow()
        h.createdAt(t, now - Duration.ofDays(KEEP_CREATED_DAYS))
        h.keepProfile(t, "k5_keep", TWO_DAY_KEEP)
        // sA (10 days, untagged: the cleanup expires it) adds g; s1 (10 days, kept by the profile's two day keeps) adds f1.
        val (sA, g) = f.add(t, G).also { (id, _) -> h.restamp(t, mapOf(id to now - Duration.ofDays(OLD_DAYS) - Duration.ofHours(1))) }
        val (s1, f1) = f.add(t, F1).also { (id, _) -> h.restamp(t, mapOf(id to now - Duration.ofDays(OLD_DAYS))) }
        // Three rewrite generations of the keep-only file (f1 → f2 → f3), then g rewritten: g2 and f3 are current.
        val (s2, f2) = f.rewrite(t, f1, F2)
        val (_, f3) = f.rewrite(t, f2, F3)
        val (s4, g2) = f.rewrite(t, g, G2)
        f.tag(t, "dp-hold-k5", s2) // a tagged snapshot still inside the window
        val recipe = f.objectAt(t, "dp/recipes/2026/11/14/k5.json", RECIPE)
        val abandoned = h.orphan(t).also { h.store.young += it }
        val inFlight = f.objectAt(t, "data/in-flight-${s4}.parquet", IN_FLIGHT).also { h.store.young += it }
        h.put(abandoned, ABANDONED)
        val oldOrphan = h.orphan(t) // past the grace and unreached: the same cleanup deletes it (B7 is not vacuous)
        h.finalise(t)
        h.protect(h.revisionOf(t, s2)) // an earlier protected revision that lists s1 (and sA, s2)
        h.store.age = CleanupHarness.OLD // every unreached key but the two young ones is past the grace

        val result = h.cleanup(observer = f.observer()).run(h.run(t))

        val listing = h.listing(t)
        val stored = f.measured(t)
        val o = f.observations.single()
        val metadataPrefix = h.target(t).keyPrefix + "metadata/"
        val expected =
            mapOf(
                StorageRow.DP_RECORDS to RowFigure(1, RECIPE.toLong()),
                StorageRow.TABLE_METADATA to f.figureOf(listing) { it.startsWith(metadataPrefix) },
                StorageRow.CURRENT to RowFigure(2, (F3 + G2).toLong()),
                StorageRow.HISTORY to RowFigure(2, (G + F2).toLong()),
                StorageRow.KEEP_HELD to RowFigure(1, F1.toLong()),
                StorageRow.NOT_REFERENCED to RowFigure(2, (ABANDONED + IN_FLIGHT).toLong()),
            )
        println("(964 B5/K5) expected=$expected")
        println("(964 B5/K5) measured=${stored.breakdown.rows}")
        println(
            "(964 B5/K5) snapshots sA=$sA s1=$s1 s2=$s2 s4=$s4 expired=${result.details["snapshots_expired"]} floor=${result.details["floor"]} " +
                "deleted=${result.figures.files} listing=${listing.size}/${listing.sumOf { it.size }} at=${stored.measuredAt}",
        )
        stored.breakdown.rows shouldBe expected
        (stored.breakdown.objects to stored.breakdown.bytes) shouldBe (listing.size.toLong() to listing.sumOf { it.size })
        listing.map { it.key }.toSet() shouldBe
            setOf(recipe, f1, g, f2, f3, g2, abandoned, inFlight) + listing.map { it.key }.filter { it.startsWith(metadataPrefix) }
        (expected.getValue(StorageRow.TABLE_METADATA).objects > 0) shouldBe true
        stored.measuredAt shouldBe o.listingTime
        stored.runId shouldBe o.runId
        result.details["observer"] shouldBe ObserverOutcome.OK.wire
        result.details["snapshots_expired"] shouldBe 1 // sA: the keep-held s1 survived the same cleanup
        // B7: none of the keep-held files is deleted by the cleanup that produced the inventory, though older than the grace.
        val keepHeld = StorageClassifier.place(ClassifierInput.of(o)).filter { it.row == StorageRow.KEEP_HELD }.map { it.listed.key }
        keepHeld shouldBe listOf(f1)
        keepHeld.forEach { h.exists(it) shouldBe true }
        // The remover did delete in this cleanup, by the same age rule the keep-held file passes: only reachability kept f1.
        result.figures.files shouldBe 1
        h.exists(oldOrphan) shouldBe false
    }

    @Test
    fun `B6 K5 two sources, two times - an append and a rewrite between the planner's pass and the cleanup`() {
        val t = h.table("b6")
        val (_, a) = f.add(t, PLAN_A)
        h.p.pass(h.p.planner())
        val planner = PlanFiguresReader(NamedParameterJdbcTemplate(h.jdbc)).figures(t.id)!!
        f.add(t, PLAN_B)
        f.rewrite(t, a, PLAN_C)

        h.cleanup(observer = f.observer()).run(h.run(t))

        val stored = f.measured(t)
        val current = stored.breakdown.row(StorageRow.CURRENT)
        println(
            "(964 B6) planner current=${planner.currentBytes} at ${planner.measuredAt}; " +
                "inventory current=${current.bytes} at ${stored.measuredAt}",
        )
        planner.currentBytes shouldBe PLAN_A.toLong()
        current shouldBe RowFigure(2, (PLAN_B + PLAN_C).toLong())
        planner.currentBytes shouldNotBe current.bytes
        planner.measuredAt shouldNotBe null
        (planner.measuredAt!! < stored.measuredAt) shouldBe true
        // Neither read returns a figure of the other, so no method can return their sum.
        StorageInventory.Measured::class.java.declaredFields.map { it.name }.toSet() shouldBe setOf("measuredAt", "runId", "breakdown")
        PlanFigures::class.java.declaredFields.none { it.type == StorageInventory::class.java || it.type == StorageBreakdown::class.java } shouldBe
            true
    }

    @Test
    fun `B8 a second cleanup replaces the six rows, a refused pass leaves them, a table with no cleanup is not measured yet`() {
        val t = h.table("b8_replace")
        val other = h.table("b8_never")
        f.add(t, PLAN_A)
        val firstRun = h.run(t)
        h.cleanup(observer = f.observer()).run(firstRun)
        val first = f.measured(t)
        f.add(t, PLAN_B)

        h.removerClock.shift(Duration.ofSeconds(SKEW_S))
        shouldThrow<CleanupClockSkew> { h.cleanup(observer = f.observer()).run(h.run(t)) }
        h.removerClock.reset()
        f.measured(t) shouldBe first

        val secondRun = h.run(t)
        h.cleanup(observer = f.observer()).run(secondRun)
        val second = f.measured(t)

        println("(964 B8 replace) first=${first.runId}@${first.measuredAt} ${first.breakdown}; second=${second.runId}@${second.measuredAt} ${second.breakdown}")
        first.runId shouldBe firstRun.runId
        second.runId shouldBe secondRun.runId
        (second.measuredAt > first.measuredAt) shouldBe true
        first.breakdown.row(StorageRow.CURRENT) shouldBe RowFigure(1, PLAN_A.toLong())
        second.breakdown.row(StorageRow.CURRENT) shouldBe RowFigure(2, (PLAN_A + PLAN_B).toLong())
        h.jdbc.queryForObject("SELECT count(*) FROM lake_storage_inventory WHERE table_id = ?", Int::class.java, t.id) shouldBe ROWS
        f.repository.forTable(other.id) shouldBe StorageInventory.NotMeasured
    }

    @Test
    fun `B8 an inventory failure leaves the cleanup's outcome as it was and its result says observer failed`() {
        val t = h.table("b8_throws")
        f.add(t, PLAN_A)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD

        val result = h.cleanup(observer = f.observer { error("the classifier failed") }).run(h.run(t))

        println("(964 B8 throws) outcome=${result.outcome} deleted=${result.figures.files} observer=${result.details["observer"]}")
        result.outcome shouldBe MaintenanceOutcome.DONE
        result.figures.files shouldBe 1
        h.exists(orphan) shouldBe false
        result.details["observer"] shouldBe ObserverOutcome.FAILED.wire
        f.repository.forTable(t.id) shouldBe StorageInventory.NotMeasured
    }

    @Test
    fun `B8 D5 a totals mismatch stores nothing - the first cleanup's rows and time stay, observer failed, totals logged`() {
        val t = h.table("b8_mismatch")
        f.add(t, PLAN_A)
        h.cleanup(observer = f.observer()).run(h.run(t))
        val first = f.measured(t)
        val orphan = h.orphan(t)
        h.store.age = CleanupHarness.OLD
        val dropsOne = StorageClassification { input -> StorageClassifier.place(input).dropLast(1) }

        var result: co.datapipelines.lake.maintenance.PartResult? = null
        val events = capturingLogEvents(log) { result = h.cleanup(observer = f.observer(dropsOne)).run(h.run(t)) }

        val r = requireNotNull(result)
        val lines = events.map { it.formattedMessage }.filter { "event=lake.inventory.totals_mismatch" in it }
        val listed = f.observations.last().inventory
        println("(964 B8 mismatch) outcome=${r.outcome} deleted=${r.figures.files} observer=${r.details["observer"]} event: $lines")
        // The stored rows and the observer's outcome first: they are what D5 protects; the log line is its report.
        f.measured(t) shouldBe first
        r.details["observer"] shouldBe ObserverOutcome.FAILED.wire
        r.outcome shouldBe MaintenanceOutcome.DONE
        r.figures.files shouldBe 1
        h.exists(orphan) shouldBe false
        val line = lines.single()
        line shouldContain "listed_objects=${listed.size} listed_bytes=${listed.sumOf { it.size }}"
        line shouldContain "row_objects=${listed.size - 1}"
        line shouldNotContain h.target(t).keyPrefix
    }

    @Test
    fun `B8a never published, real - committed metadata, abandoned first-append files inside the grace, dp objects`() {
        val t = h.table("b8a")
        val d1 = f.objectAt(t, "data/first-append-1.parquet", PLAN_A)
        val d2 = f.objectAt(t, "data/first-append-2.parquet", PLAN_B)
        val recipe = f.objectAt(t, "dp/recipes/2026/11/14/first.json", RECIPE)

        val result = h.cleanup(observer = f.observer()).run(h.run(t))

        val listing = h.listing(t)
        val stored = f.measured(t)
        val metadataPrefix = h.target(t).keyPrefix + "metadata/"
        val expected =
            mapOf(
                StorageRow.DP_RECORDS to RowFigure(1, RECIPE.toLong()),
                StorageRow.TABLE_METADATA to f.figureOf(listing) { it.startsWith(metadataPrefix) },
                StorageRow.CURRENT to RowFigure.ZERO,
                StorageRow.HISTORY to RowFigure.ZERO,
                StorageRow.KEEP_HELD to RowFigure.ZERO,
                StorageRow.NOT_REFERENCED to RowFigure(2, (PLAN_A + PLAN_B).toLong()),
            )
        println("(964 B8a) floor=${result.details["floor"]} expected=$expected measured=${stored.breakdown.rows}")
        f.observations.single().currentSnapshotId shouldBe null
        result.details["floor"] shouldBe "not_applicable"
        stored.breakdown.rows shouldBe expected
        (expected.getValue(StorageRow.TABLE_METADATA).objects > 0) shouldBe true
        (stored.breakdown.objects to stored.breakdown.bytes) shouldBe (listing.size.toLong() to listing.sumOf { it.size })
        listing.map { it.key }.toSet() shouldBe setOf(d1, d2, recipe) + listing.map { it.key }.filter { it.startsWith(metadataPrefix) }
    }

    private companion object {
        const val KEEP_CREATED_DAYS = 3L
        const val OLD_DAYS = 10L
        const val ROWS = 6

        /** Past the remover's skew bound: it refuses before it lists. */
        const val SKEW_S = 3665L

        // Distinct object sizes, so a byte sum names exactly which objects a row holds.
        const val G = 203
        const val F1 = 101
        const val F2 = 307
        const val F3 = 401
        const val G2 = 503
        const val ABANDONED = 601
        const val IN_FLIGHT = 701
        const val RECIPE = 53
        const val PLAN_A = 1009
        const val PLAN_B = 2003
        const val PLAN_C = 3001
    }
}
