package co.datapipelines.lake.definitions

import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types

/**
 * One difference between the applied definition and the intended one, in the policy's vocabulary (lake.md §4).
 * [fieldId] names the column (the partition field's source column for a partition change); [detail] is a short,
 * human-readable account (names and types only).
 */
data class LakeChange(
    val kind: LakeChangeKind,
    val fieldId: Int?,
    val detail: String,
)

/** The supported-change policy's rows (lake.md §4), each with its class. */
enum class LakeChangeKind(
    val changeClass: LakeChangeClass,
) {
    ADD_OPTIONAL(LakeChangeClass.COMPATIBLE),
    ADD_REQUIRED_EMPTY_TABLE(LakeChangeClass.COMPATIBLE),
    WIDEN(LakeChangeClass.COMPATIBLE),
    DOC(LakeChangeClass.COMPATIBLE),
    ADD_PARTITION_FIELD(LakeChangeClass.COMPATIBLE),
    RENAME(LakeChangeClass.REVIEWED),
    DROP(LakeChangeClass.REVIEWED),
    MAKE_OPTIONAL(LakeChangeClass.REVIEWED),
    REORDER(LakeChangeClass.REVIEWED),
    REMOVE_PARTITION_FIELD(LakeChangeClass.REVIEWED),
    REORDER_PARTITION(LakeChangeClass.REVIEWED),
    ADD_REQUIRED(LakeChangeClass.BREAKING),
    MAKE_REQUIRED(LakeChangeClass.BREAKING),
    IDENTIFIER_FIELDS(LakeChangeClass.BREAKING),
    TYPE_CHANGE(LakeChangeClass.BREAKING),
    ;

    val wire: String get() = name.lowercase()
}

/** The classifier's answer: the strictest class among [changes] (COMPATIBLE when there are none). */
data class LakeChangeVerdict(
    val changeClass: LakeChangeClass,
    val changes: List<LakeChange>,
)

/**
 * The supported-change policy (lake.md §4, dp-lake design record §7), as a pure function of two shapes. Only a
 * COMPATIBLE verdict is applied automatically; REVIEWED and BREAKING leave the table as it is and record why.
 *
 * Widening is the policy's explicit list (int → long, float → double, decimal precision up at the same scale), not
 * Iceberg's promotion table. A type change outside it is TYPE_CHANGE (narrowing or incompatible), BREAKING.
 */
object LakeChangeClassifier {
    fun classify(
        current: LakeTableShape,
        intent: LakeTableShape,
        tableHasRows: Boolean,
    ): LakeChangeVerdict {
        val changes = columnChanges(current, intent, tableHasRows) + partitionChanges(current, intent)
        val identifierChange =
            if (current.identifierFieldIds != intent.identifierFieldIds) {
                listOf(
                    LakeChange(
                        LakeChangeKind.IDENTIFIER_FIELDS,
                        null,
                        "identifier fields ${current.identifierFieldIds.sorted()} → ${intent.identifierFieldIds.sorted()}",
                    ),
                )
            } else {
                emptyList()
            }
        val all = changes + identifierChange
        val changeClass = all.maxOfOrNull { it.kind.changeClass } ?: LakeChangeClass.COMPATIBLE
        return LakeChangeVerdict(changeClass, all)
    }

    /** Is [to] a widening of [from] under the policy? */
    fun widens(
        from: Type,
        to: Type,
    ): Boolean =
        when {
            from is Types.IntegerType && to is Types.LongType -> true
            from is Types.FloatType && to is Types.DoubleType -> true
            from is Types.DecimalType && to is Types.DecimalType -> to.scale() == from.scale() && to.precision() > from.precision()
            else -> false
        }

