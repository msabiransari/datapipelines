package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
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
    /** §0.3's reference sigil, spelled once so no test escapes it by hand. */
    const val REFERENCE_PREFIX = "$"

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
        orgContext: OrgContext = OrgContext.DEFAULTS,
    ): PipelineValidator = PipelineValidator(datasources, templates, pipelines, maxCompositionDepth, orgContext)

    /** A CALCULATOR node (§4.10) — the shape §12.10's rules are written against. */
    fun calculatorNode(
        id: String = "fiscal_q",
        kind: String? = "fiscal_quarter",
        inputs: Map<String, JsonNode>? =
            mapOf("date" to ref("current_date"), "fiscal_start" to ref("org_fiscal_start_date")),
        contextKey: String? = "run_fiscal_quarter",
        dependsOn: List<String> = emptyList(),
    ): Node =
        Node(
            id = id,
            description = "calculator $id",
            type = NodeType.CALCULATOR,
            source = "",
            template = TemplateRef(),
            output = null,
            dependsOn = dependsOn,
            kind = kind,
            inputs = inputs,
            contextKey = contextKey,
        )

    /** A Context reference, as §0.3 spells one: a leading `$` then the key. */
    fun ref(name: String): JsonNode = JsonNodeFactory.instance.textNode(REFERENCE_PREFIX + name)

    /** A JSON literal for a calculator input. */
    fun literal(value: String): JsonNode = JsonNodeFactory.instance.textNode(value)

    fun literal(value: Int): JsonNode = JsonNodeFactory.instance.numberNode(value)

    fun literals(vararg values: String): JsonNode = JsonNodeFactory.instance.arrayNode().also { array -> values.forEach { array.add(it) } }

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

    /** As [repoFile], for a directory. */
    fun repoDirectory(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isDirectory) return candidate
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
 * The shipped Flyway migrations, in **version order** (numeric: V10 after V2), as
 * repo-relative paths ready for [Fixtures.repoFile] — the exact derivation
 * `datasources`' `ShippedMigrations` established (035/H: this suite's hand-copied
 * V1–V4 list had already missed V5, which is the failure mode the derivation
 * exists to make impossible).
 *
 * Flyway and the scripts live in `app` alone (module-structure §3.1 rule 2), so
 * integration tests that need the shipped schema apply them through plain JDBC —
 * always through THIS derivation, never a hand-copied list: a migration added to
 * the directory but not to a hand-copied list runs that suite against a stale
 * schema while production Flyway applies the real one.
 *
 * ## Accepted grammar — everything else FAILS LOUD
 *
 * `V<version>__<description>.sql` with a version that fits an [Int]. Any OTHER
 * `.sql` file in the directory — Flyway-legal repeatable `R__*.sql`, sub-versioned
 * `V2_1__*.sql`, an overflowing version — throws [IllegalArgumentException] NAMING
 * THE FILE. A silent `mapNotNull` exclusion would relocate the stale-schema drift
 * inside the guard; widening the grammar is a deliberate change, not a side effect
 * of adding a file.
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    /** The real directory's migrations as repo-relative paths, version order. */
    fun paths(): List<String> = migrations(Fixtures.repoDirectory(DIR)).map { "$DIR/${it.second.name}" }

    /** The pure rule over any directory — injectable so the ordering itself is testable. */
    fun migrations(dir: File): List<Pair<Int, File>> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> versionOf(file) to file }
            .sortedBy { (version, _) -> version }

    /** The parsed version of one `.sql` file; anything else fails loud, naming the file. */
    private fun versionOf(file: File): Int {
        val match =
            requireNotNull(VERSION_PREFIX.find(file.name)) {
                "'${file.name}' does not match the accepted migration grammar V<version>__<description>.sql — the " +
                    "suites would silently run a stale schema while production Flyway applies it. Either " +
                    "rename it to the grammar or widen ShippedMigrations deliberately, applying suites included."
            }
        return requireNotNull(match.groupValues[1].toIntOrNull()) {
            "'${file.name}' carries a version number that does not fit an Int — widen ShippedMigrations deliberately."
        }
    }
}

/**
 * A datasource registry stubbed with a fixed name → dialect map, plus an optional readonly set
 * (workspaces §6 — names in [readonly] report `readonly = true` through [describe]).
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
    private val readonly: Set<String> = emptySet(),
) : DatasourceRegistry {
    override fun describe(name: String): DatasourceFacts? = dialects[name]?.let { DatasourceFacts(it, name in readonly) }
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
    /** Per-template id, the declared names the stub reports as interpolated (042 B2). */
    private val interpolated: Map<String, Set<String>> = emptyMap(),
    /** Per-template id, the `:name` binds the stub reports the body carrying (072, §12.10). */
    private val bound: Map<String, List<String>> = emptyMap(),
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

    override fun interpolatedParameters(
        workspaceId: UUID,
        ref: TemplateRef,
        declared: Set<String>,
    ): List<String> = interpolated[ref.id]?.filter(declared::contains)?.toList() ?: emptyList()

    override fun boundParameters(
        workspaceId: UUID,
        ref: TemplateRef,
    ): List<String> = bound[ref.id] ?: emptyList()
}
