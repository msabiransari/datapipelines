package co.datapipelines.templates

import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The shipped demo content goes through the app's OWN save-time validation (T71, 049 C1).
 *
 * T70: the published v1 artifact's examples.json still interpolated declared parameters
 * (the exact defect 042's `parameter_interpolated` rule refuses), a fresh `--demo` login
 * 500ed on first provisioning, and NOTHING caught it — `SampleDataBootstrapE2eTest` seeds a
 * test fixture, and verify.sh step 5 checks structure only. The published artifact drifted
 * from the repo copy and no build input reads the repo copy at all.
 *
 * This suite is that missing build input. It reads `scripts/sample-data/content/examples.json`
 * — the file the artifact build COPIES (`load-and-dump.sh`), so the repo copy is the source of
 * truth this guards — and runs every template and pipeline in it through the same validators
 * the import services run at seeding time: [TemplateValidator] for each template, then
 * [PipelineValidator] (whose ReferenceRules dry-renders every node template through the real
 * engine and applies 042's InterpolatedParameterScanner via the real [TemplateDryRendererImpl]).
 * Templates first, pipelines second — the seeder's own order, because a pipeline node resolves
 * its template at save time.
 *
 * The environment is the demo's: datasources come from `deploy/sample-data/bootstrap-datasources.yml`
 * (the file the demo profile mounts), so an example referencing a datasource the demo does not
 * register fails HERE with unknown_datasource instead of at somebody's first login. No network,
 * no Docker, no Spring context — the registry is the in-memory double the module's own suites
 * use, behind the production [WorkspaceTemplateEngines] boundary.
 *
 * The falsification test is the guard's proof of life: re-introduce the v1 defect (a declared
 * parameter interpolated in a body) into a copy of the shipped file and the suite MUST go red
 * with `template.validation.parameter_interpolated`. A validation suite that cannot go red on
 * the defect it exists for is not a guard (049 C1's non-negotiable).
 */
class SampleDataExamplesContentTest {
    private val workspaceId = UUID.randomUUID()

    private val mapper = TemplateJson.objectMapper()
    private val templateDeserializer = TemplateDeserializer()
    private val pipelineDeserializer = PipelineDeserializer()

    @Test
    fun `every shipped template and pipeline passes the app's own save-time validation`() {
        val docs = EXAMPLES_PATHS.map(::readExamples)

        // Non-vacuity: a file with no templates or no pipelines would make every check below
        // pass by having nothing to check — the failure verify.sh step 5 alone could never see.
        docs.forEach { doc ->
            doc.path("templates").toList().shouldNotBeEmpty()
            doc.path("pipelines").toList().shouldNotBeEmpty()
        }

        // Cross-family identity collisions would fail the real seeding (the two files are
        // imported into the SAME workspace), so the shipped files must be disjoint.
        val templateIds = docs.flatMap { doc -> doc.path("templates").map { it.path("id").asText() } }
        val pipelineNames = docs.flatMap { doc -> doc.path("pipelines").map { it.path("name").asText() } }
        templateIds
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .shouldBeEmpty()
        pipelineNames
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .shouldBeEmpty()

        val problems = docs.flatMap { validateExamples(it) }

        problems.shouldBeEmpty()
    }

    @Test
    fun `falsification - a body interpolating a declared parameter is refused with parameter_interpolated`() {
        val doc = readExamples(EXAMPLES_PATHS[0])
        val monthly =
            requireNotNull(
                doc.path("templates").firstOrNull { it.path("id").asText() == POISONED_TEMPLATE_ID },
            ) { "expected template '$POISONED_TEMPLATE_ID' in the shipped examples" }
        // The v1 artifact's exact defect shape (T70): the bind form replaced by an interpolation
        // of a parameter the pipeline declares.
        (monthly as ObjectNode).put(
            "body",
            monthly.path("body").asText().replace(BIND_FORM, "'$INTERPOLATION_FORM'"),
        )

        val problems = validateExamples(doc)

        problems.joinToString("\n") shouldContain PipelineErrorCodes.Template.PARAMETER_INTERPOLATED
    }

    /** Parses a shipped file; a malformed file is this suite's failure, not a skip. */
    private fun readExamples(path: String): JsonNode = mapper.readTree(TemplateFixtures.repoFile(path).readText())

    /**
     * The seeder's sequence over [doc], with every failure collected (the validators are
     * exhaustive per save; the harness is exhaustive across saves, so one broken entry does not
     * hide another behind an early throw).
     */
    private fun validateExamples(doc: JsonNode): List<String> {
        val problems = mutableListOf<String>()
        val registry = InMemoryTemplateRegistry()
        val templateValidator = TemplateValidator(LibraryResolver { registry })

        doc.path("templates").forEachIndexed { index, entry ->
            problems += validateTemplateEntry(entry, index, templateValidator, registry)
        }

        val engine = TemplateEngine(registry, CACHE_SIZE, RENDER_TIMEOUT_MS, MAX_OUTPUT_CHARS)
        try {
            val engines =
                mockk<WorkspaceTemplateEngines> {
                    every { registryFor(any()) } returns registry
                    every { engineFor(any()) } returns engine
                }
            val validator =
                PipelineValidator(
                    datasources = demoDatasources(),
                    templates = TemplateDryRendererImpl(engines),
                    pipelines = PipelineResolver { _, _, _ -> null },
                    maxCompositionDepth = MAX_COMPOSITION_DEPTH,
                )
            doc.path("pipelines").forEach { entry ->
                val pipeline =
                    try {
                        pipelineDeserializer.readOrThrow(mapper.writeValueAsString(entry))
                    } catch (e: DatapipelinesException) {
                        problems += "pipeline '${entry.path("name").asText()}': ${e.message}"
                        return@forEach
                    }
                try {
                    validator.validateOrThrow(pipeline, workspaceId)
                } catch (e: PipelineValidationException) {
                    problems += "pipeline '${pipeline.name}': ${e.message}"
                }
            }
        } finally {
            engine.close()
        }
        return problems
    }

    /** One templates-import entry: deserialize (the import services' own reader), §7-validate, register at v1. */
    private fun validateTemplateEntry(
        entry: JsonNode,
        index: Int,
        validator: TemplateValidator,
        registry: InMemoryTemplateRegistry,
    ): List<String> =
        when (val outcome = templateDeserializer.fromTree(entry)) {
            is TemplateDeserializationOutcome.Rejected -> {
                listOf("templates[$index]: ${outcome.result.codes.joinToString()}")
            }

            is TemplateDeserializationOutcome.Parsed -> {
                val draft = outcome.draft
                val id = draft.id
                if (id == null) {
                    listOf("templates[$index] has no id — a pipeline node resolves its template by that name")
                } else {
                    try {
                        validator.validateOrThrow(draft, workspaceId)
                        registry.put(
                            TemplateFixtures.version(
                                id = id,
                                version = SEED_VERSION,
                                dialect = draft.dialect,
                                isLibrary = draft.isLibrary,
                                imports = draft.imports,
                                body = draft.body,
                            ),
                        )
                        emptyList()
                    } catch (e: TemplateValidationException) {
                        listOf("template '$id': ${e.message}")
                    }
                }
            }
        }

    /**
     * The demo's datasource set, from the files the demo profile actually mounts — not a
     * hand-copied list (one file per sample-data family, both parsed into one registry:
     * a deployment may enable both). A line scan over the flat per-entry fields; a yml
     * whose shape stops parsing fails LOUD here (empty registry ⇒ every example pipeline
     * fails with unknown_datasource), which is the right direction for a drift pin to fail.
     */
    private fun demoDatasources(): DatasourceRegistry {
        val facts = LinkedHashMap<String, DatasourceFacts>()
        var name: String? = null
        var dialect: String? = null
        var readonly: String? = null

        fun flush() {
            val entryName = name ?: return
            val entryDialect = dialect
            if (entryDialect == null) {
                error("datasource '$entryName' in the bootstrap datasources files declares no dialect — cannot model the demo environment")
            }
            facts[entryName] = DatasourceFacts(Dialect.fromWire(entryDialect), readonly == "true")
            name = null
            dialect = null
            readonly = null
        }

        BOOTSTRAP_PATHS.forEach { path ->
            TemplateFixtures
                .repoFile(path)
                .readText()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .forEach { line ->
                    NAME_LINE.find(line)?.let {
                        flush()
                        name = it.groupValues[1]
                    }
                    DIALECT_LINE.find(line)?.let { dialect = it.groupValues[1] }
                    READONLY_LINE.find(line)?.let { readonly = it.groupValues[1] }
                }
            flush()
        }
        check(facts.isNotEmpty()) { "no datasources parsed from $BOOTSTRAP_PATHS — the demo model is empty" }
        return DatasourceRegistry { facts[it] }
    }

    private companion object {
        /** One examples file per sample-data family — both ship and both must validate. */
        private val EXAMPLES_PATHS =
            listOf(
                "scripts/sample-data/content/examples.json",
                "scripts/sample-data-trade/content/examples.json",
            )

        /** One bootstrap datasources file per family; the app accepts the comma list. */
        private val BOOTSTRAP_PATHS =
            listOf(
                "deploy/sample-data/bootstrap-datasources-nyc.yml",
                "deploy/sample-data/bootstrap-datasources-census.yml",
            )

        /** The template the falsification poisons — referenced by a pipeline that declares the parameter. */
        private const val POISONED_TEMPLATE_ID = "sample_trips_monthly.sql"
        private const val BIND_FORM = ":start_date"

        /** Escaped at the use site (a dollar in a Kotlin string literal): the interpolation form of start_date. */
        private const val INTERPOLATION_FORM = "\${start_date}"

        /** The version a fresh import lands at — every shipped pipeline node pins version 1. */
        private const val SEED_VERSION = 1

        /** [TemplatesProperties] production defaults — mirrored, not redefined. */
        private const val CACHE_SIZE = 500
        private const val RENDER_TIMEOUT_MS = 5_000L
        private const val MAX_OUTPUT_CHARS = 64L * 1024 * 1024

        /** `datapipelines.pipelines.max-composition-depth` production default. */
        private const val MAX_COMPOSITION_DEPTH = 5

        /** A list entry opens a datasource record: zero-plus indent, `-`, `name:`. */
        private val NAME_LINE = Regex("^\\s*-\\s*name:\\s*(\\S+)\\s*$")
        private val DIALECT_LINE = Regex("^\\s+dialect:\\s*(\\S+)\\s*$")
        private val READONLY_LINE = Regex("^\\s+readonly:\\s*(\\S+)\\s*$")
    }
}
