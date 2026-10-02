package co.datapipelines.parameters

import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.templates.InterpolatedParameterScanner
import co.datapipelines.templates.SqlBindScanner
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The shipped demo parameter set goes through the app's OWN save-time validator (#374 §F) — the
 * parameter-set twin of `SampleDataExamplesContentTest`.
 *
 * Why it exists: the seeder imports `parameter_sets` from `scripts/sample-data/content/examples.json`
 * into EVERY personal workspace at first login, and a set the import refuses fails that login
 * loudly — while nothing else reads the repo copy at build time. This suite is the missing build
 * input: it reads the file, builds the template registry the seeder would have built (every
 * `templates` entry, at the version a fresh import lands, 1), and runs every `parameter_sets` entry
 * through [ParameterSetValidator] — structure, graph, expressions and pin rules (the pin resolves,
 * its dialect is the datasource's, no parent is interpolated, every `:bind` is a declared
 * dependency) for real, and the selector dry run through a recording probe (the SQL itself was run
 * against a real Postgres when the content was written; the app's first login re-proves it).
 *
 * The template ports are the REAL scanners over the SHIPPED bodies ([SqlBindScanner],
 * [InterpolatedParameterScanner]) — a stand-in at the port boundary, not a hand-copied list.
 * The datasource set is the demo profile's mounted file, not a literal.
 *
 * The two falsification tests are the guard's proof of life: a selector body that interpolates its
 * parent, and a set that pins a template the bundle does not ship, MUST each go red with the code
 * the rule exists for.
 */
class SampleDataParameterSetsContentTest {
    private val workspace = UUID.fromString("defa0000-0000-0000-0000-000000000374")
    private val mapper = ParameterSetJson.mapper

    @Test
    fun `the shipped geo_filters set passes the validator, the dry run of each template select included`() {
        val doc = readExamples()
        val sets = doc.path("parameter_sets").toList()
        sets.map { it.path("name").asText() } shouldContainExactlyInAnyOrder listOf(GEO_FILTERS)

        val probe = RecordingProbe()
        val outcome = validate(doc, GEO_FILTERS, probe)

        outcome.shouldBeInstanceOf<ParameterSetValidation.Valid>()
        // Non-vacuity: the dry run was REACHED for each template-backed select (not skipped by an
        // early refusal), and with the shipped selector bodies' own pins.
        probe.renders.map { it.template.id } shouldContainExactlyInAnyOrder SELECTOR_TEMPLATES
    }

    @Test
    fun `every pin in the shipped set names a template the same file ships, at the version a fresh import lands`() {
        val doc = readExamples()
        val shipped = doc.path("templates").map { it.path("id").asText() }.toSet()

        val pins = pinsOf(doc.path("parameter_sets").first { it.path("name").asText() == GEO_FILTERS })

        pins.map { it.id } shouldContainExactlyInAnyOrder SELECTOR_TEMPLATES
        pins.filter { it.id !in shipped }.shouldBeEmpty()
        pins.map { it.version }.toSet() shouldBe setOf(SEED_VERSION)
    }

    @Test
    fun `the shipped set carries no id, version or body_hash - those belong to the seeder`() {
        val set = readExamples().path("parameter_sets").first { it.path("name").asText() == GEO_FILTERS }

        listOf("id", "version", "body_hash").forEach { set.has(it) shouldBe false }
    }

    @Test
    fun `falsification - a selector body interpolating its parent is refused with parameter_interpolated`() {
        val doc = readExamples()
        val zone = templateEntry(doc, "nyc/parameters/zone_options.sql")
        // The T70 defect shape: the bind form replaced by an interpolation of a declared parent.
        zone.put("body", zone.path("body").asText().replace(":year", "'\${year}'"))

        val codes = failureCodes(validate(doc, GEO_FILTERS, RecordingProbe()))

        codes shouldContain PipelineErrorCodes.Template.PARAMETER_INTERPOLATED
    }

    @Test
    fun `falsification - a set pinning a template the bundle does not ship is refused with template_not_found`() {
        val doc = readExamples()
        val set = doc.path("parameter_sets").first { it.path("name").asText() == GEO_FILTERS } as ObjectNode
        (
            set
                .path("parameters")
                .first { it.path("name").asText() == "month" }
                .path("source")
                .path("template") as ObjectNode
        ).put("id", "nyc/parameters/not_shipped.sql")

        val codes = failureCodes(validate(doc, GEO_FILTERS, RecordingProbe()))

        codes shouldContain ParameterErrorCodes.TEMPLATE_NOT_FOUND
    }

    @Test
    fun `falsification - a selector binding a parent the set does not declare as a dependency is refused`() {
        val doc = readExamples()
        val set = doc.path("parameter_sets").first { it.path("name").asText() == GEO_FILTERS } as ObjectNode
        // Drop `year` from the zone select's depends_on: its template still binds :year.
        val zone = set.path("parameters").first { it.path("name").asText() == "pickup_zone" } as ObjectNode
        zone.putArray("depends_on").add("month")

        val codes = failureCodes(validate(doc, GEO_FILTERS, RecordingProbe()))

        codes shouldContain ParameterErrorCodes.BIND_UNDECLARED
    }

    private fun failureCodes(outcome: ParameterSetValidation): List<String> =
        outcome
            .shouldBeInstanceOf<ParameterSetValidation.Invalid>()
            .result.failures
            .map { it.code }

    private fun pinsOf(set: JsonNode): List<TemplateRef> =
        set
            .path("parameters")
            .mapNotNull { it.path("source").path("template").takeIf { pin -> !pin.isMissingNode } }
            .map { TemplateRef(it.path("id").asText(), it.path("version").asInt()) }

    private fun templateEntry(
        doc: JsonNode,
        id: String,
    ): ObjectNode = requireNotNull(doc.path("templates").firstOrNull { it.path("id").asText() == id }) { "no template '$id'" } as ObjectNode

    private fun readExamples(): JsonNode = mapper.readTree(ParametersTestFiles.read(EXAMPLES_PATH))

    /** One set of [doc] through the validator, the ports built from the same file. */
    private fun validate(
        doc: JsonNode,
        name: String,
        probe: RecordingProbe,
    ): ParameterSetValidation {
        val shipped = ShippedTemplates(doc)
        val validator = ParameterSetValidator(ParametersConfig(), shipped, shipped, demoDatasources(), probe)
        val body = (doc.path("parameter_sets").first { it.path("name").asText() == name } as ObjectNode).deepCopy()
        return validator.validate(workspace, ParameterSetReader().readOrThrow(body))
    }

    /** The demo profile's mounted datasources file, line-scanned (the sibling suite's method). */
    private fun demoDatasources(): DatasourceRegistry {
        val facts = LinkedHashMap<String, DatasourceFacts>()
        var name: String? = null
        ParametersTestFiles.read(BOOTSTRAP_PATH).lineSequence().filterNot { it.trimStart().startsWith("#") }.forEach { line ->
            NAME_LINE.find(line)?.let { name = it.groupValues[1] }
            DIALECT_LINE.find(line)?.let { match -> name?.let { facts[it] = DatasourceFacts(Dialect.fromWire(match.groupValues[1])) } }
        }
        check(facts.isNotEmpty()) { "no datasources parsed from $BOOTSTRAP_PATH - the demo model is empty" }
        return DatasourceRegistry { datasource, _ -> facts[datasource] }
    }

    /** The shipped `templates` as the set validator's two template ports see them: every entry at [SEED_VERSION]. */
    private class ShippedTemplates(
        doc: JsonNode,
    ) : TemplateDryRenderer,
        TemplateVersionStatuses {
        private val bodies: Map<String, JsonNode> = doc.path("templates").associateBy { it.path("id").asText() }

        private fun entry(ref: TemplateRef): JsonNode? = bodies[ref.id]?.takeIf { ref.version == SEED_VERSION }

        override fun lookup(
            workspaceId: UUID,
            ref: TemplateRef,
        ): TemplateLookup =
            when {
                entry(ref) != null -> {
                    val dialect = Dialect.fromWire(bodies.getValue(ref.id).path("dialect").asText())
                    TemplateLookup.Found(dialect, TemplateType.SQL)
                }

                bodies.containsKey(ref.id) -> {
                    TemplateLookup.VersionNotFound
                }

                else -> {
                    TemplateLookup.TemplateNotFound
                }
            }

        override fun dryRender(
            workspaceId: UUID,
            ref: TemplateRef,
            context: Map<String, Any?>,
        ): DryRenderOutcome = error("the set validator renders through the SelectorProbe port, never the pipeline's dry render")

        override fun interpolatedParameters(
            workspaceId: UUID,
            ref: TemplateRef,
            declared: Set<String>,
            guarded: Set<String>,
        ): List<String> = entry(ref)?.let { InterpolatedParameterScanner.scan(it.path("body").asText(), declared, guarded) }.orEmpty()

        override fun boundParameters(
            workspaceId: UUID,
            ref: TemplateRef,
        ): List<String> = entry(ref)?.let { SqlBindScanner.scan(it.path("body").asText()) }.orEmpty()

        override fun statusOf(
            workspaceId: UUID,
            templateId: String,
            version: Int,
        ): PipelineVersionStatus? = entry(TemplateRef(templateId, version))?.let { PipelineVersionStatus.RELEASED }
    }

    private companion object {
        const val EXAMPLES_PATH = "scripts/sample-data/content/examples.json"
        const val BOOTSTRAP_PATH = "deploy/sample-data/bootstrap-datasources-nyc.yml"
        const val GEO_FILTERS = "nyc/parameters/geo_filters"

        /** The three selector templates the set pins, in cascade order. */
        val SELECTOR_TEMPLATES =
            listOf(
                "nyc/parameters/year_options.sql",
                "nyc/parameters/month_options.sql",
                "nyc/parameters/zone_options.sql",
            )

        /** The version a fresh import lands at - every shipped pin names version 1. */
        const val SEED_VERSION = 1

        val NAME_LINE = Regex("^\\s*-\\s*name:\\s*(\\S+)\\s*$")
        val DIALECT_LINE = Regex("^\\s+dialect:\\s*(\\S+)\\s*$")
    }
}
