package co.datapipelines.dependencies.wire

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.PathType
import java.io.File

/**
 * The §11.1 schema and goldens read from the TEST CLASSPATH (so the tests prove what the module packages, not what sits
 * in the source tree), and the schema's sub-shapes as validators. The registry knows exactly the one schema text keyed
 * by its `$id` and never fetches a remote resource.
 */
internal object WireFiles {
    val mapper = ObjectMapper()

    val schemaText: String =
        requireNotNull(WireFiles::class.java.getResource(DependencyWire.SCHEMA_RESOURCE)) {
            "${DependencyWire.SCHEMA_RESOURCE} is not on the classpath"
        }.readText()

    val schema: JsonNode = mapper.readTree(schemaText)

    /** Every golden by case name (the file name without `.json`), sorted. */
    val goldens: Map<String, JsonNode> by lazy {
        val dir = requireNotNull(WireFiles::class.java.getResource(DependencyWire.GOLDEN_RESOURCE_DIR)) { "no golden directory" }
        File(dir.toURI())
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { it.name.removeSuffix(".json") to mapper.readTree(it) }
    }

    /** The error `details` goldens: the `refusal-` cases. */
    val refusals: Map<String, JsonNode> by lazy { goldens.filterKeys { it.startsWith(REFUSAL_PREFIX) } }

    /** The 200 `data` goldens: every other case. */
    val data: Map<String, JsonNode> by lazy { goldens.filterKeys { !it.startsWith(REFUSAL_PREFIX) } }

    private val registry: SchemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
                .schemaLoader { loader ->
                    loader.fetchRemoteResources(false).resourceLoaders { it.resources(mapOf(DependencyWire.SCHEMA_ID to schemaText)) }
                }
        }

    /** The root (the 200 `data` object) or a `$defs` member, e.g. `error_details` or `request`. */
    fun validator(def: String? = null): Schema =
        registry.getSchema(SchemaLocation.of(if (def == null) DependencyWire.SCHEMA_ID else "${DependencyWire.SCHEMA_ID}#/\$defs/$def"))

    /** The schema's `enum` members of `$defs` [def], as strings. */
    fun enumOf(def: String): Set<String> =
        requireNotNull(schema.path("\$defs").path(def).get("enum")) { "\$defs/$def has no enum" }.map { it.asText() }.toSet()

    const val REFUSAL_PREFIX = "refusal-"
}

/** Every element of [this] array-valued JSON field across a golden, flattened. */
internal fun JsonNode.items(field: String): List<JsonNode> = path(field).toList()
