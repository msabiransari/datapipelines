package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.iceberg.NullOrder
import org.apache.iceberg.ReplaceSortOrder
import org.apache.iceberg.SortDirection
import org.apache.iceberg.Table
import org.apache.iceberg.TableProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The sort order in Iceberg (lake.md §1 and §4; dp-lake design record §11a call 36 item 2, row M11), end to end through
 * the service, the applier, the JdbcCatalog and MinIO: I1 the first apply writes the order and the sort columns'
 * metrics properties; I2 an order changed outside DP is drift, and putting it back applies; I3 a legacy table stays
 * unsorted; I6 a compatible version keeps the order; I7 widening a sort column. Each case prints what it rests on.
 */
class LakeSortOrderApplyIntegrationTest {
    private val h = LakeDefinitionsHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `I1 - the first apply writes the sort order, ascending nulls first, and one metrics property per sort column`() {
        createAndRelease(SORTED_V1)
        val table = load()
        val order = table.sortOrder()
        println("(I1) sort order = $order; properties = ${table.properties()}")
        order.orderId() shouldNotBe 0
        order.fields().map { listOf(it.sourceId(), it.transform<Any, Any>().toString(), it.direction(), it.nullOrder()) } shouldBe
            listOf(
                listOf(2, "identity", SortDirection.ASC, NullOrder.NULLS_FIRST),
                listOf(1, "identity", SortDirection.ASC, NullOrder.NULLS_FIRST),
            )
        table.properties().filterKeys { it.startsWith(TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX) } shouldBe
            mapOf(
                "write.metadata.metrics.column.customer" to "truncate(16)",
                "write.metadata.metrics.column.id" to "truncate(16)",
            )
    }

