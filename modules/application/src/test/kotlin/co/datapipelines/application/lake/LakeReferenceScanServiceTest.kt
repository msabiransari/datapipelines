package co.datapipelines.application.lake

import co.datapipelines.datasources.DuckDbDefaultCatalog
import co.datapipelines.datasources.LakeRegisteredTable
import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.SqlTableReferenceExtractor
import co.datapipelines.parameters.ParameterDefinition
import co.datapipelines.parameters.ParameterKind
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.SelectorProbe
import co.datapipelines.parameters.SelectorRender
import co.datapipelines.parameters.SelectorSource
import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasedVersion
import co.datapipelines.templates.NodeSqlResolution
import co.datapipelines.templates.NodeSqlResolver
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [LakeReferenceScanService]'s site handling (slice D-A … D-D) with the REAL parser ([SqlTableReferenceExtractor], an
 * embedded parse-only engine) and the real classifier and resolver: the stubs are only the metadata reads (the stored
 * bodies, the render results, the registry), so what is asserted is the scanner's own answer — which sites it visits,
 * what status and reasons each yields, and that nothing rendered reaches a reason.
 */
class LakeReferenceScanServiceTest {
    private val workspace = UUID.randomUUID()
    private val extractor = SqlTableReferenceExtractor()

    private val pipelines = mockk<PipelineRepository>()
    private val nodeSql = mockk<NodeSqlResolver>()
    private val templates = mockk<TemplateRepository>()
    private val parameterSets = mockk<ParameterSetRepository>()
    private val selectors = mockk<SelectorProbe>()
    private val index = mockk<LakeReferenceIndexRepository>(relaxed = true)
    private val datasources =
        DatasourceRegistry { name, _ ->
            when (name) {
                "lake", "shop", "gone", "motherduck" -> DatasourceFacts(Dialect.LAKE)
                "pg" -> DatasourceFacts(Dialect.POSTGRES)
                else -> null
            }
        }

    /**
     * The registry of record's URLs (#790), composed with the REAL derivation exactly as the wiring composes them: `gone`
     * is admitted as LAKE above but has no row here (deleted in between), `motherduck`'s URL is a form the derivation
     * does not know. Every name asked is recorded.
     */
    private val urls =
        mapOf(
            "lake" to "jdbc:duckdb::memory:",
            "shop" to "jdbc:duckdb:/var/lib/lake/shop.db",
            "motherduck" to "jdbc:duckdb:md:shop",
            "pg" to "jdbc:postgresql://pg/db",
        )
    private val catalogsAsked = mutableListOf<String>()
    private val defaultCatalogs =
        LakeDefaultCatalogs { name, workspaceId ->
            workspaceId shouldBe workspace
            catalogsAsked += name
            urls[name]?.let(DuckDbDefaultCatalog::of)
        }
    private val registry =
        LakeTableCatalog { name ->
            if (name in setOf("lake", "shop", "gone", "motherduck")) {
                listOf(LakeRegisteredTable(listOf("lk"), "events", "parquet", "s3://b/events", null, null))
            } else {
                emptyList()
            }
        }

    private val scanner =
        LakeReferenceScanService(
            pipelines,
            nodeSql,
            templates,
            parameterSets,
            selectors,
            datasources,
            defaultCatalogs,
            registry,
            extractor,
            index,
            OrgContext.DEFAULTS,
        )

    @AfterEach
    fun close() = extractor.close()

    private fun template(
        id: String,
        body: String,
    ) {
        val stored = mockk<Template>()
        every { stored.body } returns body
        every { stored.imports } returns emptyList()
        every { templates.findVersion(workspace, id, 1) } returns stored
    }

    private fun rendered(sql: String): NodeSqlResolution.Rendered {
        val rendered = mockk<NodeSqlResolution.Rendered>()
        every { rendered.positionalSql } returns sql
        return rendered
    }

    private fun pipeline(
        id: UUID,
        vararg nodes: Pair<String, String>,
    ) {
        val record = mockk<PipelineRecord>()
        every { record.name } returns "e778/scanned"
        every { pipelines.findById(workspace, id) } returns record
        val nodeJson =
            nodes.joinToString(",") { (node, source) ->
                """{"id":"$node","type":"DQL","source":"$source","template":{"id":"e778/$node.sql","version":1},"depends_on":[]}"""
            }
        every { pipelines.findVersionBody(workspace, id, 1) } returns
            """{"schema_version":1,"name":"e778/scanned","display_name":"E778","description":"d","parameters":{},""" +
            """"settings":{"tempdb":{"engine":"H2"}},"nodes":[$nodeJson]}"""
    }

    private fun resolves(
        id: UUID,
        node: String,
        resolution: NodeSqlResolution,
    ) {
        every {
            nodeSql.resolve(workspace, id, node, 1, null, ReadLens.Everything, ReadLens.Everything)
        } returns resolution
    }

