package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path

/**
 * A problem with the bootstrap datasources file (datasources.md §8A). Startup refuses rather
 * than serving a half-registered demo, so this is an [IllegalStateException] in the shape
 * `ConfigValidator` already uses for a refused configuration — **not** a
 * `datasource.validation.*` error: nobody submitted a request, and the error-code catalog
 * (pipeline-contract §13.8) describes API responses.
 */
class BootstrapDatasourceFileException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * One entry of the bootstrap datasources file — the `POST /api/v1/datasources` field
 * vocabulary (datasources.md §3.1) plus the two spec-1 flags.
 *
 * Every wire name is spelled with an explicit [JsonProperty] rather than left to a naming
 * strategy: a reflective rename that silently stops binding is how a bootstrapped datasource
 * would end up with a null `jdbc_url` and no error. Unknown keys are **not** ignored — Jackson's
 * default `FAIL_ON_UNKNOWN_PROPERTIES` is what turns `jdbc_ur:` from a dead datasource into a
 * refused startup.
 */
data class BootstrapDatasourceEntry(
    val name: String,
    @JsonProperty("display_name") val displayName: String? = null,
    val description: String? = null,
    val dialect: String,
    @JsonProperty("jdbc_url") val jdbcUrl: String,
    /**
     * The LEGACY credential pair (§8A.1). Still accepted, still meaning `kind: password`, so
     * every file written before 087 keeps working byte-for-byte. Mutually exclusive with
     * [credential]: a file that declares both is refused rather than resolved by precedence,
     * because the two could disagree and the operator would never see which one won.
     */
    val username: String? = null,
    val password: String? = null,
    /** The §3.4 credential block: `{kind, username?, secret?}`. */
    val credential: BootstrapCredential? = null,
    @JsonProperty("query_timeout_seconds") val queryTimeoutSeconds: Int? = null,
    @JsonProperty("introspection_include_schemas") val introspectionIncludeSchemas: List<String> = emptyList(),
    val properties: Map<String, Any?> = emptyMap(),
    /** Writes `datasources.is_readonly` (workspaces design §6 / datasources.md §5.7). */
    val readonly: Boolean = false,
    /**
     * Must be present and `true` in v1. Nullable so a MISSING flag is distinguishable from an
     * explicit `false` — both are refused, with different messages (see [toDatasource]).
     */
    val global: Boolean? = null,
    /**
     * 089 §E — the dp-lake seed: an inline list of registry rows for a LAKE entry. Mutually
     * exclusive with [importManifest] (the credential-shape rule: the two can disagree, and no
     * silent winner is defensible). Ignored by every non-LAKE entry — see [toDatasource], which
     * REFUSES the combination instead: a `tables:` block under a Postgres entry is a paste error,
     * and skipping it silently would register a datasource missing the tables the file promised.
     */
    val tables: List<BootstrapLakeTable>? = null,
    /**
     * 089 §E — the other seed form: the URL of an 088 `manifest.json` whose `tables[]` the
     * bootstrap imports. Fetched server-side through the §A SSRF path (`LakeManifestUrl` —
     * restricted to the datasource's own roots; this file cannot turn bootstrap into an
     * arbitrary URL fetcher).
     */
    @JsonProperty("import_manifest") val importManifest: String? = null,
    /**
     * The namespace the seed's tables land in when a table row does not name its own (a
     * manifest's `tables[]` carry none). The demo file pins `[nyc, mobility]` — the 088
     * content's pipeline expects exactly that.
     */
    val namespace: List<String>? = null,
    /**
     * Optional name allowlist for the seed: when present, only these table names are imported
     * and a name the source does not offer FAILS the boot (a typo here must not silently seed
     * less than the file asked for). The demo uses it to keep the manifest's Iceberg copy out of
     * the `catalog.kind: s3` datasource — see the file's own comment.
     */
    @JsonProperty("only_tables") val onlyTables: List<String>? = null,
)

/**
 * The §3.4 credential block of a bootstrap entry — the same `{kind, username?, secret?}` shape
 * `POST /api/v1/datasources` takes.
 *
 * `secret` goes through `${ENV_VAR}` resolution like every other string in the tree (§8A.2), so a
 * real credential still never lives in the file. `kind: none` carries neither field and is what
 * the demo's SQLite and DuckDB entries use — the dummy password of the pre-087 examples is gone.
 */