    @Test
    fun `I2 - an order changed outside DP is drift with one sort_order change, and putting it back applies`() {
        val id = createAndRelease(SORTED_V1)
        replaceOrder { it.desc("customer", NullOrder.NULLS_FIRST).desc("id", NullOrder.NULLS_FIRST) }
        val descending = releaseNext(id, SORTED_V1_TAGGED)
        println("(I2) descending: $descending")
        sortOrderDrift(descending)

        replaceOrder { it }
        load().sortOrder().isUnsorted shouldBe true
        val unsorted = h.applier.apply(id, 2, deadline(), h.actor)
        println("(I2) unsorted: $unsorted")
        sortOrderDrift(unsorted)

        replaceOrder { it.asc("customer", NullOrder.NULLS_FIRST).asc("id", NullOrder.NULLS_FIRST) }
        h.applier.apply(id, 2, deadline(), h.actor) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.APPLIED to 2) }
    }

    @Test
    fun `I3 - a legacy table is created unsorted, takes a keyless compatible version, and refuses the key`() {
        val id = h.insertUnvalidatedDraft(NS, T, LEGACY_V1)
        h.landWithoutApply(id, 1)
        h.applier.apply(id, 1, deadline(), h.actor) shouldBe LakeApplyOutcome.Applied(1, committed = true)
        load().sortOrder().isUnsorted shouldBe true
        load().properties().keys.none { it.startsWith(TableProperties.METRICS_MODE_COLUMN_CONF_PREFIX) } shouldBe true

        releaseNext(id, LEGACY_V2) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        load().schema().findField(3).name() shouldBe "channel"
        load().sortOrder().isUnsorted shouldBe true

        val hash = requireNotNull(h.definitions.findVersion(id, 2)).bodyHash
        val keyed = body(LEGACY_V2_FIELDS + field(4, "note"), sort = listOf(1))
        val refusal = shouldThrow<DatapipelinesException> { h.service.writeDraft(id, keyed, hash, h.actor, WriteSurface.SESSION) }
        (refusal.code to refusal.details["reason"]) shouldBe (PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY to "locked")
        h.definitions.findDraft(id) shouldBe null
    }

    @Test
    fun `I6 - a compatible version keeps the table's sort order, id and fields`() {
        val id = createAndRelease(SORTED_V1)
        val before = load().sortOrder()
        val ordersBefore = load().sortOrders().keys
        releaseNext(id, SORTED_V2) shouldBe LakeApplyOutcome.Applied(2, committed = true)
        val after = load().sortOrder()
        println("(I6) sort order $before -> $after; orders $ordersBefore; field ids ${load().schema().columns().map { it.fieldId() }}")
        load().schema().findField(4).name() shouldBe "channel"
        after.orderId() shouldBe before.orderId()
        after.fields() shouldBe before.fields()
        load().sortOrders().keys shouldBe ordersBefore
    }

    @Test
    fun `I7 - widening a sort column from INTEGER to BIGINTEGER applies and keeps the order`() {
        val id = createAndRelease(NARROW)
        val before = load().sortOrder()
        val outcome = releaseNext(id, WIDE)
        val after = load()
        println("(I7) outcome = $outcome; type ${after.schema().findType(2)}; sort order $before -> ${after.sortOrder()}")
        outcome shouldBe LakeApplyOutcome.Applied(2, committed = true)
        after.schema().findType(2).toString() shouldBe "long"
        after.sortOrder().orderId() shouldBe before.orderId()
        after.sortOrder().fields() shouldBe before.fields()
    }

    private fun sortOrderDrift(outcome: LakeApplyOutcome) {
        outcome.shouldBeInstanceOf<LakeApplyOutcome.Failed>()
        outcome.code shouldBe PipelineErrorCodes.Lake.DEFINITION_DRIFT
        val diff = outcome.error["diff"].shouldBeInstanceOf<List<Map<String, Any?>>>()
        diff.map { it["kind"] to it["field_id"] } shouldBe listOf("sort_order" to null)
    }

    private fun createAndRelease(json: String): UUID {
        val v1 = h.service.createDraft(NS, T, json, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION).apply shouldBe LakeApplyOutcome.Applied(1, committed = true)
        return v1.tableId
    }

    private fun releaseNext(
        id: UUID,
        json: String,
    ): LakeApplyOutcome {
        val current = requireNotNull(h.definitions.findVersion(id, requireNotNull(h.table(id).currentDefinitionVersion)))
        val draft = h.service.writeDraft(id, json, current.bodyHash, h.actor, WriteSurface.SESSION)
        return h.service.release(id, draft.version, h.actor, WriteSurface.SESSION).apply
    }

    /** Replaces the table's sort order outside DP, under a foreign lease (an unchanged [edit] leaves it unsorted). */
    private fun replaceOrder(edit: (ReplaceSortOrder) -> ReplaceSortOrder) {
        val foreign = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        try {
            h.catalogs.open(foreign).use { s -> s.write { edit(s.loadTable(NS, T).replaceSortOrder()).commit() } }
        } finally {
            LakeTestDb.leases.release(foreign.operationId)
        }
    }

    private fun load(): Table {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        try {
            return h.catalogs.open(lease).use { it.loadTable(NS, T) }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun deadline() = LakeTestDb.leases.catalogNowPlus(Duration.ofMinutes(1))

    private companion object {
        const val NS = "sorted"
        const val T = "orders"

        val SORTED_V1_FIELDS =
            listOf(
                field(1, "id", "BIGINTEGER", "required" to true),
                field(2, "customer", "STRING", "tags" to listOf("pii")),
                field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
            )

        /** Sorted by customer, then id (the order differs from the field order on purpose). */
        val SORTED_V1 = body(SORTED_V1_FIELDS, sort = listOf(2, 1))

        /** [SORTED_V1] with one more tag: a version whose only change is DP's own, so its apply reads S first. */
        val SORTED_V1_TAGGED =
            body(
                listOf(SORTED_V1_FIELDS[0], field(2, "customer", "STRING", "tags" to listOf("pii", "contact")), SORTED_V1_FIELDS[2]),
                sort = listOf(2, 1),
            )

        /** [SORTED_V1] + an optional `channel` column: COMPATIBLE. */
        val SORTED_V2 = body(SORTED_V1_FIELDS + field(4, "channel"), sort = listOf(2, 1))

        val LEGACY_V1_FIELDS = listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "customer"))
        val LEGACY_V1 = body(LEGACY_V1_FIELDS, sort = null)
        val LEGACY_V2_FIELDS = LEGACY_V1_FIELDS + field(3, "channel")
        val LEGACY_V2 = body(LEGACY_V2_FIELDS, sort = null)

        val NARROW = body(listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "n", "INTEGER")), sort = listOf(2))
        val WIDE = body(listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "n", "BIGINTEGER")), sort = listOf(2))
    }
}
