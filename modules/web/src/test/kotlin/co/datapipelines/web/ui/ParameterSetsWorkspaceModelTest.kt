package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.ui.ParameterSetsWorkspaceModel.ParameterSetWorkspaceTab
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * #374 — the Parameter Sets workspace page's version resolution (workspace spec §3.1/§6.2): an explicit admitted
 * version wins and never clamps; the default is the lens-visible current pointer, then the accessible draft, then
 * choose-a-version; every absence the URL can name is ONE 404. Fixtures give each version a DIFFERENT display name,
 * so a wrong body cannot pass as the right label.
 */
class ParameterSetsWorkspaceModelTest {
    private val sets = mockk<ParameterSetService>()
    private val model = ParameterSetsWorkspaceModel(sets)
    private val ws = ParameterSetsUiFixtures.workspaceId
    private val id = UUID.randomUUID()
    private val record = ParameterSetsUiFixtures.record(id, currentVersion = 1)
    private val everything = LensedView(ReadLens.Everything, ReadLens.Everything)
    private val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(setOf(record.name)))

    private fun v(
        n: Int,
        status: PipelineVersionStatus,
    ) = ParameterSetsUiFixtures.version(record, n, status, displayName = "body of v$n")

    /** v1 current release, v2 newer release (not current), v3 draft — the pipelines suite's three-version shape. */
    private fun seedEverything(current: Int? = 1) {
        val rec = record.copy(currentVersion = current)
        val one = v(1, PipelineVersionStatus.RELEASED).copy(record = rec)
        val two = v(2, PipelineVersionStatus.RELEASED).copy(record = rec)
        val three = v(3, PipelineVersionStatus.DRAFT).copy(record = rec)
        every { sets.findWorking(ws, ReadLens.Everything, id) } returns three
        every { sets.listVersions(ws, ReadLens.Everything, id) } returns listOf(three, two, one).map { it.detail }
        every { sets.findVersion(ws, ReadLens.Everything, id, 1) } returns one
        every { sets.findVersion(ws, ReadLens.Everything, id, 2) } returns two
        every { sets.findVersion(ws, ReadLens.Everything, id, 3) } returns three
        every { sets.findVersion(ws, ReadLens.Everything, id, 99) } returns null
    }

    @Test
    fun `an explicit admitted version shows ITS body, never the current one`() {
        seedEverything()
        val r = model.resolve(ws, everything, id, 2)
        r.viewedVersion shouldBe 2
        r.selected!!.body.displayName shouldBe "body of v2"
        r.viewedIsCurrent shouldBe false
        r.viewedLabel shouldBe "v2 · released"
    }

    @Test
    fun `an explicit draft is labelled as a draft`() {
        seedEverything()
        val r = model.resolve(ws, everything, id, 3)
        r.viewedIsDraft shouldBe true
        r.viewedLabel shouldBe "v3 · draft"
    }

    @Test
    fun `an explicit version that does not exist is the house 404, never a clamp to another version`() {
        seedEverything()
        val e = shouldThrow<ResponseStatusException> { model.resolve(ws, everything, id, 99) }
        e.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `with no explicit version the CURRENT pointer wins over a newer draft`() {
        seedEverything()
        val r = model.resolve(ws, everything, id, null)
        r.viewedVersion shouldBe 1
        r.selected!!.body.displayName shouldBe "body of v1"
        r.viewedIsCurrent shouldBe true
        r.viewedLabel shouldBe "v1 · released · current"
        r.draft!!.version shouldBe 3
    }

    @Test
    fun `with no current pointer and an accessible draft the draft is shown, labelled`() {
        seedEverything(current = null)
        val r = model.resolve(ws, everything, id, null)
        r.viewedVersion shouldBe 3
        r.viewedIsDraft shouldBe true
        r.currentVisible.shouldBeNull()
        r.viewedLabel shouldBe "v3 · draft"
    }

    @Test
    fun `with no current pointer and no draft the page is choose-a-version over the admitted history`() {
        val two = v(2, PipelineVersionStatus.RELEASED).copy(record = record.copy(currentVersion = null))
        every { sets.findWorking(ws, ReadLens.Everything, id) } returns two
        every { sets.listVersions(ws, ReadLens.Everything, id) } returns listOf(two.detail)
        val r = model.resolve(ws, everything, id, null)
        r.hasSelectedBody shouldBe false
        r.viewedLabel shouldBe ParameterSetsWorkspaceModel.NO_VERSION_SELECTED
        r.versions.map { it.version } shouldBe listOf(2)
        r.versions.single().isViewed shouldBe false
    }

    @Test
    fun `a set the lens hides is the SAME 404 an unknown id is`() {
        every { sets.findWorking(ws, narrowed.parameterSets, id) } returns null
        val hidden = shouldThrow<ResponseStatusException> { model.resolve(ws, narrowed, id, null) }
        val unknown = UUID.randomUUID()
        every { sets.findWorking(ws, narrowed.parameterSets, unknown) } returns null
        val absent = shouldThrow<ResponseStatusException> { model.resolve(ws, narrowed, unknown, null) }
        hidden.statusCode shouldBe HttpStatus.NOT_FOUND
        hidden.reason shouldBe absent.reason
    }

    @Test
    fun `under a narrowing lens a draft number asked by explicit version is the same 404 an unknown number is`() {
        val one = v(1, PipelineVersionStatus.RELEASED)
        every { sets.findWorking(ws, narrowed.parameterSets, id) } returns one
        every { sets.listVersions(ws, narrowed.parameterSets, id) } returns listOf(one.detail)
        every { sets.findVersion(ws, narrowed.parameterSets, id, 3) } returns null
        every { sets.findVersion(ws, narrowed.parameterSets, id, 99) } returns null
        val draftAsked = shouldThrow<ResponseStatusException> { model.resolve(ws, narrowed, id, 3) }
        val unknownAsked = shouldThrow<ResponseStatusException> { model.resolve(ws, narrowed, id, 99) }
        draftAsked.reason shouldBe unknownAsked.reason
        draftAsked.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `a promoter's page carries no draft pointer and lists only the released versions`() {
        val one = v(1, PipelineVersionStatus.RELEASED)
        every { sets.findWorking(ws, narrowed.parameterSets, id) } returns one
        every { sets.listVersions(ws, narrowed.parameterSets, id) } returns listOf(one.detail)
        every { sets.findVersion(ws, narrowed.parameterSets, id, 1) } returns one
        val r = model.resolve(ws, narrowed, id, null)
        r.draft.shouldBeNull()
        r.versions.map { it.status } shouldBe listOf(PipelineVersionStatus.RELEASED)
        r.viewedVersion shouldBe 1
    }

    @Test
    fun `the tab set is closed - unknown or missing falls to workspace`() {
        ParameterSetWorkspaceTab.fromWire("history") shouldBe ParameterSetWorkspaceTab.HISTORY
        ParameterSetWorkspaceTab.fromWire("workspace") shouldBe ParameterSetWorkspaceTab.WORKSPACE
        ParameterSetWorkspaceTab.fromWire("nonsense") shouldBe ParameterSetWorkspaceTab.WORKSPACE
        ParameterSetWorkspaceTab.fromWire(null) shouldBe ParameterSetWorkspaceTab.WORKSPACE
    }

    @Test
    fun `the version query parameter is positive-integer-or-400, never a silent default`() {
        ParameterSetsWorkspaceModel.parseRequestedVersion(null).shouldBeNull()
        ParameterSetsWorkspaceModel.parseRequestedVersion("").shouldBeNull()
        ParameterSetsWorkspaceModel.parseRequestedVersion("4") shouldBe 4
        listOf("0", "-1", "abc", "1.5").forEach {
            val refused = shouldThrow<ResponseStatusException> { ParameterSetsWorkspaceModel.parseRequestedVersion(it) }
            refused.statusCode shouldBe HttpStatus.BAD_REQUEST
        }
    }
}
