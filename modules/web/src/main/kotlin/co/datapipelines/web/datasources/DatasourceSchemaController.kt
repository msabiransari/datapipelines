package co.datapipelines.web.datasources

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SchemaSnapshot
import co.datapipelines.datasources.TableInfo
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
 * A thin projection of [SchemaIntrospector]: this controller binds paths and emits snake_case
 * DTOs, nothing more. An unknown datasource name propagates the introspector's catalogued
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
 * pagination — the snapshot is bounded by the 200-table cap and the per-table listings are
 * naturally bounded.
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
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(introspecting(name) { introspector.snapshot(name).toResponse() })

    /** §7A — tables and views, optionally narrowed to one schema. */
    @GetMapping("/{name}/tables")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun tables(
        @PathVariable name: String,
        @RequestParam(required = false) schema: String?,
    ): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.of(introspecting(name) { introspector.tables(name, schema).map { it.toResponse() } })

    /** §7A — one table's columns with canonical types; empty when the table does not exist. */
    @GetMapping("/{name}/tables/{table}/columns")
    @RequiredScope(ScopeMatrix.RestOperation.INTROSPECT_DATASOURCE)
    fun columns(
        @PathVariable name: String,
        @PathVariable table: String,
        @RequestParam(required = false) schema: String?,
    ): ApiResponse<List<Map<String, Any?>>> =
        ApiResponse.of(introspecting(name) { introspector.columns(name, table, schema).map { it.toResponse() } })

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

    private fun TableInfo.toResponse(): Map<String, Any?> =
        mapOf(
            "schema" to schema,
            "name" to name,
            "type" to type,
        )

    /**
     * The §7A column descriptor. Omitted-when-null follows the envelope convention
     * (type-system §7.3): a missing `precision`/`scale`/`nullable` key carries its documented
     * meaning, and `"nullable": null` would assert a fact nobody reported.
     */
    private fun ColumnInfo.toResponse(): Map<String, Any?> =
        buildMap {
            put("name", column.name)
            put("type", column.type.wire)
            column.precision?.let { put("precision", it) }
            column.scale?.let { put("scale", it) }
            column.nullable?.let { put("nullable", it) }
            put("source_type", sourceTypeName)
        }

    private fun SchemaSnapshot.toResponse(): Map<String, Any?> =
        mapOf(
            "datasource" to datasource,
            "dialect" to dialect,
            "truncated" to truncated,
            "tables" to
                tables.map { (table, columns) -> mapOf("table" to table.toResponse(), "columns" to columns.map { it.toResponse() }) },
        )
}
