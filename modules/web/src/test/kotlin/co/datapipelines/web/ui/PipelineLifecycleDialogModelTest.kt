package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.web.anonymousActors
import io.mockk.every
import io.mockk.mockk
import co.datapipelines.pipeline.ExclusiveDraftTemplates
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The dialog MODELS' guard arms (ui-screens §4.3d, 102) — the deterministic coverage the
 * controller suite's happy paths only touch through mocks: each wrong-shape target raises
 * its catalogued code, the purge-entity's refusal branches carry their own prose, and the
 * switch dialog's eligibility follows the posture flag (D63's own reader).
 */
class PipelineLifecycleDialogModelTest {
    private val repository = mockk<PipelineRepository>()
    private val templates = mockk<TemplateVersionStatuses>()
    private val exclusive = mockk<ExclusiveDraftTemplates>()
    private val runStats = mockk<PipelineRunStats>()

    private val model =
        PipelineLifecycleDialogModel(
            repository,
            templates,
            exclusive,
            runStats,
            anonymousActors(),
            AuthoringGuard(enabled = true),
        )

    @Test
    fun `release - a missing pipeline is the catalogued not-found`() {
        every { repository.findById(any(), any()) } returns null
        val e = shouldThrow<DatapipelinesException> { model.release(WS, ID) }
        e.code shouldBe PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND
    }

    @Test
    fun `purge - a released target is last_release and a missing one is not-found`() {
        recordOf(current = 1)
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.findVersionDetail(any(), any(), any()) } returns
            detail(status = RELEASED)
        shouldThrow<DatapipelinesException> { model.purge(WS, ID, 1) }.code shouldBe
            PipelineErrorCodes.Versioning.LAST_RELEASE

        every { repository.findVersionDetail(any(), any(), any()) } returns null
        shouldThrow<DatapipelinesException> { model.purge(WS, ID, 9) }.code shouldBe
            PipelineErrorCodes.Validation.PIPELINE_VERSION_NOT_FOUND
    }

    @Test
    fun `discard - a draft target is not_released and the fallback preview follows the versions`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 2)
        every { repository.findVersionDetail(any(), any(), any()) } returns detail(status = DRAFT)
        shouldThrow<DatapipelinesException> { model.discard(WS, ID, 2) }.code shouldBe
            PipelineErrorCodes.Versioning.NOT_RELEASED
    }

    @Test
    fun `discard - discarding the current release with no survivor names the 503 shape`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.findVersionDetail(any(), any(), any()) } returns detail(status = RELEASED)
        every { repository.findLiveParentsPinningVersion(any(), any(), any()) } returns emptyList()
        every { repository.listVersions(any(), any()) } returns emptyList()

        val dialog = model.discard(WS, ID, 1)
        dialog.isCurrent shouldBe true
        dialog.fallback!!.contains("503") shouldBe true
    }

    @Test
    fun `restore - a non-discarded target is not_discarded`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.findVersionDetail(any(), any(), any()) } returns detail(status = RELEASED)
        shouldThrow<DatapipelinesException> { model.restore(WS, ID, 1) }.code shouldBe
            PipelineErrorCodes.Versioning.NOT_DISCARDED
    }

    @Test
    fun `purge entity - a non-sole-draft shape is the last_release refusal with no button`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.listVersions(any(), any()) } returns
            listOf(
                co.datapipelines.pipeline.PipelineVersionRecord(ID, 1, RELEASED, "h1", T0, USER),
                co.datapipelines.pipeline.PipelineVersionRecord(ID, 2, DRAFT, "h2", T0, USER),
            )
        every { runStats.totalRuns(any()) } returns 0

        val dialog = model.purgeEntity(WS, ID)
        dialog.refusal!!.code shouldBe "pipeline.version.last_release"
        dialog.exclusiveTemplates shouldBe emptyList()
    }

    @Test
    fun `switch - eligibility follows the posture - drafts only under development`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.listVersions(any(), any()) } returns
            listOf(
                co.datapipelines.pipeline.PipelineVersionRecord(ID, 2, DRAFT, "h2", T0, USER),
                co.datapipelines.pipeline.PipelineVersionRecord(ID, 1, RELEASED, "h1", T0, USER),
            )

        val dev = model.switch(WS, ID)
        dev.options.first { it.version == 2 }.eligible shouldBe true

        val hardened =
            PipelineLifecycleDialogModel(
                repository,
                templates,
                exclusive,
                runStats,
                anonymousActors(),
                AuthoringGuard(enabled = false),
            )
        val hard = hardened.switch(WS, ID)
        hard.options.first { it.version == 2 }.eligible shouldBe false
        hard.options.first { it.version == 1 }.eligible shouldBe true
    }

    @Test
    fun `switch - a discarded option is never eligible`() {
        every { repository.findByIdAnyStatus(any(), any()) } returns recordOf(current = 1)
        every { repository.listVersions(any(), any()) } returns
            listOf(co.datapipelines.pipeline.PipelineVersionRecord(ID, 3, DISCARDED, "h3", T0, USER))

        model.switch(WS, ID).options.single().eligible shouldBe false
    }

    private fun recordOf(current: Int?) =
        PipelineRecord(ID, "test/probe", "probe", "", USER, current, T0, T0)

    private fun detail(status: PipelineVersionStatus) =
        co.datapipelines.pipeline.PipelineVersionDetail(
            pipelineId = ID,
            version = 1,
            status = status,
            bodyHash = "h",
            createdAt = T0,
            createdBy = USER,
        )

    private companion object {
        val WS: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val T0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    }
}
