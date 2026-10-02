package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * The starter's CURRENT authority (the owner's 2026-09-30 ruling, "current authority on token use"; #353's
 * DECISION 3): a capability-bearing request has no principal, so the one person — or key identity — whose
 * authority the capability stands for is the run's `started_by`, re-judged at the moment of use. True only
 * while that identity is live, its workspace is live, and it still holds `visualization.update` there. The
 * implementation is `web`'s (it reads `modules/auth`); this module only asks.
 */
fun interface StarterAuthority {
    fun holdsUpdate(
        workspaceId: UUID,
        startedBy: UUID,
    ): Boolean
}

/** One case of a preview: the saved fixtures, the evaluated rows the renderer receives, or the evaluator's refusal. */
data class PreviewCase(
    val name: String,
    /** The case's fixtures exactly as saved — input name to rows. */
    val fixtures: Map<String, List<ObjectNode>>,
    /** The rows a live refresh would deliver for this case (the transform's output, or the single input's rows); null when refused. */
    val rows: List<Map<String, Any?>>?,
    /** The evaluator's own catalogued refusal when the fixtures did not evaluate; null otherwise. */
    val refusal: FixtureEvaluation.Refused?,
    val assertions: List<Assertion>,
)

/** What the preview page serves: the run's exact version and content, evaluated case by case. */
data class TestPreview(
    val runId: UUID,
    val sessionId: UUID,
    val expiresAt: Instant,
    val visualization: ArtifactVersion<VisualizationBody>,
    val cases: List<PreviewCase>,
)

/**
 * The two capability-authenticated operations (#353; the implementation spec's §11.2, the owner's ruling (b)) —
 * the preview page's read and the screenshot upload — for requests that carry NO principal. The capability
 * names the run; the run names the workspace; [StarterAuthority] re-judges the starter; then the work is
 * [VisualizationTestSessionService]'s or the fixture evaluator's, unchanged. Every failure — a wrong, revoked,
 * expired or malformed capability, another visualization's run, content that moved on, a starter who lost the
 * right — is the SAME `test.session_not_found` with the same details: the capability is an oracle for nothing.
 *
 * - **Preview** answers only while the run is RUNNING and unexpired at [now] (an unswept due row counts as
 *   expired — the gate's `statusAt` reading), only for the visualization the request addresses, and only
 *   while the run's version still carries the run's body hash. The cases are evaluated the way the mechanical
 *   test's step 3 evaluates them (the pinned transform through the real bounded evaluator, DRAFT pins admitted;
 *   without a transform, the single input's rows) — no pipeline, no datasource, no agent code.
 * - **Screenshot** resolves the run of the addressed session in whichever workspace holds it, re-judges the
 *   starter, then hands the bytes and the capability to [VisualizationTestSessionService.storeScreenshot],
 *   which verifies the upload capability against the row and consumes it atomically with the INSERT.
 */
