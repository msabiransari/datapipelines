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
    val username: String,
    val password: String,
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
)

/** The file's top level. */
data class BootstrapDatasourcesFile(
    val datasources: List<BootstrapDatasourceEntry> = emptyList(),
)

/**
 * One resolved bootstrap entry: the entity to register, plus the ENVIRONMENT VARIABLE its
 * `password` field named before resolution (061/T84).
 *
 * The env key exists on this type because [BootstrapDatasourceFileReader] resolves `${VAR}`
 * placeholders away — by the time an entry is a [Datasource] the reference is gone, and §8A.3
 * rule 3's ERROR line has to name the key an operator would go and change. On 2026-09-02 the
 * answer was `SAMPLE_PG_PASSWORD` in `deploy/.env.demo`, and a log line saying "the stored
 * credential and the file's both fail" without naming it would have sent the operator hunting.
 *
 * Null when the entry's password is a literal rather than a placeholder — the SQLite sample
 * entry is exactly that case (there is no login to have a credential for), and a literal has
 * no env key to name.
 */
data class BootstrapDatasource(
    val datasource: Datasource,
    val passwordEnvKey: String?,
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
        val envKeys = passwordEnvKeys(tree)
        return file.datasources.mapIndexed { index, entry ->
            BootstrapDatasource(entry.toDatasource(path), envKeys.getOrNull(index))
        }
    }

    /**
     * The `${VAR}` name each entry's `password` field references, positionally, read off the
     * UNRESOLVED tree — null for an entry whose password is a literal. Only the first
     * placeholder in the value is reported: a composite password is a shape nobody writes, and
     * the first variable is the one an operator would look at.
     */
    private fun passwordEnvKeys(tree: JsonNode): List<String?> =
        tree
            .get("datasources")
            ?.takeIf { it.isArray }
            ?.map { entry ->
                entry
                    .get("password")
                    ?.takeIf { it.isTextual }
                    ?.textValue()
                    ?.let { PLACEHOLDER.find(it)?.groupValues?.get(1) }
            }.orEmpty()

    /**
     * Returns [node] with every `${VAR}` in every string expanded — a rebuilt tree, not a mutated
     * one. It walks the WHOLE tree rather than the two fields one expects to be secret: the
     * passthrough `properties.jdbc` map is exactly where an SSL passphrase would live.
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
                node.properties().forEach { (field, value) -> resolved.set<JsonNode>(field, resolvePlaceholders(value, path)) }
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
 * `workspace_id NULL`, which the INSERT achieves by not naming the column at all
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
    return Datasource(
        name = name,
        displayName = displayName ?: name,
        description = description,
        dialect = resolvedDialect,
        jdbcUrl = jdbcUrl,
        username = username,
        password = password,
        queryTimeoutSeconds = queryTimeoutSeconds,
        properties = DatasourceProperties.fromRaw(properties),
        isReadonly = readonly,
        introspectionIncludeSchemas = introspectionIncludeSchemas,
    )
}
