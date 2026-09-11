package co.datapipelines.application.semantics

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.semantics.LearnedFact
import co.datapipelines.datasources.semantics.LearnedFactDrift
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.pipeline.PipelineRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Design §7.2 / D-S7 — the facts, MERGED into the metadata surface. The three introspection
 * responses (`datasources_get`, `_get_tables`, `_get_columns` and their REST twins) call one of
 * the three methods below with what they just read and attach the answer inline, so the fact is
 * where the agent is already looking and there is no separate "query the memory" step to forget.
 *
 * Two things happen on the way that a plain read would not do, both deliberate:
 *
 *  - **The §6 drift check, and its mark.** Each fact touching the object just read is checked
 *    against the columns (or tables) in hand — zero extra I/O — and a demotion is WRITTEN BACK
 *    (`markTrust`). A write on a read path is acceptable here because it is idempotent and
 *    one-way, and the alternative is serving a `stale` the server computed and then forgot, so
 *    the next reader recomputes it and the row never says what every response said.
 *  - **Provenance stops where the reader's visibility does (D-S9).** `source_pipeline` renders
 *    only when the READER's workspace can read that pipeline — through the SAME
 *    `findById(workspaceId, id)` predicate every pipeline read uses, never a second rule — so a
 *    DATASOURCE fact recorded from workspace A shows B its evidence and trust and
 *    `from_this_workspace: false`, and nothing about A's pipeline.
 *
 * Conflicts coexist (D-S5): two live facts of one kind on the same refs are both served, each
 * with `conflict: true`. Nothing picks a winner.
 */
interface FactEnrichment {
    /** `datasources_get` — the datasource-wide kinds (`window`, `sampling`). No columns in hand: served as stored. */
    fun forDatasource(
        readerWorkspaceId: UUID,
        datasource: Datasource,
    ): List<Map<String, Any?>>

    /**
     * `datasources_get_tables` — per listed table, its TABLE-grain facts (a ref with no column),
     * keyed by table name. [complete] says the listing is the WHOLE datasource (no namespace
     * filter, not truncated): only then can a fact whose table is absent be marked `stale` — a
     * filtered listing proves nothing about what it did not list.
     */
    fun forTables(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        tables: List<TableInfo>,
        complete: Boolean,
    ): Map<String, List<Map<String, Any?>>>

    /** `datasources_get_columns` — per column of [table], the facts with a ref on it, keyed by column name. */
    fun forColumns(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        table: String,
        namespace: List<String>?,
        columns: List<ColumnInfo>,
    ): Map<String, List<Map<String, Any?>>>

    companion object {
        /** No facts anywhere — the wiring for a context without the store (tests of the bare tools). */
        val NONE: FactEnrichment =
            object : FactEnrichment {
                override fun forDatasource(
                    readerWorkspaceId: UUID,
                    datasource: Datasource,
                ): List<Map<String, Any?>> = emptyList()

                override fun forTables(
                    readerWorkspaceId: UUID,
                    datasource: Datasource,
                    tables: List<TableInfo>,
                    complete: Boolean,
                ): Map<String, List<Map<String, Any?>>> = emptyMap()

                override fun forColumns(
                    readerWorkspaceId: UUID,
                    datasource: Datasource,
                    table: String,
                    namespace: List<String>?,
                    columns: List<ColumnInfo>,
                ): Map<String, List<Map<String, Any?>>> = emptyMap()
            }
    }
}