data class BootstrapCredential(
    val kind: String,
    val username: String? = null,
    val secret: String? = null,
)

/** The file's top level. */
data class BootstrapDatasourcesFile(
    val datasources: List<BootstrapDatasourceEntry> = emptyList(),
)

/**
 * 089 §E — one inline registry row of a LAKE entry's `tables:` block: the
 * `{namespace, name, format, location, partition_column?}` shape the lake-table REST import
 * takes (metadata-db §4.15). Values pass through UNVALIDATED here — validation is the registry
 * service's single validated path (049's rule), which the seed runs at registration time; this
 * type only carries what the file said.
 */
data class BootstrapLakeTable(
    val namespace: List<String>? = null,
    val name: String,
    val format: String,
    val location: String,
    @JsonProperty("partition_column") val partitionColumn: String? = null,
)

/**
 * One entry's dp-lake seed, after the two accepted shapes reconcile (089 §E): EITHER the inline
 * [tables] OR a [manifestUrl] to fetch the `tables[]` from — never both (refused at parse, the
 * credential-shape rule), and never on a non-LAKE entry (also refused at parse).
 * [namespace] is the shared namespace a table row without its own lands in; [onlyTables] is the
 * optional name allowlist.
 */
data class BootstrapLakeImport(
    val tables: List<BootstrapLakeTable>?,
    val manifestUrl: String?,
    val namespace: List<String>?,
    val onlyTables: List<String>?,
)

/**
 * One resolved bootstrap entry: the entity to register, plus the ENVIRONMENT VARIABLE its
 * credential named before resolution (061/T84) — `password:` in the legacy shape,
 * `credential.secret:` in the §3.4 one.
 *
 * The env key exists on this type because [BootstrapDatasourceFileReader] resolves `${VAR}`
 * placeholders away — by the time an entry is a [Datasource] the reference is gone, and §8A.3
 * rule 3's ERROR line has to name the key an operator would go and change. On 2026-09-02 the
 * answer was `SAMPLE_PG_PASSWORD` in `deploy/secrets.env`, and a log line saying "the stored
 * credential and the file's both fail" without naming it would have sent the operator hunting.
 *
 * Null when the entry's secret is a literal rather than a placeholder, and null for
 * `kind: none`, which has no secret at all — the SQLite and DuckDB sample entries are exactly
 * that case (there is no login to have a credential for).
 */
data class BootstrapDatasource(
    val datasource: Datasource,
    val credentialEnvKey: String?,
    /** The entry's dp-lake seed (089 §E), when it declared one — null for every ordinary entry. */
    val lakeImport: BootstrapLakeImport? = null,
)

/**
 * Reads, resolves and validates the bootstrap datasources file (sample-data design §6,
 * datasources.md §8A). Pure: no database, no pool, no logging — [BootstrapDatasourceRegistrar]
 * owns the side effects, and keeping the parse pure is what lets every rule below be unit-tested
 * without a container.
 *
 * ## `${ENV_VAR}` resolution is ours, not Spring's
 * This file is runtime **data**, not a Spring property source: it is named by a config key and
 * read from disk after the context is built, so `${...}` in it means nothing to Spring's
 * `PropertySourcesPlaceholderConfigurer`. Resolution therefore happens here, explicitly, against
 * the process environment, over **every** string in the tree (so a secret can live in
 * `properties.jdbc.*` as easily as in `password`). An unresolved placeholder is a refused
 * startup naming the variable — never a datasource registered with the literal `${...}` as its
 * password, which would fail at first query with an unintelligible authentication error.
 */
