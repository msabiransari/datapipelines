package co.datapipelines.mcp

import co.datapipelines.application.mcp.McpToolLearnings
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.pipeline.Door
import co.datapipelines.pipeline.DoorKind
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import java.time.Instant
import java.util.UUID

/*
 * The MCP entry-point checks (139): the three repeated misses the 2026-09-14 re-run measured,
 * enforced by the server against the audit log and the pipeline body instead of asked of the
 * skill a third time. **MCP-only** — a person over REST or in the editor keeps their freedom;
 * these checks read the caller's own `mcp.tool.called` audit rows, which only the agent loop
 * has, and the KDoc of each check says what the server knows that the skill could only ask.
 *
 * All three refuse with catalogued codes (pipeline-contract §12.11 and §13.3) and all three
 * are FAIL-CLOSED on their own question and FAIL-OPEN on everything else: a datasource that
 * will not list, a truncated catalog, a template that is missing — none of these is what the
 * check exists to catch, and the surface that owns each of those refusals says so in its own
 * code.
 */

/**
 * Check A — learn before you write: `pipeline.validation.table_not_learned`.
 *
 * Every listed table a saved template body names must be a table THIS API key has read the
 * columns of (`datasources_get_columns` with that datasource as `target` and the table in
 * `details.table`, a success row, any time in the key's lifetime — no time window: a key
 * learns once). The tokeniser is [FactRefMismatchCheck]'s: `[a-z0-9_]{4,}` tokens over the
 * lowercased body, so `my_orders` is not `orders` and `row_count` is not a table; every
 * `${…}` interpolation span is stripped first, because an interpolated name is DYNAMIC —
 * the check cannot know what it produces and refuses nothing on its behalf (say-so: the
 * spanRegex below). A token the catalog does not list is prose to this check — the probe
 * and the save-time validation own unknown names.
 *
 * **Stats are deliberately not required**: columns are correctness, stats are performance —
 * the playbook keeps `_get_table_stats` as advice, and this check enforces only the
 * correctness half.
 *
 * Runs at `pipelines_create` / `pipelines_update` — where the datasource is known — per
 * node, over the node's pinned template body against the node's `source`. The template
 * authoring tools are NOT checked: a template has a dialect, never a datasource, so the
 * question "did you read this table" is unanswerable there. `tempdb` sources are exempt
 * (there is no catalog to learn), as are PIPELINE/CALCULATOR nodes (no template, no SQL).
 */
