package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.ui.ParameterSetsBrowseModel.Companion.PAGE_SIZE
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * #374 — the Parameter Sets browse model: the catalog's flat list over the lensed reads (the rail's tree is
 * `/api/v1/parameter-sets/tree` since #466). The service is a mock so each test pins what the model ASKS (the
 * lens it passes, the needle, the page) and what it renders from the answer; the lens's own narrowing is
 * `ParameterSetServiceIntegrationTest`'s subject.
 */
class ParameterSetsBrowseModelTest {
    private val sets = mockk<ParameterSetService>()
    private val browse = ParameterSetsBrowseModel(sets)
    private val ws = ParameterSetsUiFixtures.workspaceId
    private val everything = LensedView(ReadLens.Everything, ReadLens.Everything)
    private val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(setOf("acme/geo_filters")))

    private fun leaf(
        name: String,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        version: Int = 1,
    ) = ParameterSetsUiFixtures.version(ParameterSetsUiFixtures.record(UUID.randomUUID(), name = name), version, status)

    @Suppress("UNCHECKED_CAST")
    private fun ExtendedModelMap.leaves() = get("parameterSets") as List<ParameterSetsBrowseModel.ParameterSetLeafView>

    @Test
    fun `every read passes the CALLER's lens, never a widened one`() {
        every { sets.search(ws, narrowed.parameterSets, null, 0, PAGE_SIZE) } returns emptyList()
        every { sets.countSearch(ws, narrowed.parameterSets, null) } returns 0
        browse.fillWrapper(ExtendedModelMap(), ws, narrowed, null, 0)
        verify(exactly = 1) { sets.search(ws, narrowed.parameterSets, null, 0, PAGE_SIZE) }
        verify(exactly = 0) { sets.search(any(), ReadLens.Everything, any(), any(), any()) }
    }

    @Test
    fun `the catalog is a flat paged list rooted at the stable id, negative offsets clamp to the first page`() {
        every { sets.search(ws, ReadLens.Everything, null, 0, PAGE_SIZE) } returns List(PAGE_SIZE) { leaf("acme/s$it") }
        every { sets.countSearch(ws, ReadLens.Everything, null) } returns PAGE_SIZE + 1
        val m = ExtendedModelMap()
        browse.fillWrapper(m, ws, everything, null, -7) shouldBe
            ParameterSetsBrowseModel.WRAPPER_VIEW
        m["searching"] shouldBe true
        m["rootId"] shouldBe ParameterSetsBrowseModel.CATALOG_ROOT_ID
        m["offset"] shouldBe 0
        m["hasMore"] shouldBe true
        m.leaves().size shouldBe PAGE_SIZE
    }

    @Test
    fun `the catalog's last page has no more`() {
        every { sets.search(ws, ReadLens.Everything, null, PAGE_SIZE, PAGE_SIZE) } returns listOf(leaf("acme/last"))
        every { sets.countSearch(ws, ReadLens.Everything, null) } returns PAGE_SIZE + 1
        val m = ExtendedModelMap()
        browse.fillWrapper(m, ws, everything, null, PAGE_SIZE)
        m["hasMore"] shouldBe false
    }

    @Test
    fun `the catalog's search asks the service for the trimmed query and renders it back (#415)`() {
        every { sets.search(ws, ReadLens.Everything, "geo", 0, PAGE_SIZE) } returns listOf(leaf("acme/geo_filters"))
        every { sets.countSearch(ws, ReadLens.Everything, "geo") } returns 1
        val m = ExtendedModelMap()
        browse.fillWrapper(m, ws, everything, "  geo  ", 0)
        m["q"] shouldBe "geo"
        m.leaves().single().name shouldBe "acme/geo_filters"
        m["total"] shouldBe 1
    }

    @Test
    fun `the search needle is trimmed and length-bounded before it reaches the service (#415)`() {
        val bounded = "x".repeat(ParameterSetsBrowseModel.MAX_QUERY_LENGTH)
        every { sets.search(ws, ReadLens.Everything, bounded, 0, PAGE_SIZE) } returns emptyList()
        every { sets.countSearch(ws, ReadLens.Everything, bounded) } returns 0
        val m = ExtendedModelMap()
        browse.fillSearch(m, ws, everything, "  " + "x".repeat(500) + "  ", 0)
        m["q"] shouldBe bounded
    }

    /**
     * #490 — the service refuses a needle over its bound on REST and MCP; the box must never meet that
     * refusal. Over the REAL service (its repository mocked) a 500-character paste renders a search for
     * the truncated term instead of an error.
     */
    @Test
    fun `a typed overflow never errors the box - it truncates to the bound the real service accepts (#490)`() {
        val repository = mockk<ParameterSetRepository>()
        val bounded = "x".repeat(ParameterSetService.MAX_QUERY_LENGTH)
        every { repository.searchAll(ws, bounded, 0, PAGE_SIZE) } returns emptyList()
        every { repository.countSearchAll(ws, bounded) } returns 0
        val real = ParameterSetsBrowseModel(ParameterSetService(repository, mockk(), mockk(), mockk(), mockk()))
        val m = ExtendedModelMap()

        real.fillSearch(m, ws, everything, "x".repeat(500), 0) shouldBe
            ParameterSetsBrowseModel.SEARCH_VIEW
        m["q"] shouldBe bounded
        // The box's bound IS the shared one (#504b) — the alias cannot drift from the rule.
        ParameterSetsBrowseModel.MAX_QUERY_LENGTH shouldBe co.datapipelines.pipeline.SearchNeedle.MAX_LENGTH
    }
}
