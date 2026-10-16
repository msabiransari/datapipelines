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
