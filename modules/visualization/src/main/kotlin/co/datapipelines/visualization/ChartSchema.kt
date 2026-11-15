package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.PathType

/**
 * The chart document v1 schemas (design record 2026-11-06-neutral-chart-grammar §4.2): JSON Schema 2020-12, every
 * object closed. The resources under [RESOURCE_DIR] are the ONE copy — the save path validates against them and the
 * web serves the same bytes at `/schemas/chart/1/<file>` (§4.2, "the served schema says 1–120").
 */
object ChartSchemas {
    /** The `$id` prefix every v1 schema carries; version 1 is the path segment (an incompatible change is `/2/`). */
    const val ID_PREFIX = "https://datapipelines.co/schemas/chart/1/"

    /** The classpath directory of the schema files. */
    const val RESOURCE_DIR = "co/datapipelines/visualization/schemas/chart/1/"

    /** The document skeleton every family shares. */
    const val SKELETON = "skeleton.schema.json"

    /** The bar family — `allOf` the skeleton, narrowing `config`; the entry of a document whose `config.mark.type` names no family. */
    const val BAR = "bar.schema.json"

    /** Every served file, in a stable order: the skeleton, then one schema per [ChartType] (its name derived, §4.2). */
    val FILES: List<String> = listOf(SKELETON) + ChartType.entries.map { it.schemaFile }

    /** The file's bytes from this module's classpath; a missing resource is a packaging defect, never a 404 to explain. */
    fun bytes(file: String): ByteArray {
        require(file in FILES) { "not a chart schema file" }
        val stream =
            checkNotNull(ChartSchemas::class.java.classLoader.getResourceAsStream(RESOURCE_DIR + file)) {
                "chart schema resource $file is missing from the classpath"
            }
        return stream.use { it.readBytes() }
    }
}

/** One schema refusal: the keyword that failed and the JSON Pointer of the instance it failed at — never document text. */
data class ChartSchemaProblem(
    val keyword: String,
    val pointer: String,
)

/**
 * Layer 1 of a chart document's save (record §4.4): the v1 schema over the WHOLE body as stored. The entry is the
 * family schema `config.mark.type` names ([ChartType.schemaFile]), so a family's refusals are its own schema's; a
 * document naming no family is judged by [entry] (lane F5, #989; before it bar was the only family and the only
 * entry). The registry knows exactly the [ChartSchemas.FILES] (an in-memory map keyed by `$id`) and has NO IRI loader — the library adds one
 * only when asked to fetch remote resources — so a `$ref` outside the map fails fast and offline
 * (`ChartSchemaValidatorTest` proves it against a listening socket that sees no connection). Patterns run on the
 * library's default JDK regex engine; every pattern in the files is a single anchored character-class run or a
 * literal-led group, so matching is linear in the input.
 */
class ChartSchemaValidator(
    resources: Map<String, String> = ChartSchemas.FILES.associate { ChartSchemas.ID_PREFIX + it to String(ChartSchemas.bytes(it)) },
    entry: String = ChartSchemas.ID_PREFIX + ChartSchemas.BAR,
) {
    private val registry: SchemaRegistry =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().pathType(PathType.JSON_POINTER).build())
                    .schemaLoader { loader -> loader.fetchRemoteResources(false).resourceLoaders { it.resources(resources) } }
            }

    private val fallback: Schema = registry.getSchema(SchemaLocation.of(entry))

    /** One entry schema per family whose file the registry holds, by wire value. */
    private val families: Map<String, Schema> =
        ChartType.entries
            .filter { ChartSchemas.ID_PREFIX + it.schemaFile in resources }
            .associate { it.wire to registry.getSchema(SchemaLocation.of(ChartSchemas.ID_PREFIX + it.schemaFile)) }

    /** Every distinct (keyword, pointer) the document fails, in the library's order; empty when it conforms. */
    fun validate(body: JsonNode): List<ChartSchemaProblem> =
        (families[body.path("config").path("mark").path("type").asText()] ?: fallback)
            .validate(body)
            .map { ChartSchemaProblem(it.keyword ?: "schema", it.instanceLocation.toString()) }
            .distinct()
}