class BootstrapDatasourceFileReader(
    private val environment: (String) -> String? = System::getenv,
    private val mapper: ObjectMapper = defaultMapper(),
) {
    /**
     * Parses [path] into ready-to-save entities, in file order.
     *
     * @throws BootstrapDatasourceFileException when the file is unreadable, unparseable, binds
     *   to an unknown/missing field, names an unknown dialect, carries an unresolved
     *   `${ENV_VAR}`, or declares a non-global entry.
     *
     * `ThrowsCount` is suppressed because each throw is a DIFFERENT refusal with a different
     * remedy for the operator (unreadable file / bad YAML / empty file / no entries). Collapsing
     * them would trade the sentence that tells them what to fix for a statement-count rule.
     */
    @Suppress("ThrowsCount")
    fun read(path: Path): List<BootstrapDatasource> {
        val text =
            try {
                Files.readString(path)
            } catch (e: java.io.IOException) {
                throw BootstrapDatasourceFileException("Bootstrap datasources file '$path' could not be read: ${e.message}", e)
            }
        val tree =
            try {
                mapper.readTree(text)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw BootstrapDatasourceFileException("Bootstrap datasources file '$path' is not valid YAML: ${e.originalMessage}", e)
            }
        if (tree == null || tree.isNull || tree.isMissingNode) {
            throw BootstrapDatasourceFileException("Bootstrap datasources file '$path' is empty.")
        }
        val resolved = resolvePlaceholders(tree, path)
        val file =
            try {
                mapper.treeToValue(resolved, BootstrapDatasourcesFile::class.java)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw BootstrapDatasourceFileException("Bootstrap datasources file '$path' is malformed: ${e.originalMessage}", e)
            }
        if (file.datasources.isEmpty()) {
            throw BootstrapDatasourceFileException(
                "Bootstrap datasources file '$path' declares no datasources — unset " +
                    "datapipelines.bootstrap.datasources-file to turn the feature off instead.",
            )
        }
        // Index-paired with the RAW tree, not name-matched: the resolver rebuilds the tree in
        // place, so entry i of the parsed file is entry i of the raw array — and a name that
        // is itself a placeholder would make name-matching wrong in the one case it matters.
        val envKeys = credentialEnvKeys(tree)
        return file.datasources.mapIndexed { index, entry ->
            val datasource = entry.toDatasource(path)
            BootstrapDatasource(datasource, envKeys.getOrNull(index), entry.resolveLakeImport(path, datasource.dialect))
        }
    }

    /**
     * The `${VAR}` name each entry's credential references, positionally, read off the
     * UNRESOLVED tree — the legacy `password:` field or the §3.4 `credential.secret:` one,
     * whichever the entry used; null for a literal and for `kind: none`. Only the first
     * placeholder in the value is reported: a composite secret is a shape nobody writes, and
     * the first variable is the one an operator would look at.
     */
    private fun credentialEnvKeys(tree: JsonNode): List<String?> =
        tree
            .get("datasources")
            ?.takeIf { it.isArray }
            ?.map { entry ->
                val secret = entry.get("password") ?: entry.get("credential")?.get("secret")
                secret
                    ?.takeIf { it.isTextual }
                    ?.textValue()
                    ?.let { PLACEHOLDER.find(it)?.groupValues?.get(1) }
            }.orEmpty()

    /**
     * Returns [node] with every `${VAR}` in every string expanded — a rebuilt tree, not a mutated
     * one. It walks the WHOLE tree rather than the two fields one expects to be secret: the
     * passthrough `properties.jdbc` map is exactly where an SSL passphrase would live.
     *
     * 109 §B — one deliberate exception, at the FIELD level: a field whose ENTIRE value is one
     * `${VAR}` and the variable is SET BUT EMPTY is OMITTED, not resolved to `""`. That is the
     * operator's off-switch — the shipped lake file's `catalog.ref: ${SAMPLE_LAKE_CATALOG_REF}`
     * stays EMPTY in production (compose defaults the var to empty), and "not declared" is the
     * honest spelling of that, not an empty string the validator must now refuse. A LITERAL
     * empty value in the file is untouched by this rule and fails validation with the key named
     * — the fail-fast the round exists for. Omission is key-level: a field that cannot be
     * absent (a name, a URL) fails binding loudly rather than silently vanishing.
     */
    private fun resolvePlaceholders(
        node: JsonNode,
        path: Path,
    ): JsonNode =
        when (node) {
            is TextNode -> {
                TextNode.valueOf(expand(node.textValue(), path))
            }

            is ObjectNode -> {
                val resolved = mapper.createObjectNode()
                node.properties().forEach { (field, value) ->
                    if (omitsWhenPlaceholderEmpty(value)) return@forEach
                    resolved.set<JsonNode>(field, resolvePlaceholders(value, path))
                }
                resolved
            }

            is ArrayNode -> {
                val resolved = mapper.createArrayNode()
                node.forEach { element -> resolved.add(resolvePlaceholders(element, path)) }
                resolved
            }

            else -> {
                node
            }
        }

    /**
     * Whether [value] is a field the 109 §B omission rule drops: a TextNode whose ENTIRE value
     * is one `${VAR}` and the variable is SET BUT EMPTY. UNSET still refuses (expand's rule,
     * §8A.2 — the omission is the off-switch, not a bypass of the fail-fast); a literal `""`
     * never matches a placeholder and survives to validation.
     */
    private fun omitsWhenPlaceholderEmpty(value: JsonNode): Boolean {
        if (value !is TextNode) return false
        val text = value.textValue()
        if (!PLACEHOLDER.matches(text)) return false
        val resolved = environment(PLACEHOLDER.find(text)!!.groupValues[1]) ?: return false
        return resolved.isEmpty()
    }

    private fun expand(
        value: String,
        path: Path,
    ): String =
        PLACEHOLDER.replace(value) { match ->
            val variable = match.groupValues[1]
            environment(variable)
                ?: throw BootstrapDatasourceFileException(
                    "Bootstrap datasources file '$path' references environment variable " +
                        "'$variable', which is not set in this process's environment.",
                )
        }

    private companion object {
        /** `${NAME}` over the POSIX environment-variable alphabet. */
        val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

        /**
         * A YAML mapper with the Kotlin module, so `data class` defaults and null-safety hold.
         * `FAIL_ON_UNKNOWN_PROPERTIES` stays at Jackson's default (on) deliberately — see
         * [BootstrapDatasourceEntry].
         */
        fun defaultMapper(): ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    }
}

