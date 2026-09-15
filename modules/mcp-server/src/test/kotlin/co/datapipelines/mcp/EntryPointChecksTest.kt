package co.datapipelines.mcp

import co.datapipelines.application.mcp.McpToolLearnings
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.TablesPage
import co.datapipelines.pipeline.Door
import co.datapipelines.pipeline.DoorKind
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The three entry-point checks' decision tables (139) — each refusal arm, each exemption
 * arm, and the fail-open arms that keep the checks from answering questions they do not own.
 * Falsified per check by disabling the call site (see the handback): each check's refusal
 * test goes red, its exemption tests stay green.
 */
class EntryPointChecksTest {
    private val workspace = UUID.fromString("99999999-9999-9999-9999-999999999999")
    private val key = "dpk_test"

    private val templates = mockk<TemplateRepository>()
    private val introspector = mockk<SchemaIntrospector>()
    private val datasources = mockk<DatasourceRegistry>(relaxed = true)
    private val learnings = mockk<McpToolLearnings>()

    private val pg = Datasource(name = "pg", displayName = "PG", dialect = Dialect.POSTGRES, jdbcUrl = "jdbc:postgresql://db/db")

    private fun listing(vararg tables: String) =
        TablesPage(tables.map { TableInfo(namespace = listOf("public"), name = it, type = "TABLE") }, truncated = false)

