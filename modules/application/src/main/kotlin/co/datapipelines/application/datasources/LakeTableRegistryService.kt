package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeIntrospectionCache
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.dao.DuplicateKeyException
import java.util.UUID

/**
 * The D8 gate for lake-table mutations, as a port — the [DatasourceCreateBinding] precedent.
 *
 * Mutating a GLOBAL datasource is admin-only (workspaces design §8), and registering a table on
 * one IS mutating it. The rule itself lives in `web`'s `DatasourceWorkspaceRules`, which sits
 * ABOVE this module and can never be imported here — so it is injected, and `web` wires its one
 * rules instance. REST and MCP therefore run the same gate object: there is no second copy of
 * the permission matrix to drift.
 */
fun interface LakeTableMutationGate {
    /** Throws the catalogued D8 refusal when [principal] may not mutate [datasource]. */
    fun check(
        principal: AuthenticatedPrincipal,
        datasource: Datasource,
    )

    companion object {
        /** No gate — tests, and any deployment whose datasources are all workspace-bound by policy. */
        val NONE = LakeTableMutationGate { _, _ -> }
    }
}

/**
 * The dp-lake catalog (metadata-db §4.15, the 2026-09-07 lake-datasource design record §2,
 * round 089 §A): registering, importing, unregistering and listing the tables of a LAKE-dialect
 * datasource — the ONE validated path behind
 * `POST/DELETE /api/v1/datasources/{name}/tables[/import]` and the `lake_tables_*` MCP tools
 * (049's rule: two entry points, one validated path).
 *
 * The datasource arrives ALREADY visibility-gated: the surfaces resolve it through the §5.3
 * predicate (`getVisible` / `requireVisible`), so an invisible datasource is not-found before
 * this service ever runs — the same split [DatasourceCreateService] keeps. What lives here is
 * everything the two surfaces must share: the D8 mutation gate, the LAKE-only refusal, the
 * grammar/location/SSRF validation, the duplicate/not-found mappings and the invalidation seam.
 *
 * ## The LAKE-only rule
 *
 * Every operation refuses a non-LAKE datasource with `datasource.validation.lake_dialect_required`
 * — a JDBC database's tables are discovered, never registered, and accepting a registration for
 * one would invent a second, contradictory source of truth for its schema.
 *
 * ## The invalidation seam (phase B's hook)
 *
 * A registry change must rebuild the datasource's pooled connections, because phase B's per-table
 * views are created in `LakeDialectAdapter.connectionInit` at pool build: a pool built before a
 * registration serves connections that cannot see the new table. So every successful mutation
 * here calls [refreshConnections] — phase C's [LakeIntrospectionCache] drop,
 * `DatasourceRegistry.evictPool(name)` for this instance and [PoolInvalidationPublisher.publish]
 * for the peers, the SAME pair `DefaultDatasourceRegistry` runs on every save/delete (§5.7). The
 * `DatasourceMetadataCache` half has nothing to drop: a lake-table write changes no datasource
 * ROW. [refreshConnections] is the single place to extend, which is the point of naming it.
 */