class VisualizationTestCapabilities(
    private val runs: TestRunRepository,
    private val visualizations: VisualizationRepository,
    private val sessions: VisualizationTestSessionService,
    private val fixtures: TestFixtureEvaluator,
    private val authority: StarterAuthority,
    private val now: () -> Instant = Instant::now,
) {
    private val previewCases = PreviewCaseEvaluator(fixtures)

    /** The preview of the run [previewToken] names, for visualization [id] — or `session_not_found`. */
    @Suppress("ThrowsCount") // every gate is its own named exit, and every exit is the same refusal
    fun preview(
        id: UUID,
        previewToken: String?,
    ): TestPreview {
        val presented = previewToken?.takeIf { it.isNotBlank() } ?: throw notFound()
        val hash = hashOrNull(TestCapability.PREVIEW_PURPOSE, presented) ?: throw notFound()
        val row = runs.findByPreviewTokenHash(hash) ?: throw notFound()
        if (row.visualizationId != id) throw notFound()
        if (row.statusAt(now()) != TestRunStatus.RUNNING) throw notFound()
        if (row.versionBodyHash != row.bodyHash) throw notFound()
        if (!authority.holdsUpdate(row.workspaceId, row.startedBy)) throw notFound()
        val version = visualizations.findVersion(row.workspaceId, id, row.version) ?: throw notFound()
        if (version.detail.bodyHash != row.bodyHash) throw notFound()
        return TestPreview(row.id, row.sessionId, row.expiresAt, version, previewCases.cases(row.workspaceId, version.body))
    }

    /**
     * The upload's gate WITHOUT the body (#353's security pass): the route calls this BEFORE it reads a byte, so a
     * request that carries no valid capability of a still-authorised starter is refused on one row read and never
     * makes the server buffer up to 4 MiB. Answers the run's workspace for the store. Pre-verified here so that EVERY
     * request without the right capability — a RUNNING run (352's `no_capability`), a consumed one, a wrong or
     * malformed token — gets the one identical answer; the service verifies again and consumes under its row lock,
     * which stays the replay fence.
     *
     * The one documented exception (#373, R1): a presented token that MATCHES an unconsumed capability past its
     * `upload_expires_at` is `session_expired` / `capability_expired` (410), judged here before any body byte —
     * the holder of the right token learns only what it already knows, and the body is never buffered for it.
     * A wrong or consumed token keeps the one 404.
     */
    @Suppress("ThrowsCount") // every gate is its own named exit — the one 404, and R1's one 410
    fun authorizeUpload(
        id: UUID,
        sessionId: UUID,
        capability: String?,
    ): UploadGrant {
        val presented = capability?.takeIf { it.isNotBlank() } ?: throw notFound()
        val hash = hashOrNull(TestCapability.UPLOAD_PURPOSE, presented) ?: throw notFound()
        val row = runs.findBySessionUnscoped(id, sessionId) ?: throw notFound()
        if (row.uploadTokenHash != hash || row.uploadConsumedAt != null) throw notFound()
        // R1 — after the hash and consumed checks, so a wrong or consumed token never sees this answer:
        if (row.uploadExpiresAt != null && !row.uploadExpiresAt.isAfter(now())) throw expired()
        if (!authority.holdsUpdate(row.workspaceId, row.startedBy)) throw notFound()
        return UploadGrant(row.workspaceId, presented)
    }

    /**
     * Stores the screenshot of session [sessionId] under visualization [id], authenticated by [capability]
     * alone: [authorizeUpload] again (the run, the capability and the starter re-judged at the store), then the
     * service refuses empty/over-size bytes, verifies and consumes the capability, and inserts.
     */
    @Suppress("LongParameterList") // the route's whole input: the address, the credential and the image
    fun storeScreenshot(
        id: UUID,
        sessionId: UUID,
        capability: String?,
        declaredMediaType: String?,
        bytes: ByteArray,
        depictedCase: String?,
    ): ScreenshotView {
        val grant = authorizeUpload(id, sessionId, capability)
        return sessions.storeScreenshot(grant.workspaceId, id, sessionId, grant.capability, declaredMediaType, bytes, depictedCase)
    }

    /** What [authorizeUpload] establishes: the run's workspace and the verified capability's wire form. */
    class UploadGrant(
        val workspaceId: UUID,
        val capability: String,
    )

    /** The stored form of a presented capability, or null when it is malformed — never a distinct refusal. */
    private fun hashOrNull(
        purpose: String,
        presented: String,
    ): String? =
        try {
            TestCapability.hashEncoded(purpose, presented)
        } catch (_: DatapipelinesException) {
            null
        }

    /** `session_not_found`, `session_unknown` — byte-for-byte the service's own answer for an absent session. */
    private fun notFound() =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
            message = "No such test session for this visualization; its capabilities verify nothing.",
            details = mapOf("reason" to "session_unknown"),
        )

    /** R1's 410 — byte-for-byte the service's own `sessionExpired("capability_expired")` (#373). */
    private fun expired() =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SESSION_EXPIRED,
            message = "The test session is expired or revoked.",
            details = mapOf("reason" to "capability_expired"),
        )

}

/**
 * A version's test cases evaluated for a preview (#399 factored this out of [VisualizationTestCapabilities] so the
 * capability preview and the signed-in workspace's Preview tab evaluate the SAME way): the cases as saved, each
 * evaluated the way the mechanical test's step 3 evaluates it — without a transform, the single input's rows; with
 * one, the pinned transform through the real bounded [TestFixtureEvaluator] (its own refusal kept as the case's
 * refusal). No pipeline, no datasource, no live data.
 */
class PreviewCaseEvaluator(
    private val fixtures: TestFixtureEvaluator,
) {
    /** Every case of [body]'s tests, in order; none when the version carries no tests. */
    fun cases(
        workspaceId: UUID,
        body: VisualizationBody,
    ): List<PreviewCase> =
        body.tests
            ?.cases
            .orEmpty()
            .map { case -> evaluate(workspaceId, body, case) }

    private fun evaluate(
        workspaceId: UUID,
        body: VisualizationBody,
        case: TestCase,
    ): PreviewCase {
        val transform = body.transform
        if (transform == null) {
            val rows =
                case.fixtures.values
                    .singleOrNull()
                    .orEmpty()
                    .map { row -> ArtifactJson.mapper.convertValue(row, ROW_TYPE) }
            return PreviewCase(case.name, case.fixtures, rows, null, case.assertions)
        }
        return when (val evaluation = fixtures.evaluate(workspaceId, transform.template, case.fixtures)) {
            is FixtureEvaluation.Rows -> PreviewCase(case.name, case.fixtures, evaluation.rows, null, case.assertions)
            is FixtureEvaluation.Refused -> PreviewCase(case.name, case.fixtures, null, evaluation, case.assertions)
        }
    }

    private companion object {
        val ROW_TYPE = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}
