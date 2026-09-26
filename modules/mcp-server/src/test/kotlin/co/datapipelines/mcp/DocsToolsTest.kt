package co.datapipelines.mcp

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Permission
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.mcp.docs.DocSet
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `docs_list` / `docs_get` (mcp-server.md §6.2.40–41) — 120/R3, the manual as tools; the
 * by-area, by-section shape of the 242a record §4.
 *
 * The properties asserted here: the top-level list is the routing table (the core first, one
 * entry per area, nothing invisible); a document over the response budget refuses the
 * whole-document form with its sections attached; the section form pages with `next`; the
 * one-release aliases answer with the NEW name; and a `docs_get` returns the SAME BYTES a
 * `resources/read` of the same name serves — the two surfaces cannot drift because both read
 * the [DocSet], and this suite proves it on every name rather than on one sample.
 */
class DocsToolsTest {
    private val docSet = DocSetTestSupport.renderedDocSet()
    private val list = DocsListTool { docSet }
    private val get = DocsGetTool { docSet }
    private val ctx = McpFixtures.ctx()

    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>(relaxed = true)
    private val datasources = mockk<DatasourceRegistry>(relaxed = true)
    private val executions = mockk<ExecutionRepository>(relaxed = true)
    private val events = mockk<ExecutionEventRepository>(relaxed = true)
    private val resourceReader =
        McpResourceReader(
            McpFixtures.pipelineService(pipelines),
            McpFixtures.templateService(templates),
            datasources,
            executions,
            events,
            // The resource-read audit (120) is not this suite's subject; the dispatcher row for
            // docs_get IS, and it has a recording sink below.
            mockk<co.datapipelines.auth.AuditEventSink>(relaxed = true),
            McpFixtures.EVERYTHING_LENS,
            docSet,
        )

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `docs_list without area is the core first, then one entry per area, nothing invisible`() {
        @Suppress("UNCHECKED_CAST")
        val entries = list.call(McpArguments(emptyMap()), ctx) as List<Map<String, Any?>>

        entries.first()["name"] shouldBe DocSet.CORE_NAME
        // Eight areas, one entry point each; every document reachable: top-level entries plus
        // their nested references cover the whole set exactly.
        val topLevel = entries.map { it["name"] as String }
        topLevel.size shouldBe docSet.areas.size
        val reachable =
            topLevel +
                entries.flatMap { entry -> (entry["references"] as List<Map<String, Any?>>).map { it["name"] as String } }
        reachable.sorted() shouldBe docSet.docs.map { it.name }.sorted()
        reachable.toSet().size shouldBe docSet.docs.size
        // Each entry declares its area, layer, size, budget flag and sections.
        val core = entries.first()
        core["area"] shouldBe "core"
        core["layer"] shouldBe "core"
        (core["chars"] as Int).shouldBeGreaterThan(1000)
        core["over_budget"] shouldBe true // the core (the old SKILL.md) is over budget until 242b shrinks it
        (core["sections"] as List<*>).size.shouldBeGreaterThan(2)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `docs_list with area returns that area's documents flat`() {
        @Suppress("UNCHECKED_CAST")
        val entries = list.call(McpArguments(mapOf("area" to "pipelines")), ctx) as List<Map<String, Any?>>
        entries.map { it["area"] } shouldContainExactly List(entries.size) { "pipelines" }
        entries.map { it["name"] } shouldContainExactly docSet.byArea(co.datapipelines.mcp.docs.DocArea.PIPELINES).map { it.name }
    }

    @Test
    fun `an unknown area is refused with the known areas attached`() {
        val failure =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                list.call(McpArguments(mapOf("area" to "weather")), ctx)
            }
        failure.code shouldBe PipelineErrorCodes.Mcp.DOC_NOT_FOUND
        failure.details["reason"] shouldBe "unknown_area"
        @Suppress("UNCHECKED_CAST")
        (failure.details["known_areas"] as List<String>).shouldContain("endpoints")
    }

    private fun List<*>.shouldContain(item: String): Boolean = contains(item)

