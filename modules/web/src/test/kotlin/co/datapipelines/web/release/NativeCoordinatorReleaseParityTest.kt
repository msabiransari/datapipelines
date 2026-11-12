package co.datapipelines.web.release

import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReleaseCheckGate
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.pipeline.release.PipelineReleaseRequest
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.Test

/**
 * #877 §E.3 (ii) — the retirement's parity: every release case `PipelineReleaseServiceTest` drove through the NATIVE
 * entry point (`PipelineReleaseService.release`), plus the two release cases `PipelineServiceIntegrationTest` asserts on
 * a template pin, driven the same way through the native entry point and through the coordinator's compatibility path,
 * each over a fresh [NativeReleaseWorld] with identical stubs. The two runs must agree on the refusal (type, code,
 * message, every detail) or on the release (record, version, body, checks overridden and the reason, templates released,
 * warnings, caller output), on every repository flip, on every template release and its order, and on the transaction
 * boundary log (`begin`, the gate, each template, the flip, `commit`/`rollback`, the review-marks read).
 *
 * Two fields differ by design and are not compared: `auditedInTransaction` (the coordinator writes the strict audit row
 * inside its transaction) and `dependenciesReleased` (the coordinator names the alias's cascaded templates there too).
 *
 * This class exists to be deleted WITH the native entry point: it is the evidence that the coordinator answers exactly
 * what the native release answered before the native one goes (§E.3 order: inventory, parity, migration, deletion).
 */
class NativeCoordinatorReleaseParityTest {
    private data class Scenario(
        val name: String,
        val request: NativeReleaseWorld.() -> PipelineReleaseRequest = { request() },
        val service: NativeReleaseWorld.() -> PipelineReleaseService = { service() },
        val setup: NativeReleaseWorld.() -> Unit,
    )

    /** What one entry point answered, with every side effect the world observed. */
    private data class Run(
        val outcome: Any,
        val log: List<String>,
        val templates: List<TemplateRef>,
    )

    private data class Refused(
        val type: String,
        val code: String,
        val message: String?,
        val details: Map<String, Any?>,
    )

    private data class Flipped(
        val record: Any,
        val version: Any,
        val bodyJson: String,
        val checksOverridden: List<String>,
        val checksOverrideReason: String?,
        val templatesReleased: List<TemplateRef>,
        val warnings: List<Any>,
        val callerOutput: Any,
    )

    @Test
    fun `the coordinator answers every native release case exactly as the native entry point did`() {
        val report = StringBuilder("scenario | native | coordinator | agree\n")
        val disagreements =
            SCENARIOS.filter { scenario ->
                val native = run(scenario) { world, request -> scenario.service(world).release(request) }
                val coordinated = run(scenario) { world, request -> world.coordinator(scenario.service(world)).release(request) }
                val agree = native == coordinated
                report.append("${scenario.name} | ${label(native)} | ${label(coordinated)} | $agree\n")
                if (!agree) report.append("  native:      $native\n  coordinator: $coordinated\n")
                !agree
            }
        println(report)
        // Non-vacuity: both kinds of answer occur, and every refusal family the native unit test pinned is exercised.
        val outcomes = SCENARIOS.map { label(run(it) { world, request -> world.coordinator(it.service(world)).release(request) }) }
        outcomes.filter { it == "released" } shouldHaveAtLeastSize 10
        outcomes.toSet().filter { it != "released" }.toSet() shouldBe
            setOf(
                "DatapipelinesException:pipeline.authoring.disabled",
                "DatapipelinesException:pipeline.version.not_draft",
                "PipelineValidationException:pipeline.validation.cycle_detected",
                "DatapipelinesException:pipeline.release.dependency_not_released",
                "DatapipelinesException:pipeline.version.conflict",
                "DatapipelinesException:pipeline.check.failed",
                "DatapipelinesException:template.version.conflict",
            )
        disagreements.map { it.name } shouldBe emptyList()
    }

    private fun run(
        scenario: Scenario,
        release: (NativeReleaseWorld, PipelineReleaseRequest) -> PipelineReleaseService.Released,
    ): Run {
        val world = NativeReleaseWorld()
        scenario.setup(world)
        val outcome =
            try {
                flipped(release(world, scenario.request(world)))
            } catch (e: PipelineValidationException) {
                Refused("PipelineValidationException", e.code, e.message, emptyMap())
            } catch (e: DatapipelinesException) {
                Refused("DatapipelinesException", e.code, e.message, e.details)
            }
        return Run(outcome, world.log.toList(), world.templatesReleased.toList())
    }

    private fun PipelineReleaseService.release(request: PipelineReleaseRequest) =
        release(
            request.workspaceId,
            request.pipelineId,
            request.expectedHash,
            request.actor,
            request.overrideChecksReason,
            request.releasePinnedTemplates,
        )

