package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.mcp.McpToolLearnings
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetFolder
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set tools over mocked collaborators (the `ExecutionToolsTest` shape): the two rules
 * the 194d merge's security pass found missing — a promoter never sees a draft pointer (178,
 * `pipelines_get`'s shape) and the 094 new-root check reads the WHOLE level — and the answer shapes
 * and refusals of the list, update, purge and evaluate tools (the cascade E2E drives them through the
 * server; this is the module's own coverage of their bodies — the 194d landing gate's koverVerify).
 */
class ParameterSetsToolsTest {
    private val sets = mockk<ParameterSetService>()
    private val repository = mockk<ParameterSetRepository>()
    private val templates = mockk<TemplateRepository>(relaxed = true)
    private val ctx = McpFixtures.ctx()
    private val workspaceId = McpFixtures.WORKSPACE.id
    private val setId = UUID.randomUUID()
    private val at = Instant.parse("2026-09-28T00:00:00Z")
    private val record =
        ParameterSetRecord(
            id = setId,
            workspaceId = workspaceId,
            name = "nyc/sales/region_filters",
            displayName = "Region filters",
            description = "",
            currentVersion = 1,
            createdAt = at,
            updatedAt = at,
            createdBy = McpFixtures.USER,
        )
    private val released =
        ParameterSetVersionDetail(
            parameterSetId = setId,
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "hash-v1",
            createdAt = at,
            createdBy = McpFixtures.USER,
        )
    private val body: ParameterSetBody =
        ParameterSetJson.mapper.treeToValue(
            ParameterSetJson.mapper.readTree("""{"display_name":"Region filters","parameters":[]}"""),
            ParameterSetBody::class.java,
        )

