package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTableProperties
import org.apache.iceberg.NullOrder
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortDirection
import org.apache.iceberg.SortField
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types

/**
 * The definition-owned state of a table (dp-lake design record §7, §11a call 36 item 2): its columns by field id
 * (name, type, required, doc, in schema order), its partition fields, its identifier fields and its sort order. The
 * apply state machine compares shapes, never metadata pointers: an Iceberg table and a body with equal shapes hold
 * the same definition. Tags are DP's own and are not part of a shape; nor are properties or snapshots.
 *
 * [sortOrder] is the order as Iceberg holds it, field by field (source id, transform, direction, null order), so an
 * order changed outside DP to another direction or transform differs too; an unsorted table's is empty. The order id
 * is not part of a shape.
 */
data class LakeTableShape(
    val columns: List<LakeColumnShape>,
    val partition: List<LakePartitionShape>,
    val identifierFieldIds: Set<Int>,
    val sortOrder: List<LakeSortFieldShape>,
) {
    fun column(fieldId: Int): LakeColumnShape? = columns.firstOrNull { it.fieldId == fieldId }

    companion object {
        /** The shape [body] intends. */
        fun of(body: LakeDefinitionBody): LakeTableShape =
            LakeTableShape(
                body.fields.map { LakeColumnShape(it.id, it.name, it.icebergType, it.required, it.doc) },
                body.partition.map { LakePartitionShape(it.fieldId, it.transform) },
                body.identifierFieldIds.toSet(),
                body.sortFieldIds.map(LakeSortFieldShape::ascending),
            )

        /** The shape [table] holds now, read from its current schema, default spec and sort order. */
        fun of(table: Table): LakeTableShape = of(table.schema(), table.spec(), table.sortOrder())

        fun of(
            schema: Schema,
            spec: PartitionSpec,
            sortOrder: SortOrder,
        ): LakeTableShape =
            LakeTableShape(
                schema.columns().map {
                    LakeColumnShape(
                        it.fieldId(),
                        it.name(),
                        it.type(),
                        it.isRequired,
                        it.doc()?.takeUnless(String::isEmpty),
                    )
                },
                spec.fields().map { LakePartitionShape(it.sourceId(), it.transform().toString()) },
                schema.identifierFieldIds().toSet(),
                sortOrder.fields().map(LakeSortFieldShape::of),
            )

        /** The Iceberg schema [body] creates a table with (ids as listed: Iceberg keeps 1..n of a first definition). */
        fun schemaOf(body: LakeDefinitionBody): Schema =
            Schema(
                body.fields.map {
                    (if (it.required) Types.NestedField.required(it.name) else Types.NestedField.optional(it.name))
                        .withId(it.id)
                        .ofType(it.icebergType)
                        .withDoc(it.doc)
                        .build()
                },
                body.identifierFieldIds.toSet(),
            )

        /** The partition spec [body] creates a table with, bound to [schema]. */
        fun specOf(
            body: LakeDefinitionBody,
            schema: Schema,
        ): PartitionSpec {
            if (body.partition.isEmpty()) return PartitionSpec.unpartitioned()
            val builder = PartitionSpec.builderFor(schema)
            body.partition.forEach { p ->
                val name = requireNotNull(body.field(p.fieldId)) { "partition field ${p.fieldId} is not a field" }.name
                LakeTransforms.addTo(builder, name, p.transform)
            }
            return builder.build()
        }

        /**
         * The sort order [body] creates a table with, bound to [schema]: each sort column ascending, nulls first, both
         * written out (call 36 item 2's fixed order); unsorted when the body has no sort columns (a legacy table).
         */
        fun sortOrderOf(
            body: LakeDefinitionBody,
            schema: Schema,
        ): SortOrder {
            if (body.sortFieldIds.isEmpty()) return SortOrder.unsorted()
            val builder = SortOrder.builderFor(schema)
            body.sortFieldIds.forEach { id ->
                val name = requireNotNull(body.field(id)) { "sort column $id is not a field" }.name
                builder.asc(name, NullOrder.NULLS_FIRST)
            }
            return builder.build()
        }

        /** The definition version a table's properties record, or null when the table carries none. */
        fun definitionVersionOf(table: Table): Int? = table.properties()[LakeTableProperties.DEFINITION_VERSION]?.toIntOrNull()

        /**
         * Does [table] hold rows? No current snapshot: no. Otherwise its summary's `total-records`; a snapshot whose
         * summary lacks the key is assumed to hold rows (the conservative answer for the required-column rule).
         */
        fun hasRows(table: Table): Boolean {
            val snapshot = table.currentSnapshot() ?: return false
            val total = snapshot.summary()?.get(TOTAL_RECORDS)?.toLongOrNull() ?: return true
            return total > 0
        }

        private const val TOTAL_RECORDS = "total-records"
    }
}

/** One column of a [LakeTableShape]. [doc] is null when absent or empty (Iceberg stores either). */
data class LakeColumnShape(
    val fieldId: Int,
    val name: String,
    val type: Type,
    val required: Boolean,
    val doc: String?,
)

/** One partition field of a [LakeTableShape]: its source field id and its transform string (`day`, `bucket[16]`). */
data class LakePartitionShape(
    val sourceId: Int,
    val transform: String,
)

/** One sort field of a [LakeTableShape], as Iceberg holds it: source field id, transform string, direction, null order. */
data class LakeSortFieldShape(
    val sourceId: Int,
    val transform: String,
    val direction: SortDirection,
    val nullOrder: NullOrder,
) {
    override fun toString(): String = "$transform($sourceId) $direction $nullOrder"

    companion object {
        /** The only sort field a body spells: [sourceId] by identity, ascending, nulls first. */
        fun ascending(sourceId: Int): LakeSortFieldShape = LakeSortFieldShape(sourceId, IDENTITY, SortDirection.ASC, NullOrder.NULLS_FIRST)

        fun of(field: SortField): LakeSortFieldShape =
            LakeSortFieldShape(field.sourceId(), field.transform<Any, Any>().toString(), field.direction(), field.nullOrder())

        private const val IDENTITY = "identity"
    }
}
