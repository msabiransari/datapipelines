package co.datapipelines.web.release

import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReleaseCallerOutput
import co.datapipelines.pipeline.ReleaseCheckGate
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * #877 §E.3 (iii) — the pipeline release cases that `PipelineReleaseServiceTest` (and two of
 * `PipelineServiceIntegrationTest`) drove through the retired native entry point, moved one-to-one to the coordinator:
 * the same stubs over [NativeReleaseWorld], the same assertions, released through [ReleaseCoordinator.release] — the only
 * pipeline release there is. Each case's KDoc names the case it replaces; the handback's case map lists them.
 *
 * Where the native case observed a port the coordinator no longer calls, the assertion moved to the coordinator's own
 * seam, and the case says which: the #142 cascade's template write is [TemplateReleaseParticipant] over
 * `TemplateReleaseService.releaseExact` (the native `TemplateReleaser` port), and the transaction boundary is the
 * coordinator's metadata transaction (the native `transactions` operations).
 */
class PipelineReleaseCoordinatorCasesTest {
    private val world = NativeReleaseWorld()
    private val workspaceId = world.workspaceId
    private val pipelineId = world.pipelineId
    private val userId = world.userId
    private val pipelines = world.pipelines

    private fun release(
        releases: co.datapipelines.pipeline.PipelineReleaseService = world.service(),
        expectedHash: String = "draft-hash",
        overrideChecksReason: String? = null,
        releasePinnedTemplates: Boolean = false,
    ) = world.coordinator(releases).release(world.request(expectedHash, overrideChecksReason, releasePinnedTemplates))

    private fun draft(
        body: String,
        hash: String = "draft-hash",
    ) {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns world.draftDetail(hash)
        every { pipelines.findVersionBody(workspaceId, pipelineId, NativeReleaseWorld.VERSION) } returns body
        every { world.validator.validateOrThrow(any(), workspaceId, any()) } answers { firstArg() }
    }

    private fun status(
        id: String,
        version: Int,
        status: PipelineVersionStatus?,
    ) {
        every { world.templates.statusOf(workspaceId, id, version) } returns status
    }

    private fun flipsTo(result: PipelineRepository.Released = world.releasedFlip()) {
        every {
            pipelines.releaseDraft(
                workspaceId,
                pipelineId,
                NativeReleaseWorld.NAME,
                "M",
                "d",
                "draft-hash",
                userId,
                NativeReleaseWorld.VERSION,
            )
        } returns result
    }

