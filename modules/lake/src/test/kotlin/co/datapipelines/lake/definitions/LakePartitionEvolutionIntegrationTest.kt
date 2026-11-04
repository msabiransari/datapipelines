package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.partition
import co.datapipelines.pipeline.WriteSurface
import io.kotest.matchers.shouldBe
import org.apache.iceberg.Table
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "Add a partition field" is a COMPATIBLE change (lake.md §4): a release adding two partition fields is one more
 * Iceberg commit whose spec reads back with the original field and both new ones, by source field id and transform.
 */
class LakePartitionEvolutionIntegrationTest {
    private val h = LakeDefinitionsHarness()

    @BeforeEach
    fun clean() = h.reset()

    @Test
    fun `v2 adding bucket and truncate partition fields commits once and the spec reads back as intent(v2)`() {
        val v1 = h.service.createDraft(NS, T, ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        val before = h.revisions(NS, T)

        val draft = h.service.writeDraft(v1.tableId, V2_PARTITIONED, v1.bodyHash, h.actor, WriteSurface.SESSION)
        val outcome = h.service.release(v1.tableId, draft.version, h.actor, WriteSurface.SESSION).apply
        val after = h.revisions(NS, T)
        val spec = load().spec().fields().map { it.sourceId() to it.transform().toString() }
        println("(partition) revisions $before -> $after; spec = $spec")

        outcome shouldBe LakeApplyOutcome.Applied(2, committed = true)
        after shouldBe before + 1
        spec shouldBe listOf(4 to "day", 1 to "bucket[8]", 2 to "truncate[4]")
        LakeTableShape.of(load()) shouldBe LakeTableShape.of(LakeDefinitionBodies.parse(V2_PARTITIONED, null))
        h.table(v1.tableId).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
    }

    private fun load(): Table {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        try {
            return h.catalogs.open(lease).use { it.loadTable(NS, T) }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private companion object {
        const val NS = "sales"
        const val T = "orders"

        /** `orders` v1's columns, partitioned by day(ordered_at) as v1 is, plus bucket[8](id) and truncate[4](customer). */
        val V2_PARTITIONED: String =
            body(
                listOf(
                    field(1, "id", "BIGINTEGER", "required" to true, "doc" to "order id"),
                    field(2, "customer", "STRING", "tags" to listOf("pii")),
                    field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
                    field(4, "ordered_at", "TIMESTAMP", "required" to true),
                ),
                partition = listOf(partition(4, "day"), partition(1, "bucket[8]"), partition(2, "truncate[4]")),
                identifiers = listOf(1),
            )
    }
}
