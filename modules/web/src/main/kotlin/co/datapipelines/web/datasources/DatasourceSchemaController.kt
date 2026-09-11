package co.datapipelines.web.datasources

import co.datapipelines.application.semantics.FactEnrichment
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The schema-introspection endpoints (datasources.md §7A, rest-api.md §9.7).
 *
 * A thin projection of [SchemaIntrospector]: this controller binds paths and emits the shared
 * §7A wire maps (`toWireMap`, in `modules/datasources` beside the data classes — the same
 * projections the MCP tools use, so the two surfaces cannot drift), nothing more. An unknown
 * datasource name propagates the introspector's catalogued
 * `datasource.not_found` to [co.datapipelines.web.api.ApiExceptionHandler] (HTTP 404), and an
 * unknown table/schema filter is the introspector's empty list — "no results", not an error.
 *
 * Workspace visibility (workspaces design §5.3) is enforced BEFORE the introspector runs:
 * a datasource bound to another workspace is `datasource.not_found` here too — by-name
 * access to an invisible datasource behaves as not-found on every surface.
 *
 * A connection failure arrives as the introspector's [DatasourceUnreachableException] — the
 * lease boundary there translates BOTH the SQLException and the RuntimeException pool-build
 * family — and this layer maps it to the catalogued
 * `pipeline.execution.datasource_unreachable` ([PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE],
 * HTTP 502 via [co.datapipelines.web.api.ApiErrorCatalog]) — a customer database being down is
 * not a server error. The driver's message never reaches the wire (it can carry internal
 * topology); the cause stays attached for diagnostics. The catch cannot live in a shared home:
 * the code belongs to `pipeline-contract`, a sibling of `datasources`, so each surface keeps
 * its own three-line translation (accepted in the round-2 hardening review).
 *
 * All three endpoints are `author`-scoped (§7A, the §8.1 connection-test precedent): each opens a
 * live connection against the datasource, and the stated consumer is pipeline authoring. No
 * pagination — the tables and schemas listings are each capped at 2000 (`truncated: true` when
 * the cap dropped any); per-table column listings are naturally bounded.
 *
 * Since 118 the tables and columns listings carry the learned facts on what they list
 * (rest-api §9.7A, the learned-semantic-layer design D-S7): `facts[]` per table / per column
 * through the shared [FactEnrichment] — the same enrichment the MCP tools attach, so the two
 * surfaces serve one shape. The enrichment runs the §6 drift check against the columns just read
 * and writes a demotion back; that read-path write is the enricher's documented choice.
 */