class TableLearningCheck(
    private val templates: TemplateRepository,
    private val introspector: SchemaIntrospector,
    private val datasources: DatasourceRegistry,
    private val learnings: McpToolLearnings,
) {
    /** The `${…}` interpolation spans — dynamic names the check cannot resolve, so exempts. */
    private val spanRegex = Regex("\\$\\{[^}]*}")

    /** [FactRefMismatchCheck]'s tokeniser, restated: identifier-shaped, ≥ 4 chars. */
    private val tokenRegex = Regex("[a-z0-9_]{4,}")

    /**
     * Refuses when any template the body pins names a listed table the key never learned.
     * A datasource that is not visible, not reachable, or whose listing is truncated skips
     * the check for its nodes — those are other surfaces' refusals, and a fail-open here
     * still leaves the probe and the execution to say the truth.
     */
    fun require(
        workspaceId: UUID,
        keyId: String?,
        pipeline: Pipeline,
    ) {
        val grouped =
            pipeline.nodes
                .filter { it.template.id.isNotBlank() && it.resolvedSource is NodeSource.Datasource }
                .groupBy { (it.resolvedSource as NodeSource.Datasource).name }
        val missing = linkedSetOf<MissingTable>()
        for ((datasourceName, datasourceNodes) in grouped) {
            val listing =
                try {
                    val gated = datasources.requireVisible(datasourceName, workspaceId)
                    introspector.tables(gated)
                } catch (_: DatapipelinesException) {
                    // Not visible (D-R5 not-found), not registered (§12.5 will refuse), or the
                    // dialect's catalog read failed — none of these is "you did not learn".
                    null
                } catch (_: DatasourceUnreachableException) {
                    null
                }
            // A truncated listing is not the catalog's truth — failing closed on it would
            // tax tables the listing never showed. Skip; the probe still answers.
            if (listing == null || listing.truncated) continue
            val catalogSpelling = listing.tables.associate { it.name.lowercase() to it.name }
            if (catalogSpelling.isEmpty()) continue
            val named = namedTables(workspaceId, datasourceNodes, catalogSpelling.keys)
            if (named.isEmpty()) continue
            val learned = learnings.columnsRead(keyId, datasourceName).map { it.lowercase() }.toSet()
            // Name the refusal as the catalog spells the table, never as the body did.
            missing += named.filterNot { it in learned }.map { MissingTable(datasourceName, catalogSpelling.getValue(it)) }
        }
        if (missing.isNotEmpty()) throw refusal(missing)
    }

    /** The catalog tables (in catalog spelling) the nodes' template bodies name. */
    private fun namedTables(
        workspaceId: UUID,
        nodes: List<Node>,
        listed: Set<String>,
    ): Set<String> =
        nodes
            .flatMap { node ->
                templates
                    .findVersion(workspaceId, node.template.id, node.template.version)
                    ?.body
                    ?.let { body -> tokenRegex.findAll(spanRegex.replace(body.lowercase(), " ")).map { it.value } }
                    ?: emptySequence()
            }.filterTo(mutableSetOf()) { it in listed }

    private fun refusal(missing: Set<MissingTable>): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Validation.TABLE_NOT_LEARNED,
            message =
                "The pipeline names a table whose columns this key never read: " +
                    missing.joinToString("; ") { "'${it.table}' on '${it.datasource}'" } +
                    ". Learn before you write — for each one call datasources_get_columns " +
                    "{\"name\": \"<datasource>\", \"table\": \"<table>\"}, then save again.",
            details =
                mapOf(
                    "tables" to
                        missing.map {
                            mapOf(
                                "datasource" to it.datasource,
                                "table" to it.table,
                                "clearing_call" to "datasources_get_columns",
                            )
                        },
                ),
        )

    private data class MissingTable(
        val datasource: String,
        val table: String,
    )
}

/**
 * Check B — render before you run: `pipeline.execution.template_unrendered`.
 *
 * Executing a DRAFT pipeline version over MCP requires that this key has rendered every
 * pinned template version that is itself a DRAFT, AFTER that draft's last write — a
 * `templates_render` success row with `details.template` naming it, newer than the
 * template draft's `updated_at`. RELEASED pins are exempt (they rendered before release and
 * cannot change); a `templates_update` therefore refuses the NEXT execute until a fresh
 * render — the render-then-run loop the skill's step 3 and 5 describe, now enforced at the
 * entry point. Both stamps are the metadata database's own clock, so the comparison carries
 * no skew.
 */
