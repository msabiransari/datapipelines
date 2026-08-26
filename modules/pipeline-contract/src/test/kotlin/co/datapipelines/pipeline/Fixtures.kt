package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.UUID

/**
 * Builders and test doubles shared across this module's specs.
 *
 * Every builder defaults to a **valid** value so a test names only what it is about: a spec
 * for `duplicate_node_id` sets two ids and nothing else, and its assertion on the resulting
 * codes therefore proves the rule rather than the fixture.
 */
internal object Fixtures {
    val mapper: ObjectMapper = PipelineJson.objectMapper()

    fun json(text: String): JsonNode = mapper.readTree(text)

    fun node(
        id: String = "fetch_orders",
        type: NodeType = NodeType.DQL,
        source: String = "pg-prod",
        template: TemplateRef = TemplateRef("fetch_orders.sql", 1),
        output: NodeOutput? = NodeOutput.Caller,
        dependsOn: List<String> = emptyList(),
    ): Node =
        Node(
            id = id,
            description = "A node.",
            type = type,
            source = source,
            template = template,
            output = if (type == NodeType.DQL) output else null,
            dependsOn = dependsOn,
        )

    fun pipeline(
        name: String = "monthly_revenue",
        nodes: List<Node> = listOf(node()),
        parameters: Map<String, Parameter> = emptyMap(),
        settings: PipelineSettings = PipelineSettings(),
        schemaVersion: Int = Pipeline.SUPPORTED_SCHEMA_VERSION,
    ): Pipeline =
        Pipeline(
            schemaVersion = schemaVersion,
            name = name,
            displayName = "Monthly Revenue",
            description = "A pipeline.",
            settings = settings,
            parameters = parameters,
            nodes = nodes,
        )

    /**
     * A validator whose environment resolves everything the default fixtures reference.
     *
     * The default resolver answers **null** for every pinned pipeline reference — the right
     * default for specs that never declare a PIPELINE node, since [CompositionRules] only
     * consults it for those.
     */
    fun validator(
        datasources: DatasourceRegistry = StubDatasources(),
        templates: TemplateDryRenderer = StubTemplates(),
        pipelines: PipelineResolver = PipelineResolver { _, _, _ -> null },
        maxCompositionDepth: Int = 5,
    ): PipelineValidator = PipelineValidator(datasources, templates, pipelines, maxCompositionDepth)

    /**
     * Locates a repository file by walking up from the working directory, so tests do not
     * encode how deep this module sits in the tree (the pattern `ColumnSchemaSpecDriftTest`
     * established in `typesystem`).
     */
    fun repoFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }

    /** Reads a golden pipeline fixture from `src/test/resources/examples`. */
    fun example(fileName: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/examples/$fileName")) {
            "Missing test fixture /examples/$fileName"
        }.use { it.readBytes().decodeToString() }
}

/**
 * A datasource registry stubbed with a fixed name → dialect map.
 *
 * Defaults cover the names the worked examples use, so an unregistered name in a test is
 * always deliberate.
 */
internal class StubDatasources(
    private val dialects: Map<String, Dialect> =
        mapOf(
            "pg-prod" to Dialect.POSTGRES,
            "pg-warehouse" to Dialect.POSTGRES,
            "pg-meta" to Dialect.POSTGRES,
            "mysql-prod" to Dialect.MYSQL,
        ),
) : DatasourceRegistry {
    override fun dialectOf(name: String): Dialect? = dialects[name]
}

/**
 * A template registry + dry-render engine stubbed per template id.
 *
 * The real engine lands in the `templates` module (P3a); this double is what lets §12.6 be
 * specified and tested here, which is the point of [TemplateDryRenderer] being an interface
 * this module owns.
 */
internal class StubTemplates(
    private val lookups: Map<String, TemplateLookup> = emptyMap(),
    private val defaultLookup: TemplateLookup = TemplateLookup.Found(Dialect.POSTGRES),
    private val renders: Map<String, DryRenderOutcome> = emptyMap(),
) : TemplateDryRenderer {
    /** Contexts the validator passed in, keyed by template id — the §7.4 sample-context evidence. */
    val renderedContexts = mutableMapOf<String, Map<String, Any?>>()

    override fun lookup(
        workspaceId: UUID,
        ref: TemplateRef,
    ): TemplateLookup = lookups[ref.id] ?: defaultLookup

    override fun dryRender(
        workspaceId: UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome {
        renderedContexts[ref.id] = context
        return renders[ref.id] ?: DryRenderOutcome.Success
    }
}
