package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.ClientAddressResolver
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ScreenshotView
import co.datapipelines.visualization.TestRunView
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.TestSessionWire
import co.datapipelines.visualization.TestSubmissionReader
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationTestCapabilities
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.requestlimits.RequestBodyCapFilter
import com.fasterxml.jackson.databind.JsonNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The visualization test workflow on the wire (rest-api.md §22.2; the implementation spec's §6.1 last two rows,
 * §11.2) — #353 binds 352's backend, it decides nothing: the session service starts, submits, stores and reads; the
 * capability operations authenticate the one session-less route. "Transport binds, service decides."
 *
 * ## Two credentials
 * Every route here but one is session- or key-authenticated and carries `@RequiredScope`, re-evaluated per request
 * by `ScopeInterceptor` — a member demoted between start and submit is refused by the role they hold NOW. The
 * screenshot upload is the exception (the owner's ruling (b), DECISION 2): an agent's browser holds no key (an `mcp`
 * key reaches no MVC route), so the route is a `PublicPaths` entry whose ONLY credential is the single-use upload
 * capability in [TestSessionLinks.UPLOAD_TOKEN_HEADER]; it reads no cookie and no principal, and the run the
 * capability names decides the workspace. Its body is raw `image/png` / `image/webp` under its own 4 MiB cap
 * (`RequestBodyCapFilter`'s one route exemption), never the platform's 2 MiB.
 *
 * ## The upload is audited (#164)
 * The route reads no principal, so the row [AUDIT_SCREENSHOT_UPLOADED] names the run's STARTER (`started_by`, which
 * the stored screenshot already carries as `uploadedBy`) with no key, and the source IP the platform's one resolver
 * reads. It is written once the store has returned — a refused upload (the capability judged before a byte is
 * read, or the service's refusals) stores nothing and writes no row — and its details are ids, size, type and the
 * case named: never the capability, its hash or the image bytes. "Everything an agent did is in the audit log" is
 * the MCP tool rows plus this one: it is the one write an agent makes over REST.
 *
 * ## Lensed reads
 * The evidence reads sit on `visualization.read` and pass the caller's lens: the visualization must be visible
 * (absent, foreign and lens-hidden are the family's 404), and a run is visible only when its VERSION is — a
 * promoter, who sees released versions only, never reads a draft's evidence.
 */
@RestController
@RequestMapping("/api/v1/visualizations")
class VisualizationTestsController(
    private val sessions: VisualizationTestSessionService,
    private val capabilities: VisualizationTestCapabilities,
    private val visualizations: VisualizationService,
    private val links: TestSessionLinks,
    /** The promoter lens: every read below passes the caller's view, never `Everything`. */
    private val lens: PromoterLens,
    /** The sink behind the one audit row this controller writes: the screenshot upload (#164). */
    private val audit: AuditEventSink,
    /** The client address of the upload — resolved as every auth-side row resolves it, never `remoteAddr` raw. */
    private val clientAddresses: ClientAddressResolver,
) {
    /** §22.2 — open a session on the WORKING version; the answer is the only place the preview capability appears. */
    @PostMapping("/{id}/tests/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(Permission.VISUALIZATION_UPDATE)
    fun start(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val started = sessions.start(principal.requireWorkspace().id, id, principal.userId)
        return ApiResponse.of(TestSessionWire.started(started, links))
    }

    /** §22.2 — the verdicts; the SAME principal that started submits. A GREEN run answers the upload capability once. */
    @PostMapping("/{id}/tests/sessions/{sessionId}/results")
    @RequiredScope(Permission.VISUALIZATION_UPDATE)
    fun results(
        @PathVariable id: UUID,
        @PathVariable sessionId: UUID,
        @RequestBody body: String,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val submission = TestSubmissionReader.read(ArtifactHttp.readTree(FAMILY, body))
        val submitted =
            sessions.submit(principal.requireWorkspace().id, id, sessionId, principal.userId, submission.verdicts, submission.environment)
        return ApiResponse.of(TestSessionWire.submitted(submitted, id, links))
    }

    /**
     * §22.2 — the session's ONE screenshot, authenticated by the upload capability alone (a `PublicPaths` entry: no
     * `@RequiredScope`, no cookie read). The capability is checked FIRST, then the body is read, bounded at the cap +
     * 1 byte; the request filter has already refused a declared length over the cap, and its counting stream refuses
     * a chunked body past it.
     */
    @PostMapping("/{id}/tests/sessions/{sessionId}/screenshot")
    @ResponseStatus(HttpStatus.CREATED)
    fun screenshot(
        @PathVariable id: UUID,
        @PathVariable sessionId: UUID,
        @RequestHeader(value = TestSessionLinks.UPLOAD_TOKEN_HEADER, required = false) capability: String?,
        @RequestParam(value = "case", required = false) depictedCase: String?,
        request: HttpServletRequest,
    ): ApiResponse<Map<String, Any?>> {
        // The capability is judged BEFORE a byte is read: an unauthorised caller never makes the server buffer the body.
        val grant = capabilities.authorizeUpload(id, sessionId, capability)
        val bytes = readImage(request)
        val stored = capabilities.storeScreenshot(id, sessionId, capability, declaredType(request), bytes, depictedCase)
        auditUpload(grant.workspaceId, id, stored, bytes.size, request)
        return ApiResponse.of(TestSessionWire.screenshot(stored))
    }

    /** §22.2 — the visualization's runs, newest first, redacted; capped at the newest [RUNS_CAP]. */
    @GetMapping("/{id}/tests/runs")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun runs(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val visible = visibleVersions(principal, workspaceId, id)
        val runs = sessions.runs(workspaceId, id).filter { it.version in visible }
        return ApiResponse.of(mapOf("runs" to runs, "limit" to RUNS_CAP))
    }

    /** §22.2 — one run by its id, redacted (capabilities as presence and stamps only). */
    @GetMapping("/{id}/tests/runs/{runId}")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun run(
        @PathVariable id: UUID,
        @PathVariable runId: UUID,
    ): ApiResponse<TestRunView> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val visible = visibleVersions(principal, workspaceId, id)
        val view = sessions.runById(workspaceId, id, runId)
        if (view.version !in visible) throw runNotFound()
        return ApiResponse.of(view)
    }

    /** §22.2 — the run's stored screenshot, streamed with the media type detected at upload. */
    @GetMapping("/{id}/tests/runs/{runId}/screenshot")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun runScreenshot(
        @PathVariable id: UUID,
        @PathVariable runId: UUID,
    ): ResponseEntity<ByteArray> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val visible = visibleVersions(principal, workspaceId, id)
        if (sessions.runById(workspaceId, id, runId).version !in visible) throw runNotFound()
        val image = sessions.screenshotBytes(workspaceId, id, runId)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.mediaType))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(image.bytes)
    }

    /** §22.2 — the §11.3 mechanical test on demand over version v as stored: no session, no write (spec §6.1: read). */
    @PostMapping("/{id}/versions/{version}/check")
    @RequiredScope(Permission.VISUALIZATION_READ)
    fun check(
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): ApiResponse<JsonNode> {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val loaded =
            visualizations.findVersion(workspaceId, lens.viewFor(principal).visualizations, id, version)
                ?: throw FAMILY.notFound(id.toString(), version)
        return ApiResponse.of(sessions.check(workspaceId, loaded.body))
    }

    /**
     * The upload's audit row (#164): actor = the run's starter, `key_id` null (the route has none), details redaction-bound —
     * ids, the stored size and type, the case when one was named.
     */
    private fun auditUpload(
        workspaceId: UUID,
        id: UUID,
        stored: ScreenshotView,
        sizeBytes: Int,
        request: HttpServletRequest,
    ) {
        audit.log(
            event = AUDIT_SCREENSHOT_UPLOADED,
            userId = stored.uploadedBy,
            keyId = null,
            sourceIp = clientAddresses.clientAddressOf(request),
            details =
                buildMap {
                    put("workspace_id", workspaceId.toString())
                    put("visualization_id", id.toString())
                    put("run_id", stored.runId.toString())
                    put("media_type", stored.mediaType)
                    put("size_bytes", sizeBytes)
                    stored.depictedCase?.let { put("case", it) }
                },
        )
    }

    /** The versions the caller's lens admits — none is the family's 404 (absent, foreign, lens-hidden alike). */
    private fun visibleVersions(
        principal: AuthenticatedPrincipal,
        workspaceId: UUID,
        id: UUID,
    ): Set<Int> {
        val listed = visualizations.listVersions(workspaceId, lens.viewFor(principal).visualizations, id)
        if (listed.isEmpty()) throw FAMILY.notFound(id.toString())
        return listed.map { it.version }.toSet()
    }

    /** The image bytes, at most the cap + 1 (so the service's typed check sees an over-size body as over-size). */
    private fun readImage(request: HttpServletRequest): ByteArray =
        try {
            request.inputStream.use { it.readNBytes(VisualizationTestSessionService.MAX_SCREENSHOT_BYTES + 1) }
        } catch (_: RequestBodyCapFilter.RequestBodyTooLargeException) {
            throw screenshotTooLarge()
        }

    /** The declared media type without parameters, or null — the service judges it against the detected one. */
    private fun declaredType(request: HttpServletRequest): String? =
        request.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        /** enums.md §15 and auth.md §10.1 — the screenshot upload's audit event (#164). */
        const val AUDIT_SCREENSHOT_UPLOADED = "visualization.test.screenshot_uploaded"

        private val FAMILY = ArtifactFamily.VISUALIZATION

        /** The runs list's bound: the repository's newest-first read (rest-api §22.2 documents it). */
        private const val RUNS_CAP = 100

        /** The service's own refusal for an unknown run — a lens-hidden run answers identically. */
        private fun runNotFound() =
            DatapipelinesException(
                code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                message = "No such test session for this visualization; its capabilities verify nothing.",
                details = mapOf("reason" to "session_unknown"),
            )

        /** The route cap's refusal, spelt as the service and the request filter spell it. */
        private fun screenshotTooLarge() =
            DatapipelinesException(
                code = VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE,
                message = "The screenshot exceeds the 4 MiB cap.",
                details = mapOf("cap_bytes" to VisualizationTestSessionService.MAX_SCREENSHOT_BYTES),
            )
    }
}