    private fun template(
        id: String,
        body: String,
    ): Template =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = id,
            description = "",
            body = body,
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
        )

    private fun node(
        id: String,
        source: String,
        templateId: String,
        body: String,
    ): Node =
        Node(
            id = id,
            description = "",
            type = NodeType.DQL,
            source = source,
            template = TemplateRef(id = templateId, version = 1),
            output = null,
            dependsOn = emptyList(),
        )

    private fun pipeline(vararg nodes: Node) = Pipeline(1, "test/p", "P", "", PipelineSettings(), emptyMap(), nodes.toList())

    private fun stubListing(vararg tables: String) {
        every { datasources.getVisible("pg", workspace) } returns pg
        every { introspector.tables(eq(pg), isNull(), any(), isNull()) } returns listing(*tables)
    }

    // ---------------------------------------------------------------------------------
    // A — table_not_learned
    // ---------------------------------------------------------------------------------

    @Test
    fun `a body naming a listed table the key never learned is refused with the clearing call`() {
        stubListing("trips")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns template("test/t.sql", "SELECT * FROM trips")
        every { learnings.columnsRead(key, "pg") } returns emptySet()

        val e =
            shouldThrow<DatapipelinesException> {
                TableLearningCheck(templates, introspector, datasources, learnings)
                    .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
            }
        e.code shouldBe PipelineErrorCodes.Validation.TABLE_NOT_LEARNED
        e.details["tables"] shouldBe
            listOf(mapOf("datasource" to "pg", "table" to "trips", "clearing_call" to "datasources_get_columns"))
    }

    @Test
    fun `a table the key has learned passes`() {
        stubListing("trips")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns template("test/t.sql", "SELECT * FROM trips")
        every { learnings.columnsRead(key, "pg") } returns setOf("trips")

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
    }

    @Test
    fun `my_orders is not orders - the tokeniser reads whole identifiers`() {
        stubListing("orders")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "SELECT * FROM my_orders WHERE row_count > 0")
        every { learnings.columnsRead(key, "pg") } returns emptySet()

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
        // Only prose tokens and a non-listed identifier: nothing was refused, and the
        // catalog was still consulted for the listing.
        verify { introspector.tables(eq(pg), isNull(), any(), isNull()) }
    }

    @Test
    fun `a dynamic name inside a dollar-brace span is exempt`() {
        stubListing("orders")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "SELECT * FROM <#assign t = \"or\">${'$'}{t}ders")
        every { learnings.columnsRead(key, "pg") } returns emptySet()

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
    }

    @Test
    fun `a tempdb source is exempt - there is no catalog to learn`() {
        val tempdbNode = node("stage", "tempdb", "test/t.sql", "ignored")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "SELECT * FROM trips")

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(tempdbNode))
        verify(exactly = 0) { introspector.tables(any<Datasource>(), isNull(), any<Int>(), isNull()) }
    }

    @Test
    fun `an unreachable datasource fails open - the probe and 12_5 own that answer`() {
        every { datasources.getVisible("pg", workspace) } returns pg
        every { introspector.tables(eq(pg), isNull(), any(), isNull()) } throws
            DatasourceUnreachableException("pg", RuntimeException("down"))
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "SELECT * FROM trips")

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
    }

    @Test
    fun `a truncated listing fails open - it is not the catalog's truth`() {
        every { datasources.getVisible("pg", workspace) } returns pg
        every { introspector.tables(eq(pg), isNull(), any(), isNull()) } returns
            TablesPage(listOf(TableInfo(listOf("public"), "trips", "TABLE")), truncated = true)
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "SELECT * FROM trips")

        TableLearningCheck(templates, introspector, datasources, learnings)
            .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
        verify(exactly = 0) { learnings.columnsRead(any(), any()) }
    }

    @Test
    fun `the refusal names the table as the catalog spells it`() {
        stubListing("TRIPS")
        every { templates.findVersion(workspace, "test/t.sql", 1) } returns
            template("test/t.sql", "select * from trips")
        every { learnings.columnsRead(key, "pg") } returns emptySet()

        val e =
            shouldThrow<DatapipelinesException> {
                TableLearningCheck(templates, introspector, datasources, learnings)
                    .require(workspace, key, pipeline(node("rows", "pg", "test/t.sql", "ignored")))
            }

        @Suppress("UNCHECKED_CAST")
        val tables = e.details["tables"] as List<Map<String, Any?>>
        tables.single()["table"] shouldBe "TRIPS"
    }

    // ---------------------------------------------------------------------------------
    // B — template_unrendered
    // ---------------------------------------------------------------------------------

    private fun draftDetail(updatedAt: Instant) =
        PipelineVersionDetail(
            pipelineId = UUID.randomUUID(),
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "h",
            createdAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
            updatedAt = updatedAt,
        )

    private fun templateVersion(
        status: PipelineVersionStatus,
        updatedAt: Instant?,
    ) = TemplateVersion(
        id = "test/t.sql",
        version = 1,
        dialect = Dialect.POSTGRES,
        isLibrary = false,
        imports = emptyList(),
        body = "SELECT 1",
        createdAt = Instant.EPOCH,
        createdBy = UUID.randomUUID(),
        status = status,
        updatedAt = updatedAt,
    )

    @Test
    fun `a draft pin never rendered is refused with last_render null`() {
        val detail = draftDetail(Instant.parse("2026-09-15T10:00:00Z"))
        every { templates.lookupVersion(workspace, "test/t.sql", 1) } returns
            templateVersion(PipelineVersionStatus.DRAFT, detail.updatedAt)
        every { learnings.lastRenderAt(key, "test/t.sql") } returns null

        val e =
            shouldThrow<DatapipelinesException> {
                TemplateRenderFreshness(templates, learnings)
                    .require(workspace, key, detail, listOf(node("rows", "pg", "test/t.sql", "ignored")))
            }
        e.code shouldBe PipelineErrorCodes.Execution.TEMPLATE_UNRENDERED
        e.details["templates"] shouldBe
            listOf(
                mapOf(
                    "id" to "test/t.sql",
                    "version" to 1,
                    "updated_at" to "2026-09-15T10:00:00Z",
                    "last_render" to null,
                ),
            )
    }

    @Test
    fun `a render older than the draft's write is refused - the update re-arms the check`() {
        val detail = draftDetail(Instant.parse("2026-09-15T10:00:00Z"))
        every { templates.lookupVersion(workspace, "test/t.sql", 1) } returns
            templateVersion(PipelineVersionStatus.DRAFT, detail.updatedAt)
        every { learnings.lastRenderAt(key, "test/t.sql") } returns Instant.parse("2026-09-15T09:00:00Z")

        shouldThrow<DatapipelinesException> {
            TemplateRenderFreshness(templates, learnings)
                .require(workspace, key, detail, listOf(node("rows", "pg", "test/t.sql", "ignored")))
        }
    }

    @Test
    fun `a render newer than the draft's write passes`() {
        val detail = draftDetail(Instant.parse("2026-09-15T10:00:00Z"))
        every { templates.lookupVersion(workspace, "test/t.sql", 1) } returns
            templateVersion(PipelineVersionStatus.DRAFT, detail.updatedAt)
        every { learnings.lastRenderAt(key, "test/t.sql") } returns Instant.parse("2026-09-15T10:30:00Z")

        TemplateRenderFreshness(templates, learnings)
            .require(workspace, key, detail, listOf(node("rows", "pg", "test/t.sql", "ignored")))
    }

    @Test
    fun `a RELEASED pin is exempt - it rendered before release and cannot change`() {
        val detail = draftDetail(Instant.parse("2026-09-15T10:00:00Z"))
        every { templates.lookupVersion(workspace, "test/t.sql", 1) } returns
            templateVersion(PipelineVersionStatus.RELEASED, null)

        TemplateRenderFreshness(templates, learnings)
            .require(workspace, key, detail, listOf(node("rows", "pg", "test/t.sql", "ignored")))
        verify(exactly = 0) { learnings.lastRenderAt(any(), any()) }
    }

    @Test
    fun `a RELEASED pipeline version is exempt entirely`() {
        val released =
            draftDetail(Instant.parse("2026-09-15T10:00:00Z")).copy(status = PipelineVersionStatus.RELEASED)
        TemplateRenderFreshness(templates, learnings)
            .require(workspace, key, released, listOf(node("rows", "pg", "test/t.sql", "ignored")))
        verify(exactly = 0) { templates.lookupVersion(any(), any(), any()) }
    }

    // ---------------------------------------------------------------------------------
    // C — door_unacknowledged
    // ---------------------------------------------------------------------------------

    private fun doorPipeline(vararg params: Pair<String, LogicalType>): Pipeline =
        Pipeline(
            1,
            "test/p",
            "P",
            "",
            PipelineSettings(),
            params.associate { (n, t) -> n to Parameter(type = t) },
            listOf(node("rows", "pg", "test/t.sql", "ignored")),
        )

    @Test
    fun `a raw date pair without the flag is refused, naming the parameters`() {
        val p = doorPipeline("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE)
        val e = shouldThrow<DatapipelinesException> { DoorAcknowledgment.require(p, null) }
        e.code shouldBe PipelineErrorCodes.Validation.DOOR_UNACKNOWLEDGED
        e.details["parameters"] shouldBe listOf("end_date", "start_date")
    }

    @Test
    fun `the flag is accepted`() {
        val p = doorPipeline("start_date" to LogicalType.DATE, "end_date" to LogicalType.DATE)
        DoorAcknowledgment.require(p, true)
        Door.classify(p.parameters, p.nodes) shouldBe DoorKind.RAW_DATE_PAIR
    }

    @Test
    fun `period and none doors pass without any flag`() {
        DoorAcknowledgment.require(doorPipeline("year" to LogicalType.INTEGER, "as_of" to LogicalType.DATE), null)
        DoorAcknowledgment.require(doorPipeline("limit" to LogicalType.INTEGER), null)
    }
}
