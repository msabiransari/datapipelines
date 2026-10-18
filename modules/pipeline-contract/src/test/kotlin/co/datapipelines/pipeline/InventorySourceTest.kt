package co.datapipelines.pipeline

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random

class InventorySourceTest {
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `SQL groups objects in the correct family with bound workspace and lens`(family: NavigationFamily) {
        val (table, versions, fk) =
            when (family) {
                NavigationFamily.PIPELINES -> Triple("pipelines", "pipeline_versions", "pipeline_id")
                NavigationFamily.TEMPLATES -> Triple("templates", "template_versions", "template_id")
                NavigationFamily.DASHBOARDS -> Triple("dashboards", "dashboard_versions", "dashboard_id")
                NavigationFamily.VISUALIZATIONS -> Triple("visualizations", "visualization_versions", "visualization_id")
                NavigationFamily.PARAMETER_SETS -> Triple("parameter_sets", "parameter_set_versions", "parameter_set_id")
            }
        listOf(false, true).forEach { narrowed ->
            val sql = InventorySource.sql(family, narrowed)
            sql shouldContain "FROM $table s"
            sql shouldContain "JOIN $versions v ON v.$fk = s.id"
            sql shouldContain "s.workspace_id = :workspace"
            sql shouldContain "GROUP BY s.id"
            sql shouldNotContain "current_version"
            sql shouldNotContain "LIMIT"
            if (narrowed) {
                sql shouldContain "AND s.name IN (:names) AND v.status = 'RELEASED'"
            } else {
                sql shouldNotContain ":names"
                sql shouldNotContain "AND v.status"
            }
            println("family=$family narrowed=$narrowed\n$sql")
        }
    }

    /**
     * #556 — the catalog's lifecycle condition computes the SAME three flags as the inventory's
     * statement (the aggregate's text is unchanged, so the flags are spelled twice; this pins the two
     * spellings equal), over the same admitted versions, and reads the bucket's own predicate.
     */
    @ParameterizedTest
    @EnumSource(NavigationFamily::class)
    fun `the lifecycle membership reads the inventory's flags and the bucket's predicate`(family: NavigationFamily) {
        val normalize = { text: String -> text.replace(Regex("\\s+"), " ").trim() }
        val inventoryFlags = normalize(InventorySource.sql(family, narrowed = false).substringAfter("SELECT ").substringBefore(" FROM "))
        LifecycleBucket.entries.forEach { bucket ->
            listOf(false, true).forEach { narrowed ->
                val condition = InventorySource.membership(family, "t", bucket, narrowed)
                normalize(condition.substringAfter("SELECT 1 FROM (SELECT ").substringBefore(" FROM ")) shouldBe inventoryFlags
                condition shouldContain "WHERE v.${family.foreignKey} = t.id"
                condition shouldContain ") a WHERE ${bucket.predicate})"
                if (narrowed) condition shouldContain "AND v.status = 'RELEASED'" else condition shouldNotContain "AND v.status"
                condition shouldNotContain ":"
            }
        }
    }

    @Test
    fun `every lifecycle wire value names one bucket and nothing else does`() {
        LifecycleBucket.entries.forEach { LifecycleBucket.ofWire(it.wire) shouldBe it }
        listOf("", "DRAFT", "Draft", "draft ", "discarded-only", "' OR 1=1 --").forEach { LifecycleBucket.ofWire(it) shouldBe null }
        val inventory = Inventory(1, 2, 3, 4)
        LifecycleBucket.entries.associateWith { it.countIn(inventory) } shouldBe
            mapOf(
                LifecycleBucket.DRAFT_ONLY to 1L,
                LifecycleBucket.RELEASED_ONLY to 2L,
                LifecycleBucket.RELEASED_WITH_DRAFT to 3L,
                LifecycleBucket.DISCARDED_ONLY to 4L,
                LifecycleBucket.WITH_DRAFTS to 4L,
                LifecycleBucket.WITH_RELEASES to 5L,
            )
    }

    @Test
    fun `random bucket arithmetic preserves distinct totals and overlapping draft release counts`() {
        val random = Random(553)
        repeat(10_000) {
            val buckets = List(4) { random.nextLong(0, Int.MAX_VALUE.toLong() + 1) }
            val inventory = Inventory(buckets[0], buckets[1], buckets[2], buckets[3])
            inventory.objects shouldBe buckets.sum()
            inventory.withDrafts shouldBe buckets[0] + buckets[2]
            inventory.withReleases shouldBe buckets[1] + buckets[2]
        }
        Inventory(0, 0, 0, 0).objects shouldBe 0L
    }
}
