package co.datapipelines.dependencies.wire

import co.datapipelines.dependencies.AccessMode
import co.datapipelines.dependencies.ConstraintKind
import co.datapipelines.dependencies.DiagnosticKind
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LimitKind
import co.datapipelines.dependencies.OperationalKind
import co.datapipelines.dependencies.PromotionCapability
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ScheduleCondition
import co.datapipelines.dependencies.SelectorKind
import co.datapipelines.dependencies.VersionKind
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Section F / A47 contract half: the §11.1 schema and its goldens. Every golden validates; every schema enum equals a
 * B.1 vocabulary in both directions; every token, variant and value of those vocabularies appears in at least one golden
 * (the expected sets are DERIVED from the Kotlin types, so a new token without a golden is red by construction).
 */
class WireContractTest {
    @Test
    fun `the golden directory is on the classpath, non-empty, and every golden validates against its shape`() {
        WireFiles.data.keys.shouldNotBeEmpty()
        WireFiles.refusals.keys.shouldNotBeEmpty()
        val data = WireFiles.validator()
        val details = WireFiles.validator("error_details")
        WireFiles.data.forEach { (case, json) -> withClue(case) { data.validate(json).shouldBeEmpty() } }
        WireFiles.refusals.forEach { (case, json) -> withClue(case) { details.validate(json).shouldBeEmpty() } }
    }

    @Test
    fun `the schema is 2020-12, its id carries v1 and schema_version is the constant 1`() {
        WireFiles.schema.path("\$schema").asText() shouldBe "https://json-schema.org/draft/2020-12/schema"
        WireFiles.schema.path("\$id").asText() shouldBe DependencyWire.SCHEMA_ID
        DependencyWire.SCHEMA_ID shouldContain "/v1/"
        val version =
            WireFiles.schema
                .path("\$defs")
                .path("data")
                .path("properties")
                .path("schema_version")
        version.path("const").asInt() shouldBe DependencyWire.SCHEMA_VERSION
        WireFiles.data.values.forEach { it.path("schema_version").asInt() shouldBe DependencyWire.SCHEMA_VERSION }
    }

    @Test
    fun `every schema enum is a closed B_1 vocabulary, compared in both directions`() {
        val vocabularies =
            mapOf(
                "family" to FamilyKey.PRODUCTION.keys.map { it.wire },
                "version_kind" to VersionKind.entries.map { it.wire },
                "selector_kind" to SelectorKind.entries.map { it.wire },
                "resolution" to Resolution.entries.map { it.wire },
                "lifecycle" to Lifecycle.entries.map { it.wire },
                "schedule_condition" to ScheduleCondition.entries.map { it.wire },
                "release_capability" to ReleaseCapability.entries.map { it.wire },
                "promotion_capability" to PromotionCapability.entries.map { it.wire },
                "relationship" to Relationship.entries.map { it.wire },
                "access_mode" to AccessMode.entries.map { it.wire },
                "diagnostic_code" to DiagnosticKind.entries.map { it.wire },
                "request_field" to RequestField.entries.map { it.wire },
                "refusal_reason" to RefusalReason.entries.map { it.wire },
            )
        val defs = WireFiles.schema.path("\$defs")
        val enumDefs =
            defs
                .properties()
                .filter { it.value.has("enum") }
                .map { it.key }
                .toSet()
        enumDefs shouldBe vocabularies.keys
        vocabularies.forEach { (def, tokens) -> withClue(def) { WireFiles.enumOf(def) shouldBe tokens.toSet() } }

        val constraintKinds =
            defs.path("constraint").path("oneOf").map {
                it
                    .path("properties")
                    .path("kind")
                    .path("const")
                    .asText()
            }
        constraintKinds.toSet() shouldBe ConstraintKind.entries.map { it.wire }.toSet()
        val operational =
            defs
                .properties()
                .map { it.key }
                .filter { it.startsWith("operational_") }
                .map { it.removePrefix("operational_") }
        operational.toSet() shouldBe OperationalKind.entries.map { it.family.wire }.toSet()
    }