class TemplateRenderFreshness(
    private val templates: TemplateRepository,
    private val learnings: McpToolLearnings,
) {
    fun require(
        workspaceId: UUID,
        keyId: String?,
        version: PipelineVersionDetail,
        nodes: List<Node>,
    ) {
        if (version.status != PipelineVersionStatus.DRAFT) return
        val stale =
            nodes
                .filter { it.template.id.isNotBlank() }
                .associateBy { it.template.id to it.template.version }
                .mapNotNull { (_, node) ->
                    val templateVersion =
                        templates.lookupVersion(workspaceId, node.template.id, node.template.version)
                            ?: return@mapNotNull null // §12.6 / TEMPLATE_NOT_FOUND owns a missing pin
                    if (templateVersion.status != PipelineVersionStatus.DRAFT) return@mapNotNull null
                    val updatedAt = templateVersion.updatedAt ?: Instant.EPOCH
                    val lastRender = learnings.lastRenderAt(keyId, node.template.id)
                    if (lastRender != null && lastRender.isAfter(updatedAt)) {
                        null
                    } else {
                        StaleTemplate(node.template.id, node.template.version, updatedAt, lastRender)
                    }
                }
        if (stale.isNotEmpty()) throw refusal(stale)
    }

    private fun refusal(stale: List<StaleTemplate>): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.TEMPLATE_UNRENDERED,
            message =
                "A pinned template draft was written after this key's last render of it — the run is not its " +
                    "render. Render before you run: " +
                    stale.joinToString("; ") {
                        "'${it.templateId}' v${it.version}" +
                            (it.lastRender?.let { last -> " (rendered $last" } ?: " (never rendered") +
                            ", draft updated ${it.updatedAt})"
                    } +
                    ". Call templates_render for each, then execute again.",
            details =
                mapOf(
                    "templates" to
                        stale.map {
                            mapOf(
                                "id" to it.templateId,
                                "version" to it.version,
                                "updated_at" to it.updatedAt.toString(),
                                "last_render" to it.lastRender?.toString(),
                            )
                        },
                ),
        )

    private data class StaleTemplate(
        val templateId: String,
        val version: Int,
        val updatedAt: Instant,
        val lastRender: Instant?,
    )
}

/**
 * Check C — the door is a decision: `pipeline.validation.door_unacknowledged`.
 *
 * [Door.classify] answers RAW_DATE_PAIR (two DATE parameters, no INTEGER period parameter,
 * no window calculator) and this object refuses it unless the call carries
 * `door_acknowledged: true` — the [NewRootConfirmation] shape: the flag forces the decision
 * instead of a copy, and the refusal names the two parameters and rule 13's alternatives.
 * The owner ruling (2026-09-14, "Agree") is refusal + flag, not an advisory.
 */
internal object DoorAcknowledgment {
    /** The opt-in argument both pipeline write tools accept. */
    const val ARG = "door_acknowledged"

    /** The sentence both tool schemas carry, verbatim, on the property. */
    const val ARG_DESC =
        "Set true ONLY when the question truly fixes two dates. A pipeline whose parameters are two raw DATE " +
            "inputs with no period parameter (year, quarter, month, *_year) and no window CALCULATOR node is " +
            "refused pipeline.validation.door_unacknowledged — the door is a decision: prefer the period " +
            "vocabulary of rule 13, and never pass this to silence the refusal."

    /** Refuses a RAW_DATE_PAIR body whose call did not acknowledge it. */
    fun require(
        pipeline: Pipeline,
        acknowledged: Boolean?,
    ) {
        if (Door.classify(pipeline.parameters, pipeline.nodes) != DoorKind.RAW_DATE_PAIR) return
        if (acknowledged == true) return
        val dates =
            pipeline.parameters
                .filterValues { it.type == LogicalType.DATE }
                .keys
                .toList()
                .sorted()
                .take(2)
        throw DatapipelinesException(
            code = PipelineErrorCodes.Validation.DOOR_UNACKNOWLEDGED,
            message =
                "Parameters ${dates.joinToString(" and ") { "'$it'" }} are two raw DATE parameters — the question's " +
                    "period baked in as literals, which is the miss rule 13 exists to catch. Doors that wear the " +
                    "question's vocabulary: a period parameter (year, quarter, month, *_year) or an anchor date " +
                    "with a CALCULATOR node (period_bounds, trailing_periods) deriving the window. If the question " +
                    "truly fixes two dates, retry with door_acknowledged: true.",
            details =
                mapOf(
                    "parameters" to dates,
                    "door" to DoorKind.RAW_DATE_PAIR.wire,
                ),
        )
    }
}

/** The wire value a DoorKind travels under in details. */
private val DoorKind.wire: String
    get() = name.lowercase()