class LakeTableRegistryService(
    private val datasources: DatasourceRegistry,
    private val tables: LakeTableRepository,
    private val invalidation: PoolInvalidationPublisher = PoolInvalidationPublisher.NONE,
    private val manifestFetcher: LakeManifestFetcher = LakeManifestFetcher.HTTP,
    private val mutationGate: LakeTableMutationGate = LakeTableMutationGate.NONE,
    private val introspectionCache: LakeIntrospectionCache = LakeIntrospectionCache.NONE,
) {
    /** The datasource's registered tables, in tree order. Read-only; LAKE-only like the writes. */
    fun list(datasource: Datasource): List<LakeTable> {
        requireLake(datasource)
        return tables.findByDatasource(datasource.name)
    }

    /**
     * Registers one table from the REST/MCP body: `{namespace, name, format, location,
     * partition_column?}` — `namespace` as an array of segments or the dotted shorthand.
     *
     * @return the stored row.
     * @throws DatapipelinesException `datasource.lake_table_duplicate` (409) when the triple is
     *   taken, or any of the §13.8 validation codes the body trips.
     */
    fun register(
        datasource: Datasource,
        body: JsonNode,
        principal: AuthenticatedPrincipal,
    ): LakeTable {
        mutationGate.check(principal, datasource)
        requireLake(datasource)
        val registration =
            LakeTableRegistration(
                namespace = LakeTableValidator.namespaceOf(namespaceFieldOf(body)),
                name = LakeTableValidator.nameOf(requiredText(body, "name")),
                format = LakeTableValidator.formatOf(requiredText(body, "format")),
                location = LakeTableValidator.locationOf(requiredText(body, "location")),
                partitionColumn = LakeTableValidator.partitionColumnOf(optionalText(body, "partition_column")),
            )
        val inserted =
            try {
                tables.insert(datasource.name, registration, principal.userId)
            } catch (e: DuplicateKeyException) {
                throw DatapipelinesException(
                    PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE,
                    "Lake table '${registration.qualified()}' is already registered on datasource '${datasource.name}'.",
                    mapOf("datasource_name" to datasource.name, "table" to registration.qualified()),
                    e,
                )
            }
        refreshConnections(datasource.name)
        return inserted
    }

    /**
     * Unregisters one table by its triple. An absent triple is the catalogued
     * `datasource.lake_table_not_found` (404) — never a silent no-op: an agent that believes it
     * removed a table should be told when it did not.
     */
    fun unregister(
        datasource: Datasource,
        namespace: List<String>,
        name: String,
        principal: AuthenticatedPrincipal,
    ) {
        mutationGate.check(principal, datasource)
        requireLake(datasource)
        val validNamespace = LakeTableValidator.namespaceOf(namespace)
        val validName = LakeTableValidator.nameOf(name)
        if (!tables.delete(datasource.name, validNamespace, validName)) {
            val qualified = (validNamespace + validName).joinToString(".")
            throw DatapipelinesException(
                PipelineErrorCodes.Datasource.LAKE_TABLE_NOT_FOUND,
                "Lake table '$qualified' is not registered on datasource '${datasource.name}'.",
                mapOf("datasource_name" to datasource.name, "table" to qualified),
            )
        }
        refreshConnections(datasource.name)
    }

    /**
     * Bulk registration — 088's `manifest.json` `tables[]` shape, from EITHER of two body forms:
     *
     * - `{tables: [...], namespace?, publish_prefix?, access?}` — the block inline. Each entry
     *   carries `name`, `format`, and `location` (absolute) or `path` (resolved against the
     *   body's `publish_prefix`, or its `access.https_base`). An entry's own `namespace` wins;
     *   otherwise the body's shared `namespace` applies; an entry with neither is refused.
     * - `{manifest_url: "https://…"}` — the manifest is fetched SERVER-SIDE, and only from the
     *   datasource's own endpoint/bucket ([LakeManifestUrl] — the SSRF boundary); the fetched
     *   document's `tables[]`/`publish_prefix`/`access` drive the same import.
     *
     * Import is IDEMPOTENT by design (bootstrap re-runs it on every boot): an already-registered
     * triple is reported in [LakeImportResult.alreadyRegistered], not an error. Validation is
     * all-or-nothing — every entry is validated before the first insert, so a bad entry fails
     * the whole import rather than leaving half a manifest registered.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    fun importTables(
        datasource: Datasource,
        body: JsonNode,
        principal: AuthenticatedPrincipal,
    ): LakeImportResult {
        mutationGate.check(principal, datasource)
        requireLake(datasource)
        val sharedNamespace = body.get("namespace")?.let { namespaceValueOf(it) }
        val inlineTables = body.get("tables")?.isArray == true
        val url = optionalText(body, "manifest_url") ?: optionalText(body, "url")
        if (inlineTables && url != null) {
            throw shape(
                "the import body carries BOTH 'tables' and 'manifest_url' — they can disagree and " +
                    "there is no defensible winner (the credential-shape rule, datasources.md §3.4)",
            )
        }
        val source: JsonNode =
            when {
                inlineTables -> {
                    body
                }

                else -> {
                    val manifestUrl =
                        url
                            ?: throw shape(
                                "the import body carries EITHER a 'tables' array OR a 'manifest_url' (optionally " +
                                    "with a shared 'namespace'); it carried neither",
                            )
                    manifestFetcher.fetch(LakeManifestUrl.resolveFetchUrl(datasource, manifestUrl))
                }
            }
        val entries = source.get("tables")?.takeIf { it.isArray } ?: throw shape("the manifest carries no 'tables' array")
        val base = basePrefixOf(source)
        // Validate EVERY entry first — all-or-nothing (see the KDoc).
        val registrations = entries.map { entry -> registrationOf(entry, sharedNamespace, base) }
        val registered = mutableListOf<LakeTable>()
        val alreadyRegistered = mutableListOf<String>()
        registrations.forEach { registration ->
            val inserted = tables.insertIfAbsent(datasource.name, registration, principal.userId)
            if (inserted != null) registered += inserted else alreadyRegistered += registration.qualified()
        }
        if (registered.isNotEmpty()) refreshConnections(datasource.name)
        return LakeImportResult(registered, alreadyRegistered)
    }

    /**
     * One manifest entry, validated: `name`/`format` required, `location` absolute or `path`
     * resolved against [base], the entry's own `namespace` beating the shared one (an entry
     * with neither is refused — a namespaceless table is unaddressable, metadata-db §4.15).
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    private fun registrationOf(
        entry: JsonNode,
        sharedNamespace: List<String>?,
        base: String?,
    ): LakeTableRegistration {
        if (!entry.isObject) throw shape("every tables[] entry must be an object")
        val location =
            optionalText(entry, "location")
                ?: optionalText(entry, "path")?.let { path ->
                    base?.let { "${it.trimEnd('/')}/${path.trimStart('/')}" }
                        ?: throw shape(
                            "tables[] entry '${entry.path("name")}' has a relative 'path' but no " +
                                "publish_prefix/access.https_base to resolve it against",
                        )
                }
                ?: throw shape("tables[] entry '${entry.path("name")}' carries neither 'location' nor 'path'")
        return LakeTableRegistration(
            namespace =
                LakeTableValidator.namespaceOf(
                    entry.get("namespace")?.let { namespaceValueOf(it) }
                        ?: sharedNamespace
                        ?: throw shape(
                            "tables[] entry '${entry.path("name")}' has no namespace and the request supplies no shared one",
                        ),
                ),
            name = LakeTableValidator.nameOf(requiredText(entry, "name")),
            format = LakeTableValidator.formatOf(requiredText(entry, "format")),
            location = LakeTableValidator.locationOf(location),
            partitionColumn = LakeTableValidator.partitionColumnOf(optionalText(entry, "partition_column")),
        )
    }

    /**
     * The invalidation seam (see the class KDoc): phase C's introspection cache, local pool
     * eviction, then the §5.7 fan-out — AFTER the row write returned, the same ordering
     * `DefaultDatasourceRegistry` keeps, so a subscriber never rebuilds from a state that is
     * not yet durable. Phase B's view creation and phase C's registry-backed introspection
     * cache extend THIS method and no other.
     */
    private fun refreshConnections(datasourceName: String) {
        introspectionCache.invalidate(datasourceName)
        datasources.evictPool(datasourceName)
        invalidation.publish(datasourceName)
    }

    private fun requireLake(datasource: Datasource) {
        if (datasource.dialect != Dialect.LAKE) {
            throw DatapipelinesException(
                PipelineErrorCodes.Datasource.LAKE_DIALECT_REQUIRED,
                "Datasource '${datasource.name}' is ${datasource.dialect.wire}; lake tables can only be " +
                    "registered on a LAKE-dialect datasource.",
                mapOf("datasource_name" to datasource.name, "dialect" to datasource.dialect.wire),
            )
        }
    }

    /** The manifest's base for relative `path` entries: `publish_prefix`, else `access.https_base`. */
    private fun basePrefixOf(source: JsonNode): String? =
        optionalText(source, "publish_prefix")
            ?: source.get("access")?.takeIf { it.isObject }?.let { optionalText(it, "https_base") }

    /** A namespace field in either spelling: an array of segments, or the dotted shorthand. */
    private fun namespaceFieldOf(body: JsonNode): List<String> =
        body.get("namespace")?.let { namespaceValueOf(it) }
            ?: throw shape("a 'namespace' is required — an array of segments or the dotted 'nyc.mobility' form")

    private fun namespaceValueOf(node: JsonNode): List<String> =
        when {
            node.isArray -> {
                node.map { element ->
                    if (!element.isTextual) throw shape("namespace array entries must be strings")
                    element.asText()
                }
            }

            node.isTextual -> {
                node
                    .asText()
                    .split('.')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }

            else -> {
                throw shape("namespace must be an array of segments or the dotted shorthand")
            }
        }

    private fun requiredText(
        body: JsonNode,
        field: String,
    ): String = optionalText(body, field) ?: throw shape("'$field' is required")

    private fun optionalText(
        body: JsonNode,
        field: String,
    ): String? =
        body
            .get(field)
            ?.takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.isNotBlank() }

    private fun shape(why: String): DatapipelinesException =
        DatapipelinesException(
            PipelineErrorCodes.Datasource.PROPERTIES_INVALID,
            "Invalid lake table payload: $why.",
            mapOf("field" to "tables"),
        )

    private fun LakeTableRegistration.qualified(): String = (namespace + name).joinToString(".")
}

/**
 * The import's outcome: the rows this call registered, and the triples that were already
 * registered (idempotent re-import — see [LakeTableRegistryService.importTables]), as dotted
 * qualified names.
 */
data class LakeImportResult(
    val registered: List<LakeTable>,
    val alreadyRegistered: List<String>,
) {
    /** The shared wire projection — ONE shape for REST and MCP (the `toWireMap` precedent). */
    fun toWireMap(): Map<String, Any?> =
        linkedMapOf(
            "registered" to registered.map { it.toWireMap() },
            "registered_count" to registered.size,
            "already_registered" to alreadyRegistered,
            "already_registered_count" to alreadyRegistered.size,
        )
}