    @Test
    fun `every token, variant and value is covered by at least one golden - counted, expected sets from the types`() {
        val c = Coverage(WireFiles.data.values)
        assertCovered("relationship", Relationship.entries.map { it.wire }, c.relationships)
        assertCovered("constraint kind", ConstraintKind.entries.map { it.wire }, c.constraintKinds)
        assertCovered("datasource access mode", AccessMode.entries.map { it.wire }, c.accessModes)
        assertCovered("resolution", Resolution.entries.map { it.wire }, c.resolutions)
        assertCovered("lifecycle", Lifecycle.entries.map { it.wire }, c.lifecycles)
        assertCovered("diagnostic code", DiagnosticKind.entries.map { it.wire }, c.diagnosticCodes)
        assertCovered("LIMIT kind (named with its maximum)", LimitKind.entries.map { it.wire }, c.limitKinds)
        assertCovered("acyclic", listOf("true", "false", "null"), c.acyclic)
        assertCovered("operational variant", OperationalKind.entries.map { it.family.wire }, c.operationalVariants)
        assertCovered("schedule condition", ScheduleCondition.entries.map { it.wire }, c.operationalValues("schedule", "condition"))
        listOf("api_endpoint" to "enabled", "datasource" to "readonly", "visualization" to "evidence_ready").forEach { (family, field) ->
            assertCovered("$family $field", listOf("true", "false"), c.operationalValues(family, field))
        }
        assertCovered("family", FamilyKey.PRODUCTION.keys.map { it.wire }, c.families)
        withClue("an INCOMPLETE_VIEW diagnostic names no node and no edge") { c.incompleteViewWithoutIds shouldBeGreaterThanOrEqual 1 }
    }

    @Test
    fun `every request-rule refusal reason has exactly one details golden naming its field`() {
        val byReason = WireFiles.refusals.values.groupBy { it.path("reason").asText() }
        byReason.keys shouldBe RefusalReason.entries.map { it.wire }.toSet()
        byReason.forEach { (reason, files) -> withClue(reason) { files.size shouldBe 1 } }
        WireFiles.refusals.values.forEach { (it.path("field").asText() in RequestField.entries.map(RequestField::wire)) shouldBe true }
        WireFiles.refusals.keys shouldBe RefusalReason.entries.map { WireFiles.REFUSAL_PREFIX + it.wire.replace('_', '-') }.toSet()
    }

    @Test
    fun `every golden edge is a section 6 row between its endpoint families, and every edge endpoint is a node`() {
        WireFiles.data.forEach { (case, json) ->
            val families = json.items("nodes").associate { it.path("node_id").asText() to FamilyKey(it.path("family").asText()) }
            json.items("roots").forEach { withClue("$case root") { families.containsKey(it.asText()) shouldBe true } }
            json.items("edges").forEach { edge ->
                val from = families.getValue(edge.path("from").asText())
                val to = families.getValue(edge.path("to").asText())
                withClue("$case ${edge.path("edge_id").asText()}") {
                    edge.path("relationship").asText() shouldBe Relationship.between(from, to)?.wire
                }
            }
        }
    }

    @Test
    fun `the request shape states the field rules a schema can state`() {
        val request = WireFiles.validator("request")
        val uuid = "7d2c0000-0000-4000-8000-000000000009"
        val accepted =
            listOf(
                mapOf("family" to "pipeline", "name" to "test_widget/report", "version" to "2"),
                mapOf("family" to "schedule", "id" to uuid),
                mapOf("family" to "datasource", "name" to "test_widget_warehouse"),
            )
        val refused =
            mapOf(
                "non-UUID schedule id" to mapOf("family" to "schedule", "id" to "nightly"),
                "upper-case UUID" to mapOf("family" to "schedule", "id" to uuid.uppercase()),
                "name with id" to mapOf("family" to "schedule", "id" to uuid, "name" to "x"),
                "version on a datasource" to mapOf("family" to "datasource", "name" to "w", "version" to "1"),
                "missing version" to mapOf("family" to "template", "name" to "test_widget/query"),
                "leading zero" to mapOf("family" to "pipeline", "name" to "test_widget/report", "version" to "02"),
                "current" to mapOf("family" to "pipeline", "name" to "test_widget/report", "version" to "current"),
                "201 characters" to mapOf("family" to "pipeline", "name" to "a".repeat(201), "version" to "1"),
                "unknown family" to mapOf("family" to "test_widget", "name" to "x", "version" to "1"),
                "unknown field" to mapOf("family" to "pipeline", "name" to "x", "version" to "1", "client_flag" to "1"),
            )
        accepted.forEach { withClue(it) { request.validate(WireFiles.mapper.valueToTree<JsonNode>(it)).shouldBeEmpty() } }
        refused.forEach { (case, value) ->
            withClue(case) { request.validate(WireFiles.mapper.valueToTree<JsonNode>(value)).shouldNotBeEmpty() }
        }
    }