/**
 * Maps one parsed entry to the entity the registry saves.
 *
 * `global: true` is the only admitted value in v1. The file shape carries the flag because
 * spec 1 defines both scopes, but registration runs **before any workspace exists**, so there is
 * no answer to "which workspace does a non-global bootstrap entry bind to". `global: true` means
 * `owner_workspace_id NULL` — an INSTANCE datasource (D-R7) — which the INSERT achieves by not naming the column at all
 * (metadata-db §4.10: NULL = global).
 *
 * `ThrowsCount` is suppressed for the same reason as [BootstrapDatasourceFileReader.read]: three
 * distinct refusals — `global: false`, a missing `global`, and an unknown dialect — each of which
 * needs the operator to do something different about it.
 */
@Suppress("ThrowsCount")
private fun BootstrapDatasourceEntry.toDatasource(path: Path): Datasource {
    if (global == false) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' declares 'global: false'. Workspace-bound " +
                "bootstrap entries are not supported in v1: registration runs before any workspace exists, " +
                "so there is no workspace for the entry to bind to.",
        )
    }
    if (global == null) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' does not declare 'global'. " +
                "It is required and must be 'true' in v1 — the flag is stated explicitly so a file " +
                "written for a later version cannot be silently read as global.",
        )
    }
    val resolvedDialect =
        try {
            Dialect.fromWire(dialect.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw BootstrapDatasourceFileException(
                "Bootstrap datasource '$name' in '$path' names unknown dialect '$dialect'. " +
                    "Supported: ${Dialect.entries.joinToString(", ") { it.wire }}.",
                e,
            )
        }
    val resolvedCredential = resolveCredential(path)
    return Datasource(
        name = name,
        displayName = displayName ?: name,
        description = description,
        dialect = resolvedDialect,
        jdbcUrl = jdbcUrl,
        username = resolvedCredential.username,
        credentialKind = resolvedCredential.kind,
        secret = resolvedCredential.secret,
        queryTimeoutSeconds = queryTimeoutSeconds,
        properties = DatasourceProperties.fromRaw(properties),
        isReadonly = readonly,
        introspectionIncludeSchemas = introspectionIncludeSchemas,
    )
}

/** One entry's credential, after the legacy-pair / `credential:` block reconciliation. */
private data class ResolvedBootstrapCredential(
    val kind: CredentialKind,
    val username: String?,
    val secret: String?,
)

