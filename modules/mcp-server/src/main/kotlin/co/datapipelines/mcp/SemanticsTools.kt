package co.datapipelines.mcp

import co.datapipelines.application.semantics.SemanticsService
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema
import java.time.Instant
import java.time.format.DateTimeParseException

/*
 * The learned semantic layer's three tools (mcp-server.md §6.2.36–38; the 2026-09-11 design
 * record §7.1) — thin adapters over [SemanticsService], the same path a later REST twin would
 * call. Each tool binds arguments, passes the §5.3 visibility gate where a datasource is
 * addressed (an ungranted datasource is not-found BEFORE anything runs — for a DATASOURCE-scope
 * record that gate IS the grant requirement), and returns the service's wire map. The `author`
 * floor and capability are the §7.6 matrix's, enforced by the dispatcher.
 */

/** The kind enum restated in the schemas — DERIVED from [LearnedFactKind], never typed. */
internal val KIND_ENUM_JSON: String = LearnedFactKind.entries.joinToString(prefix = "[", postfix = "]") { "\"${it.wire}\"" }

/** The two scopes, as the argument binder accepts them. */
private val SCOPES: Set<String> = LearnedFactScope.entries.map { it.name }.toSet()

/** `semantics_record` — record one learned fact with the probe that showed it. Scope: `author` / `AUTHOR`. Mutating. */
class SemanticsRecordTool(
    private val datasources: DatasourceRegistry,
    private val service: SemanticsService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "semantics_record",
            description =
                "Record ONE fact you learned about a datasource that introspection could not tell you — a unit, a time " +
                    "zone, a sample rate, a grain, what a coded value means, a join that holds, a trap — so the next " +
                    "session reads it beside the columns instead of probing again. Never record what introspection " +
                    "already returns (types, keys, comments). refs name the table(s) and column(s) the fact is about, " +
                    "structurally; every ref is checked against the live schema and an unknown one is refused. Pass " +
                    "evidence_sql (the SELECT that showed the fact): it runs once, its first rows become " +
                    "evidence_summary, and the fact is stored as observed — without it the fact is only asserted. " +
                    "scope DATASOURCE is about the data and is shared with every workspace the datasource is granted " +
                    "to; scope WORKSPACE (definition, exclusion, preference) is this organisation's meaning and stays " +
                    "here. To correct a stale or wrong fact, record the replacement with supersedes: the old one is " +
                    "retired as superseded. An identical live fact is refused as semantics.duplicate. Mutating.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["scope", "datasource", "kind", "fact", "refs"],
                  "additionalProperties": false,
                  "properties": {
                    "scope": {"type": "string", "enum": ["DATASOURCE", "WORKSPACE"], "description": "DATASOURCE: a fact about the data, visible wherever the datasource is granted. WORKSPACE: this organisation's meaning, visible here only."},
                    "datasource": {"type": "string", "description": "Datasource name (must be granted to this workspace)."},
                    "kind": {"type": "string", "enum": $KIND_ENUM_JSON, "description": "What the fact is about. unit, time_zone, sampling, grain, window, enum_meaning, join, caveat, format are DATASOURCE kinds; definition, exclusion, preference are WORKSPACE kinds."},
                    "fact": {"type": "string", "minLength": 8, "maxLength": 1000, "description": "The fact, in one or two sentences, specific enough to act on: 'value is already in the unit named by unit_col', 'pickup_ts is naive local time (America/New_York)'."},
                    "refs": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "object",
                        "required": ["table"],
                        "additionalProperties": false,
                        "properties": {
                          "schema": {"type": "string", "description": "Namespace as datasources_get_tables reported it (a label, or the dotted catalog.schema form). Omit for the connection's current schema."},
                          "table": {"type": "string", "description": "Table name exactly as datasources_get_tables returned it."},
                          "column": {"type": "string", "description": "Column name exactly as datasources_get_columns returned it. Omit for a table-grain fact (grain, window, sampling, a table-level caveat)."}
                        }
                      },
                      "description": "The object(s) the fact is about. One ref for a column fact, two for a join, a column-less ref for a table-grain fact."
                    },
                    "evidence_sql": {"type": "string", "description": "ONE read-only SELECT/WITH that shows the fact (no :parameters). Runs once at record time under the sql_probe rules; a statement that fails refuses the record."},
                    "evidence_summary": {"type": "string", "maxLength": 300, "description": "What the evidence showed, in your words. Defaults to the probe's first rows."},
                    "source_pipeline_id": {"type": "string", "format": "uuid", "description": "The pipeline you learned this while building, if any. Shown only to readers who can read that pipeline."},
                    "source_version": {"type": "integer", "minimum": 1},
                    "supersedes": {"type": "string", "format": "uuid", "description": "The id of the fact this one replaces (a stale or wrong one); it is retired with reason superseded."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val scope = scopeOf(args.enumString("scope", SCOPES) ?: throw McpArguments.invalidParams("Missing required argument 'scope'."))
        val name = args.requiredString("datasource")
        val refs = refsOf(args)
        val command =
            SemanticsService.RecordCommand(
                scope = scope,
                kind = args.requiredString("kind"),
                fact = args.requiredString("fact"),
                refs = refs,
                evidenceSql = args.string("evidence_sql"),
                evidenceSummary = args.string("evidence_summary"),
                sourcePipelineId = args.uuid("source_pipeline_id"),
                sourceVersion = args.version("source_version"),
                supersedes = args.uuid("supersedes"),
            )
        val gated = datasources.requireVisible(name, ctx)
        return recording(name) { service.record(ctx.principal, gated, command, WriteSurface.MCP) }
    }

    private fun scopeOf(token: String): LearnedFactScope = LearnedFactScope.valueOf(token)

    /** `refs` → [FactRef]s. Shape faults are `-32602`; an empty array reaches the service as `semantics.fact_invalid`. */
    private fun refsOf(args: McpArguments): List<FactRef> {
        val raw =
            args.rawMap()["refs"] as? List<*> ?: throw McpArguments.invalidParams("refs must be an array of {schema?, table, column?}.")
        return raw.map { entry -> refOf(entry) }
    }

    private fun refOf(entry: Any?): FactRef {
        val ref = entry as? Map<*, *>
        val table = (ref?.get("table") as? String)?.trim()
        if (ref == null || table.isNullOrEmpty()) throw McpArguments.invalidParams("Each ref must be an object naming a table.")
        return FactRef(
            schema = (ref["schema"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            table = table,
            column = (ref["column"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** The evidence run and the ref check open a live connection: the introspection tools' unreachable mapping applies. */
    private fun <T> recording(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DatasourceUnreachableException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "Datasource '$name' could not be reached to validate the fact.",
                details = mapOf("datasource" to name),
                cause = e,
            )
        }
}

/** `semantics_list` — the facts on a datasource, with trust, drift and provenance. Scope: `read` / `VIEW`. */
class SemanticsListTool(
    private val datasources: DatasourceRegistry,
    private val service: SemanticsService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "semantics_list",
            description =
                "List the learned facts recorded on a datasource this workspace can see — every DATASOURCE fact " +
                    "(whoever recorded it) and this workspace's own WORKSPACE facts — with trust, drift, refs, the " +
                    "evidence SQL and who recorded it through what. The same facts also arrive inline on " +
                    "datasources_get / _get_tables / _get_columns, which is where to read them while authoring; use " +
                    "this to review, to find a fact's id to supersede or retire, or to answer 'what was recorded " +
                    "since <time>'. Retired facts are hidden unless include_retired.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["datasource"],
                  "additionalProperties": false,
                  "properties": {
                    "datasource": {"type": "string", "description": "Datasource name."},
                    "table": {"type": "string", "description": "Only facts with a ref on this table."},
                    "scope": {"type": "string", "enum": ["DATASOURCE", "WORKSPACE"]},
                    "include_retired": {"type": "boolean", "default": false},
                    "since": {"type": "string", "format": "date-time", "description": "Only facts recorded at or after this ISO-8601 instant."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val name = args.requiredString("datasource")
        val query =
            SemanticsService.ListQuery(
                table = args.string("table"),
                scope = args.enumString("scope", SCOPES)?.let { LearnedFactScope.valueOf(it) },
                includeRetired = args.boolean("include_retired") ?: false,
                since = args.string("since")?.let { sinceOf(it) },
            )
        val gated = datasources.requireVisible(name, ctx)
        val facts = service.list(ctx.principal, gated, query)
        return mapOf("datasource" to gated.name, "facts" to facts, "count" to facts.size)
    }

    private fun sinceOf(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw McpArguments.invalidParams("since must be an ISO-8601 instant (e.g. 2026-09-11T00:00:00Z): ${e.message}")
        }
}

/** `semantics_retire` — retire one fact with a reason. Scope: `author` / `AUTHOR`. Mutating. */
class SemanticsRetireTool(
    private val service: SemanticsService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "semantics_retire",
            description =
                "Retire one learned fact with a reason — it stops being served beside the columns but keeps its row " +
                    "(facts are never deleted; history is the audit). Prefer semantics_record with supersedes when " +
                    "you know the correct fact: that retires the old one and records the new in one step. A fact " +
                    "this workspace cannot see is not-found; a DATASOURCE fact another workspace established can " +
                    "only be retired by a workspace admin. Mutating.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id", "reason"],
                  "additionalProperties": false,
                  "properties": {
                    "id": {"type": "string", "format": "uuid", "description": "The fact's id, from semantics_list or an introspection response."},
                    "reason": {"type": "string", "minLength": 3, "maxLength": 300, "description": "Why — one sentence, kept on the row and in the audit log."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val reason = args.requiredString("reason").trim()
        if (reason.length < MIN_REASON_LENGTH) throw McpArguments.invalidParams("reason must be at least $MIN_REASON_LENGTH characters.")
        return service.retire(ctx.principal, args.requiredUuid("id"), reason)
    }

    private companion object {
        const val MIN_REASON_LENGTH = 3
    }
}

/** The three tools, in `tools/list` order. */
object SemanticsTools {
    fun all(
        datasources: DatasourceRegistry,
        service: SemanticsService,
    ): List<McpTool> =
        listOf(
            SemanticsRecordTool(datasources, service),
            SemanticsListTool(datasources, service),
            SemanticsRetireTool(service),
        )
}
