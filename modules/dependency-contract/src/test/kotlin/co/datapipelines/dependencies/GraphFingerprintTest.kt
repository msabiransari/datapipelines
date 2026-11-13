package co.datapipelines.dependencies

import co.datapipelines.dependencies.FamilyKey.Companion.API_ENDPOINT
import co.datapipelines.dependencies.FamilyKey.Companion.DASHBOARD
import co.datapipelines.dependencies.FamilyKey.Companion.DATASOURCE
import co.datapipelines.dependencies.FamilyKey.Companion.PARAMETER_SET
import co.datapipelines.dependencies.FamilyKey.Companion.PIPELINE
import co.datapipelines.dependencies.FamilyKey.Companion.SCHEDULE
import co.datapipelines.dependencies.FamilyKey.Companion.TEMPLATE
import co.datapipelines.dependencies.FamilyKey.Companion.VISUALIZATION
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The §5.3 internal fingerprint over the §5.4 worked mixed graph: the canonical bytes and the digest are pinned by a
 * golden file, every excluded input (observed time, labels, operational state, observations, discovery order) is varied
 * with the fingerprint unchanged, and every covered input is varied with it changed.
 */
class GraphFingerprintTest {
    @Test
    fun `the worked graph's canonical bytes and digest equal the committed golden`() {
        val snapshot = WorkedGraph().walk()
        snapshot.complete shouldBe true
        snapshot.acyclic shouldBe true
        snapshot.nodes shouldHaveSize 13 // warehouse, common@4, totals@2 and report@6 are each one shared node
        val canonical = WorkedGraph().canonical()
        val file = RepoFiles.file(GOLDEN)
        if (!file.exists() && System.getenv(WRITE_FLAG) == "1") file.also { it.parentFile.mkdirs() }.writeText(canonical)
        withClue("golden $GOLDEN is missing; set $WRITE_FLAG=1 once to create it") { file.exists() shouldBe true }
        canonical shouldBe file.readText()
        snapshot.internalFingerprint shouldBe GraphFingerprint.digest(canonical)
        snapshot.internalFingerprint shouldBe GOLDEN_DIGEST
    }