    /** A promoter's view: only [admitted] is visible (178). */
    private fun narrowed(vararg admitted: String) =
        PromoterLens { LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(admitted.toSet())) }

    @Test
    fun `parameter_sets_get under a narrowing lens that admits the set carries no draft pointer - the draft is never read`() {
        every { sets.findWorking(workspaceId, any(), setId) } returns ParameterSetVersion(record, released, body)
        every { repository.findDraft(workspaceId, setId) } returns released.copy(version = 2, status = PipelineVersionStatus.DRAFT)

        val answer =
            ParameterSetsGetTool(sets, repository, templates, narrowed(record.name))
                .call(McpArguments(mapOf("id" to setId.toString())), ctx) as Map<*, *>

        answer["draft"] shouldBe null
        verify(exactly = 0) { repository.findDraft(any(), any()) }
    }

    @Test
    fun `parameter_sets_create reads the whole root level - a name under the second root is not a new root`() {
        val roots = listOf(ParameterSetFolder("finance", "finance", 3), ParameterSetFolder("nyc", "nyc", 2))
        // The repository honours its limit, as the real statement's LIMIT does.
        every { repository.listChildFolders(workspaceId, null, any()) } answers { roots.take(thirdArg()) }
        every { sets.create(workspaceId, any(), McpFixtures.USER, WriteSurface.MCP) } returns
            ParameterSetVersion(record, released.copy(status = PipelineVersionStatus.DRAFT), body)

        val answer =
            ParameterSetsCreateTool(sets, repository, ParametersConfig(), McpFixtures.EVERYTHING_LENS)
                .call(
                    McpArguments(
                        mapOf("name" to "nyc/sales/region_filters", "display_name" to "Region filters", "parameters" to emptyList<Any>()),
                    ),
                    ctx,
                ) as Map<*, *>

        answer["id"] shouldBe setId.toString()
        verify(exactly = 1) { repository.listChildFolders(workspaceId, null, any()) }
    }

    @Test
    fun `parameter_sets_list answers the folders and the sets of one level with the returned count`() {
        every { sets.listChildFolders(workspaceId, any(), null) } returns listOf(ParameterSetFolder("nyc", "nyc", 2))
        every { sets.listChildSets(workspaceId, any(), null, 0, 50) } returns listOf(ParameterSetVersion(record, released, body))

        val answer = ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS).call(McpArguments(emptyMap()), ctx) as Map<*, *>

        answer["prefix"] shouldBe ""
        answer["returned"] shouldBe 2
        (answer["folders"] as List<*>).map { (it as Map<*, *>)["segment"] } shouldContainExactly listOf("nyc")
        val sets = answer["parameter_sets"] as List<*>
        (sets.single() as Map<*, *>).let {
            it["id"] shouldBe setId.toString()
            it["name"] shouldBe record.name
            it["version"] shouldBe 1
            it["status"] shouldBe "RELEASED"
            it["current_version"] shouldBe 1
        }
    }

    @Test
    fun `parameter_sets_list searches name, display name and description when q is given`() {
        every { sets.search(workspaceId, any(), "region", 0, 50) } returns listOf(ParameterSetVersion(record, released, body))
        every { sets.countSearch(workspaceId, any(), "region") } returns 12

        val answer =
            ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS)
                .call(McpArguments(mapOf("q" to "region")), ctx) as Map<*, *>

        answer["q"] shouldBe "region"
        answer["returned"] shouldBe 1
        // `total` is the full match count, so an agent sees that `limit` truncated (a search has no offset).
        answer["total"] shouldBe 12
        // A search is not a level: the browse-only `folders`/`prefix` keys are absent.
        answer.containsKey("folders") shouldBe false
        answer.containsKey("prefix") shouldBe false
        val hits = answer["parameter_sets"] as List<*>
        (hits.single() as Map<*, *>).let {
            it["id"] shouldBe setId.toString()
            it["name"] shouldBe record.name
            it["version"] shouldBe 1
            it["status"] shouldBe "RELEASED"
            it["current_version"] shouldBe 1
        }
        verify(exactly = 0) { sets.listChildFolders(any(), any(), any()) }
        verify(exactly = 0) { sets.listChildSets(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `parameter_sets_list browses and ignores q while prefix is present`() {
        // `prefix: ""` is the root, and the browse value stays the existing `string("prefix")`
        // (null for empty) — what the `has` switch changes is that this is a BROWSE, never a search.
        every { sets.listChildFolders(workspaceId, any(), null) } returns listOf(ParameterSetFolder("nyc", "nyc", 2))
        every { sets.listChildSets(workspaceId, any(), null, 0, 50) } returns listOf(ParameterSetVersion(record, released, body))

        val answer =
            ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS)
                .call(McpArguments(mapOf("prefix" to "", "q" to "region")), ctx) as Map<*, *>

        answer["prefix"] shouldBe ""
        (answer["folders"] as List<*>).size shouldBe 1
        verify(exactly = 0) { sets.search(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { sets.countSearch(any(), any(), any()) }
    }

    @Test
    fun `parameter_sets_list treats a blank q as no search and browses the roots`() {
        every { sets.listChildFolders(workspaceId, any(), null) } returns emptyList()
        every { sets.listChildSets(workspaceId, any(), null, 0, 50) } returns emptyList()

        val answer =
            ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS)
                .call(McpArguments(mapOf("q" to "   ")), ctx) as Map<*, *>

        answer["prefix"] shouldBe ""
        answer.containsKey("q") shouldBe false
        verify(exactly = 0) { sets.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `parameter_sets_list hands its limit to the search page`() {
        every { sets.search(workspaceId, any(), "region", 0, 5) } returns emptyList()
        every { sets.countSearch(workspaceId, any(), "region") } returns 0

        ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS)
            .call(McpArguments(mapOf("q" to "region", "limit" to 5)), ctx)

        verify(exactly = 1) { sets.search(workspaceId, any(), "region", 0, 5) }
    }

    @Test
    fun `parameter_sets_list refuses a non-string q as invalid params`() {
        val refusal =
            shouldThrow<McpError> {
                ParameterSetsListTool(sets, McpFixtures.EVERYTHING_LENS)
                    .call(McpArguments(mapOf("q" to 5)), ctx)
            }
        refusal.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
    }

    @Test
    fun `parameter_sets_update writes through the service under the expected hash and answers the new version`() {
        val draft = released.copy(version = 2, status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v2")
        every { sets.write(workspaceId, setId, any(), "hash-v1", McpFixtures.USER, WriteSurface.MCP) } returns
            ParameterSetVersion(record, draft, body)

        val answer =
            ParameterSetsUpdateTool(sets, ParametersConfig(), McpFixtures.EVERYTHING_LENS)
                .call(
                    McpArguments(
                        mapOf(
                            "id" to setId.toString(),
                            "expected_hash" to "hash-v1",
                            "name" to record.name,
                            "display_name" to "Region filters",
                            "description" to "The region cascade",
                            "parameters" to emptyList<Any>(),
                        ),
                    ),
                    ctx,
                ) as Map<*, *>

        answer["version"] shouldBe 2
        answer["status"] shouldBe "DRAFT"
        answer["body_hash"] shouldBe "hash-v2"
    }

    @Test
    fun `parameter_sets_purge_draft purges under the expected hash - an unknown id is the catalogued not-found`() {
        every { repository.findRecord(workspaceId, setId) } returns record
        every { sets.purgeDraft(workspaceId, setId, "hash-v2") } returns co.datapipelines.parameters.Purged.Version
        val tool = ParameterSetsPurgeDraftTool(sets, repository)

        val answer = tool.call(McpArguments(mapOf("id" to setId.toString(), "expected_hash" to "hash-v2")), ctx) as Map<*, *>
        answer["purged"] shouldBe true

        val unknown = UUID.randomUUID()
        every { repository.findRecord(workspaceId, unknown) } returns null
        shouldThrow<DatapipelinesException> {
            tool.call(McpArguments(mapOf("id" to unknown.toString(), "expected_hash" to "hash-v2")), ctx)
        }.code shouldBe ParameterErrorCodes.NOT_FOUND
    }

    private val evaluator = mockk<ParameterEvaluator>()
    private val learnings = mockk<McpToolLearnings>()

    private fun evaluateTool(lens: PromoterLens = McpFixtures.EVERYTHING_LENS) =
        ParameterSetsEvaluateTool(sets, repository, evaluator, learnings, templates, lens)

    @Test
    fun `parameter_sets_evaluate - an explicit version never falls back to the served one (C26)`() {
        every { sets.findVersion(workspaceId, any(), setId, 3) } returns null

        val refusal =
            shouldThrow<DatapipelinesException> {
                evaluateTool().call(McpArguments(mapOf("id" to setId.toString(), "version" to 3)), ctx)
            }

        refusal.code shouldBe ParameterErrorCodes.NOT_FOUND
        refusal.details["version"] shouldBe 3
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
    }

    @Test
    fun `parameter_sets_evaluate - the served version is read through the lens, a hidden set is not-found`() {
        every { repository.findCurrent(workspaceId, setId) } returns ParameterSetVersion(record, released, body)

        val refusal =
            shouldThrow<DatapipelinesException> {
                evaluateTool(narrowed("acme/other/set")).call(McpArguments(mapOf("id" to setId.toString())), ctx)
            }

        refusal.code shouldBe ParameterErrorCodes.NOT_FOUND
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
    }

    @Test
    fun `parameter_sets_evaluate - a DRAFT set whose DRAFT pin postdates this key's last render is template_unrendered`() {
        val pinned: ParameterSetBody =
            ParameterSetJson.mapper.treeToValue(
                ParameterSetJson.mapper.readTree(
                    """{"display_name":"Region filters","parameters":[{"name":"state","label":"State","type":"STRING",""" +
                        """"kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                        """"source":{"template":{"id":"test/states.sql","version":1},"datasource":"crm"}}]}""",
                ),
                ParameterSetBody::class.java,
            )
        val draft = released.copy(version = 2, status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v2")
        every { sets.findVersion(workspaceId, any(), setId, 2) } returns ParameterSetVersion(record, draft, pinned)
        val templateDraft =
            mockk<TemplateVersion> {
                every { status } returns PipelineVersionStatus.DRAFT
                every { updatedAt } returns at
            }
        every { templates.lookupVersion(workspaceId, "test/states.sql", 1) } returns templateDraft
        every { learnings.lastRenderAt(any(), any()) } returns null

        val refusal =
            shouldThrow<DatapipelinesException> {
                evaluateTool().call(McpArguments(mapOf("id" to setId.toString(), "version" to 2)), ctx)
            }

        refusal.code shouldBe ParameterErrorCodes.EVALUATE_TEMPLATE_UNRENDERED
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
    }
}
