package co.datapipelines.mcp

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `docs_list` / `docs_get` (mcp-server.md §6.2.40–41) — 120/R3, the skill as tools.
 *
 * The two properties that make the ruling hold are the ones asserted here: the catalog is the
 * skill's own map (`skill` first, then every reference in the map's order, with its purposes),
 * and a `docs_get` returns the SAME BYTES a `resources/read` of the same name serves — the two
 * surfaces cannot drift because both read [SkillDocs], and this suite proves it on every name
 * rather than on one sample.
 */
class DocsToolsTest {
    private val list = DocsListTool()
    private val get = DocsGetTool()
    private val ctx = McpFixtures.ctx(Scope.READ)

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
        )

    @Test
    fun `docs_list is skill first then the reference map in its own order, with its purposes`() {
        @Suppress("UNCHECKED_CAST")
        val entries = list.call(McpArguments(emptyMap()), ctx) as List<Map<String, Any?>>

        // The count is the map plus the core; the order is the map's own, `skill` first —
        // asserted from SkillDocs' parse of the packaged SKILL.md, never a transcribed copy.
        entries.map { it["name"] } shouldContainExactly
            SkillDocs.catalog.map { it.name }
        entries.size shouldBe SkillDocs.references.size + 1
        entries.first()["name"] shouldBe "skill"
        entries.forEach { entry ->
            entry["title"] shouldBe SkillDocs.catalog.first { it.name == entry["name"] }.title
            entry["purpose"] shouldBe SkillDocs.catalog.first { it.name == entry["name"] }.purpose
        }
    }

    @Test
    fun `the catalog cannot drift from the packaged files - a map line and a file answer to each other`() {
        // SkillDocs.catalog throws at load when the two disagree; SkillDistributionTest asserts
        // the same agreement in the build. Here: the load happened, and both directions match.
        SkillDocs.catalog
            .drop(1)
            .map { it.name }
            .toSet() shouldBe SkillDocs.references.keys
    }

    @Test
    fun `docs_get of every name returns the bytes the resource read of the same name serves`() {
        SkillDocs.catalog.forEach { entry ->
            val uri =
                if (entry.name == SkillDocs.SKILL_NAME) {
                    McpResourceUri.skill()
                } else {
                    McpResourceUri.skillReference(entry.name)
                }
            val resourceText =
                resourceReader
                    .read(uri, ctx)
                    .contents()
                    .first()
                    .shouldBeInstanceOf<McpSchema.TextResourceContents>()
                    .text()

            @Suppress("UNCHECKED_CAST")
            val payload = get.call(McpArguments(mapOf("name" to entry.name)), ctx) as Map<String, Any?>
            withClue("docs_get {\"name\": \"${entry.name}\"} disagrees with resources/read $uri") {
                payload["markdown"] shouldBe resourceText
                payload["name"] shouldBe entry.name
                payload["title"] shouldBe entry.title
            }
        }
    }

    @Test
    fun `an unknown name is mcp_doc_not_found with the catalogued names attached`() {
        val failure =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                get.call(McpArguments(mapOf("name" to "the-manual-i-imagined")), ctx)
            }

        failure.code shouldBe PipelineErrorCodes.Mcp.DOC_NOT_FOUND
        failure.details["known_docs"] shouldBe SkillDocs.catalog.map { it.name }
    }

    @Test
    fun `both tools are read-only in the catalog and require only the read scope`() {
        McpToolCatalog.isMutating("docs_list") shouldBe false
        McpToolCatalog.isMutating("docs_get") shouldBe false
        co.datapipelines.auth.ScopeMatrix
            .requiredScopeForTool("docs_list") shouldBe Scope.READ
        co.datapipelines.auth.ScopeMatrix
            .requiredScopeForTool("docs_get") shouldBe Scope.READ
    }

    @Test
    fun `a docs_get call writes the same mcp_tool_called audit row as any tool call`() {
        // A REAL recording sink, never a strict mock: the contract is "the call is recorded",
        // and a strict double passes precisely when the call is missing (MISTAKES).
        val sink = RecordingAuditSink()
        val dispatcher = McpToolDispatcher(DocsTools.all(), sink)

        val result = dispatcher.call(McpFixtures.request("docs_get", mapOf("name" to "skill")), ctx)

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

    /** The in-memory [AuditEventSink] that records instead of mocking — the effect is the assertion. */
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
            rows += AuditRow(event, userId, keyId, details)
        }
    }
}