    private fun neverFlipped() = verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any(), 2) }

    /** Replaces `PipelineReleaseServiceTest` "release and purge refuse when authoring is disabled" (its release half). */
    @Test
    fun `release refuses when authoring is disabled`() {
        val error = shouldThrow<DatapipelinesException> { release(world.service(authoring = false)) }

        error.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        verify { pipelines wasNot Called }
    }

    /** Replaces "release without a draft is the catalogued not_draft". */
    @Test
    fun `release without a draft is the catalogued not_draft`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null

        shouldThrow<DatapipelinesException> { release() }.code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
    }

    /** Replaces "release re-runs save-time validation on the draft body". */
    @Test
    fun `release re-runs save-time validation on the draft body`() {
        draft(DRAFT_BODY)
        every { world.validator.validateOrThrow(any(), workspaceId, any()) } throws
            PipelineValidationException(ValidationResult(listOf(ValidationFailure("pipeline.validation.cycle_detected", "nodes", "cycle"))))

        shouldThrow<PipelineValidationException> { release() }.code shouldBe "pipeline.validation.cycle_detected"
        neverFlipped()
    }

    /**
     * Replaces "a DRAFT template pin blocks release - templates lock first", and `PipelineServiceIntegrationTest`
     * "release refuses while a pinned template version is still a draft" (nothing flipped: the draft is still there).
     */
    @Test
    fun `a DRAFT template pin blocks release - templates lock first`() {
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.DRAFT)

        val error = shouldThrow<DatapipelinesException> { release() }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_DEPENDENCY_NOT_RELEASED
        blockersOf(error) shouldBe listOf(Triple("test/t.sql", 2, "DRAFT/not_included"))
        neverFlipped()
    }

    /** Replaces "all pins released - the flip happens with the draft body's metadata". */
    @Test
    fun `all pins released - the flip happens with the draft body's metadata`() {
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()

        val released = release()

        released.record.currentVersion shouldBe 2
        released.version.status shouldBe PipelineVersionStatus.RELEASED
    }

    /**
     * Replaces `PipelineServiceIntegrationTest` "a node with no template pin does not block the release": a CALCULATOR
     * node has no template to ask about, and the statuses port answers MISSING for its empty default ref, so a release
     * that still asked would refuse.
     */
    @Test
    fun `a node with no template pin does not block the release`() {
        draft(CALCULATOR_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        status("", 0, null)
        flipsTo()

        release().version.status shouldBe PipelineVersionStatus.RELEASED
    }

    /** Replaces "a SQL caller node releases recorded or not_observed - never refused for a missing run". */
    @Test
    fun `a SQL caller node releases recorded or not_observed - never refused for a missing run`() {
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo(world.releasedFlip().copy(version = world.releasedFlip().version.copy(callerOutputJson = null)))

        release().callerOutput shouldBe ReleaseCallerOutput.NOT_OBSERVED

        flipsTo(world.releasedFlip().copy(version = world.releasedFlip().version.copy(callerOutputJson = CALLER_RECORD)))

        release().callerOutput shouldBe ReleaseCallerOutput.RECORDED
    }

    /** Replaces "no caller node releases the word none". */
    @Test
    fun `no caller node releases the word none`() {
        draft(TEMPDB_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()

        release().callerOutput shouldBe ReleaseCallerOutput.NONE
    }

    /** Replaces "a script caller releases declared - the record never overrides the contract". */
    @Test
    fun `a script caller releases declared - the record never overrides the contract`() {
        draft(SCRIPT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo(world.releasedFlip().copy(version = world.releasedFlip().version.copy(callerOutputJson = CALLER_RECORD)))

        release().callerOutput shouldBe ReleaseCallerOutput.DECLARED
    }

    /** Replaces "a pin citing a retired fact warns and the release proceeds". */
    @Test
    fun `a pin citing a retired fact warns and the release proceeds`() {
        val marks = mockk<TemplateReviewMarks>()
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()
        val pin = TemplateRef("test/t.sql", 2)
        every { marks.retiredCitations(workspaceId, listOf(pin)) } returns
            mapOf(pin to listOf(RetiredFactCitation("fact-old", "superseded", "fact-new")))

        val released = release(world.service(reviewMarks = marks))

        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 2
        val warning = released.warnings.single()
        warning.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NEEDS_REVIEW
        warning.template shouldBe "test/t.sql"
        warning.version shouldBe 2
        warning.message shouldContain "fact-old — superseded by fact-new"
        verify(exactly = 1) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any(), 2) }
    }

    /** Replaces "a clean pin carries no warning, and a refused release never reads the marks". */
    @Test
    fun `a clean pin carries no warning, and a refused release never reads the marks`() {
        val marks = mockk<TemplateReviewMarks>()
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()
        every { marks.retiredCitations(workspaceId, any()) } returns emptyMap()
        val withMarks = world.service(reviewMarks = marks)

        release(withMarks).warnings shouldBe emptyList()

        status("test/t.sql", 2, PipelineVersionStatus.DRAFT)
        shouldThrow<DatapipelinesException> { release(withMarks) }
        verify(exactly = 1) { marks.retiredCitations(any(), any()) }
    }

    /** Replaces "a stale hash on release is the conflict carrying the draft's current hash". */
    @Test
    fun `a stale hash on release is the conflict carrying the draft's current hash`() {
        draft(DRAFT_BODY, hash = "current-hash")
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        every { pipelines.releaseDraft(workspaceId, pipelineId, any(), any(), any(), "stale", any(), 2) } returns null

        val error = shouldThrow<DatapipelinesException> { release(expectedHash = "stale") }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        error.details["current_body_hash"] shouldBe "current-hash"
        error.details["current_status"] shouldBe "DRAFT"
    }

    private fun outcome(verdict: CheckRunVerdict) =
        CheckRunOutcome("revenue_matches", "Revenue matches the ledger", CheckExpectation(kind = "value", value = 1.0), "1", verdict, null)

    private fun checkedDraft() {
        draft(CHECKED_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()
    }

    private fun gated(verdict: CheckRunVerdict) = world.service(checkGate = { _, _, _, _, _ -> listOf(outcome(verdict)) })

    /** Replaces "a failing check refuses the release with pipeline check failed and the details". */
    @Test
    fun `a failing check refuses the release with pipeline check failed and the details`() {
        checkedDraft()

        val error = shouldThrow<DatapipelinesException> { release(gated(CheckRunVerdict.FAIL)) }

        error.code shouldBe PipelineErrorCodes.Check.FAILED
        @Suppress("UNCHECKED_CAST")
        val checks = error.details["checks"] as List<Map<String, Any?>>
        checks.single()["check_id"] shouldBe "revenue_matches"
        checks.single()["verdict"] shouldBe "fail"
        neverFlipped()
    }

    /** Replaces "an error verdict refuses too - a check that could not run is not a pass". */
    @Test
    fun `an error verdict refuses too - a check that could not run is not a pass`() {
        checkedDraft()

        shouldThrow<DatapipelinesException> { release(gated(CheckRunVerdict.ERROR)) }.code shouldBe PipelineErrorCodes.Check.FAILED
    }

    /** Replaces "the override reason releases past failing checks and rides the result for the audit". */
    @Test
    fun `the override reason releases past failing checks and rides the result for the audit`() {
        checkedDraft()

        val released = release(gated(CheckRunVerdict.FAIL), overrideChecksReason = "Ledger lags one day; verified by hand.")

        released.checksOverridden shouldBe listOf("revenue_matches")
        released.checksOverrideReason shouldBe "Ledger lags one day; verified by hand."
    }

    /** Replaces "an override reason shorter than the minimum still refuses". */
    @Test
    fun `an override reason shorter than the minimum still refuses`() {
        checkedDraft()

        shouldThrow<DatapipelinesException> { release(gated(CheckRunVerdict.FAIL), overrideChecksReason = "ok") }
            .code shouldBe PipelineErrorCodes.Check.FAILED
        neverFlipped()
    }

    /** Replaces "all checks passing releases clean - and a check-less draft never calls the gate". */
    @Test
    fun `all checks passing releases clean - and a check-less draft never calls the gate`() {
        checkedDraft()
        var gateCalls = 0
        val gated =
            world.service(
                checkGate =
                    ReleaseCheckGate { _, _, _, _, _ ->
                        gateCalls++
                        listOf(outcome(CheckRunVerdict.PASS))
                    },
            )

        release(gated).checksOverridden shouldBe emptyList()
        gateCalls shouldBe 1

        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns DRAFT_BODY
        gateCalls = 0
        release(gated)
        gateCalls shouldBe 0
    }

    private fun twoDraftPins() {
        draft(TWO_TEMPLATE_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.DRAFT)
        status("test/u.sql", 1, PipelineVersionStatus.DRAFT)
        flipsTo()
    }

    /** Replaces "the flag defaults to false - a DRAFT pin refuses exactly as before and names every pin that is not released". */
    @Test
    fun `the flag defaults to false - a DRAFT pin refuses exactly as before and names every pin that is not released`() {
        twoDraftPins()

        val error = shouldThrow<DatapipelinesException> { release() }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_DEPENDENCY_NOT_RELEASED
        error.message shouldBe
            "Release blocked: 2 dependencies are not released. Release them first, or include the drafts in this release."
        blockersOf(error) shouldBe
            listOf(Triple("test/t.sql", 2, "DRAFT/not_included"), Triple("test/u.sql", 1, "DRAFT/not_included"))
        error.details["blockers_truncated"] shouldBe false
        world.templatesReleased shouldBe emptyList()
        neverFlipped()
    }

    /**
     * Replaces "with the flag every DRAFT pin is released once, templates first, and the result lists them". The template
     * write is the coordinator's template participant (`TemplateReleaseService.releaseExact`), recorded by the world.
     */
    @Test
    fun `with the flag every DRAFT pin is released once, templates first, and the result lists them`() {
        twoDraftPins()
        val order = mutableListOf<String>()
        world.onTemplateRelease = { order += "template:${it.id}@${it.version}" }
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, NativeReleaseWorld.NAME, "M", "d", "draft-hash", userId, 2)
        } answers {
            order += "pipeline"
            world.releasedFlip()
        }

        val released = release(releasePinnedTemplates = true)

        order shouldBe listOf("template:test/t.sql@2", "template:test/u.sql@1", "pipeline")
        released.templatesReleased shouldBe listOf(TemplateRef("test/t.sql", 2), TemplateRef("test/u.sql", 1))
        released.version.status shouldBe PipelineVersionStatus.RELEASED
    }

    /** Replaces "with the flag a pin that is already RELEASED is left alone and the result lists nothing". */
    @Test
    fun `with the flag a pin that is already RELEASED is left alone and the result lists nothing`() {
        draft(DRAFT_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        flipsTo()

        val released = release(releasePinnedTemplates = true)

        world.templatesReleased shouldBe emptyList()
        released.templatesReleased shouldBe emptyList()
    }

    /** Replaces "a MISSING or DISCARDED pin is not releasable - the flag refuses as before, before any template is touched". */
    @Test
    fun `a MISSING or DISCARDED pin is not releasable - the flag refuses as before, before any template is touched`() {
        twoDraftPins()
        status("test/u.sql", 1, null)

        val missing = shouldThrow<DatapipelinesException> { release(releasePinnedTemplates = true) }
        missing.code shouldBe PipelineErrorCodes.Versioning.RELEASE_DEPENDENCY_NOT_RELEASED
        blockersOf(missing) shouldBe listOf(Triple("test/u.sql", 1, "MISSING/missing"))

        status("test/u.sql", 1, PipelineVersionStatus.DISCARDED)
        val discarded = shouldThrow<DatapipelinesException> { release(releasePinnedTemplates = true) }
        discarded.code shouldBe PipelineErrorCodes.Versioning.RELEASE_DEPENDENCY_NOT_RELEASED
        blockersOf(discarded) shouldBe listOf(Triple("test/u.sql", 1, "DISCARDED/discarded"))

        world.templatesReleased shouldBe emptyList()
        neverFlipped()
    }

    /** Replaces "a template's own refusal propagates unmapped and the pipeline never flips". */
    @Test
    fun `a template's own refusal propagates unmapped and the pipeline never flips`() {
        twoDraftPins()
        val refusal =
            DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFLICT,
                message = "Template was modified by someone else after you loaded it.",
                details = emptyMap(),
            )
        world.onTemplateRelease = { if (it.id == "test/u.sql") throw refusal }

        val error = shouldThrow<DatapipelinesException> { release(releasePinnedTemplates = true) }

        error shouldBe refusal
        neverFlipped()
    }

    /**
     * Replaces "the boundary - template releases run INSIDE the metadata transaction, the check gate OUTSIDE". The
     * boundary is the coordinator's metadata transaction ([NativeReleaseWorld.log]'s `begin`).
     */
    @Test
    fun `the boundary - template releases run INSIDE the metadata transaction, the check gate OUTSIDE`() {
        twoDraftPins()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns CHECKED_TWO_TEMPLATE_BODY
        world.onTemplateRelease = { world.log += "template:${it.id}(inside=${world.inside()})" }
        val gate =
            ReleaseCheckGate { _, _, _, _, _ ->
                world.log += "gate(inside=${world.inside()})"
                listOf(outcome(CheckRunVerdict.PASS))
            }
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, NativeReleaseWorld.NAME, "M", "d", "draft-hash", userId, 2)
        } answers {
            world.log += "flip(inside=${world.inside()})"
            world.releasedFlip()
        }

        release(world.service(checkGate = gate), releasePinnedTemplates = true)

        world.log shouldBe
            listOf(
                "gate(inside=false)",
                "begin",
                "template:test/t.sql(inside=true)",
                "template:test/u.sql(inside=true)",
                "flip(inside=true)",
                "commit",
            )
    }

    /** Replaces "atomicity - a stale hash after the templates flipped throws out of the SAME transaction". */
    @Test
    fun `atomicity - a stale hash after the templates flipped throws out of the SAME transaction`() {
        twoDraftPins()
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns world.draftDetail("current-hash")
        every { pipelines.releaseDraft(workspaceId, pipelineId, any(), any(), any(), "stale", any(), 2) } returns null
        world.onTemplateRelease = { world.log += "template:${it.id}" }

        val error = shouldThrow<DatapipelinesException> { release(expectedHash = "stale", releasePinnedTemplates = true) }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        world.log shouldBe listOf("begin", "template:test/t.sql", "template:test/u.sql", "rollback")
    }

    /**
     * #877 §E.3 (v) — the review warnings after the retirement, on BOTH coordinator paths: the compatible release (no
     * plan) and the planned one (the preview's tree hash). Three nodes pin two templates (`test/t.sql@2` twice, then
     * `test/u.sql@1`), both citing a retired fact: the marks are asked once with the DISTINCT pins in node order, and the
     * result carries one warning per distinct pin in that order — `templatePins` is the only source of both.
     */
    @Test
    fun `two distinct retired pins warn once each, in node order, on the compatible and the planned path`() {
        val asked = mutableListOf<List<TemplateRef>>()
        val marks =
            TemplateReviewMarks { _, pins ->
                asked.add(pins.toList())
                pins.associateWith { listOf(RetiredFactCitation("fact-old", "superseded", "fact-new")) }
            }
        draft(TWO_TEMPLATE_BODY)
        status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
        status("test/u.sql", 1, PipelineVersionStatus.RELEASED)
        flipsTo()
        val pins = listOf(TemplateRef("test/t.sql", 2), TemplateRef("test/u.sql", 1))

        val compatible = world.coordinator(world.service(reviewMarks = marks)).release(world.request())
        val planned = world.coordinator(world.service(reviewMarks = marks)).release(world.request(plan = world.plan()))

        listOf(compatible, planned).forEach { released ->
            released.warnings.map { it.code }.toSet() shouldBe setOf(PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NEEDS_REVIEW)
            released.warnings.map { TemplateRef(it.template, it.version) } shouldBe pins
        }
        asked shouldBe listOf(pins, pins)
    }

    private fun blockersOf(error: DatapipelinesException): List<Triple<String, Int, String>> {
        @Suppress("UNCHECKED_CAST")
        val blockers = error.details["blockers"] as List<Map<String, Any?>>
        blockers.map { it["family"] }.toSet() shouldBe setOf("template")
        return blockers.map { Triple(it["name"] as String, it["version"] as Int, "${it["status"]}/${it["reason"]}") }
    }

    internal companion object {
        const val DRAFT_BODY = NativeReleaseBodies.DRAFT
        const val TEMPDB_BODY = NativeReleaseBodies.TEMPDB
        const val SCRIPT_BODY = NativeReleaseBodies.SCRIPT
        const val CHECKED_BODY = NativeReleaseBodies.CHECKED
        const val TWO_TEMPLATE_BODY = NativeReleaseBodies.TWO_TEMPLATES
        val CHECKED_TWO_TEMPLATE_BODY = NativeReleaseBodies.CHECKED_TWO_TEMPLATES
        const val CALCULATOR_BODY = NativeReleaseBodies.CALCULATOR
        const val CALLER_RECORD = NativeReleaseBodies.CALLER_RECORD
    }
}
