package co.datapipelines.application.semantics

import co.datapipelines.application.templates.FactImplementations
import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.semantics.LearnedFact
import co.datapipelines.datasources.semantics.LearnedFactDrift
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.datasources.semantics.LearnedFactTrust
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.ImplementingVersion
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Design §7.2 / D-S7 — the facts, MERGED into the metadata surface. The listing and the three
 * introspection responses (`datasources_list`, `datasources_get`, `_get_tables`, `_get_columns`
 * and their REST twins) call one of the methods below with what they just read and attach the
 * answer inline, so the fact is where the agent is already looking and there is no separate
 * "query the memory" step to forget.
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
    /**
     * The two per-datasource blocks `datasources_list` and `datasources_get` carry (136 §A):
     * [facts], the datasource-wide kinds (`window`, `sampling`), and [definitions], every
     * WORKSPACE-scope fact (`definition`, `exclusion`, `preference`) visible to the reader that
     * names this datasource — with refs or with none (136 §B). Both are served as stored (no
     * columns in hand), newest last, from ONE store read.
     *
     * A rule used to reach the agent only on the table or column its refs named, buried among
     * units and codes in a columns listing, and the acceptance run's agent defined "rainy" its
     * own way beside the workspace's recorded rule (T287): a rule is read BEFORE the tables are
     * chosen, so it rides the listing — the call every agent makes first.
     */
    data class DatasourceBlocks(
        val facts: List<Map<String, Any?>>,
        val definitions: List<Map<String, Any?>>,
    ) {
        companion object {
            val EMPTY = DatasourceBlocks(emptyList(), emptyList())
        }
    }

    /**
     * `datasources_list` / `datasources_get` — both blocks of [DatasourceBlocks], one store read.
     * Each rule in [DatasourceBlocks.definitions] carries `implemented_by` (7e, transform-nodes
     * design §8.3): the template versions citing it that [templateLens] — the reader's template
     * lens, no default — admits. The listing is where an agent reads a rule first, so it is where
     * the transform implementing it is found.
     */
    fun forListing(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        templateLens: ReadLens,
    ): DatasourceBlocks

    /**
     * The datasource-wide kinds only, as [forListing] serves them. They are DATASOURCE facts, which
     * nothing cites, so the template lens is irrelevant and nothing is admitted.
     */
    fun forDatasource(
        readerWorkspaceId: UUID,
        datasource: Datasource,
    ): List<Map<String, Any?>> = forListing(readerWorkspaceId, datasource, ReadLens.NOTHING).facts

    /**
     * `datasources_get_tables` — per listed table, its TABLE-grain facts (a ref with no column)
     * plus every `stale` fact on it (a fact whose column is gone has no column to ride on in the
     * columns listing; §6 shows it beside the current columns, here), keyed by table name.
     * [complete] says the listing is the WHOLE datasource (no namespace
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
                override fun forListing(
                    readerWorkspaceId: UUID,
                    datasource: Datasource,
                    templateLens: ReadLens,
                ): DatasourceBlocks = DatasourceBlocks.EMPTY

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
    /** 7e — the rules' `implemented_by`; [FactImplementations.NONE] lists every rule as implemented by nothing. */
    private val implementations: FactImplementations = FactImplementations.NONE,
) : FactEnrichment {
    private val log = LoggerFactory.getLogger(LearnedFactsEnricher::class.java)

    override fun forListing(
        readerWorkspaceId: UUID,
        datasource: Datasource,
        templateLens: ReadLens,
    ): FactEnrichment.DatasourceBlocks {
        // One read; the visibility predicate already keeps another workspace's rules out.
        val visible = repository.findVisibleByDatasource(datasource.name, readerWorkspaceId)
        val rules = visible.filter { it.scope == LearnedFactScope.WORKSPACE }
        // 7e: one reverse read for every rule on the listing (the fact index, lens in its SQL).
        val implemented =
            if (rules.isEmpty()) emptyMap() else implementations.implementedBy(readerWorkspaceId, templateLens, rules.map { it.id })
        return FactEnrichment.DatasourceBlocks(
            facts = asStored(visible.filter { it.kind in LearnedFactKind.DATASOURCE_WIDE }, readerWorkspaceId),
            definitions = asStored(rules, readerWorkspaceId) { implemented[it.id].orEmpty() },
        )
    }

    /**
     * Served as stored — no columns in hand, so the verdict is the row's own trust and drift.
     * [implementedBy] is the 7e projection, given for the WORKSPACE rules only (null elsewhere).
     */
    private fun asStored(
        facts: List<LearnedFact>,
        readerWorkspaceId: UUID,
        implementedBy: (LearnedFact) -> List<ImplementingVersion>? = { null },
    ): List<Map<String, Any?>> {
        val conflicts = conflictsAmong(facts)
        return facts.map {
            render(
                it,
                readerWorkspaceId,
                LearnedFactDrift.Verdict(it.trust, LearnedFactDrift.storedDrift(it)),
                it.id in conflicts,
                implementedBy(it),
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
                // The table-grain facts (a ref with no column) — plus every STALE fact on the
                // table: a fact whose column is gone has no column entry to ride on in the
                // columns listing, and §6 says it is shown BESIDE the current columns, not lost.
                // The tables listing is where the agent looks before asking for columns.
                val onTable =
                    facts.filter { fact ->
                        fact.refs.any {
                            it.table == table.name &&
                                (it.column == null || verdicts.getValue(fact.id).trust == LearnedFactTrust.STALE)
                        }
                    }
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
        implementedBy: List<ImplementingVersion>? = null,
    ): Map<String, Any?> =
        FactWire.summary(fact, readerWorkspaceId, verdict, conflict, sourcePipelineFor(fact, readerWorkspaceId), implementedBy)

    /** D-S9 — the pipeline link, only where the reader may read it: the pipeline repositories' own predicate. */
    private fun sourcePipelineFor(
        fact: LearnedFact,
        readerWorkspaceId: UUID,
    ): Map<String, Any?>? =
        fact.sourcePipelineId
            ?.let { pipelines.findById(readerWorkspaceId, it) }
            ?.let { mapOf("id" to it.id.toString(), "name" to it.name) }
}