    @Test
    fun `the data shape refuses an acyclic flag on an incomplete graph and an INCOMPLETE_VIEW naming a node`() {
        val data = WireFiles.validator()
        val incomplete = WireFiles.data.getValue("diagnostic-incomplete-view").deepCopy<JsonNode>()
        (incomplete as ObjectNode).put("acyclic", true)
        data.validate(incomplete).shouldNotBeEmpty()
        val named = WireFiles.data.getValue("diagnostic-incomplete-view").deepCopy<JsonNode>()
        (named.path("diagnostics").first() as ObjectNode).put("node_id", "n0")
        data.validate(named).shouldNotBeEmpty()
        val camel = WireFiles.data.getValue("acyclic-true").deepCopy<JsonNode>()
        (camel as ObjectNode).set<JsonNode>("observedAt", camel.remove("observed_at"))
        data.validate(camel).shouldNotBeEmpty()
    }

    private fun assertCovered(
        what: String,
        expected: List<String>,
        counts: Map<String, Int>,
    ) {
        withClue("$what tokens outside the vocabulary") { (counts.keys - expected.toSet()).shouldBeEmpty() }
        expected.forEach { token -> withClue("$what '$token' appears in no golden") { (counts[token] ?: 0) shouldBeGreaterThanOrEqual 1 } }
    }
}

/** Token counts over the data goldens. */
private class Coverage(
    goldens: Collection<JsonNode>,
) {
    private val nodes = goldens.flatMap { it.items("nodes") }
    private val edges = goldens.flatMap { it.items("edges") }
    private val diagnostics = goldens.flatMap { it.items("diagnostics") }
    private val constraints = edges.flatMap { it.items("constraints") }

    val relationships = edges.tally { it.path("relationship").asText() }
    val constraintKinds = constraints.tally { it.path("kind").asText() }
    val accessModes = constraints.filter { it.has("mode") }.tally { it.path("mode").asText() }
    val resolutions = nodes.tally { it.path("resolution").asText() }
    val lifecycles = nodes.filter { !it.path("lifecycle").isNull }.tally { it.path("lifecycle").asText() }
    val diagnosticCodes = diagnostics.tally { it.path("code").asText() }
    val families = nodes.tally { it.path("family").asText() }
    val acyclic = goldens.tally { it.path("acyclic").let { a -> if (a.isNull) "null" else a.asText() } }
    val operationalVariants = nodes.filter { !it.path("operational").isNull }.tally { it.path("family").asText() }
    val limitKinds =
        diagnostics
            .filter { it.path("code").asText() == DiagnosticKind.LIMIT.wire }
            .mapNotNull { LIMIT_MESSAGE.matchEntire(it.path("message").asText())?.groupValues?.get(1) }
            .groupingBy { it }
            .eachCount()
    val incompleteViewWithoutIds =
        diagnostics.count {
            it.path("code").asText() == DiagnosticKind.INCOMPLETE_VIEW.wire && it.path("node_id").isNull && it.path("edge_id").isNull
        }

    fun operationalValues(
        family: String,
        field: String,
    ): Map<String, Int> =
        nodes
            .filter { it.path("family").asText() == family && !it.path("operational").isNull }
            .tally { it.path("operational").path(field).asText() }

    private fun <T> Iterable<T>.tally(key: (T) -> String): Map<String, Int> = groupingBy(key).eachCount()

    private companion object {
        /** The walker's LIMIT wording: the limit name and its maximum (§11.1 has no extra field for either). */
        val LIMIT_MESSAGE = Regex("""The ([a-z_]+) limit \([0-9]+\) was reached; the graph is incomplete\.""")
    }
}
