package co.datapipelines.mcp

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
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
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set tools over mocked collaborators, the two rules the 194d merge's security pass
 * found missing: a promoter never sees a draft pointer (178 — `pipelines_get`'s shape), and the
 * 094 new-root check reads the WHOLE level, not its first root.
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
}
