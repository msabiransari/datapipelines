package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTableProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import org.apache.iceberg.Table
import org.apache.iceberg.Transaction
import org.apache.iceberg.UpdateSchema
import org.apache.iceberg.types.TypeUtil
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §7 decision of one apply (dp-lake design record §7, lake.md §3), before anything is committed:
 * - no table → create it with its sort order (its fresh field ids must be the body's, or the create would silently
 *   renumber);
 * - S == intent(vN), `dp.definition.version` included → applied with no commit (the crash-between case);
 * - S == intent(vN) under another or no `dp.definition.version` → ONE commit that sets only the property (drift's
 *   remedy: the live shape adopted as vN; also a version that changes only tags, which are not part of a shape);
 * - S == intent(last applied) and the change is COMPATIBLE → ONE staged transaction, checked against intent before
 *   it is handed back;
 * - REVIEWED / BREAKING → refused, no commit; anything else → drift, with a field-by-field diff.
 *
 * S is read from the table's current schema, default spec, sort order and properties; "last applied" is the version its
 * `dp.definition.version` names, whose body [bodyOf] reads from the definition versions.
 */
internal class LakeApplyPlanner(
    private val bodyOf: (Int) -> LakeDefinitionBody?,
) {
    sealed interface Plan {
        data object AlreadyApplied : Plan

        data class Refuse(
            val code: String,
            val error: Map<String, Any?>,
        ) : Plan

        /** A commit to run through the session (a create, or the staged transaction's `commitTransaction`). */
        data class Commit(
            val run: () -> Unit,
        ) : Plan
    }

    fun forNewTable(
        intent: LakeDefinitionBody,
        create: () -> Unit,
    ): Plan {
        val schema = LakeTableShape.schemaOf(intent)
        val counter = AtomicInteger()
        val fresh = TypeUtil.assignFreshIds(schema) { counter.incrementAndGet() }
        val created = LakeTableShape.of(fresh, LakeTableShape.specOf(intent, fresh), LakeTableShape.sortOrderOf(intent, fresh))
        if (created != LakeTableShape.of(intent)) {
            return drift(REASON_FIELD_IDS, null, LakeChangeClassifier.classify(LakeTableShape.of(intent), created, false).changes)
        }
        return Plan.Commit(create)
    }

    @Suppress("ReturnCount") // each early return is one decision of lake.md §4, in the order the section lists them
    fun forExistingTable(
        table: Table,
        version: Int,
        intent: LakeDefinitionBody,
    ): Plan {
        val current = LakeTableShape.of(table)
        val intentShape = LakeTableShape.of(intent)
        val appliedVersion = LakeTableShape.definitionVersionOf(table)
        if (current == intentShape) {
            if (appliedVersion == version) return Plan.AlreadyApplied
            // The table already holds the intent under another stamp (or none): drift's remedy, the live shape adopted
            // as a new version, or a tags-only version. Only the version property moves; no schema, spec or data change.
            return Plan.Commit { table.updateProperties().set(LakeTableProperties.DEFINITION_VERSION, version.toString()).commit() }
        }
        val base = appliedVersion?.let(bodyOf)
        if (base == null) return drift(REASON_UNKNOWN_VERSION, appliedVersion, emptyList())
        val expected = LakeTableShape.of(base)
        if (expected != current) return drift(REASON_DRIFT, appliedVersion, LakeChangeClassifier.classify(expected, current, true).changes)
        val hasRows = LakeTableShape.hasRows(table)
        val verdict = LakeChangeClassifier.classify(current, intentShape, hasRows)
        when (verdict.changeClass) {
            LakeChangeClass.COMPATIBLE -> Unit
            LakeChangeClass.REVIEWED -> return refuse(PipelineErrorCodes.Lake.DEFINITION_REVIEWED_CHANGE, verdict, hasRows)
            LakeChangeClass.BREAKING -> return refuse(PipelineErrorCodes.Lake.DEFINITION_BREAKING_CHANGE, verdict, hasRows)
        }
        val txn = table.newTransaction()
        stage(txn, current, intent)
        txn.updateProperties().set(LakeTableProperties.DEFINITION_VERSION, version.toString()).commit()
        val staged = LakeTableShape.of(txn.table())
        if (staged != intentShape) {
            return drift(REASON_STAGED_MISMATCH, appliedVersion, LakeChangeClassifier.classify(intentShape, staged, hasRows).changes)
        }
        return Plan.Commit { txn.commitTransaction() }
    }

    /**
     * Stages the compatible changes in intent order: new columns (Iceberg numbers them `lastColumnId + 1` in call
     * order, which the body's id rule matches), widenings, docs; then the new partition fields.
     */
    private fun stage(
        txn: Transaction,
        current: LakeTableShape,
        intent: LakeDefinitionBody,
    ) {
        // Opened only when a column changes: an uncommitted UpdateSchema makes the transaction refuse every later
        // operation, so a version that changes no column (tags, logical types) must never open one.
        var opened: UpdateSchema? = null
        val schema = { opened ?: txn.updateSchema().also { opened = it } }
        intent.fields.forEach { wanted ->
            val have = current.column(wanted.id)
            if (have == null) {
                // Only an empty table reaches here with a required new column (ADD_REQUIRED_EMPTY_TABLE, COMPATIBLE).
                if (wanted.required) {
                    schema().allowIncompatibleChanges().addRequiredColumn(wanted.name, wanted.icebergType, wanted.doc)
                } else {
                    schema().addColumn(wanted.name, wanted.icebergType, wanted.doc)
                }
                return@forEach
            }
            if (have.type != wanted.icebergType) {
                schema().updateColumn(wanted.name, wanted.icebergType.asPrimitiveType())
            }
            if (have.doc != wanted.doc) {
                schema().updateColumnDoc(wanted.name, wanted.doc)
            }
        }
        opened?.commit()
        val added = intent.partition.filter { p -> current.partition.none { it.sourceId == p.fieldId && it.transform == p.transform } }
        if (added.isNotEmpty()) {
            val spec = txn.updateSpec()
            added.forEach { p ->
                val column = requireNotNull(intent.field(p.fieldId)) { "partition field ${p.fieldId} is not a field" }.name
                spec.addField(LakeTransforms.term(column, p.transform))
            }
            spec.commit()
        }
    }

    private fun refuse(
        code: String,
        verdict: LakeChangeVerdict,
        hasRows: Boolean,
    ): Plan =
        Plan.Refuse(
            code,
            mapOf("change_class" to verdict.changeClass.wire, "table_has_rows" to hasRows, "changes" to changesOf(verdict.changes)),
        )

    private fun drift(
        reason: String,
        appliedVersion: Int?,
        diff: List<LakeChange>,
    ): Plan =
        Plan.Refuse(
            PipelineErrorCodes.Lake.DEFINITION_DRIFT,
            mapOf("reason" to reason, "applied_definition_version" to appliedVersion, "diff" to changesOf(diff)),
        )

    private fun changesOf(changes: List<LakeChange>): List<Map<String, Any?>> =
        changes.map { mapOf("kind" to it.kind.wire, "field_id" to it.fieldId, "detail" to it.detail) }

    companion object {
        /** The table's definition-owned state differs from the version its `dp.definition.version` names. */
        const val REASON_DRIFT = "definition_drift"

        /** The table names no definition version, or one this environment does not hold. */
        const val REASON_UNKNOWN_VERSION = "unknown_definition_version"

        /** A create would number the fields differently from the body. */
        const val REASON_FIELD_IDS = "field_ids"

        /** The staged transaction does not read back as the intent (a guard: the classifier admitted the change). */
        const val REASON_STAGED_MISMATCH = "staged_mismatch"
    }
}
