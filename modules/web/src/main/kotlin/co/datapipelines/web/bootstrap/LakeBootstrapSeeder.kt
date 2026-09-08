package co.datapipelines.web.bootstrap

import co.datapipelines.application.datasources.LakeManifestFetcher
import co.datapipelines.application.datasources.LakeManifestUrl
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.UserRepository
import co.datapipelines.datasources.BootstrapDatasourceFileException
import co.datapipelines.datasources.BootstrapLakeImport
import co.datapipelines.datasources.BootstrapLakeTableSeeder
import co.datapipelines.datasources.Datasource
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * `web`'s answer to the registrar's [BootstrapLakeTableSeeder] port (089 §E): seeds a freshly
 * bootstrapped LAKE datasource's registry through [LakeTableRegistryService.importTables] — the
 * ONE validated import path REST and MCP share (049's rule; bootstrap is a third caller, not a
 * third path), which is also what makes the per-boot re-run idempotent by construction.
 *
 * ## The two seed shapes
 *
 * - **Inline `tables:`** — the file's rows are mapped onto the import body verbatim
 *   (`{namespace?, name, format, location, partition_column?}`); validation is the service's.
 * - **`import_manifest:`** — the manifest is fetched through the §A SSRF pair
 *   ([LakeManifestUrl.resolveFetchUrl] vets the URL against the datasource's own roots FIRST,
 *   then [LakeManifestFetcher] reads it), so bootstrap can never become an arbitrary URL
 *   fetcher. The fetched `tables[]` then go through the same import as an inline body.
 *
 * ## Where a relative `path` resolves — the mirror rule
 *
 * A manifest entry's RELATIVE `path` is resolved against **the manifest URL's own parent**, not
 * the manifest's embedded `publish_prefix`/`access.https_base` — those describe the canonical
 * PUBLICATION (the bucket 088's build wrote), not necessarily where THIS deployment reads from.
 * That is what makes a mirror work unchanged: fetch the same manifest from
 * `file:///srv/lake-mirror/v1/manifest.json` and every table lands under
 * `file:///srv/lake-mirror/v1/…`. For an http(s) manifest URL the parent is not a location the
 * registry grammar can serve (`s3://`/`file://` only — `LakeTableValidator.locationOf`), so the
 * manifest's own `publish_prefix` (then `https_base`) supplies the base instead; an entry whose
 * resolved location still fails the grammar is refused by the service, loudly. An entry that
 * carries only an ABSOLUTE `location` keeps it untouched.
 *
 * `namespace:` on the entry is the import body's shared namespace — a manifest's rows carry
 * none, and the demo file pins `[nyc, mobility]` because the 088 pipeline expects exactly that.
 * `only_tables:` is a name allowlist applied BEFORE the import; a name the source does not
 * offer fails the boot (a typo must not silently seed less than the file promised).
 *
 * ## The principal
 *
 * Registration is recorded against the bootstrap actor (`lake_tables.registered_by`), the
 * deployment's configured bootstrap ADMIN — so the principal carries `Scope.ADMIN` as a
 * statement of fact (`UserService.provisionBootstrapActor` grants admin by construction), which
 * is also what the D8 global-mutation gate reads. `AuthMethod.OIDC` is the closest fit for a
 * human administrator's row; nothing on the import path reads it.
 */
class LakeBootstrapSeeder(
    private val lakeTables: LakeTableRegistryService,
    private val users: UserRepository,
    private val fetcher: LakeManifestFetcher = LakeManifestFetcher.HTTP,
) : BootstrapLakeTableSeeder {
    private val log = LoggerFactory.getLogger(LakeBootstrapSeeder::class.java)

    // See ExampleContentSeeder for the same pattern: declared NOT as a constructor parameter so
    // Spring can never inject the servlet mapper over it (ObjectMapperDefaultParameterKonsistTest).
    private val mapper: ObjectMapper = ObjectMapper()

    override fun seed(
        datasource: Datasource,
        import: BootstrapLakeImport,
        actor: UUID,
    ) {
        val body =
            when {
                import.tables != null -> inlineBody(datasource.name, import)
                else -> manifestBody(datasource, import)
            }
        val result = lakeTables.importTables(datasource, body, bootstrapPrincipal(actor))
        log.info(
            "event=datasource.bootstrap_lake_imported name={} registered={} already_registered={}",
            datasource.name,
            result.registered.size,
            result.alreadyRegistered.size,
        )
    }

    /** The inline `tables:` block as an import body: the file's rows, plus the shared namespace. */
    private fun inlineBody(
        datasourceName: String,
        import: BootstrapLakeImport,
    ): JsonNode {
        val rows = import.tables.orEmpty()
        assertAllowlistSatisfied(datasourceName, import, rows.map { it.name })
        val body = mapper.createObjectNode()
        val array = body.putArray("tables")
        rows.filter { allowed(import, it.name) }.forEach { row ->
            val entry = array.addObject()
            row.namespace?.let { entry.putNamespace(it) }
            entry.put("name", row.name)
            entry.put("format", row.format)
            entry.put("location", row.location)
            row.partitionColumn?.let { entry.put("partition_column", it) }
        }
        import.namespace?.let { body.putNamespace(it) }
        return body
    }

    /** A namespace's segments as the `namespace` array child of this node (either spelling's array form). */
    private fun ObjectNode.putNamespace(segments: List<String>) {
        putArray("namespace").apply { segments.forEach { add(it) } }
    }

    /**
     * The `import_manifest:` block as an import body: fetch (SSRF-vetted), apply the allowlist,
     * resolve relative `path`s per the mirror rule (see the class KDoc), keep everything else.
     */
    private fun manifestBody(
        datasource: Datasource,
        import: BootstrapLakeImport,
    ): JsonNode {
        val url =
            import.manifestUrl
                ?: throw BootstrapDatasourceFileException(
                    "Bootstrap datasource '${datasource.name}' has a lake-table seed with neither 'tables' nor 'import_manifest'.",
                )
        val manifest = fetcher.fetch(LakeManifestUrl.resolveFetchUrl(datasource, url))
        val entries =
            manifest.get("tables")?.takeIf { it.isArray }
                ?: throw BootstrapDatasourceFileException(
                    "Bootstrap datasource '${datasource.name}': the manifest at '$url' carries no 'tables' array.",
                )
        val base = locationBaseOf(url, manifest)
        val offered = entries.filter { it.isObject }.map { it.get("name")?.asText().orEmpty() }
        assertAllowlistSatisfied(datasource.name, import, offered)
        val body = mapper.createObjectNode()
        val array = body.putArray("tables")
        entries
            .filter { it.isObject }
            .filter { allowed(import, it.get("name")?.asText().orEmpty()) }
            .forEach { entry ->
                val rewritten = entry.deepCopy<ObjectNode>()
                rewritten.get("path")?.takeIf { it.isTextual }?.let { path ->
                    rewritten.put("location", "${base.trimEnd('/')}/${path.asText().trimStart('/')}")
                    rewritten.remove("path")
                }
                array.add(rewritten)
            }
        import.namespace?.let { body.putNamespace(it) }
        return body
    }

    /**
     * The mirror rule's base (see the class KDoc): the manifest URL's own parent when that URL
     * already names a location the registry grammar serves (`s3://`, `file://`); the manifest's
     * declared publication prefix otherwise.
     */
    private fun locationBaseOf(
        givenUrl: String,
        manifest: JsonNode,
    ): String =
        when {
            givenUrl.startsWith("s3://") || givenUrl.startsWith("file://") -> {
                givenUrl.substringBeforeLast('/')
            }

            else -> {
                manifest.get("publish_prefix")?.takeIf { it.isTextual }?.asText()
                    ?: manifest
                        .get("access")
                        ?.takeIf { it.isObject }
                        ?.get("https_base")
                        ?.takeIf { it.isTextual }
                        ?.asText()
                    ?: givenUrl.substringBeforeLast('/')
            }
        }

    /** The `only_tables` predicate for one row: no allowlist declared, or the row's name is on it. */
    private fun allowed(
        import: BootstrapLakeImport,
        name: String,
    ): Boolean = import.onlyTables.let { it == null || name in it }

    /**
     * The allowlist's fail-loud half: a declared name the seed source never offers is a refused
     * boot naming it — a typo must not silently seed less than the file promised (089 §E).
     */
    private fun assertAllowlistSatisfied(
        datasourceName: String,
        import: BootstrapLakeImport,
        offered: List<String>,
    ) {
        val missing = import.onlyTables?.filter { it !in offered }.orEmpty()
        if (missing.isNotEmpty()) {
            throw BootstrapDatasourceFileException(
                "Bootstrap datasource '$datasourceName' has an 'only_tables' allowlist naming " +
                    missing.joinToString(", ") +
                    ", which the seed source does not offer — fix the allowlist or the source.",
            )
        }
    }

    /** The bootstrap actor as the import's principal — see the class KDoc. */
    private fun bootstrapPrincipal(actor: UUID): AuthenticatedPrincipal {
        val user =
            checkNotNull(users.findById(actor)) {
                "the bootstrap actor $actor has no users row — provisionBootstrapActor runs before registration"
            }
        return AuthenticatedPrincipal(
            userId = user.id,
            email = user.email,
            displayName = user.displayName,
            scopes = setOf(Scope.ADMIN),
            authMethod = AuthMethod.OIDC,
        )
    }
}
