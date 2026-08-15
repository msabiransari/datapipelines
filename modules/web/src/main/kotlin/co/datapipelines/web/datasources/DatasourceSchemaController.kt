package co.datapipelines.web.datasources

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.toWireMap
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException

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
 * A connection failure while leasing is translated here to the catalogued
 * `pipeline.execution.datasource_unreachable` ([PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE],
 * HTTP 502 via [co.datapipelines.web.api.ApiErrorCatalog]) — a customer database being down is
 * not a server error. The driver's message never reaches the wire (it can carry internal
 * topology); the cause stays attached for diagnostics.
 *
 * All three endpoints are `author`-scoped (§7A, the §8.1 connection-test precedent): each opens a
 * live connection against the datasource, and the stated consumer is pipeline authoring. No
 * pagination — the tables listing is capped at 2000 (`truncated: true` when the cap dropped
 * any), the snapshot at 200, and the per-table listings are naturally bounded.
 */
@RestController
@RequestMapping("/api/v1/datasources")
class DatasourceSchemaController(
    private val introspector: SchemaIntrospector,
) {
    /** §7A — the whole-schema snapshot (`datasources_get_schema`'s REST twin). */
    @GetMapping("/{name}/schema")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun schema(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(introspecting(name) { introspector.snapshot(name).toWireMap() })

    /** §7A — tables and views, optionally narrowed to one schema; capped, `truncated` when the cap dropped any. */
    @GetMapping("/{name}/tables")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun tables(
        @PathVariable name: String,
        @RequestParam(required = false) schema: String?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.of(introspecting(name) { introspector.tables(name, schema).toWireMap() })

    /** §7A — one table's columns with canonical types; empty when the table does not exist. */
    @GetMapping("/{name}/tables/{table}/columns")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun columns(
        @PathVariable name: String,
        @PathVariable table: String,
        @RequestParam(required = false) schema: String?,
    ): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.of(introspecting(name) { introspector.columns(name, table, schema).map { it.toWireMap() } })

    /**
     * The §7A connection-failure boundary: an [SQLException] from the lease is the catalogued
     * `pipeline.execution.datasource_unreachable`, never a raw 500. Message is static — driver
     * text stays off the wire (§13 forbids internal topology in error messages).
     */
    private fun <T> introspecting(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: SQLException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "Datasource '$name' could not be reached for schema introspection.",
                details = mapOf("datasource" to name),
                cause = e,
            )
        }

}
