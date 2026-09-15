package co.datapipelines.web.ui

import co.datapipelines.pipeline.AuthoringGuard
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
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.anonymousActors
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
    private val usage = mockk<TemplateUsageService>()

    private val model =
        PipelineLifecycleDialogModel(
            repository,
            templates,
            exclusive,
            runStats,
            anonymousActors(),
            AuthoringGuard(enabled = true),
            usage,
        )

    @Test
    fun `release - a missing pipeline is the catalogued not-found`() {
        every { repository.findById(any(), any()) } returns null
        val e = shouldThrow<DatapipelinesException> { model.release(WS, ID) }
        e.code shouldBe PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND
    }

    @Test
    fun `release - hasChecks follows the draft body's checks key`() {
        every { repository.findById(any(), any()) } returns recordOf(current = 1)
        every { repository.findDraftDetail(any(), any()) } returns detail(status = DRAFT)
        every { templates.statusOf(any(), any(), any()) } returns RELEASED

        every { repository.findVersionBody(any(), any(), any()) } returns
            """{"name":"test/probe","display_name":"probe","description":"d","nodes":[],"checks":[""" +
            """{"id":"share_matches","name":"Share","datasource":"h2","sql":"SELECT 1","expected":{"kind":"value","value":1}}]}"""
        model.release(WS, ID).hasChecks shouldBe true

        every { repository.findVersionBody(any(), any(), any()) } returns
            """{"name":"test/probe","display_name":"probe","description":"d","nodes":[]}"""
        model.release(WS, ID).hasChecks shouldBe false

        // A body that does not parse is no checks AND no pins — the dialog opens and the
        // POST's re-validation is what answers for the malformed draft.
        every { repository.findVersionBody(any(), any(), any()) } returns "not json"
        model.release(WS, ID).hasChecks shouldBe false
    }

    /** A draft body pinning `t1@1` from two nodes and `t2@1` from a third. */
    private val twoTemplateBody =
        """{"name":"test/probe","display_name":"probe","description":"d","nodes":[""" +
            """{"id":"a","type":"DQL","source":"h2","template":{"id":"test/t1.sql","version":1},"depends_on":[]},""" +
            """{"id":"b","type":"DQL","source":"h2","template":{"id":"test/t1.sql","version":1},"depends_on":[]},""" +
            """{"id":"c","type":"DQL","source":"h2","template":{"id":"test/t2.sql","version":1},"depends_on":[]}]}"""

    private fun pin(
        pipelineId: UUID,
        version: Int = 1,
    ) = co.datapipelines.pipeline.TemplatePin(pipelineId, "test/other", version, DRAFT, "n", 1)

    @Test
    fun `release - 142 - a DRAFT pin is a cascade row with its other-pinner count, not a blocking one`() {
        every { repository.findById(any(), any()) } returns recordOf(current = 1)
        every { repository.findDraftDetail(any(), any()) } returns detail(status = DRAFT)
        every { repository.findVersionBody(any(), any(), any()) } returns twoTemplateBody
        every { templates.statusOf(any(), "test/t1.sql", 1) } returns DRAFT
        every { templates.statusOf(any(), "test/t2.sql", 1) } returns RELEASED
        val other = UUID.randomUUID()
        // t1@1's used-by: THIS pipeline (twice — two nodes) and one other pipeline (twice too).
        every { usage.usedBy(WS, "test/t1.sql", 1) } returns
            TemplateUsageService.UsedBy(
                "test/t1.sql",
                1,
                listOf(pin(ID), pin(ID), pin(other), pin(other)),
                pipelineCount = 2,
            )

        val dialog = model.release(WS, ID)

        // Two nodes, one pin: the list is per VERSION, and the shared pin counts one other pipeline.
        dialog.pins.map { it.label } shouldBe listOf("test/t1.sql@1", "test/t2.sql@1")
        dialog.draftPins.map { it.label } shouldBe listOf("test/t1.sql@1")
        dialog.draftPins.single().otherPinners shouldBe 1
        dialog.blockingPins shouldBe emptyList()
        // The RELEASED pin never asked the used-by service (no count to show).
        dialog.pins.last().otherPinners shouldBe 0
    }

    @Test
    fun `release - 142 - a MISSING or DISCARDED pin still blocks and offers no cascade`() {
        every { repository.findById(any(), any()) } returns recordOf(current = 1)
        every { repository.findDraftDetail(any(), any()) } returns detail(status = DRAFT)
        every { repository.findVersionBody(any(), any(), any()) } returns twoTemplateBody
        every { templates.statusOf(any(), "test/t1.sql", 1) } returns DRAFT
        every { templates.statusOf(any(), "test/t2.sql", 1) } returns null
        every { usage.usedBy(WS, "test/t1.sql", 1) } returns
            TemplateUsageService.UsedBy("test/t1.sql", 1, listOf(pin(ID)), pipelineCount = 1)

        val missing = model.release(WS, ID)
        missing.blockingPins.map { it.label } shouldBe listOf("test/t2.sql@1")
        missing.draftPins.map { it.label } shouldBe listOf("test/t1.sql@1")
        missing.draftPins.single().otherPinners shouldBe 0

        every { templates.statusOf(any(), "test/t2.sql", 1) } returns DISCARDED
        val discarded = model.release(WS, ID)
        discarded.blockingPins.map { it.statusLabel } shouldBe listOf("DISCARDED")
    }

    @Test
    fun `release - 142 - a used-by miss answers zero other pinners rather than refusing to open`() {
        every { repository.findById(any(), any()) } returns recordOf(current = 1)
        every { repository.findDraftDetail(any(), any()) } returns detail(status = DRAFT)
        every { repository.findVersionBody(any(), any(), any()) } returns twoTemplateBody
        every { templates.statusOf(any(), any(), any()) } returns DRAFT
        every { usage.usedBy(any(), any(), any()) } throws
            DatapipelinesException(PipelineErrorCodes.Template.NOT_FOUND, "gone", emptyMap())

        model.release(WS, ID).draftPins.map { it.otherPinners } shouldBe listOf(0, 0)
    }

    /**
     * 142 review — ONLY the disappearing-template race is a known zero. Any other failure of
     * the used-by lookup (the database, a programming error, a different refusal) must reach
     * the dialog's existing error path rather than be shown as "shared by nobody".
     */
    @Test
    fun `release - 142 - an unexpected used-by failure propagates instead of reading as zero other pinners`() {
        every { repository.findById(any(), any()) } returns recordOf(current = 1)
        every { repository.findDraftDetail(any(), any()) } returns detail(status = DRAFT)
        every { repository.findVersionBody(any(), any(), any()) } returns twoTemplateBody
        every { templates.statusOf(any(), any(), any()) } returns DRAFT

        every { usage.usedBy(any(), any(), any()) } throws IllegalStateException("connection refused")
        shouldThrow<IllegalStateException> { model.release(WS, ID) }.message shouldBe "connection refused"

        every { usage.usedBy(any(), any(), any()) } throws
            DatapipelinesException(PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND, "another refusal", emptyMap())
        shouldThrow<DatapipelinesException> { model.release(WS, ID) }.code shouldBe PipelineErrorCodes.Validation.PIPELINE_NOT_FOUND
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
                usage,
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

        model
            .switch(WS, ID)
            .options
            .single()
            .eligible shouldBe false
    }

    private fun recordOf(current: Int?) = PipelineRecord(ID, "test/probe", "probe", "", USER, current, T0, T0)

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