    private fun parameter(
        name: String,
        source: SelectorSource? = null,
        dependsOn: List<String> = emptyList(),
        type: LogicalType = LogicalType.STRING,
        cardinality: ParameterCardinality = ParameterCardinality.SINGLE,
    ) = ParameterDefinition(
        name = name,
        label = name,
        type = type,
        kind = ParameterKind.INPUT,
        cardinality = cardinality,
        source = source,
        dependsOn = dependsOn,
    )

    private fun parameterSet(
        id: UUID,
        vararg parameters: ParameterDefinition,
    ) {
        val stored = mockk<ParameterSetVersion>()
        every { stored.record.name } returns "e778/filters"
        every { stored.body } returns ParameterSetBody(displayName = "Filters", parameters = parameters.toList())
        every { parameterSets.findVersion(workspace, id, 1) } returns stored
    }

    @Test
    fun `a pipeline - only LAKE SQL nodes are sites, ordinals run across them, the weakest site sets the status`() {
        val id = UUID.randomUUID()
        pipeline(id, "read" to "lake", "warehouse" to "pg", "broken" to "lake")
        template("e778/read.sql", "SELECT * FROM lk.events")
        resolves(id, "read", rendered("SELECT e.* FROM lk.events e JOIN lk.zones z ON z.id = e.zone"))
        resolves(id, "broken", mockk<NodeSqlResolution.RenderFailed>())

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.name shouldBe "e778/scanned"
        scan.status shouldBe LakeScanStatus.UNSCANNABLE
        scan.references.map { it.ordinal to it.resolved?.dotted } shouldContainExactly listOf(0 to "lk.events", 1 to "lk.zones")
        scan.references.map { it.site }.distinct() shouldContainExactly listOf("node:read")
        scan.reasons shouldContainExactly listOf(LakeScanReason("node:broken", "render_failed", "e778/broken.sql@1"))
        verify(exactly = 0) { nodeSql.resolve(workspace, id, "warehouse", any(), any(), any(), any()) }
        verify(exactly = 1) { index.replace(scan) }
    }

    @Test
    fun `a site the workspace-scoped lookup does not admit as LAKE stays excluded - its catalog is never asked`() {
        val id = UUID.randomUUID()
        pipeline(id, "warehouse" to "pg", "elsewhere" to "not-visible", "read" to "lake")
        template("e778/read.sql", "SELECT * FROM memory.lk.events")
        resolves(id, "read", rendered("SELECT * FROM memory.lk.events"))

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.references.map { it.site } shouldContainExactly listOf("node:read")
        catalogsAsked shouldContainExactly listOf("lake")
    }

    @Test
    fun `a file-backed datasource - its own catalog's three-part name indexes under the one-segment namespace`() {
        val id = UUID.randomUUID()
        pipeline(id, "read" to "shop")
        template("e778/read.sql", "SELECT * FROM shop.lk.events")
        resolves(id, "read", rendered("SELECT e.* FROM shop.lk.events e JOIN memory.lk.events m ON m.id = e.id"))

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.references.map { it.writtenCatalog to it.resolved } shouldContainExactly
            listOf(
                "shop" to LakeTableIdentity(listOf("lk"), "events"),
                "memory" to LakeTableIdentity(listOf("memory", "lk"), "events"),
            )
        catalogsAsked shouldContainExactly listOf("shop")
    }

    @Test
    fun `an admitted datasource the registry of record no longer holds - a catalog-qualified name is UNRESOLVED`() {
        catalogUnknownAt("gone")
    }

    @Test
    fun `an admitted datasource whose URL form is not known - a catalog-qualified name is UNRESOLVED`() {
        catalogUnknownAt("motherduck")
    }

    /** #790 outcome 2 (owner ruling 2026-11-13): no `memory` fallback, no error; names without a catalog resolve as today. */
    private fun catalogUnknownAt(datasource: String) {
        val id = UUID.randomUUID()
        pipeline(id, "read" to datasource)
        template("e778/read.sql", "SELECT * FROM lk.events")
        resolves(id, "read", rendered("SELECT * FROM memory.lk.events UNION ALL SELECT * FROM lk.events UNION ALL SELECT * FROM events"))

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.status shouldBe LakeScanStatus.EXACT
        val written = scan.references.map { listOfNotNull(it.writtenCatalog, it.writtenSchema, it.writtenTable).joinToString(".") }
        written.zip(scan.references.map { it.resolved }) shouldContainExactly
            listOf(
                "memory.lk.events" to null,
                "lk.events" to LakeTableIdentity(listOf("lk"), "events"),
                "events" to LakeTableIdentity(listOf("lk"), "events"),
            )
        catalogsAsked shouldContainExactly listOf(datasource)
    }