    private fun flipped(released: PipelineReleaseService.Released) =
        Flipped(
            released.record,
            released.version,
            released.bodyJson,
            released.checksOverridden,
            released.checksOverrideReason,
            released.templatesReleased,
            released.warnings,
            released.callerOutput,
        )

    private fun label(run: Run): String =
        when (val outcome = run.outcome) {
            is Refused -> "${outcome.type}:${outcome.code}"
            else -> "released"
        }

    private companion object {
        const val DRAFT_BODY = NativeReleaseBodies.DRAFT
        const val TEMPDB_BODY = NativeReleaseBodies.TEMPDB
        const val SCRIPT_BODY = NativeReleaseBodies.SCRIPT
        const val CHECKED_BODY = NativeReleaseBodies.CHECKED
        const val TWO_TEMPLATE_BODY = NativeReleaseBodies.TWO_TEMPLATES
        const val CALCULATOR_BODY = NativeReleaseBodies.CALCULATOR
        val CHECKED_TWO_TEMPLATE_BODY = NativeReleaseBodies.CHECKED_TWO_TEMPLATES
        const val CALLER_RECORD = NativeReleaseBodies.CALLER_RECORD
        const val OVERRIDE = "Ledger lags one day; verified by hand."

        fun NativeReleaseWorld.draft(
            body: String,
            hash: String = "draft-hash",
        ) {
            every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail(hash)
            every { pipelines.findVersionBody(workspaceId, pipelineId, NativeReleaseWorld.VERSION) } returns body
            every { validator.validateOrThrow(any(), workspaceId, any()) } answers { firstArg() }
        }

        fun NativeReleaseWorld.status(
            id: String,
            version: Int,
            status: PipelineVersionStatus?,
        ) {
            every { templates.statusOf(workspaceId, id, version) } returns status
        }

        /** The repository's compare-and-set flip at [hash], logged with the transaction it ran in. */
        fun NativeReleaseWorld.flips(
            result: PipelineRepository.Released = releasedFlip(),
            hash: String = "draft-hash",
        ) {
            every {
                pipelines.releaseDraft(
                    workspaceId,
                    pipelineId,
                    NativeReleaseWorld.NAME,
                    "M",
                    "d",
                    any(),
                    userId,
                    NativeReleaseWorld.VERSION,
                )
            } answers {
                log += "flip:${arg<String>(5)}(inside=${inside()})"
                if (arg<String>(5) == hash) result else null
            }
        }

        fun NativeReleaseWorld.gate(verdict: CheckRunVerdict) =
            ReleaseCheckGate { _, _, _, _, _ ->
                log += "gate(inside=${inside()})"
                listOf(
                    CheckRunOutcome(
                        "revenue_matches",
                        "Revenue matches the ledger",
                        CheckExpectation(kind = "value", value = 1.0),
                        "1",
                        verdict,
                        null,
                    ),
                )
            }

        fun NativeReleaseWorld.marks(retired: Boolean) =
            TemplateReviewMarks { _, pins ->
                log += "marks:$pins"
                if (!retired) {
                    emptyMap()
                } else {
                    pins.associateWith { listOf(RetiredFactCitation("fact-old", "superseded", "fact-new")) }
                }
            }

        fun NativeReleaseWorld.twoDraftPins(body: String = TWO_TEMPLATE_BODY) {
            draft(body)
            status("test/t.sql", 2, PipelineVersionStatus.DRAFT)
            status("test/u.sql", 1, PipelineVersionStatus.DRAFT)
            flips()
            onTemplateRelease = { log += "template:${it.id}@${it.version}(inside=${inside()})" }
        }

        fun NativeReleaseWorld.cleanPin(body: String = DRAFT_BODY) {
            draft(body)
            status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
            flips()
        }

        val SCENARIOS =
            listOf(
                Scenario("authoring disabled", service = { service(authoring = false) }) {},
                Scenario("no draft") { every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns null },
                Scenario("validation re-run refuses") {
                    draft(DRAFT_BODY)
                    every { validator.validateOrThrow(any(), workspaceId, any()) } throws
                        PipelineValidationException(
                            ValidationResult(listOf(ValidationFailure("pipeline.validation.cycle_detected", "nodes", "cycle"))),
                        )
                },
                Scenario("a DRAFT template pin blocks") {
                    draft(DRAFT_BODY)
                    status("test/t.sql", 2, PipelineVersionStatus.DRAFT)
                    flips()
                },
                Scenario("all pins released flips") { cleanPin() },
                Scenario("SQL caller, no record: not_observed") {
                    cleanPin()
                    flips(releasedFlip().copy(version = releasedFlip().version.copy(callerOutputJson = null)))
                },
                Scenario("SQL caller, record: recorded") {
                    cleanPin()
                    flips(releasedFlip().copy(version = releasedFlip().version.copy(callerOutputJson = CALLER_RECORD)))
                },
                Scenario("no caller node: none") { cleanPin(TEMPDB_BODY) },
                Scenario("script caller: declared") {
                    cleanPin(SCRIPT_BODY)
                    flips(releasedFlip().copy(version = releasedFlip().version.copy(callerOutputJson = CALLER_RECORD)))
                },
                Scenario("a retired fact warns", service = { service(reviewMarks = marks(retired = true)) }) { cleanPin() },
                Scenario("a clean pin, no warning", service = { service(reviewMarks = marks(retired = false)) }) { cleanPin() },
                Scenario("two distinct retired pins, one warning each", service = { service(reviewMarks = marks(retired = true)) }) {
                    draft(TWO_TEMPLATE_BODY)
                    status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
                    status("test/u.sql", 1, PipelineVersionStatus.RELEASED)
                    flips()
                },
                Scenario("a refused release never reads the marks", service = { service(reviewMarks = marks(retired = true)) }) {
                    draft(DRAFT_BODY)
                    status("test/t.sql", 2, PipelineVersionStatus.DRAFT)
                    flips()
                },
                Scenario("a stale hash is the conflict", request = { request(expectedHash = "stale") }) {
                    draft(DRAFT_BODY, hash = "current-hash")
                    status("test/t.sql", 2, PipelineVersionStatus.RELEASED)
                    flips(hash = "current-hash")
                },
                Scenario("a failing check refuses", service = { service(checkGate = gate(CheckRunVerdict.FAIL)) }) {
                    cleanPin(CHECKED_BODY)
                },
                Scenario("an error verdict refuses", service = { service(checkGate = gate(CheckRunVerdict.ERROR)) }) {
                    cleanPin(CHECKED_BODY)
                },
                Scenario(
                    "the override releases past failing checks",
                    request = { request(overrideChecksReason = OVERRIDE) },
                    service = { service(checkGate = gate(CheckRunVerdict.FAIL)) },
                ) { cleanPin(CHECKED_BODY) },
                Scenario(
                    "a short override still refuses",
                    request = { request(overrideChecksReason = "ok") },
                    service = { service(checkGate = gate(CheckRunVerdict.FAIL)) },
                ) { cleanPin(CHECKED_BODY) },
                Scenario("passing checks release clean", service = { service(checkGate = gate(CheckRunVerdict.PASS)) }) {
                    cleanPin(CHECKED_BODY)
                },
                Scenario("a check-less draft never calls the gate", service = { service(checkGate = gate(CheckRunVerdict.FAIL)) }) {
                    cleanPin()
                },
                Scenario("without the flag every DRAFT pin is named") { twoDraftPins() },
                Scenario("with the flag each DRAFT pin releases once, first", request = { request(releasePinnedTemplates = true) }) {
                    twoDraftPins()
                },
                Scenario("with the flag a RELEASED pin is left alone", request = { request(releasePinnedTemplates = true) }) {
                    cleanPin()
                    onTemplateRelease = { log += "template:${it.id}" }
                },
                Scenario("with the flag a MISSING pin refuses", request = { request(releasePinnedTemplates = true) }) {
                    twoDraftPins()
                    status("test/u.sql", 1, null)
                },
                Scenario("with the flag a DISCARDED pin refuses", request = { request(releasePinnedTemplates = true) }) {
                    twoDraftPins()
                    status("test/u.sql", 1, PipelineVersionStatus.DISCARDED)
                },
                Scenario("a template's own refusal propagates", request = { request(releasePinnedTemplates = true) }) {
                    twoDraftPins()
                    onTemplateRelease = {
                        log += "template:${it.id}"
                        if (it.id == "test/u.sql") {
                            throw DatapipelinesException(code = "template.version.conflict", message = "Template was modified.")
                        }
                    }
                },
                Scenario(
                    "the boundary: gate outside, templates and flip inside",
                    request = { request(releasePinnedTemplates = true) },
                    service = { service(checkGate = gate(CheckRunVerdict.PASS)) },
                ) { twoDraftPins(CHECKED_TWO_TEMPLATE_BODY) },
                Scenario(
                    "atomicity: a stale hash after the templates flipped",
                    request = { request("stale", releasePinnedTemplates = true) },
                ) {
                    twoDraftPins()
                    every { pipelines.findDraftDetail(workspaceId, pipelineId) } returns draftDetail("current-hash")
                    flips(hash = "current-hash")
                },
                Scenario("a CALCULATOR node has no pin to block") { cleanPin(CALCULATOR_BODY) },
            )
    }
}