/**
 * 089 §E — one entry's dp-lake seed, after the shape rules. Returns null for an entry that
 * declares no seed at all (every ordinary entry — the feature is opt-in per entry).
 *
 * The refusals, each a startup failure naming the entry, because each is a paste error a silent
 * skip would hide:
 * - `tables:` AND `import_manifest:` together (the credential-shape rule — they can disagree);
 * - either one on a non-LAKE entry (a JDBC database's tables are discovered, never registered —
 *   the registry service's LAKE-only rule, stated here at parse so the failure arrives
 *   before the first save);
 * - `namespace:` / `only_tables:` with NO seed — dead config, never a deliberate choice;
 * - an explicitly EMPTY `tables:` block — a seed of zero tables is a mistake, not a policy.
 */
@Suppress("ThrowsCount") // each throw is a DIFFERENT refusal with a different remedy — see read()
private fun BootstrapDatasourceEntry.resolveLakeImport(
    path: Path,
    dialect: Dialect,
): BootstrapLakeImport? {
    if (tables != null && importManifest != null) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' declares BOTH 'tables' and 'import_manifest'. " +
                "Use one: the inline registry rows, or the URL of a manifest whose tables[] the bootstrap imports.",
        )
    }
    if (tables == null && importManifest == null) {
        if (namespace != null || onlyTables != null) {
            throw BootstrapDatasourceFileException(
                "Bootstrap datasource '$name' in '$path' declares 'namespace'/'only_tables' but no seed " +
                    "('tables' or 'import_manifest') — they qualify a seed, they are not one.",
            )
        }
        return null
    }
    if (dialect != Dialect.LAKE) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' is ${dialect.wire} but declares a lake-table seed " +
                "('tables' or 'import_manifest') — only a LAKE entry carries one.",
        )
    }
    if (tables != null && tables.isEmpty()) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' declares an EMPTY 'tables' block — " +
                "remove the block to seed nothing, or list the registry rows.",
        )
    }
    return BootstrapLakeImport(tables = tables, manifestUrl = importManifest, namespace = namespace, onlyTables = onlyTables)
}

/**
 * §8A.1/§3.4 — reconciles the two accepted credential shapes into one.
 *
 * The legacy `username:`/`password:` pair means `kind: password`, unchanged, so every file
 * written before 087 registers exactly as it did. The `credential:` block states the kind
 * explicitly and carries `username`/`secret` under the rules of [CredentialKind].
 *
 * Declaring BOTH is a startup refusal rather than a precedence rule: the two can disagree
 * (different usernames, one secret a placeholder and the other a literal) and no silent winner
 * is defensible — the operator would be told nothing and get a datasource neither half describes.
 *
 * Declaring NEITHER is a refusal too, naming both shapes: it is the shape of a file whose
 * `password:` key was mistyped, and the pre-087 reader turned that into a Jackson
 * missing-field failure, which this must not quietly relax into "kind: none, no credential".
 */
@Suppress("ThrowsCount")
private fun BootstrapDatasourceEntry.resolveCredential(path: Path): ResolvedBootstrapCredential {
    val hasLegacy = username != null || password != null
    if (hasLegacy && credential != null) {
        throw BootstrapDatasourceFileException(
            "Bootstrap datasource '$name' in '$path' declares BOTH the legacy 'username'/'password' pair and a " +
                "'credential' block. Use one: 'credential: {kind, username, secret}' is the current shape " +
                "(datasources.md §3.4); the pair still works and means 'kind: password'.",
        )
    }
    if (credential == null) {
        if (!hasLegacy) {
            throw BootstrapDatasourceFileException(
                "Bootstrap datasource '$name' in '$path' declares no credential. Supply either " +
                    "'credential: {kind: ...}' (datasources.md §3.4 — 'kind: none' for a file database with no " +
                    "login) or the legacy 'username'/'password' pair.",
            )
        }
        return ResolvedBootstrapCredential(CredentialKind.PASSWORD, username, password)
    }
    val kind =
        CredentialKind.fromWireOrNull(credential.kind.trim().lowercase())
            ?: throw BootstrapDatasourceFileException(
                "Bootstrap datasource '$name' in '$path' names unknown credential kind '${credential.kind}'. " +
                    "Supported: ${CredentialKind.entries.joinToString(", ") { it.wire }}.",
            )
    return ResolvedBootstrapCredential(kind, credential.username, credential.secret)
}
