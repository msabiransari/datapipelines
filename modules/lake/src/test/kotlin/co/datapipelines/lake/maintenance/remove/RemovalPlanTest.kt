package co.datapipelines.lake.maintenance.remove

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The pure decisions of a pass (D4–D6, D8): the skew interval's two ends, and every listed key's fate. */
class RemovalPlanTest {
    private val target =
        RemovalTarget(
            tableId = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            namespace = "sales",
            name = "orders",
            status = "active",
            bucket = "lake-bucket",
            location = "s3://lake-bucket/lake/sales/00000000-0000-0000-0000-0000000000aa",
            keyPrefix = "lake/sales/00000000-0000-0000-0000-0000000000aa/",
        )
    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val grace = Duration.ofHours(72)
    private val old = now.minus(grace).minusSeconds(1)
    private val atGrace = now.minus(grace)

    @Test
    fun `the skew interval admits only when both ends lie within the allowance`() {
        val allowance = Duration.ofSeconds(3_600)
        SkewInterval(Duration.ofSeconds(-3_600), Duration.ofSeconds(3_600)).within(allowance) shouldBe true
        SkewInterval(Duration.ofSeconds(-3_601), Duration.ofSeconds(-3_599)).within(allowance) shouldBe false
        SkewInterval(Duration.ofSeconds(3_599), Duration.ofSeconds(3_601)).within(allowance) shouldBe false
        // Midpoint 0 s, both ends outside: a midpoint test would admit it.
        SkewInterval(Duration.ofSeconds(-3_700), Duration.ofSeconds(3_700)).within(allowance) shouldBe false
        val lm = Instant.parse("2026-11-14T12:00:00Z")
        SkewInterval.of(before = lm.minusMillis(400), after = lm.plusMillis(300), lastModified = lm) shouldBe
            SkewInterval(Duration.ofMillis(-300), Duration.ofMillis(1_400))
    }

    @Test
    fun `every listed key is a candidate or left with exactly one reason`() {
        val own = UUID.randomUUID()
        val terminal = UUID.randomUUID()
        val live = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        val states = mapOf(terminal to true, live to false)
        val listing =
            listOf(
                obj("data/orphan.parquet", old),
                obj("data/at-grace.parquet", atGrace),
                obj("data/reached.parquet", old),
                obj("dp/recipes/f/d/o.json", old),
                obj("dp/clock/$own", now),
                obj("dp/clock/$terminal", old),
                obj("dp/clock/$live", old),
                obj("dp/clock/$unknown", old),
                obj("dp/clock/${terminal}x", old),
                obj("dp/clock/$terminal/nested", old),
                obj("dp/other-record", old),
                ListedObject("lake/sales/other/data/x.parquet", 1, old),
            )
        val plan =
            RemovalPlan.of(
                target,
                listing,
                setOf(key("data/reached.parquet")),
                now,
                grace,
                key("dp/clock/$own"),
                { states[it] },
                emptyList(),
            )
        plan.candidates.map { target.relative(it.key) } shouldBe listOf("data/orphan.parquet", "dp/clock/$terminal")
        plan.left.mapKeys { target.relative(it.key) } shouldBe
            mapOf(
                "data/at-grace.parquet" to LeftReason.YOUNG,
                "data/reached.parquet" to LeftReason.REACHED,
                "dp/recipes/f/d/o.json" to LeftReason.RECIPE,
                "dp/clock/$live" to LeftReason.MARKER_RUN_LIVE,
                "dp/clock/$unknown" to LeftReason.MARKER_RUN_UNKNOWN,
                "dp/clock/${terminal}x" to LeftReason.OTHER_DP_KEY,
                "dp/clock/$terminal/nested" to LeftReason.OTHER_DP_KEY,
                "dp/other-record" to LeftReason.OTHER_DP_KEY,
            )
        plan.otherDpKeys shouldBe listOf("dp/clock/${terminal}x", "dp/clock/$terminal/nested", "dp/other-record")
    }

    @Test
    fun `a young marker of a terminal run stays, and a recipe stays at any age`() {
        val run = UUID.randomUUID()
        val plan =
            RemovalPlan.of(
                target,
                listOf(obj("dp/clock/$run", now.minusSeconds(1)), obj("dp/recipes/a.json", Instant.EPOCH)),
                emptySet(),
                now,
                grace,
                key("dp/clock/${UUID.randomUUID()}"),
                { true },
                emptyList(),
            )
        plan.candidates shouldBe emptyList()
        plan.left.values.toList() shouldBe listOf(LeftReason.YOUNG, LeftReason.RECIPE)
    }

    @Test
    fun `hints are decided by the same tests, and a hint outside the location is never a candidate`() {
        val listing = listOf(obj("data/old.parquet", old), obj("data/young.parquet", now), obj("data/reached.parquet", old))
        val hints =
            listOf(
                "s3://lake-bucket/${key("data/old.parquet")}",
                "s3a://lake-bucket/${key("data/young.parquet")}",
                "s3://lake-bucket/${key("data/reached.parquet")}",
                "s3://lake-bucket/${key("dp/recipes/a.json")}",
                "s3://lake-bucket/${key("data/gone.parquet")}",
                "s3://other-bucket/${key("data/old.parquet")}",
                "s3://lake-bucket/lake/sales/other/data/x.parquet",
                "s3://lake-bucket/${target.keyPrefix.dropLast(1)}-evil/data/x.parquet",
                "file:///etc/passwd",
                key("data/old.parquet"),
            )
        val plan = RemovalPlan.of(target, listing, setOf(key("data/reached.parquet")), now, grace, key("dp/clock/x"), { null }, hints)
        plan.hints shouldBe
            mapOf(
                HintReason.YOUNG to 1,
                HintReason.REACHED to 1,
                HintReason.UNDER_DP to 1,
                HintReason.NOT_LISTED to 1,
                HintReason.OUTSIDE_LOCATION to 4,
            )
        plan.candidates.map { target.relative(it.key) } shouldBe listOf("data/old.parquet")
    }

    @Test
    fun `keyOf accepts only this bucket's s3 locations`() {
        target.keyOf("s3://lake-bucket/a/b") shouldBe "a/b"
        target.keyOf("s3n://lake-bucket/a") shouldBe "a"
        target.keyOf("s3://lake-bucket-2/a") shouldBe null
        target.keyOf("s3://lake-bucket") shouldBe null
        target.keyOf("gs://lake-bucket/a") shouldBe null
        target.markerKey(UUID.fromString("00000000-0000-0000-0000-000000000001")) shouldBe
            "lake/sales/00000000-0000-0000-0000-0000000000aa/dp/clock/00000000-0000-0000-0000-000000000001"
    }

    private fun key(relative: String) = "${target.keyPrefix}$relative"

    private fun obj(
        relative: String,
        lastModified: Instant,
    ) = ListedObject(key(relative), 1, lastModified)
}