    @Test
    fun `observed time, labels, operational state, observations and discovery order do not move the fingerprint`() {
        val golden = WorkedGraph().walk().internalFingerprint
        val variants =
            mapOf(
                "observedAt" to WorkedGraph(clock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)),
                "names and display names" to WorkedGraph(label = "renamed"),
                "subtype" to WorkedGraph(subtype = "html"),
                "operational (enabled, condition, readonly)" to WorkedGraph(enabled = false),
                "observations" to WorkedGraph(observation = "failed"),
                "discovery order (node ids)" to WorkedGraph(reversedRoots = true),
            )
        variants.forEach { (input, graph) ->
            val snapshot = graph.walk()
            withClue("varying $input") { snapshot.internalFingerprint shouldBe golden }
        }
        WorkedGraph(reversedRoots = true)
            .walk()
            .nodes
            .first()
            .family shouldBe SCHEDULE // the variant really reordered
        WorkedGraph(clock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)).walk().observedAt shouldNotBe
            WorkedGraph().walk().observedAt
    }

    @Test
    fun `a declared import alias rides the edge verbatim and never moves the fingerprint - provenance only (#873 R2)`() {
        val plain = WorkedGraph().walk()
        val aliased = WorkedGraph(importAlias = "lib_common").walk()

        val importEdges = aliased.edges.filter { it.relationship == Relationship.IMPORT }
        withClue("both IMPORT occurrences carry the alias their reference declared") {
            importEdges.map { it.alias } shouldBe listOf("lib_common", "lib_common")
        }
        plain.edges.map { it.alias }.toSet() shouldBe setOf(null)
        withClue("tree_hash v1 inputs exclude the alias: two graphs differing only in alias fingerprint equal") {
            aliased.internalFingerprint shouldBe plain.internalFingerprint
            aliased.internalFingerprint shouldBe GOLDEN_DIGEST
        }
        GraphFingerprint.canonical(aliased.roots, aliased.nodes, aliased.edges) { 1 } shouldBe
            GraphFingerprint.canonical(plain.roots, plain.nodes, plain.edges) { 1 }
    }

    @Test
    fun `identity, version, lifecycle, body hash, generation, pointer resolution, paths and extractor versions move it`() {
        val golden = WorkedGraph().walk().internalFingerprint
        val variants =
            mapOf(
                "body hash" to WorkedGraph(commonHash = "hash-common-changed"),
                "lifecycle" to WorkedGraph(chartLifecycle = Lifecycle.RELEASED),
                "generation" to WorkedGraph(warehouseGeneration = 8),
                "current pointer" to WorkedGraph(reportCurrent = 7),
                "source path" to WorkedGraph(formatterPath = "transform.template2"),
                "extractor schema version" to WorkedGraph(templateExtractor = 2),
                "stable identity (incarnation)" to WorkedGraph(warehouseIncarnation = "warehouse-recreated"),
                "constraint" to WorkedGraph(parameterName = "country"),
            )
        variants.forEach { (input, graph) ->
            withClue("varying $input") { graph.walk().internalFingerprint shouldNotBe golden }
        }
    }

    @Test
    fun `canonical strings escape quotes, backslashes and control characters`() {
        val g = TestGraph()
        val p = g.node(exact(PIPELINE, "p", 1), Lifecycle.DRAFT, pin(PIPELINE, exact(TEMPLATE, "t", 1), "a\"b\\c\u0001"))
        val snapshot = g.walk(p)
        val canonical = GraphFingerprint.canonical(snapshot.roots, snapshot.nodes, snapshot.edges) { 1 }
        canonical.contains("""a\"b\\c\u0001""") shouldBe true
    }

    /** The §5.4 worked graph over fake providers of all eight production families, each knob one covered/excluded input. */
    private class WorkedGraph(
        val clock: Clock = FIXED_CLOCK,
        val label: String = "acme",
        val subtype: String = "sql",
        val enabled: Boolean = true,
        val observation: String = "succeeded",
        val reversedRoots: Boolean = false,
        val commonHash: String = "hash-common-4",
        val chartLifecycle: Lifecycle = Lifecycle.DRAFT,
        val warehouseGeneration: Long = 7,
        val reportCurrent: Int = 6,
        val formatterPath: String = "transform.template",
        val templateExtractor: Int = 1,
        val warehouseIncarnation: String = "6f1c1b7e-0000-4000-8000-000000000001",
        val parameterName: String = "region",
        val importAlias: String? = null,
    ) {
        private val families = FamilyKey.PRODUCTION.keys.associateWith { FakeFamily(it) }

        init {
            val warehouse = entity(DATASOURCE, "warehouse")
            put(
                warehouse,
                NodeFacts(
                    name = "warehouse",
                    versionInfo = VersionInfo.Unversioned,
                    lifecycle = null,
                    operational = Operational.Datasource(readonly = !enabled),
                    generation = warehouseGeneration,
                    observations = listOf(Observation("last_test", Instant.parse("2026-11-09T00:00:00Z"), observation)),
                ),
                identity = ResolvedIdentity(DATASOURCE, OwnerScope.Deployment, warehouseIncarnation, null),
            )
            val common = artifact(exact(TEMPLATE, "common", 4), Lifecycle.RELEASED, hash = commonHash)
            val import = pin(TEMPLATE, common, "imports[0]").copy(alias = importAlias)
            val selector = artifact(exact(TEMPLATE, "selector", 5), Lifecycle.RELEASED, import)
            val query = artifact(exact(TEMPLATE, "query", 6), Lifecycle.RELEASED, import)
            val formatter = artifact(exact(TEMPLATE, "formatter", 3), Lifecycle.RELEASED)
            val filters =
                artifact(
                    exact(PARAMETER_SET, "filters", 2),
                    Lifecycle.DRAFT,
                    pin(PARAMETER_SET, selector, "parameters[0].source.template"),
                    pin(PARAMETER_SET, warehouse, "parameters[1].source.datasource", Constraint.DatasourceDialect("postgresql")),
                )
            val totals =
                artifact(
                    exact(PIPELINE, "totals", 2),
                    Lifecycle.RELEASED,
                    pin(PIPELINE, query, "nodes[0].template"),
                    pin(PIPELINE, warehouse, "nodes[1].datasource", Constraint.DatasourceAccess(AccessMode.READ)),
                )
            val report7 = report(7, Lifecycle.DRAFT, totals)
            val report6 = report(6, Lifecycle.RELEASED, totals)
            val chart = artifact(exact(VISUALIZATION, "chart", 2), chartLifecycle, pin(VISUALIZATION, formatter, formatterPath))
            artifact(
                exact(DASHBOARD, "sales", 3),
                Lifecycle.DRAFT,
                pin(DASHBOARD, filters, "parameter_set", Constraint.ParameterNames(listOf(parameterName))),
                pin(DASHBOARD, report7, "sources[0].pipeline"),
                pin(DASHBOARD, chart, "visualizations[0].visualization"),
            )
            put(
                exact(API_ENDPOINT, "/sales/report", 2),
                NodeFacts("/sales/report", VersionInfo.Artifact(2), Lifecycle.RELEASED, operational = Operational.ApiEndpoint(enabled)),
                pin(API_ENDPOINT, report6, "pipeline"),
            )
            put(
                entity(SCHEDULE, "nightly"),
                NodeFacts(
                    "nightly",
                    VersionInfo.Revision(9),
                    null,
                    operational = Operational.Schedule(if (enabled) ScheduleCondition.ENABLED else ScheduleCondition.PAUSED),
                ),
                pin(SCHEDULE, report6, "payload.pipeline"),
                identity = ResolvedIdentity(SCHEDULE, OwnerScope.Workspace(WS), "7d2c0000-0000-4000-8000-000000000009", null),
            )
        }

        private fun report(
            version: Int,
            lifecycle: Lifecycle,
            totals: ObjectRequest,
        ) = artifact(exact(PIPELINE, "report", version), lifecycle, pin(PIPELINE, totals, "nodes[0].pipeline"), current = reportCurrent)

        private fun artifact(
            request: ObjectRequest,
            lifecycle: Lifecycle,
            vararg pins: Reference,
            hash: String = "hash-${request.address.lookupKey}-${request.selector.number}",
            current: Int? = null,
        ): ObjectRequest =
            put(
                request,
                NodeFacts(
                    name = "$label/${request.address.lookupKey}",
                    versionInfo = VersionInfo.Artifact(requireNotNull(request.selector.number)),
                    lifecycle = lifecycle,
                    displayName = "$label ${request.address.lookupKey}",
                    subtype = subtype.takeIf { request.family == TEMPLATE },
                    currentVersion = current,
                    bodyHash = hash,
                ),
                *pins,
            )

        private fun put(
            request: ObjectRequest,
            facts: NodeFacts,
            vararg pins: Reference,
            identity: ResolvedIdentity? = null,
        ): ObjectRequest {
            val family = families.getValue(request.family)
            if (identity == null) family.put(request, facts, pins.toList()) else family.put(request, facts, pins.toList(), identity)
            return request
        }

        private val roots =
            listOf(exact(DASHBOARD, "sales", 3), exact(API_ENDPOINT, "/sales/report", 2), entity(SCHEDULE, "nightly")).let {
                if (reversedRoots) it.reversed() else it
            }

        private fun registry() =
            FamilyRegistry.production(
                families.values.map { it.descriptor(extractorSchemaVersion = if (it.key == TEMPLATE) templateExtractor else 1) },
            )

        fun walk(): GraphSnapshot = DependencyWalker(registry(), TestGraph.LIMITS, clock).walk(roots, context())

        fun canonical(): String {
            val snapshot = walk()
            val registry = registry()
            return GraphFingerprint.canonical(snapshot.roots, snapshot.nodes, snapshot.edges) { registry[it]?.extractorSchemaVersion }
        }
    }

    private companion object {
        const val GOLDEN =
            "modules/dependency-contract/src/test/resources/" + "co/datapipelines/dependencies/fingerprint/worked-graph.canonical.txt"
        const val WRITE_FLAG = "DP_WRITE_FINGERPRINT_GOLDEN"
        const val GOLDEN_DIGEST = "fp1:af31c112a4b4b2fce8866a39946888a243f63cad1cb21392cb2a3c26fefbd049"
    }
}
