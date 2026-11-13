package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The release steps this module owns, called directly over mocked repositories: [PipelineReleaseService.prepare] (the
 * draft read, the save-time validation and the 140 release-check gate), [PipelineReleaseService.revalidateStrict],
 * [PipelineReleaseService.flip] and [PipelineReleaseService.finish].
 *
 * Since #877 §E.3 retired the native release entry point, the release coordinator in `web` is the only caller of these
 * steps, and `PipelineReleaseCoordinatorCasesTest` drives them end to end. These cases pin each step's own contract in
 * the module that declares it — the refusal codes and the `details.checks` shape of `pipeline.check.failed` — so the
 * module's suite still fails when a step regresses, whatever wraps it.
 */
class PipelineReleasePrepareTest {
    private val pipelines = mockk<PipelineRepository>()
    private val validator = mockk<PipelineValidator>()
    private val userId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val plainBody =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}]}"""

    /** [plainBody] with one release check — the gate's input. */
    private val checkedBody =
        plainBody.dropLast(1) +
            ""","checks":[{"id":"revenue_matches","name":"Revenue matches the ledger","datasource":"pg",""" +
            """"sql":"SELECT 1","expected":{"kind":"value","value":1.0}}]}"""

    private fun draftDetail(hash: String = "draft-hash") =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = 2,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = hash,
            createdAt = Instant.EPOCH,
            createdBy = userId,
        )

    private fun record() =
        PipelineRecord(
            id = pipelineId,
            name = "test/monthly_revenue",
            displayName = "M",
            description = "d",
            ownerId = userId,
            currentVersion = 2,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    /** Every optional expectation field set, so the refusal's `expected` map is read in full. */
    private val fullExpectation = CheckExpectation(kind = "range", value = 1.0, min = 0.5, max = 2.0, rows = 3L, tolerance = 0.1)

    private fun outcome(
        verdict: CheckRunVerdict,
        expected: CheckExpectation = CheckExpectation(kind = "value", value = 1.0),
    ) = CheckRunOutcome(
        checkId = "revenue_matches",
        name = "Revenue matches the ledger",
        expected = expected,
        observed = "0",
        verdict = verdict,
        message = "observed 0",
    )

    private fun service(gate: ReleaseCheckGate = ReleaseCheckGate.NONE) =
        PipelineReleaseService(
            pipelines,
            validator,
            AuthoringGuard(true),
            checkGate = gate,
            dashboards = mockk(relaxed = true),
            endpointVersions = NoApiVersionPins,
            schedules = NoSchedulePins,
        )

    private fun stubDraft(body: String = checkedBody) {
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findDraftBodySnapshot(workspaceId, pipelineId, 2) } returns
            PipelineRepository.DraftBodySnapshot(body, "snapshot-hash")
        every { validator.validateOrThrow(any(), workspaceId, any()) } answers { firstArg() }
    }

    @Test
    fun `a failing check refuses prepare with pipeline check failed and every detail of the failing check`() {
        stubDraft()
        val asked = mutableListOf<Int>()
        val gated =
            service { _, _, version, _, _ ->
                asked += version
                listOf(outcome(CheckRunVerdict.FAIL, fullExpectation), outcome(CheckRunVerdict.PASS).copy(checkId = "rows_ok"))
            }

        val error = shouldThrow<DatapipelinesException> { gated.prepare(workspaceId, pipelineId, userId, null) }

        error.code shouldBe PipelineErrorCodes.Check.FAILED
        error.details["pipeline_id"] shouldBe pipelineId.toString()
        error.details["version"] shouldBe 2
        error.details["checks"] shouldBe
            listOf(
                mapOf(
                    "check_id" to "revenue_matches",
                    "name" to "Revenue matches the ledger",
                    "expected" to mapOf("kind" to "range", "value" to 1.0, "min" to 0.5, "max" to 2.0, "rows" to 3L, "tolerance" to 0.1),
                    "observed" to "0",
                    "verdict" to "fail",
                    "message" to "observed 0",
                ),
            )
        asked shouldBe listOf(2)
    }

    @Test
    fun `an error verdict refuses too, and an absent expectation field is left out of the details`() {
        stubDraft()
        val error =
            shouldThrow<DatapipelinesException> {
                service { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.ERROR)) }.prepare(workspaceId, pipelineId, userId, null)
            }

        error.code shouldBe PipelineErrorCodes.Check.FAILED
        @Suppress("UNCHECKED_CAST")
        val checks = error.details["checks"] as List<Map<String, Any?>>
        checks.single()["verdict"] shouldBe "error"
        checks.single()["expected"] shouldBe mapOf("kind" to "value", "value" to 1.0)
    }

    @Test
    fun `an override reason of the minimum length prepares past failing checks, trimmed, and a shorter one refuses`() {
        stubDraft()
        val gated = service { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.FAIL)) }
        val reason = "x".repeat(PipelineReleaseService.OVERRIDE_REASON_MIN_CHARS)

        val prepared = gated.prepare(workspaceId, pipelineId, userId, "  $reason  ")

        prepared.checksOverridden shouldBe listOf("revenue_matches")
        prepared.checksOverrideReason shouldBe reason
        // Padding does not count toward the minimum: one character short after the trim still refuses.
        shouldThrow<DatapipelinesException> { gated.prepare(workspaceId, pipelineId, userId, "   ${reason.drop(1)}   ") }
            .code shouldBe PipelineErrorCodes.Check.FAILED
    }

    @Test
    fun `passing checks prepare clean, and a check-less body never asks the gate`() {
        stubDraft()
        var calls = 0
        val gated =
            service { _, _, _, _, _ ->
                calls++
                listOf(outcome(CheckRunVerdict.PASS))
            }

        val prepared = gated.prepare(workspaceId, pipelineId, userId, null)

        prepared.checksOverridden shouldBe emptyList()
        prepared.checksOverrideReason shouldBe null
        prepared.draft.bodyHash shouldBe "snapshot-hash"
        calls shouldBe 1

        stubDraft(plainBody)
        gated.prepare(workspaceId, pipelineId, userId, null).checksOverridden shouldBe emptyList()
        calls shouldBe 1
    }

    @Test
    fun `no draft, or a draft whose body is gone, is not_draft before validation or the gate`() {
        val gate = ReleaseCheckGate { _, _, _, _, _ -> error("the gate must not run") }
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null
        shouldThrow<DatapipelinesException> { service(gate).prepare(workspaceId, pipelineId, userId, null) }
            .code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT

        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail()
        every { pipelines.findDraftBodySnapshot(workspaceId, pipelineId, 2) } returns null
        shouldThrow<DatapipelinesException> { service(gate).prepare(workspaceId, pipelineId, userId, null) }
            .code shouldBe PipelineErrorCodes.Versioning.NOT_DRAFT
        verify(exactly = 0) { validator.validateOrThrow(any(), any(), any()) }
    }

    @Test
    fun `prepare passes the draft-children allowance through, and revalidateStrict never does`() {
        stubDraft(plainBody)

        val prepared = service().prepare(workspaceId, pipelineId, userId, null, allowDraftChildren = true)
        service().revalidateStrict(prepared)

        verify(exactly = 1) { validator.validateOrThrow(prepared.pipeline, workspaceId, true) }
        verify(exactly = 1) { validator.validateOrThrow(prepared.pipeline, workspaceId, false) }
    }

    @Test
    fun `flip refuses a stale hash or a lost flip with the conflict carrying the current draft, and finish carries the override`() {
        stubDraft()
        val gated = service { _, _, _, _, _ -> listOf(outcome(CheckRunVerdict.FAIL)) }
        val prepared = gated.prepare(workspaceId, pipelineId, userId, "Ledger lags one day; verified by hand.")
        every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")

        val stale = shouldThrow<DatapipelinesException> { gated.flip(prepared, "stale", userId) }
        stale.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        stale.details["current_body_hash"] shouldBe "current-hash"
        verify(exactly = 0) { pipelines.releaseDraft(any(), any(), any(), any(), any(), any(), any(), any()) }

        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "snapshot-hash", userId, 2)
        } returns null
        shouldThrow<DatapipelinesException> { gated.flip(prepared, "snapshot-hash", userId) }.code shouldBe
            PipelineErrorCodes.Versioning.VERSION_CONFLICT

        val flipped = PipelineRepository.Released(record(), draftDetail().copy(status = PipelineVersionStatus.RELEASED))
        every {
            pipelines.releaseDraft(workspaceId, pipelineId, "test/monthly_revenue", "M", "d", "snapshot-hash", userId, 2)
        } returns flipped
        val released = gated.finish(prepared, gated.flip(prepared, "snapshot-hash", userId), emptyList(), readWarnings = false)

        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.checksOverridden shouldBe listOf("revenue_matches")
        released.checksOverrideReason shouldBe "Ledger lags one day; verified by hand."
        released.warnings shouldBe emptyList()
        released.callerOutput shouldBe ReleaseCallerOutput.NOT_OBSERVED
    }
}
