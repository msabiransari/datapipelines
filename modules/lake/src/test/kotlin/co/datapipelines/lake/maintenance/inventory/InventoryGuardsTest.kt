package co.datapipelines.lake.maintenance.inventory

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.maintenance.LakeMaintenanceAutoConfiguration
import co.datapipelines.lake.maintenance.remove.RemovalTarget
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Brief 964's structural guards: D7 (no deletion path reads the figures), the documented row names (enums.md §62) are
 * the Kotlin wire values, the key rule matches the remover's, and the production observer is the storage breakdown.
 */
class InventoryGuardsTest {
    @Test
    fun `D7 nothing in the remove or cleanup packages reads the inventory's table, package or classifier`() {
        val forbidden = Regex("""lake_storage_inventory|maintenance\.inventory|StorageClassifier|StorageInventory|StorageBreakdown|StorageRow""")
        val scanned =
            listOf("remove", "cleanup").flatMap { pkg ->
                LakeTestDb
                    .repoFile("modules/lake/src/main/kotlin/co/datapipelines/lake/maintenance/$pkg")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }
        val offenders = scanned.filter { forbidden.containsMatchIn(it.readText()) }.map { it.name }

        offenders shouldBe emptyList()
        // Non-vacuity: both packages were read (958's remover and 962's cleanup).
        (scanned.size >= MIN_SCANNED_FILES) shouldBe true
        println("(964 D7) scanned=${scanned.size} offenders=$offenders expected=[]")
    }

    @Test
    fun `the documented storage rows are the Kotlin wire values, in order`() {
        val doc = LakeTestDb.repoFile("docs/enums.md").readText()
        val start = doc.indexOf("## 62. `StorageRow`")
        check(start >= 0) { "Missing enum heading: ## 62. `StorageRow`" }
        val section = doc.substring(start).substringBefore("\n---")
        val pairs =
            Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
                .findAll(section)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()

        pairs shouldBe StorageRow.entries.map { it.name to it.wire }
        pairs.size shouldBe ROWS
    }

    @Test
    fun `the inventory's key rule is the remover's RemovalTarget keyOf, location by location`() {
        val target =
            RemovalTarget(
                tableId = UUID.randomUUID(),
                namespace = "sales",
                name = "orders",
                status = "active",
                bucket = "lake-bucket",
                location = "s3://lake-bucket/lake/sales/2b1c",
                keyPrefix = "lake/sales/2b1c/",
            )
        val mine = TableLocation.parse(target.location)
        mine.keyPrefix shouldBe target.keyPrefix
        listOf(
            "s3://lake-bucket/lake/sales/2b1c/data/a.parquet",
            "s3a://lake-bucket/lake/sales/2b1c/metadata/v1.metadata.json",
            "s3n://lake-bucket/lake/sales/2b1c/data/b.parquet",
            "s3://other-bucket/lake/sales/2b1c/data/a.parquet",
            "s3://lake-bucket",
            "s3:///lake/x",
            "file:///tmp/a.parquet",
            "lake/sales/2b1c/data/relative.parquet",
        ).forEach { location -> mine.keyOf(location) shouldBe target.keyOf(location) }
    }

    @Test
    fun `the production cleanup observer is the storage breakdown`() {
        LakeMaintenanceAutoConfiguration()
            .lakeCleanupObserver(mockk<StorageInventoryRepository>())
            .shouldBeInstanceOf<StorageInventoryObserver>()
    }

    private companion object {
        const val ROWS = 6
        const val MIN_SCANNED_FILES = 15
    }
}