    @Suppress("CyclomaticComplexMethod") // one branch per column row of the policy table (lake.md §4)
    private fun columnChanges(
        current: LakeTableShape,
        intent: LakeTableShape,
        tableHasRows: Boolean,
    ): List<LakeChange> {
        val out = ArrayList<LakeChange>()
        val currentIds = current.columns.map { it.fieldId }
        val intentIds = intent.columns.map { it.fieldId }
        current.columns.filter { it.fieldId !in intentIds }.forEach {
            out += LakeChange(LakeChangeKind.DROP, it.fieldId, "drop ${it.name}")
        }
        intent.columns.forEach { wanted ->
            val have = current.column(wanted.fieldId)
            if (have == null) {
                out += added(wanted, tableHasRows)
                return@forEach
            }
            if (have.name != wanted.name) out += LakeChange(LakeChangeKind.RENAME, wanted.fieldId, "rename ${have.name} → ${wanted.name}")
            if (have.type != wanted.type) {
                val kind = if (widens(have.type, wanted.type)) LakeChangeKind.WIDEN else LakeChangeKind.TYPE_CHANGE
                out += LakeChange(kind, wanted.fieldId, "${wanted.name}: ${have.type} → ${wanted.type}")
            }
            if (have.required &&
                !wanted.required
            ) {
                out += LakeChange(LakeChangeKind.MAKE_OPTIONAL, wanted.fieldId, "${wanted.name} optional")
            }
            if (!have.required &&
                wanted.required
            ) {
                out += LakeChange(LakeChangeKind.MAKE_REQUIRED, wanted.fieldId, "${wanted.name} required")
            }
            if (have.doc != wanted.doc) out += LakeChange(LakeChangeKind.DOC, wanted.fieldId, "${wanted.name} doc")
        }
        // Surviving columns keep their relative order, and new ones go after all of them: Iceberg appends.
        val surviving = intentIds.filter { it in currentIds }
        val lastSurvivor = intentIds.indexOfLast { it in currentIds }
        val newBeforeSurvivor = intentIds.withIndex().any { (i, id) -> id !in currentIds && i < lastSurvivor }
        if (surviving != currentIds.filter { it in intentIds } || newBeforeSurvivor) {
            out +=
                LakeChange(
                    LakeChangeKind.REORDER,
                    null,
                    "column order ${current.columns.map { it.name }} → ${intent.columns.map { it.name }}",
                )
        }
        return out
    }

    private fun added(
        wanted: LakeColumnShape,
        tableHasRows: Boolean,
    ): LakeChange =
        when {
            !wanted.required -> LakeChange(LakeChangeKind.ADD_OPTIONAL, wanted.fieldId, "add ${wanted.name} ${wanted.type}")
            tableHasRows -> LakeChange(LakeChangeKind.ADD_REQUIRED, wanted.fieldId, "add required ${wanted.name} to a table with rows")
            else -> LakeChange(LakeChangeKind.ADD_REQUIRED_EMPTY_TABLE, wanted.fieldId, "add required ${wanted.name} ${wanted.type}")
        }

    private fun partitionChanges(
        current: LakeTableShape,
        intent: LakeTableShape,
    ): List<LakeChange> {
        val out = ArrayList<LakeChange>()
        current.partition.filter { it !in intent.partition }.forEach {
            out += LakeChange(LakeChangeKind.REMOVE_PARTITION_FIELD, it.sourceId, "remove partition ${it.transform}(${it.sourceId})")
        }
        intent.partition.filter { it !in current.partition }.forEach {
            out += LakeChange(LakeChangeKind.ADD_PARTITION_FIELD, it.sourceId, "add partition ${it.transform}(${it.sourceId})")
        }
        val keptInCurrent = current.partition.filter { it in intent.partition }
        val keptInIntent = intent.partition.filter { it in current.partition }
        val lastKept = intent.partition.indexOfLast { it in current.partition }
        val addedBeforeKept = intent.partition.withIndex().any { (i, p) -> p !in current.partition && i < lastKept }
        if (keptInCurrent != keptInIntent || addedBeforeKept) {
            out += LakeChange(LakeChangeKind.REORDER_PARTITION, null, "partition order changed")
        }
        return out
    }
}