/** The production [FactEnrichment]: the store, the drift mark, and the D-S9 pipeline predicate. */
class LearnedFactsEnricher(
    private val repository: LearnedFactRepository,
    private val pipelines: PipelineRepository,
) : FactEnrichment {
    private val log = LoggerFactory.getLogger(LearnedFactsEnricher::class.java)

    override fun forDatasource(
        readerWorkspaceId: UUID,
        datasource: Datasource,
    ): List<Map<String, Any?>> {
        val facts =
            repository.findVisibleByDatasource(datasource.name, readerWorkspaceId).filter {
                it.kind in
                    LearnedFactKind.DATASOURCE_WIDE
            }
        val conflicts = conflictsAmong(facts)
        return facts.map {
            render(
                it,
                readerWorkspaceId,
                LearnedFactDrift.Verdict(it.trust, LearnedFactDrift.storedDrift(it)),
                it.id in conflicts,
            )
        }
    }

    override fun forTables(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        tables: List<TableInfo>,
        complete: Boolean,
    ): Map<String, List<Map<String, Any?>>> {
        val listed = tables.map { it.name }.toSet()
        val facts = repository.findVisibleByDatasource(datasource.name, readerWorkspaceId)
        // Every fact is checked — including one whose table is no longer listed, which is
        // exactly the one that must be marked — but only a COMPLETE listing may say "absent".
        val verdicts =
            facts.associate { fact ->
                fact.id to
                    if (complete) {
                        checked(fact, LearnedFactDrift.againstTables(fact, listed))
                    } else {
                        LearnedFactDrift.Verdict(fact.trust, LearnedFactDrift.storedDrift(fact))
                    }
            }
        val conflicts = conflictsAmong(facts)
        return tables
            .associate { table ->
                val onTable = facts.filter { fact -> fact.refs.any { it.table == table.name && it.column == null } }
                table.name to onTable.map { render(it, readerWorkspaceId, verdicts.getValue(it.id), it.id in conflicts) }
            }.filterValues { it.isNotEmpty() }
    }

    override fun forColumns(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        table: String,
        namespace: List<String>?,
        columns: List<ColumnInfo>,
    ): Map<String, List<Map<String, Any?>>> {
        val facts =
            repository
                .findVisibleByDatasource(
                    datasource.name,
                    readerWorkspaceId,
                ).filter { it.refsOn(table, namespace).isNotEmpty() }
        // The check runs ONCE per fact, and its mark is written before any column reads it:
        // a two-column join fact must render the same verdict on both columns.
        val verdicts =
            facts.associate { fact ->
                fact.id to checked(fact, LearnedFactDrift.againstColumns(fact, table, namespace, columns))
            }
        val conflicts = conflictsAmong(facts)
        return columns
            .associate { column ->
                val onColumn = facts.filter { fact -> fact.refsOn(table, namespace).any { it.column == column.column.name } }
                column.column.name to onColumn.map { render(it, readerWorkspaceId, verdicts.getValue(it.id), it.id in conflicts) }
            }.filterValues { it.isNotEmpty() }
    }

    /** The §6 mark: persist a demotion the check just found. Idempotent, one-way, logged. */
    private fun checked(
        fact: LearnedFact,
        verdict: LearnedFactDrift.Verdict,
    ): LearnedFactDrift.Verdict {
        if (verdict.demotes(fact.trust) && repository.markTrust(fact.id, verdict.trust)) {
            log.info(
                "event=semantics.drift_marked fact_id={} datasource={} kind={} from={} to={} drift=\"{}\"",
                fact.id,
                fact.datasourceName,
                fact.kind.wire,
                fact.trust.wire,
                verdict.trust.wire,
                verdict.drift,
            )
        }
        return verdict
    }

    /** D-S5 — the ids of every fact sharing its `(kind, refs)` with another live one in [facts]. */
    private fun conflictsAmong(facts: List<LearnedFact>): Set<UUID> =
        facts
            .groupBy { fact -> fact.kind to fact.refs.map { "${it.schema.orEmpty()}.${it.table}.${it.column.orEmpty()}" }.sorted() }
            .values
            .filter { it.size > 1 }
            .flatMap { group -> group.map { it.id } }
            .toSet()

    /** The §7.2 fact shape as it rides inside an introspection response. */
    private fun render(
        fact: LearnedFact,
        readerWorkspaceId: UUID,
        verdict: LearnedFactDrift.Verdict,
        conflict: Boolean,
    ): Map<String, Any?> = FactWire.summary(fact, readerWorkspaceId, verdict, conflict, sourcePipelineFor(fact, readerWorkspaceId))

    /** D-S9 — the pipeline link, only where the reader may read it: the pipeline repositories' own predicate. */
    private fun sourcePipelineFor(
        fact: LearnedFact,
        readerWorkspaceId: UUID,
    ): Map<String, Any?>? =
        fact.sourcePipelineId
            ?.let { pipelines.findById(readerWorkspaceId, it) }
            ?.let { mapOf("id" to it.id.toString(), "name" to it.name) }
}