    @Test
    fun `docs_get of every name returns the bytes the resource read of the same name serves`() {
        docSet.docs.forEach { doc ->
            val uri =
                if (doc === docSet.core) {
                    McpResourceUri.skill()
                } else {
                    McpResourceUri.skillReference(doc.name)
                }
            val resourceText =
                resourceReader
                    .read(uri, ctx)
                    .contents()
                    .first()
                    .shouldBeInstanceOf<McpSchema.TextResourceContents>()
                    .text()

            if (doc.overBudget) {
                // Over-budget documents refuse the whole form on the TOOL (the budget is the
                // tools' contract); the resource still serves whole — its URI carries no
                // section segment, so the section form has no shape there (record §4, O3).
                val failure =
                    shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                        get.call(McpArguments(mapOf("name" to doc.name)), ctx)
                    }
                failure.details["reason"] shouldBe "document_over_budget"
            } else {
                @Suppress("UNCHECKED_CAST")
                val payload = get.call(McpArguments(mapOf("name" to doc.name)), ctx) as Map<String, Any?>
                withClue("docs_get {\"name\": \"${doc.name}\"} disagrees with resources/read $uri") {
                    payload["markdown"] shouldBe resourceText
                }
                payload["name"] shouldBe doc.name
                payload["title"] shouldBe doc.title
            }
        }
    }

    @Test
    fun `a whole-document call on an over-budget document is refused with the section list`() {
        val overBudget = docSet.docs.first { it.overBudget }
        val failure =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                get.call(McpArguments(mapOf("name" to overBudget.name)), ctx)
            }
        failure.code shouldBe PipelineErrorCodes.Mcp.DOC_NOT_FOUND
        failure.details["reason"] shouldBe "document_over_budget"
        @Suppress("UNCHECKED_CAST")
        val sections = failure.details["sections"] as List<Map<String, Any?>>
        sections.map { it["id"] } shouldContainExactly overBudget.sections.map { it.id }
    }

    @Test
    fun `the section form serves one section and pages over an over-budget one`() {
        val doc = docSet.docs.first { it.overBudget }
        val first = doc.sections.first()

        @Suppress("UNCHECKED_CAST")
        val answer = get.call(McpArguments(mapOf("name" to doc.name, "section" to first.id)), ctx) as Map<String, Any?>
        answer["name"] shouldBe doc.name
        answer["section"] shouldBe first.id
        (answer["markdown"] as String).length shouldBeGreaterThan 0

        // Page through the whole document from each section id, following `next` when the
        // section itself was split; the walk terminates and covers every section.
        var answers = 0
        for (section in doc.sections) {
            var cursor: String? = section.id
            var steps = 0
            while (cursor != null) {
                @Suppress("UNCHECKED_CAST")
                val part = get.call(McpArguments(mapOf("name" to doc.name, "section" to cursor)), ctx) as Map<String, Any?>
                (part["markdown"] as String).length shouldBeGreaterThan 0
                answers += 1
                cursor = part["next"] as String?
                steps += 1
                steps shouldBeLessThanOrEqual 50
            }
        }
        answers shouldBeGreaterThan doc.sections.size
    }

    @Test
    fun `an alias answers with the new name and the same bytes`() {
        @Suppress("UNCHECKED_CAST")
        val viaAlias = get.call(McpArguments(mapOf("name" to "naming")), ctx) as Map<String, Any?>
        viaAlias["name"] shouldBe "pipelines-naming"
        viaAlias["title"] shouldBe docSet.get("pipelines-naming").title

        @Suppress("UNCHECKED_CAST")
        val viaNewName = get.call(McpArguments(mapOf("name" to "pipelines-naming")), ctx) as Map<String, Any?>
        viaAlias["markdown"] shouldBe viaNewName["markdown"]

        // The core's alias answers through the section form too: `skill` is what the
        // handshake still says, and its sections are how the over-budget core is read.
        @Suppress("UNCHECKED_CAST")
        val viaSkill =
            get.call(
                McpArguments(
                    mapOf(
                        "name" to "skill",
                        "section" to
                            docSet.core.sections
                                .first()
                                .id,
                    ),
                ),
                ctx,
            ) as Map<String, Any?>
        viaSkill["name"] shouldBe DocSet.CORE_NAME
    }

    @Test
    fun `an unknown name is mcp_doc_not_found with the catalogued names attached`() {
        val failure =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                get.call(McpArguments(mapOf("name" to "the-manual-i-imagined")), ctx)
            }

        failure.code shouldBe PipelineErrorCodes.Mcp.DOC_NOT_FOUND
        failure.details["known_docs"] shouldBe docSet.docs.map { it.name }
    }

    @Test
    fun `an unknown section is refused with the known sections attached`() {
        val failure =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                get.call(McpArguments(mapOf("name" to "templates", "section" to "nope")), ctx)
            }
        failure.details["reason"] shouldBe "unknown_section"
        @Suppress("UNCHECKED_CAST")
        (failure.details["known_sections"] as List<String>) shouldBe docSet.get("templates").sections.map { it.id }
    }

    @Test
    fun `both tools are read-only in the catalog and declare only docs-read`() {
        McpToolCatalog.isMutating("docs_list") shouldBe false
        McpToolCatalog.isMutating("docs_get") shouldBe false
        McpToolCatalog.permissionOf("docs_list") shouldBe Permission.DOCS_READ
        McpToolCatalog.permissionOf("docs_get") shouldBe Permission.DOCS_READ
    }

    @Test
    fun `a docs_get call writes the same mcp_tool_called audit row as any tool call`() {
        // A REAL recording sink, never a strict mock: the contract is "the call is recorded",
        // and a strict double passes precisely when the call is missing (MISTAKES).
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(DocsTools.all({ docSet }), sink)

        // A within-budget document, so the answer is content and not the over-budget refusal.
        val result = dispatcher.call(McpFixtures.request("docs_get", mapOf("name" to "templates")), ctx)

        result.isError shouldBe false
        val row = sink.rows.single { it.event == "mcp.tool.called" }
        row.userId shouldBe McpFixtures.USER
        row.keyId shouldBe McpFixtures.KEY_ID
        row.details["tool"] shouldBe "docs_get"
        row.details["outcome"] shouldBe "success"
        row.details["correlation_id"] shouldBe McpFixtures.CORRELATION_ID.toString()
        (row.details["elapsed_ms"] is Number) shouldBe true
        // A read tool writes no mcp.tool.write row — the mutating declaration is what keys it.
        sink.rows.none { it.event == "mcp.tool.write" } shouldBe true
    }

    /** One audit row as the sink received it. */
    private data class AuditRow(
        val event: String,
        val userId: UUID?,
        val keyId: String?,
        val details: Map<String, Any?>,
    )

    private class RecordingAuditSink : AuditEventSink {
        val rows = mutableListOf<AuditRow>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows.add(AuditRow(event, userId, keyId, details))
        }
    }
}
