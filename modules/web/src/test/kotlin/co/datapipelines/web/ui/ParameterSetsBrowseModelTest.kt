package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetFolder
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.ui.ParameterSetsBrowseModel.Companion.PAGE_SIZE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * #374 — the Parameter Sets browse model: the rail's tree level and the catalog's flat list over the SAME lensed
 * reads. The service is a mock so each test pins what the model ASKS (the lens it passes, the prefix, the page) and
 * what it renders from the answer; the lens's own narrowing is `ParameterSetServiceIntegrationTest`'s subject.
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

    @Suppress("UNCHECKED_CAST")
    private fun ExtendedModelMap.folders() = get("folders") as List<ParameterSetsBrowseModel.ParameterSetFolderView>

    @Test
    fun `the root level holds folders only, even when the service has sets directly under it`() {
        every { sets.listChildFolders(ws, ReadLens.Everything, null) } returns listOf(ParameterSetFolder("acme", "acme", 2))
        every { sets.listChildSets(ws, ReadLens.Everything, null, 0, PAGE_SIZE) } returns listOf(leaf("top"))
        every { sets.countChildSets(ws, ReadLens.Everything, null) } returns 1
        val m = ExtendedModelMap()
        browse.fillLevel(m, ws, everything, null, 0) shouldBe ParameterSetsBrowseModel.LEVEL_VIEW
        m.leaves().shouldBeEmpty()
        m.folders().single().path shouldBe "acme"
        m["levelId"] shouldBe ParameterSetsBrowseModel.NAV_ROOT_ID
    }

    @Test
    fun `a prefix level renders its folders then its leaves at their listed version, drafts marked`() {
        every { sets.listChildFolders(ws, ReadLens.Everything, "acme") } returns listOf(ParameterSetFolder("acme/geo", "geo", 1))
        every { sets.listChildSets(ws, ReadLens.Everything, "acme", 0, PAGE_SIZE) } returns
            listOf(leaf("acme/a"), leaf("acme/b", PipelineVersionStatus.DRAFT, 3))
        every { sets.countChildSets(ws, ReadLens.Everything, "acme") } returns 2
        val m = ExtendedModelMap()
        browse.fillLevel(m, ws, everything, "acme", 0)
        m.leaves().map { it.segment to it.released } shouldBe listOf("a" to true, "b" to false)
        m.leaves().last().version shouldBe 3
        m["hasMore"] shouldBe false
    }

    @Test
    fun `a pager offset asks the service for THAT page and hasMore follows the truthful total`() {
        every { sets.listChildFolders(any(), any(), any()) } returns emptyList()
        every { sets.listChildSets(ws, ReadLens.Everything, "acme", PAGE_SIZE, PAGE_SIZE) } returns List(PAGE_SIZE) { leaf("acme/s$it") }
        every { sets.countChildSets(ws, ReadLens.Everything, "acme") } returns PAGE_SIZE * 2 + 1
        val m = ExtendedModelMap()
        browse.fillLevel(m, ws, everything, "acme", PAGE_SIZE)
        m["offset"] shouldBe PAGE_SIZE
        m["hasMore"] shouldBe true
        m["total"] shouldBe PAGE_SIZE * 2 + 1
    }

    @Test
    fun `an illegal prefix is an ordinary empty level, and no read is made for it`() {
        val m = ExtendedModelMap()
        browse.fillLevel(m, ws, everything, "../etc/%", 0) shouldBe ParameterSetsBrowseModel.LEVEL_VIEW
        m.leaves().shouldBeEmpty()
        m.folders().shouldBeEmpty()
        // A prefix carries no trailing slash (the service appends it): the slashed spelling is not a name either.
        browse.fillLevel(ExtendedModelMap(), ws, everything, "acme/", 0)
        verify(exactly = 0) { sets.listChildFolders(any(), any(), any()) }
        verify(exactly = 0) { sets.listChildSets(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `every read passes the CALLER's lens, never a widened one`() {
        every { sets.listChildFolders(ws, narrowed.parameterSets, null) } returns emptyList()
        every { sets.listChildSets(ws, narrowed.parameterSets, null, 0, PAGE_SIZE) } returns emptyList()
        every { sets.countChildSets(ws, narrowed.parameterSets, null) } returns 0
        every { sets.listAll(ws, narrowed.parameterSets, 0, PAGE_SIZE) } returns emptyList()
        every { sets.countAll(ws, narrowed.parameterSets) } returns 0
        browse.fillLevel(ExtendedModelMap(), ws, narrowed, null, 0)
        browse.fillCatalog(ExtendedModelMap(), ws, narrowed, 0)
        verify(exactly = 0) { sets.listAll(any(), ReadLens.Everything, any(), any()) }
        verify(exactly = 0) { sets.listChildFolders(any(), ReadLens.Everything, any()) }
    }

    @Test
    fun `the catalog is a flat paged list rooted at the stable id, negative offsets clamp to the first page`() {
        every { sets.listAll(ws, ReadLens.Everything, 0, PAGE_SIZE) } returns List(PAGE_SIZE) { leaf("acme/s$it") }
        every { sets.countAll(ws, ReadLens.Everything) } returns PAGE_SIZE + 1
        val m = ExtendedModelMap()
        browse.fillCatalog(m, ws, everything, -7) shouldBe ParameterSetsBrowseModel.CATALOG_VIEW
        m["rootId"] shouldBe ParameterSetsBrowseModel.CATALOG_ROOT_ID
        m["offset"] shouldBe 0
        m["hasMore"] shouldBe true
        m.leaves().size shouldBe PAGE_SIZE
    }

    @Test
    fun `the catalog's last page has no more`() {
        every { sets.listAll(ws, ReadLens.Everything, PAGE_SIZE, PAGE_SIZE) } returns listOf(leaf("acme/last"))
        every { sets.countAll(ws, ReadLens.Everything) } returns PAGE_SIZE + 1
        val m = ExtendedModelMap()
        browse.fillCatalog(m, ws, everything, PAGE_SIZE)
        m["hasMore"] shouldBe false
    }

    @Test
    fun `a level id is stable per prefix, distinct across prefixes and scopes, and never contains the raw prefix`() {
        val a = ParameterSetsBrowseModel.levelId("nav", "acme/geo")
        a shouldBe ParameterSetsBrowseModel.levelId("nav", "acme/geo")
        (a == ParameterSetsBrowseModel.levelId("nav", "acme/other")) shouldBe false
        (a == ParameterSetsBrowseModel.levelId("page", "acme/geo")) shouldBe false
        a shouldStartWith "params-level-nav-"
        a.contains("acme") shouldBe false
        ParameterSetsBrowseModel.levelId("nav", null) shouldBe ParameterSetsBrowseModel.NAV_ROOT_ID
    }

    @Test
    fun `the nav stamp names the workspace and whether the lens narrows`() {
        ParameterSetsBrowseModel.navStamp("acme", everything) shouldBe "acme|all"
        ParameterSetsBrowseModel.navStamp("acme", narrowed) shouldBe "acme|lens"
    }
}