    @Test
    fun `refused SQL is UNSCANNABLE and its reason names the parser's error type, never the rendered text`() {
        val id = UUID.randomUUID()
        pipeline(id, "read" to "lake")
        template("e778/read.sql", "SELECT * FROM lk.events WHERE")
        resolves(id, "read", rendered("SELECT * FROM lk.events WHERE note = 'sample-secret-778' AND AND"))

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.status shouldBe LakeScanStatus.UNSCANNABLE
        scan.references.shouldBeEmpty()
        val reason = scan.reasons.single()
        reason.site shouldBe "node:read"
        reason.kind shouldBe LakeReferenceScanService.KIND_UNPARSEABLE
        reason.detail shouldNotContain "sample-secret-778"
        reason.detail shouldNotContain "lk.events"
    }

    @Test
    fun `a site whose template version is unreadable is PARTIAL, not EXACT - the classifier had nothing to read`() {
        val id = UUID.randomUUID()
        pipeline(id, "read" to "lake")
        every { templates.findVersion(workspace, "e778/read.sql", 1) } returns null
        resolves(id, "read", rendered("SELECT * FROM lk.events"))

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.status shouldBe LakeScanStatus.PARTIAL
        scan.references.single().resolved shouldBe LakeTableIdentity(listOf("lk"), "events")
    }

    @Test
    fun `a parameter set - LAKE template selectors are sites, rendered against samples, binds translated for the parser`() {
        val id = UUID.randomUUID()
        val zoneTemplate = TemplateRef("e778/zone.sql", 1)
        parameterSet(
            id,
            parameter("city"),
            parameter("markets", cardinality = ParameterCardinality.MULTI),
            parameter("zone", SelectorSource(template = zoneTemplate, datasource = "lake"), dependsOn = listOf("city", "markets")),
            parameter("warehouse", SelectorSource(template = TemplateRef("e778/wh.sql", 1), datasource = "pg")),
        )
        template("e778/zone.sql", "SELECT zone FROM lk.events WHERE city = :city")
        val context = slot<Map<String, Any?>>()
        every { selectors.render(workspace, zoneTemplate, capture(context)) } returns
            SelectorRender.Rendered("SELECT zone FROM lk.events WHERE city = :city")

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PARAMETER_SET, id, 1))

        scan.name shouldBe "e778/filters"
        scan.status shouldBe LakeScanStatus.EXACT
        scan.references.single().let {
            it.site shouldBe "selector:zone"
            it.resolved shouldBe LakeTableIdentity(listOf("lk"), "events")
        }
        context.captured["city"] shouldBe "sample"
        context.captured["markets_count"] shouldBe 1
        context.captured.containsKey("markets") shouldBe false
        context.captured.keys.containsAll(OrgContext.DEFAULTS.keys) shouldBe true
        verify(exactly = 0) { selectors.render(workspace, TemplateRef("e778/wh.sql", 1), any()) }
    }

    @Test
    fun `a selector whose render fails is UNSCANNABLE and the render's message is not kept`() {
        val id = UUID.randomUUID()
        val zoneTemplate = TemplateRef("e778/zone.sql", 1)
        parameterSet(id, parameter("zone", SelectorSource(template = zoneTemplate, datasource = "lake")))
        every { selectors.render(workspace, zoneTemplate, any()) } returns SelectorRender.Failed("city is 'sample-secret-778'")

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PARAMETER_SET, id, 1))

        scan.status shouldBe LakeScanStatus.UNSCANNABLE
        scan.reasons shouldContainExactly listOf(LakeScanReason("selector:zone", "render_failed", "e778/zone.sql@1"))
    }

    @Test
    fun `a version with no LAKE site is EXACT with no references, and is still written`() {
        val id = UUID.randomUUID()
        pipeline(id, "warehouse" to "pg")

        val scan = scanner.scan(ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))

        scan.status shouldBe LakeScanStatus.EXACT
        scan.references.shouldBeEmpty()
        verify(exactly = 1) { index.replace(scan) }
    }

    @Test
    fun `the release seam indexes pipelines and parameter sets and ignores every other family`() {
        listOf(ReleaseFamily.TEMPLATE, ReleaseFamily.VISUALIZATION, ReleaseFamily.DASHBOARD).forEach { family ->
            scanner.released(ReleasedVersion(workspace, family, UUID.randomUUID(), 1))
        }
        verify(exactly = 0) { index.replace(any()) }

        val id = UUID.randomUUID()
        pipeline(id, "warehouse" to "pg")
        scanner.released(ReleasedVersion(workspace, ReleaseFamily.PIPELINE, id, 1))
        verify(exactly = 1) { index.replace(match { it.version == ScannedVersion(workspace, ReleaseFamily.PIPELINE, id, 1) }) }
    }
}
