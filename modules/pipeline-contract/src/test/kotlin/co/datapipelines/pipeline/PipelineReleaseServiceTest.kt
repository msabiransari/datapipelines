package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import java.time.Instant
import java.util.UUID

/**
 * The release/discard service's §5.3 preconditions over mocked repositories: the not-draft
 * refusal, the §6 templates-lock-first pin rule, and the stale-hash conflict — the guard
 * ordering the repository cannot express on its own.
 *
 * Moved here from `modules/web` by 056 with the service itself: release is a PIPELINE-aggregate
 * use case and belongs in the module that owns the aggregate. The only wiring change is that
 * `templates` is now the [TemplateVersionStatuses] port rather than `TemplateRepository`
 * directly — `pipeline-contract` cannot depend on `templates` (the arrow runs the other way), so
 * the aggregation layer supplies the one fact the release gate asks for. Not one assertion
 * changed.
 */
class PipelineReleaseServiceTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateVersionStatuses>()
    private val validator = mockk<PipelineValidator>()
    private val service = PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(true))

    @Test
    fun `release and purge refuse when authoring is disabled`() {
        // versioning §5.5: release and the draft purge are authoring actions — a receiver refuses.
        val receiver = PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(false))

        val release =
            shouldThrow<DatapipelinesException> {
                receiver.release(UUID.randomUUID(), UUID.randomUUID(), "hash", UUID.randomUUID())
            }
        release.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED

        val purge =
            shouldThrow<DatapipelinesException> {
                receiver.purge(UUID.randomUUID(), UUID.randomUUID(), "hash")
            }
        purge.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        io.mockk.verify { pipelines wasNot Called }
    }

    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun pipelineRecord() =
        co.datapipelines.pipeline.PipelineRecord(
            id = pipelineId,
            name = "test/monthly_revenue",
            displayName = "M",
            description = "d",
            ownerId = userId,
            currentVersion = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    /** A deserializable body pinning one template at v2 — what release re-validates. */
    private val draftBody =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}]}"""

    private fun draftDetail(hash: String = "draft-hash") =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = hash,
            createdAt = Instant.EPOCH,
            createdBy = userId,
        )

    @Test
    fun `release without a draft is the catalogued not_draft`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "h", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
    }

    @Test
    fun `release re-runs save-time validation on the draft body`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } throws
            co.datapipelines.pipeline.PipelineValidationException(
                co.datapipelines.pipeline.ValidationResult(
                    listOf(co.datapipelines.pipeline.ValidationFailure("pipeline.validation.cycle_detected", "nodes", "cycle")),
                ),
            )

        val error =
            shouldThrow<co.datapipelines.pipeline.PipelineValidationException> {
                service.release(workspaceId, pipelineId, "draft-hash", userId)
            }

        error.code shouldBe "pipeline.validation.cycle_detected"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a DRAFT template pin blocks release - templates lock first`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.DRAFT

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "draft-hash", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        error.details["template_id"] shouldBe "test/t.sql"
        error.details["template_version"] shouldBe 2
        error.details["template_status"] shouldBe "DRAFT"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `all pins released - the flip happens with the draft body's metadata`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns
            PipelineRepository.Released(
                co.datapipelines.pipeline.PipelineRecord(
                    id = pipelineId,
                    name = "test/monthly_revenue",
                    displayName = "M",
                    description = "d",
                    ownerId = userId,
                    currentVersion = 2,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )

        val released = service.release(workspaceId, pipelineId, "draft-hash", userId)

        released.record.currentVersion shouldBe 2
        released.version.status shouldBe PipelineVersionStatus.RELEASED
    }

    /** The flip's answer for [draftBody] — v2 RELEASED, the pointer moved. */
    private fun releasedFlip() =
        PipelineRepository.Released(
            pipelineRecord().copy(currentVersion = 2),
            draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
        )

    /**
     * 7e (transform-nodes design §8.2) — pinning a version that cites a retired fact WARNS and
     * the release PROCEEDS: the flip happens exactly as for a clean pin, and the result carries
     * one `pipeline.release.template_needs_review` naming the pin, the retired fact and its
     * successor. The falsification (the handback's F-3) makes the warning a refusal and this
     * case goes red at the release call.
     */
    @Test
    fun `a pin citing a retired fact warns and the release proceeds`() {
        val marks = mockk<TemplateReviewMarks>()
        val withMarks =
            PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(true), reviewMarks = marks)
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns releasedFlip()
        val pin = TemplateRef("test/t.sql", 2)
        every { marks.retiredCitations(workspaceId, listOf(pin)) } returns
            mapOf(pin to listOf(RetiredFactCitation("fact-old", "superseded", "fact-new")))

        val released = withMarks.release(workspaceId, pipelineId, "draft-hash", userId)

        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 2
        released.warnings.size shouldBe 1
        val warning = released.warnings.single()
        warning.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NEEDS_REVIEW
        warning.template shouldBe "test/t.sql"
        warning.version shouldBe 2
        warning.message shouldContain "fact-old — superseded by fact-new"
        verify(exactly = 1) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a clean pin carries no warning, and a refused release never reads the marks`() {
        val marks = mockk<TemplateReviewMarks>()
        val withMarks =
            PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(true), reviewMarks = marks)
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns releasedFlip()
        every { marks.retiredCitations(workspaceId, any()) } returns emptyMap()

        withMarks.release(workspaceId, pipelineId, "draft-hash", userId).warnings shouldBe emptyList()

        // A DRAFT pin refuses BEFORE the flip; the warning read belongs to a release that happened.
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.DRAFT
        shouldThrow<DatapipelinesException> { withMarks.release(workspaceId, pipelineId, "draft-hash", userId) }
        verify(exactly = 1) { marks.retiredCitations(any(), any()) }
    }

    @Test
    fun `a stale hash on release is the conflict carrying the draft's current hash`() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every { pipelines.releaseDraft(workspaceId, pipelineId, any(), any(), any(), "stale", any()) } returns null

        val error = shouldThrow<DatapipelinesException> { service.release(workspaceId, pipelineId, "stale", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        error.details["current_body_hash"] shouldBe "current-hash"
        error.details["current_status"] shouldBe "DRAFT"
    }

    // -------------------------------------------------------------------------------------
    // 140 — the release-check gate (§13.17)
    // -------------------------------------------------------------------------------------

    /** A draft body carrying one check — the gate's input. */
    private val checkedDraftBody =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}],""" +
            """"checks":[{"id":"revenue_matches","name":"Revenue matches the ledger","datasource":"pg",""" +
            """"sql":"SELECT 1","expected":{"kind":"value","value":1.0}}]}"""

    private fun outcome(verdict: CheckRunVerdict) =
        CheckRunOutcome(
            checkId = "revenue_matches",
            name = "Revenue matches the ledger",
            expected = CheckExpectation(kind = "value", value = 1.0),
            observed = "1",
            verdict = verdict,
            message = null,
        )

    private fun stubSuccessfulFlip() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns checkedDraftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.RELEASED
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns
            PipelineRepository.Released(
                pipelineRecord().copy(currentVersion = 2),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )
    }

    @Test
    fun `a failing check refuses the release with pipeline check failed and the details`() {
        stubSuccessfulFlip()
        val gated =
            PipelineReleaseService(
                pipelines,
                templates,
                validator,
                AuthoringGuard(true),
                checkGate = ReleaseCheckGate { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.FAIL)) },
            )

        val error = shouldThrow<DatapipelinesException> { gated.release(workspaceId, pipelineId, "draft-hash", userId) }

        error.code shouldBe PipelineErrorCodes.Check.FAILED
        @Suppress("UNCHECKED_CAST")
        val checks = error.details["checks"] as List<Map<String, Any?>>
        checks.single()["check_id"] shouldBe "revenue_matches"
        checks.single()["verdict"] shouldBe "fail"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an error verdict refuses too - a check that could not run is not a pass`() {
        stubSuccessfulFlip()
        val gated =
            PipelineReleaseService(
                pipelines,
                templates,
                validator,
                AuthoringGuard(true),
                checkGate = ReleaseCheckGate { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.ERROR)) },
            )

        shouldThrow<DatapipelinesException> { gated.release(workspaceId, pipelineId, "draft-hash", userId) }
            .code shouldBe PipelineErrorCodes.Check.FAILED
    }

    @Test
    fun `the override reason releases past failing checks and rides the result for the audit`() {
        stubSuccessfulFlip()
        val gated =
            PipelineReleaseService(
                pipelines,
                templates,
                validator,
                AuthoringGuard(true),
                checkGate = ReleaseCheckGate { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.FAIL)) },
            )

        val released =
            gated.release(workspaceId, pipelineId, "draft-hash", userId, overrideChecksReason = "Ledger lags one day; verified by hand.")

        released.checksOverridden shouldBe listOf("revenue_matches")
        released.checksOverrideReason shouldBe "Ledger lags one day; verified by hand."
    }

    @Test
    fun `an override reason shorter than the minimum still refuses`() {
        stubSuccessfulFlip()
        val gated =
            PipelineReleaseService(
                pipelines,
                templates,
                validator,
                AuthoringGuard(true),
                checkGate = ReleaseCheckGate { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.FAIL)) },
            )

        shouldThrow<DatapipelinesException> { gated.release(workspaceId, pipelineId, "draft-hash", userId, overrideChecksReason = "ok") }
            .code shouldBe PipelineErrorCodes.Check.FAILED
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `all checks passing releases clean - and a check-less draft never calls the gate`() {
        stubSuccessfulFlip()
        var gateCalls = 0
        val gated =
            PipelineReleaseService(
                pipelines,
                templates,
                validator,
                AuthoringGuard(true),
                checkGate =
                    ReleaseCheckGate { _, _, _, _, _ ->
                        gateCalls++
                        listOf(outcome(CheckRunVerdict.PASS))
                    },
            )

        gated.release(workspaceId, pipelineId, "draft-hash", userId).checksOverridden shouldBe emptyList()
        gateCalls shouldBe 1

        // The plain body (no checks) releases exactly as before: the gate is never consulted.
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        gateCalls = 0
        gated.release(workspaceId, pipelineId, "draft-hash", userId)
        gateCalls shouldBe 0
    }

    // -------------------------------------------------------------------------------------
    // 142 — the release cascade (§5.3 precondition 2, releasePinnedTemplates)
    // -------------------------------------------------------------------------------------

    /**
     * A draft body pinning `test/t.sql@2` from TWO nodes and `test/u.sql@1` from a third —
     * the shape that proves a shared pin is released once, and that the cascade walks every
     * pin, not the first.
     */
    private val twoTemplateDraftBody =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]},""" +
            """{"id":"n2","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]},""" +
            """{"id":"n3","type":"DQL","source":"pg","template":{"id":"test/u.sql","version":1},"depends_on":[]}]}"""

    /** A releaser that records every call and answers the reference it was asked for. */
    private class RecordingReleaser(
        private val onRelease: (TemplateRef) -> Unit = {},
    ) : TemplateReleaser {
        val released = mutableListOf<TemplateRef>()

        override fun release(
            workspaceId: UUID,
            templateId: String,
            version: Int,
            actor: UUID,
        ): TemplateRef {
            val ref = TemplateRef(templateId, version)
            onRelease(ref)
            released += ref
            return ref
        }
    }

    private fun stubTwoDraftPins() {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns twoTemplateDraftBody
        every { validator.validateOrThrow(any(), workspaceId) } answers { firstArg() }
        every { templates.statusOf(workspaceId, "test/t.sql", 2) } returns PipelineVersionStatus.DRAFT
        every { templates.statusOf(workspaceId, "test/u.sql", 1) } returns PipelineVersionStatus.DRAFT
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } returns
            PipelineRepository.Released(
                pipelineRecord().copy(currentVersion = 2),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )
    }

    private fun cascading(
        releaser: TemplateReleaser,
        checkGate: ReleaseCheckGate = ReleaseCheckGate.NONE,
        transactions: org.springframework.transaction.support.TransactionOperations? = null,
    ) = if (transactions == null) {
        PipelineReleaseService(pipelines, templates, validator, AuthoringGuard(true), checkGate = checkGate, templateReleaser = releaser)
    } else {
        PipelineReleaseService(
            pipelines,
            templates,
            validator,
            AuthoringGuard(true),
            checkGate = checkGate,
            templateReleaser = releaser,
            transactions = transactions,
        )
    }

    @Test
    fun `the flag defaults to false - a DRAFT pin refuses exactly as before and names every pin that is not released`() {
        stubTwoDraftPins()
        val releaser = RecordingReleaser()

        val error =
            shouldThrow<DatapipelinesException> { cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId) }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        error.message shouldBe "Template 'test/t.sql' version 2 is not released; release the template first."
        error.details["template_id"] shouldBe "test/t.sql"
        error.details["template_version"] shouldBe 2
        error.details["template_status"] shouldBe "DRAFT"
        // 142: the refusal ALSO lists every non-RELEASED pin (deduplicated) so a client can
        // decide whether a retry with release_pinned_templates=true would succeed.
        @Suppress("UNCHECKED_CAST")
        val pins = error.details["pins_not_released"] as List<Map<String, Any?>>
        pins.map { it["template_id"] to it["template_version"] } shouldBe listOf("test/t.sql" to 2, "test/u.sql" to 1)
        pins.map { it["template_status"] } shouldBe listOf("DRAFT", "DRAFT")
        releaser.released shouldBe emptyList()
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `with the flag every DRAFT pin is released once, templates first, and the result lists them`() {
        stubTwoDraftPins()
        val order = mutableListOf<String>()
        val releaser = RecordingReleaser { order += "template:${it.id}@${it.version}" }
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } answers {
            order += "pipeline"
            PipelineRepository.Released(
                pipelineRecord().copy(currentVersion = 2),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )
        }

        val released = cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)

        // The shared pin (two nodes, one version) is released ONCE; the flip is last.
        order shouldBe listOf("template:test/t.sql@2", "template:test/u.sql@1", "pipeline")
        released.templatesReleased shouldBe listOf(TemplateRef("test/t.sql", 2), TemplateRef("test/u.sql", 1))
        released.version.status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `with the flag a pin that is already RELEASED is left alone and the result lists nothing`() {
        stubSuccessfulFlip()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns draftBody
        val releaser = RecordingReleaser()

        val released = cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)

        releaser.released shouldBe emptyList()
        released.templatesReleased shouldBe emptyList()
    }

    @Test
    fun `a MISSING or DISCARDED pin is not releasable - the flag refuses as before, before any template is touched`() {
        stubTwoDraftPins()
        every { templates.statusOf(workspaceId, "test/u.sql", 1) } returns null
        val releaser = RecordingReleaser()

        val missing =
            shouldThrow<DatapipelinesException> {
                cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)
            }
        missing.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        missing.details["template_id"] shouldBe "test/u.sql"
        missing.details["template_status"] shouldBe "MISSING"

        every { templates.statusOf(workspaceId, "test/u.sql", 1) } returns PipelineVersionStatus.DISCARDED
        val discarded =
            shouldThrow<DatapipelinesException> {
                cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)
            }
        discarded.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        discarded.details["template_status"] shouldBe "DISCARDED"

        // The pin scan refuses BEFORE the cascade starts: test/t.sql (a releasable DRAFT) was
        // never released on the way to the refusal.
        releaser.released shouldBe emptyList()
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a template's own refusal propagates unmapped and the pipeline never flips`() {
        stubTwoDraftPins()
        val refusal =
            DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFLICT,
                message = "Template was modified by someone else after you loaded it.",
                details = emptyMap(),
            )
        val releaser = RecordingReleaser { if (it.id == "test/u.sql") throw refusal }

        val error =
            shouldThrow<DatapipelinesException> {
                cascading(releaser).release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)
            }

        error shouldBe refusal
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * A [org.springframework.transaction.support.TransactionOperations] that records the
     * boundary: what ran inside `execute`, and whether the action threw (which a
     * `TransactionTemplate` turns into a rollback).
     */
    private class RecordingTransactions : org.springframework.transaction.support.TransactionOperations {
        var inside = false
        var threw: Throwable? = null
        val log = mutableListOf<String>()

        override fun <T : Any?> execute(action: TransactionCallback<T>): T? {
            log += "begin"
            inside = true
            try {
                return action.doInTransaction(SimpleTransactionStatus())
            } catch (e: RuntimeException) {
                threw = e
                log += "rollback"
                throw e
            } finally {
                inside = false
            }
        }
    }

    @Test
    fun `the boundary - template releases run INSIDE the metadata transaction, the check gate OUTSIDE`() {
        stubTwoDraftPins()
        every { pipelines.findVersionBody(workspaceId, pipelineId, 2) } returns
            twoTemplateDraftBody.dropLast(1) +
            ""","checks":[{"id":"revenue_matches","name":"Revenue matches","datasource":"pg",""" +
            """"sql":"SELECT 1","expected":{"kind":"value","value":1.0}}]}"""
        val transactions = RecordingTransactions()
        val releaser = RecordingReleaser { transactions.log += "template:${it.id}(inside=${transactions.inside})" }
        val gate =
            ReleaseCheckGate { _, _, _, _, _ ->
                transactions.log += "gate(inside=${transactions.inside})"
                listOf(outcome(CheckRunVerdict.PASS))
            }
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "draft-hash", userId)
        } answers {
            transactions.log += "flip(inside=${transactions.inside})"
            PipelineRepository.Released(
                pipelineRecord().copy(currentVersion = 2),
                draftDetail().copy(status = PipelineVersionStatus.RELEASED, version = 2),
            )
        }

        cascading(releaser, checkGate = gate, transactions = transactions)
            .release(workspaceId, pipelineId, "draft-hash", userId, releasePinnedTemplates = true)

        transactions.log shouldBe
            listOf(
                "gate(inside=false)",
                "begin",
                "template:test/t.sql(inside=true)",
                "template:test/u.sql(inside=true)",
                "flip(inside=true)",
            )
    }

    @Test
    fun `atomicity - a stale hash after the templates flipped throws out of the SAME transaction`() {
        stubTwoDraftPins()
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")
        every { pipelines.releaseDraft(workspaceId, pipelineId, any(), any(), any(), "stale", any()) } returns null
        val transactions = RecordingTransactions()
        val releaser = RecordingReleaser { transactions.log += "template:${it.id}" }

        val error =
            shouldThrow<DatapipelinesException> {
                cascading(releaser, transactions = transactions)
                    .release(workspaceId, pipelineId, "stale", userId, releasePinnedTemplates = true)
            }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        // Both template releases happened INSIDE the transaction that then rolled back — so
        // a TransactionTemplate over the metadata datasource undoes them with the flip.
        transactions.log shouldBe listOf("begin", "template:test/t.sql", "template:test/u.sql", "rollback")
        transactions.threw shouldBe error
    }

    @Test
    fun `purge deletes the version or the entity, or refuses - never clobbers`() {
        // 101: the draft verb is PURGE — the row (and its executions) are gone either way;
        // the flip-to-DISCARDED branch is withdrawn.
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns
            co.datapipelines.pipeline.PurgeOutcome
                .VersionPurged(3, pipelineRecord())
        val purged = service.purge(workspaceId, pipelineId, "draft-hash")
        (purged as PipelineReleaseService.Purged.Version).executionsDeleted shouldBe 3

        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns
            co.datapipelines.pipeline.PurgeOutcome
                .EntityPurged(1)
        (service.purge(workspaceId, pipelineId, "draft-hash") as PipelineReleaseService.Purged.Entity)
            .executionsDeleted shouldBe 1

        // The repository's guard failed (0 rows) and no draft exists: not_draft, and the
        // conflict path never fires.
        every { pipelines.purgeDraft(workspaceId, pipelineId, "draft-hash", true) } returns null
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null
        shouldThrow<DatapipelinesException> { service.purge(workspaceId, pipelineId, "draft-hash") }
            .code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
    }
}
