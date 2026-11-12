package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.AccessMode
import co.datapipelines.dependencies.Capabilities
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.Diagnostic
import co.datapipelines.dependencies.DiagnosticKind
import co.datapipelines.dependencies.Edge
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphSnapshot
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.LimitKind
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.Node
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.PromotionCapability
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.ReleaseCapability
import co.datapipelines.dependencies.Resolution
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.ScheduleCondition
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.dependencies.wire.DependencyWire
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.time.Instant

/**
 * The projection is G1's wire, byte for byte (#875 G4 §B): every G1 `data` golden is read back into a [GraphSnapshot],
 * projected and serialized, and must equal the golden — keys, values AND key order. The reader below inverts only what
 * the wire carries; a field the projection drops or invents, renames or reorders, is red.
 */
class DependencyTreeProjectionTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `every G1 data golden round-trips through the projection byte for byte`() {
        val goldens = dataGoldens()
        goldens.size shouldBeGreaterThanOrEqual MIN_DATA_GOLDENS
        goldens.forEach { (name, golden) ->
            withClue(name) {
                val projected = mapper.valueToTree<JsonNode>(DependencyTreeProjection.project(snapshotOf(golden), unmetOf(golden)))
                projected shouldBe golden
                mapper.writeValueAsString(projected) shouldBe mapper.writeValueAsString(golden)
            }
        }
    }

    @Test
    fun `the round trip covers every resolution and the unresolved node's request-derived version`() {
        val resolutions = dataGoldens().values.flatMap { g -> g["nodes"].map { it["resolution"].asText() } }.toSet()
        resolutions shouldContainAll Resolution.entries.map { it.wire }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "nodes[2].template, /nodes/2/template",
        "transform.template, /transform/template",
        "pipeline_version, /pipeline_version",
        "nodes[0].inputs[3], /nodes/0/inputs/3",
        "/pipeline, /pipeline",
        "a~b.c/d, /a~0b/c~1d",
    )
    fun `a family locator becomes an RFC 6901 pointer`(
        locator: String,
        pointer: String,
    ) {
        DependencyTreeProjection.jsonPointer(locator) shouldBe pointer
    }

    @Test
    fun `the whole-body locator has no pointer - null, no single field`() {
        DependencyTreeProjection.jsonPointer("") shouldBe null
    }

    private fun dataGoldens(): Map<String, JsonNode> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val dir = File(root, "modules/dependency-contract/src/main/resources${DependencyWire.GOLDEN_RESOURCE_DIR}")
        return dir
            .listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith("refusal-") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { it.name.removeSuffix(".json") to mapper.readTree(it) }
    }

    /** The golden as the walk would have produced it — only the wire's fields; the rest at neutral values. */
    private fun snapshotOf(golden: JsonNode): GraphSnapshot {
        val complete = golden["complete"].asBoolean()
        val acyclic = golden["acyclic"].takeUnless { it.isNull }?.asBoolean()
        return GraphSnapshot(
            schemaVersion = golden["schema_version"].asInt(),
            roots = golden["roots"].map { it.asText() },
            nodes = golden["nodes"].map(::nodeOf),
            edges = golden["edges"].map(::edgeOf),
            diagnostics = golden["diagnostics"].map(::diagnosticOf),
            complete = complete,
            acyclic = acyclic,
            dependencyFirst = if (complete && acyclic == true) emptyList() else null,
            observedAt = Instant.parse(golden["observed_at"].asText()),
            internalFingerprint = "not-on-the-wire",
        )
    }

    /** #892: a constraint's `unmet_names` is not a snapshot field — it is the stage's request-local mark, read back here. */
    private fun unmetOf(golden: JsonNode): UnmetParameterNames.Unmet =
        UnmetParameterNames.Unmet(
            golden["edges"]
                .flatMap { e ->
                    e["constraints"].filter { it.has("unmet_names") }.map { c ->
                        (e["edge_id"].asText() to constraintOf(c) as Constraint.ParameterNames) to c["unmet_names"].map { it.asText() }
                    }
                }.toMap(),
        )

    private fun nodeOf(n: JsonNode): Node {
        val family = FamilyKey(n["family"].asText())
        val resolution = Resolution.entries.single { it.wire == n["resolution"].asText() }
        val resolved = resolution == Resolution.RESOLVED
        val version = n["version"]
        val number = version["number"].takeUnless { it.isNull }?.asInt()
        return Node(
            nodeId = n["node_id"].asText(),
            identity = if (resolved) ResolvedIdentity(family, OwnerScope.Workspace(TREE_WS), n["entity_id"].asText(), number) else null,
            requestedAddress = ObjectAddress(family, LookupScope(TREE_WS), n["name"].asText()),
            requestedSelector =
                when {
                    version["kind"].asText() != "artifact" -> Selector.Entity
                    number == null -> Selector.Current
                    else -> Selector.ExactVersion(number)
                },
            name = n.text("name"),
            displayName = n.text("display_name"),
            subtype = n.text("subtype"),
            versionInfo =
                when (version["kind"].asText()) {
                    "artifact" -> number?.let { VersionInfo.Artifact(it) }
                    "revision" -> VersionInfo.Revision(requireNotNull(number))
                    else -> VersionInfo.Unversioned
                }.takeIf { resolved },
            resolution = resolution,
            lifecycle = n.text("lifecycle")?.let(Lifecycle::valueOf),
            operational = n["operational"].takeUnless { it.isNull }?.let(::operationalOf),
            capabilities =
                Capabilities(
                    ReleaseCapability.valueOf(n["capabilities"]["release"].asText()),
                    PromotionCapability.valueOf(n["capabilities"]["promotion"].asText()),
                ),
            currentVersion = n["current_version"].takeUnless { it.isNull }?.asInt(),
            bodyHash = n.text("body_hash"),
            generation = 7L,
            observations = emptyList(),
        )
    }

    private fun operationalOf(o: JsonNode): Operational =
        when {
            o.has("condition") -> Operational.Schedule(ScheduleCondition.entries.single { it.wire == o["condition"].asText() })
            o.has("enabled") -> Operational.ApiEndpoint(o["enabled"].asBoolean())
            o.has("readonly") -> Operational.Datasource(o["readonly"].asBoolean())
            else -> Operational.Visualization(o["evidence_ready"].asBoolean())
        }

    private fun edgeOf(e: JsonNode): Edge {
        val selector = e["requested_selector"]
        return Edge(
            edgeId = e["edge_id"].asText(),
            fromNodeId = e["from"].asText(),
            toNodeId = e["to"].asText(),
            relationship = Relationship.valueOf(e["relationship"].asText()),
            sourcePath = e.text("source_path"),
            requestedSelector =
                when (selector["kind"].asText()) {
                    "exact_version" -> Selector.ExactVersion(selector["number"].asInt())
                    "current" -> Selector.Current
                    else -> Selector.Entity
                },
            resolvedVersion = e["resolved_version"].takeUnless { it.isNull }?.asInt(),
            constraints = e["constraints"].map(::constraintOf),
            alias = "not-on-the-wire",
        )
    }

    private fun constraintOf(c: JsonNode): Constraint =
        when (c["kind"].asText()) {
            "parameter_names" -> Constraint.ParameterNames(c["names"].map { it.asText() })
            "datasource_access" -> Constraint.DatasourceAccess(AccessMode.entries.single { it.wire == c["mode"].asText() })
            else -> Constraint.DatasourceDialect(c["dialect"].asText())
        }

    private fun diagnosticOf(d: JsonNode): Diagnostic {
        val kind = DiagnosticKind.valueOf(d["code"].asText())
        val limit = kind == DiagnosticKind.LIMIT
        return Diagnostic(
            kind = kind,
            nodeId = d.text("node_id"),
            edgeId = d.text("edge_id"),
            message = d["message"].asText(),
            limit = if (limit) LimitKind.DEPTH else null,
            maximum = if (limit) 1 else null,
            trail = if (kind == DiagnosticKind.CYCLE) listOf("not-on-the-wire") else emptyList(),
        )
    }

    private fun JsonNode.text(field: String): String? = get(field).takeUnless { it == null || it.isNull }?.asText()

    private companion object {
        /** G1 ships 47 goldens at the base, six of them refusals; a reader that found none would pass vacuously. */
        const val MIN_DATA_GOLDENS = 41
    }
}
