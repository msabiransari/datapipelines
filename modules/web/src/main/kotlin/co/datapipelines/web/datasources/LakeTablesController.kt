package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.application.datasources.toWireMap
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * The dp-lake catalog endpoints (rest-api.md §9.8, metadata-db §4.15, round 089 §A).
 *
 * A thin HTTP shell over [LakeTableRegistryService] — the ONE validated path the
 * `lake_tables_*` MCP tools call too (049's rule), so what lives here is only the wire shape:
 * the §5.3 visibility gate (an invisible datasource is not-found, identical to unknown), the
 * status codes and the shared `toWireMap` projections. Validation (grammar, location, SSRF),
 * the D8 mutation gate, the LAKE-only refusal and the duplicate/not-found mappings are all the
 * service's.
 *
 * Scope: `author` (auth.md §7.6 — [ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES]),
 * the datasource-mutation floor these operations are. Mutating a GLOBAL datasource additionally
 * requires admin — a D8 rule the service's injected gate enforces, not a scope, so it does not
 * appear on the annotations.
 *
 * The READ twin (`GET /{name}/tables`) is deliberately untouched: today it is the §7A
 * introspection listing; a later phase makes it registry-backed for LAKE.
 */
@RestController
@RequestMapping("/api/v1/datasources")
class LakeTablesController(
    private val datasources: DatasourceRegistry,
    private val lakeTables: LakeTableRegistryService,
) {
    /**
     * §9.8 — register one table: `{namespace, name, format, location, partition_column?}`,
     * `namespace` as an array of segments or the dotted shorthand. 201 with the stored row;
     * `datasource.lake_table_duplicate` (409) when the triple is taken.
     */
    @PostMapping("/{name}/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES)
    fun register(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val datasource = datasources.getVisible(name, principal.requireWorkspace().id) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(lakeTables.register(datasource, body, principal).toWireMap())
    }

    /**
     * §9.8 — unregister one table, addressed by its dotted namespace and name
     * (`DELETE …/tables/nyc.mobility/hvfhv_zone_day`). An absent triple is
     * `datasource.lake_table_not_found` (404).
     */
    @DeleteMapping("/{name}/tables/{namespace}/{table}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES)
    fun unregister(
        @PathVariable name: String,
        @PathVariable namespace: String,
        @PathVariable table: String,
    ) {
        val principal = currentPrincipal()
        val datasource = datasources.getVisible(name, principal.requireWorkspace().id) ?: throw ApiErrors.datasourceNotFound(name)
        lakeTables.unregister(
            datasource,
            namespace.split('.').map { it.trim() }.filter { it.isNotEmpty() },
            table,
            principal,
        )
    }

    /**
     * §9.8 — bulk import: EITHER a 088-style `tables[]` block inline, OR `manifest_url` —
     * fetched server-side ONLY from the datasource's own endpoint/bucket
     * (`datasource.validation.lake_manifest_url_forbidden` otherwise; no arbitrary URL fetch).
     * Idempotent: already-registered triples are reported, not errors.
     */
    @PostMapping("/{name}/tables/import")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES)
    fun import(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val datasource = datasources.getVisible(name, principal.requireWorkspace().id) ?: throw ApiErrors.datasourceNotFound(name)
        return ApiResponse.of(lakeTables.importTables(datasource, body, principal).toWireMap())
    }

    /**
     * §9.8 — the registry listing: the tables REGISTERED on a LAKE datasource (the catalog's
     * own rows), as distinct from the §7A introspection listing on `GET /{name}/tables`, which
     * reads live metadata. Read scope; the tree UI renders this.
     */
    @GetMapping("/{name}/lake-tables")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun listRegistered(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val datasource = datasources.getVisible(name, principal.requireWorkspace().id) ?: throw ApiErrors.datasourceNotFound(name)
        val tables = lakeTables.list(datasource)
        return ApiResponse.of(
            mapOf(
                "datasource" to name,
                "tables" to tables.map { it.toWireMap() },
                "count" to tables.size,
            ),
        )
    }
}