@RestController
@RequestMapping("/api/v1/datasources")
class DatasourceSchemaController(
    private val introspector: SchemaIntrospector,
    private val datasources: DatasourceRegistry,
    // Defaulted for the hand-constructed slice tests; the assembled application injects the bean.
    private val facts: FactEnrichment = FactEnrichment.NONE,
) {
    /**
     * §7A — the schema listing, the introspection flow's entry point (schemas → tables →
     * columns). The shared wire projection (`{"schemas": [...], "truncated": bool}`); empty is
     * a valid answer on schemaless dialects.
     */
    @GetMapping("/{name}/schemas")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun schemas(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(visible(name) { introspector.schemas(it).toWireMap() })

    /**
     * §7A — tables and views, optionally narrowed to one NAMESPACE; capped, `truncated` when the
     * cap dropped any.
     *
     * Two spellings of one filter (087). `?namespace=a1&namespace=sales` is the repeated-parameter
     * form Spring binds to a list, `?namespace=a1.sales` the dotted shorthand, and `?schema=sales`
     * the pre-087 parameter — which keeps working forever (§12.1) and, on a dialect with two
     * browsable levels, may itself carry a dotted namespace so an agent can pass back exactly what
     * a listing gave it. `namespace` wins when both are present.
     */
    @GetMapping("/{name}/tables")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun tables(
        @PathVariable name: String,
        @RequestParam(required = false) schema: String?,
        @RequestParam(required = false) namespace: List<String>? = null,
    ): ApiResponse<Map<String, Any?>> {
        val filter = namespaceOf(namespace)
        return ApiResponse.of(
            visible(name) { datasource ->
                val page = introspector.tables(datasource, schema, namespaceFilter = filter)
                // Only a COMPLETE listing (unfiltered, untruncated) may mark a fact's table absent.
                val complete = filter == null && schema.isNullOrBlank() && !page.truncated
                page.toWireMap(facts.forTables(currentPrincipal().requireWorkspace().id, datasource, page.tables, complete))
            },
        )
    }

    /** §7A — one table's columns with canonical types; empty when the table does not exist. */
    @GetMapping("/{name}/tables/{table}/columns")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun columns(
        @PathVariable name: String,
        @PathVariable table: String,
        @RequestParam(required = false) schema: String?,
        @RequestParam(required = false) namespace: List<String>? = null,
    ): ApiResponse<List<Map<String, Any?>>> {
        val filter = namespaceOf(namespace)
        return ApiResponse.of(
            visible(name) { datasource ->
                val columns = introspector.columns(datasource, table, schema, filter)
                val byColumn = facts.forColumns(currentPrincipal().requireWorkspace().id, datasource, table, filter, columns)
                columns.map { c -> c.toWireMap(byColumn[c.column.name]) }
            },
        )
    }

    /**
     * The `namespace` parameter, in either accepted spelling: Spring binds `?namespace=a&namespace=b`
     * to a two-element list and `?namespace=a.b` to a one-element list holding the dotted form, so
     * a single element carrying dots is expanded here. One home for the rule, shared by both
     * endpoints — the module-level parser ([co.datapipelines.datasources] `Namespaces`) is the same
     * one the MCP tools and the include-schemas allowlist use.
     */
    private fun namespaceOf(namespace: List<String>?): List<String>? =
        namespace
            ?.flatMap { it.split('.') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /**
     * §5.3 visibility, then the shared error boundaries — and the gate's snapshot is the
     * datasource the introspector walks (025 C3): the old `getVisible == null` check
     * discarded the resolved row and re-resolved the name unscoped, a TOCTOU where a
     * re-bind between the two introspected a datasource the gate would now refuse. An
     * invisible datasource is not-found, identical to unknown.
     */
    private fun <T> visible(
        name: String,
        block: (co.datapipelines.datasources.Datasource) -> T,
    ): T {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val gated =
            datasources.getVisible(name, workspaceId)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Datasource.NOT_FOUND,
                    message = "Datasource '$name' not found.",
                    details = mapOf("datasource_name" to name),
                )
        return introspecting(name) { block(gated) }
    }

    /**
     * The §7A error boundaries shared by the three endpoints. The introspector's
     * [DatasourceUnreachableException] (both the SQLException lease family and the
     * RuntimeException pool-build family, translated at its lease boundary) is the catalogued
     * `pipeline.execution.datasource_unreachable`, never a raw 500; its
     * [co.datapipelines.datasources.CurrentSchemaUnknownException] is the catalogued
     * `pipeline.execution.parameter_required` — the closest §13.3 invalid-argument code,
     * reused per the additive-catalog rule — with a message that names the recovery (list
     * schemas, then pass one). Messages are static — driver text stays off the wire (§13
     * forbids internal topology in error messages).
     */
    private fun <T> introspecting(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: DatasourceUnreachableException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "Datasource '$name' could not be reached for schema introspection.",
                details = mapOf("datasource" to name),
                cause = e,
            )
        } catch (e: co.datapipelines.datasources.CurrentSchemaUnknownException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.PARAMETER_REQUIRED,
                message =
                    "Datasource '$name' reports no current schema, so an unqualified read could merge the " +
                        "columns of same-named tables across schemas. Pass an explicit schema (list them with " +
                        "GET /api/v1/datasources/$name/schemas).",
                details = mapOf("datasource" to name),
                cause = e,
            )
        }
}
