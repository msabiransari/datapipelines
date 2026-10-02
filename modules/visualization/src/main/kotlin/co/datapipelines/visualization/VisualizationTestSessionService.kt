package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.UUID

/**
 * The test sessions' use cases (the spec's §11.2, the owner's 2026-09-30 ruling) — start, read, submit,
 * expire, and the screenshot store. The surfaces (#353) add transport only; there is no route here.
 *
 * - **Start** resolves the artifact's WORKING version (the draft when one exists, else the current) and
 *   validates its case inventory; a run pins workspace, artifact, version, body hash and actor. The
 *   preview capability is minted (32 random bytes), stored hash-only, and dies at the configured TTL
 *   (`datapipelines.visualization.session-ttl-minutes`, the spec's §11.2 60 minutes).
 * - **Submit** refuses unknown/duplicate case names, bounds the notes and the environment's closed field
 *   set, runs the §11.3 mechanical test NOW, derives the status (any failed verdict or mechanical failure
 *   RED; a missing verdict INCOMPLETE; every case green AND mechanical success GREEN), revokes the preview
 *   capability and mints the SEPARATE single-use upload capability — expiring no later than the session's
 *   original deadline, its raw form shown exactly once, only on a GREEN run. A completed run is never
 *   re-submitted: no replacement token, no unlimited overwrite.
 * - **Expiry** is enforced where the state is read and where it is judged; the sweep touches RUNNING rows
 *   ONLY, so a valid completed GREEN release record is never retrospectively erased. A completed run whose
 *   version's hash moved on is EXPIRED on read and can never qualify a release.
 * - **Screenshot** consumes the upload capability atomically with the stored image (one transaction, one
 *   guarded UPDATE): failed validation stores and consumes nothing; two competing valid uploads have
 *   exactly one winner; a draft version's superseded screenshot is deleted on completion. The capability
 *   is the operation's only credential — uploaded_by is the session's owner.
 */
class VisualizationTestSessionService(
    private val runs: TestRunRepository,
    private val visualizations: VisualizationRepository,
    private val mechanical: VisualizationMechanicalCheck,
    private val config: VisualizationConfig,
    private val transactions: TransactionOperations,
    private val newId: () -> UUID = UUID::randomUUID,
    private val now: () -> Instant = Instant::now,
    private val randomBytes: (Int) -> ByteArray = { TestCapability.secureBytes(it) },
) {
    // ---- start ----------------------------------------------------------------------------------------

    /**
     * Opens a session over the working version. Refuses an unknown artifact (`not_found`), a version with
     * no case inventory (`test_case_invalid`) and — the uniqueness constraint's mapping — a duplicate
     * session id (`version.conflict`; a reused session id is a caller bug, never a second run).
     */
    @Suppress("ThrowsCount") // each refusal is a distinct catalogued outcome the surfaces map
    fun start(
        workspaceId: UUID,
        id: UUID,
        actor: UUID,
    ): TestSessionStarted {
        val started =
            transactions.execute {
                val working = visualizations.findWorking(workspaceId, id) ?: throw lifecycleNotFound(id)
                val record = checkNotNull(visualizations.findRecord(workspaceId, id))
                val cases =
                    working.body.tests
                        ?.cases
                        .orEmpty()
                if (cases.isEmpty()) {
                    throw DatapipelinesException(
                        code = VisualizationErrorCodes.TEST_CASE_INVALID,
                        message = "Visualization '${record.name.safeEcho()}' declares no test case; there is nothing to test.",
                        details = mapOf("path" to "tests.cases", "reason" to "no_cases"),
                    )
                }
                val session = newId()
                val at = now()
                val previewMaterial = randomBytes(TestCapability.BYTES)
                val expiresAt = at.plusSeconds(config.sessionTtlMinutes * SECONDS_PER_MINUTE)
                val runId = newId()
                try {
                    runs.insertRunning(
                        TestRunRepository.RunningRun(
                            id = runId,
                            visualizationId = id,
                            version = working.detail.version,
                            bodyHash = working.detail.bodyHash,
                            sessionId = session,
                            previewTokenHash = TestCapability.hash(TestCapability.PREVIEW_PURPOSE, previewMaterial),
                            expiresAt = expiresAt,
                            startedBy = actor,
                            startedAt = at,
                        ),
                    )
                } catch (_: DuplicateKeyException) {
                    // mapped to the family's catalogued conflict; the driver detail is noise
                    throw DatapipelinesException(
                        code = VisualizationErrorCodes.VERSION_CONFLICT,
                        message = "Session '$session' already exists for this version.",
                        details = mapOf("session_id" to session.toString()),
                    )
                }
                TestSessionStarted(
                    runId = runId,
                    sessionId = session,
                    visualizationId = id,
                    version = working.detail.version,
                    bodyHash = working.detail.bodyHash,
                    expiresAt = expiresAt,
                    previewToken = TestCapability.encode(previewMaterial),
                    cases = cases.map { it.name },
                )
            }
        return checkNotNull(started)
    }

    // ---- reads ----------------------------------------------------------------------------------------

    /** One run, redacted. Sweeps a due RUNNING session first — reading enforces the deadline. */
    fun run(
        workspaceId: UUID,
        id: UUID,
        sessionId: UUID,
    ): TestRunView {
        val row = runs.findBySession(workspaceId, id, sessionId) ?: throw sessionNotFound()
        return view(swept(row))
    }

    /** Every run of the visualization, newest first, redacted. */
    fun runs(
        workspaceId: UUID,
        id: UUID,
    ): List<TestRunView> = runs.listByVisualization(workspaceId, id).map { view(swept(it)) }

    /** The run's screenshot view — the bytes themselves are #353's transport decision. */
    fun screenshot(
        workspaceId: UUID,
        id: UUID,
        sessionId: UUID,
    ): ScreenshotView {
        val row = runs.findBySession(workspaceId, id, sessionId) ?: throw sessionNotFound()
        return runs.screenshotOf(row.id) ?: throw screenshotInvalid("no_screenshot")
    }

    // ---- the evidence reads by RUN id (#353's routes address a run, not a session) -------------------

    /** One run by its id, redacted; an unknown or foreign run is `session_not_found`. Sweeps a due session first. */
    fun runById(
        workspaceId: UUID,
        id: UUID,
        runId: UUID,
    ): TestRunView {
        val row = runs.findById(workspaceId, id, runId) ?: throw sessionNotFound()
        return view(swept(row))
    }

    /**
     * The run's stored screenshot bytes with the media type detected at upload — the evidence read's payload.
     * An unknown run is `session_not_found`; a run that stores no image (none uploaded, or superseded by a
     * newer draft-era upload — the D35 retention) is `screenshot_invalid` / `no_screenshot`.
     */
    fun screenshotBytes(
        workspaceId: UUID,
        id: UUID,
        runId: UUID,
    ): ScreenshotBytes {
        val row = runs.findById(workspaceId, id, runId) ?: throw sessionNotFound()
        return runs.screenshotBytes(row.id) ?: throw screenshotInvalid("no_screenshot")
    }

    /**
     * The §11.3 mechanical test on demand (`POST …/versions/{v}/check`, the spec's §6.1): the same check submit
     * and release run, over [body] as stored, at this moment — no session, no write, nothing recorded.
     */
    fun check(
        workspaceId: UUID,
        body: VisualizationBody,
    ): JsonNode = mechanical.run(workspaceId, body, now()).toJson()

    // ---- submit ---------------------------------------------------------------------------------------

    /**
     * Completes the run with the agent's verdicts. [verdicts] may omit cases (the run lands INCOMPLETE),
     * may not name unknown ones or name one twice; [environment] carries only the closed field set, each
     * bounded. Returns the upload capability's material on a GREEN run — evidence of failure needs none.
     */
    @Suppress("ThrowsCount") // each refusal is a distinct catalogued outcome the surfaces map
    fun submit(
        workspaceId: UUID,
        id: UUID,
        sessionId: UUID,
        actor: UUID,
        verdicts: List<SubmittedCase>,
        environment: TestEnvironment? = null,
    ): TestSessionSubmitted {
        validateSubmission(verdicts, environment)
        sweepIfDue(workspaceId, id, sessionId)
        val submitted =
            transactions.execute {
                val row = swept(runs.findBySession(workspaceId, id, sessionId) ?: throw sessionNotFound())
                if (row.startedBy != actor) throw sessionNotFound() // a session is its owner's; absence, not a leak
                if (row.status != TestRunStatus.RUNNING) throw sessionExpired("not_running")
                val version =
                    visualizations.findVersion(workspaceId, id, row.version)
                        ?: throw sessionExpired("version_gone")
                if (version.detail.bodyHash != row.bodyHash) throw sessionExpired("content_moved_on")

                val at = now()
                val cases = checkNotNull(version.body.tests?.cases)
                val byName = verdicts.associateBy { it.name }
                val unknown = verdicts.map { it.name }.filter { it != "" && it !in cases.map { case -> case.name } }
                if (unknown.isNotEmpty()) throw bodyInvalid("unknown_case", "tests.cases.${unknown.first().safeEcho()}")
                val report = mechanical.run(workspaceId, version.body, at)
                val finalStatus = deriveStatus(cases, byName, report)
                val uploadMinted = finalStatus == TestRunStatus.GREEN
                val uploadMaterial = if (uploadMinted) randomBytes(TestCapability.BYTES) else null
                val recorded =
                    runs.completeSubmission(
                        TestRunRepository.Submission(
                            id = row.id,
                            actor = actor,
                            now = at,
                            status = finalStatus,
                            completedAt = at,
                            casesJson = casesJson(cases, byName),
                            environmentJson = environment?.let { ArtifactJson.mapper.writeValueAsString(it) },
                            mechanicalJson = ArtifactJson.mapper.writeValueAsString(report.toJson()),
                            uploadTokenHash = uploadMaterial?.let { TestCapability.hash(TestCapability.UPLOAD_PURPOSE, it) },
                            uploadExpiresAt = if (uploadMinted) row.expiresAt else null,
                        ),
                    )
                if (!recorded) throw sessionExpired("not_running")
                TestSessionSubmitted(
                    runId = row.id,
                    sessionId = sessionId,
                    status = finalStatus,
                    completedAt = at,
                    mechanical = report.toJson(),
                    uploadToken = uploadMaterial?.let(TestCapability::encode),
                    uploadExpiresAt = if (uploadMinted) row.expiresAt else null,
                )
            }
        return checkNotNull(submitted)
    }

    // ---- screenshot -----------------------------------------------------------------------------------

    /**
     * Stores the run's one screenshot, consuming [capability] atomically. Validation precedes the
     * transaction (a malformed upload stores and consumes nothing); the winner is decided by the guarded
     * UPDATE, the loser inserts nothing.
     */
    @Suppress("ThrowsCount") // each refusal is a distinct catalogued outcome the surfaces map
    fun storeScreenshot(
        workspaceId: UUID,
        id: UUID,
        sessionId: UUID,
        capability: String,
        declaredMediaType: String?,
        bytes: ByteArray,
        depictedCase: String? = null,
    ): ScreenshotView {
        if (bytes.isEmpty()) throw screenshotInvalid("empty")
        if (bytes.size > MAX_SCREENSHOT_BYTES) throw tooLarge()
        val image = ScreenshotImages.parse(declaredMediaType, bytes)
        val stored =
            transactions.execute {
                val row =
                    swept(runs.findBySession(workspaceId, id, sessionId) ?: throw sessionNotFound())
                when {
                    row.status == TestRunStatus.RUNNING -> {
                        throw DatapipelinesException(
                            code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                            message = "The screenshot follows results submission; the run has no upload capability yet.",
                            details = mapOf("reason" to "no_capability"),
                        )
                    }

                    row.status == TestRunStatus.EXPIRED || row.effectiveStatus == TestRunStatus.EXPIRED -> {
                        throw sessionExpired("run_expired")
                    }
                }
                val hash = capabilityGate(row, capability, depictedCase)
                if (!runs.consumeUploadCapability(row.id, hash, now())) throw sessionExpired("capability_expired")
                val inserted =
                    runs.insertScreenshot(
                        TestRunRepository.StoredScreenshot(
                            runId = row.id,
                            mediaType = image.mediaType,
                            bytes = bytes,
                            sha256 = ScreenshotImages.sha256(bytes),
                            width = image.width,
                            height = image.height,
                            depictedCase = depictedCase,
                            uploadedBy = row.startedBy,
                            uploadedAt = now(),
                        ),
                    )
                if (!inserted) {
                    throw DatapipelinesException(
                        code = VisualizationErrorCodes.TEST_SCREENSHOT_INVALID,
                        message = "The run already stores a screenshot; exactly one upload wins.",
                        details = mapOf("reason" to "already_stored"),
                    )
                }
                runs.supersedeDraftScreenshots(id, row.version, row.id)
                runs.screenshotOf(row.id) ?: throw screenshotInvalid("store_failed")
            }
        return checkNotNull(stored)
    }

    // ---- internals ------------------------------------------------------------------------------------

    /**
     * The capability gate: the run's minted hash, unexpired and unconsumed, must verify against the
     * presented material, and a depicted case must belong to the run's inventory. Wrong or consumed is
     * `session_not_found` (indistinguishable from absent — no oracle); past the deadline is
     * `session_expired`. Answers the stored hash the consume statement re-checks.
     *
     * The expiry judges AFTER the hash (#373, R1): only the holder of the RIGHT token is told the
     * capability expired — a wrong token keeps the one 404, so the service alone never answers 410
     * to a token that verified nothing.
     */
    @Suppress("ThrowsCount") // each gate refusal is its own named exit (the ReadOnlyPipelineRule mould)
    private fun capabilityGate(
        row: TestRunRow,
        capability: String,
        depictedCase: String?,
    ): String {
        val hash = row.uploadTokenHash ?: throw sessionNotFound()
        if (row.uploadConsumedAt != null) throw sessionNotFound() // consumed: indistinguishable from absent
        if (TestCapability.hashEncoded(TestCapability.UPLOAD_PURPOSE, capability) != hash) throw sessionNotFound()
        if (row.uploadExpiresAt != null && !row.uploadExpiresAt.isAfter(now())) throw sessionExpired("capability_expired")
        if (depictedCase != null) {
            val names = row.casesJson?.map { it.path("name").asText() }.orEmpty()
            if (depictedCase !in names) throw screenshotInvalid("case_unknown")
        }
        return hash
    }

    /**
     * The expiry sweep in its OWN transaction, before a submit's (the 352 merge's F6): inside the submit's
     * transaction the sweep's write rolled back WITH the `not_running` refusal it caused, so a due session stayed
     * RUNNING until some non-throwing read swept it — and the release gate judged an open session meanwhile.
     */
    private fun sweepIfDue(
        workspaceId: UUID,
        id: UUID,
        sessionId: UUID,
    ) {
        transactions.execute { runs.findBySession(workspaceId, id, sessionId)?.let { swept(it) } }
    }

    /** Sweeps a due RUNNING row and re-reads it; a completed row is returned untouched. */
    private fun swept(row: TestRunRow): TestRunRow {
        if (row.status != TestRunStatus.RUNNING || row.expiresAt.isAfter(now())) return row
        runs.expireIfDue(row.id, now())
        return runs.findById(row.workspaceId, row.visualizationId, row.id) ?: row
    }

    private fun view(row: TestRunRow): TestRunView =
        TestRunView(
            runId = row.id,
            sessionId = row.sessionId,
            version = row.version,
            bodyHash = row.bodyHash,
            status = row.effectiveStatus,
            startedAt = row.startedAt,
            completedAt = row.completedAt,
            expiresAt = row.expiresAt,
            cases = row.casesJson,
            environment = row.environmentJson,
            mechanical = row.mechanicalJson,
            previewRevoked = row.previewTokenHash == null,
            uploadCapability =
                row.uploadTokenHash?.let {
                    TestRunView.UploadCapabilityView(
                        checkNotNull(row.uploadExpiresAt),
                        row.uploadConsumedAt,
                    )
                },
        )

    /**
     * Derives the run status (the spec's §11.2): any failed verdict or mechanical failure RED; a missing
     * verdict INCOMPLETE; GREEN requires every case green AND mechanical success.
     */
    private fun deriveStatus(
        cases: List<TestCase>,
        byName: Map<String, SubmittedCase>,
        mechanical: VisualizationMechanicalCheck.Report,
    ): TestRunStatus =
        when {
            cases.any { byName[it.name]?.verdict == CaseVerdict.RED } || !mechanical.ok -> TestRunStatus.RED
            cases.any { it.name !in byName } -> TestRunStatus.INCOMPLETE
            else -> TestRunStatus.GREEN
        }

    /** The stored verdict array: the body's cases in order, each with its verdict or none (INCOMPLETE evidence). */
    private fun casesJson(
        cases: List<TestCase>,
        byName: Map<String, SubmittedCase>,
    ): String =
        ArtifactJson.mapper.writeValueAsString(
            ArtifactJson.mapper.createArrayNode().addAll(
                cases.map { case ->
                    ArtifactJson.mapper.createObjectNode().apply {
                        put("name", case.name)
                        byName[case.name]?.let { submitted ->
                            put("verdict", submitted.verdict.wire)
                            submitted.notes?.let { put("notes", it) }
                        }
                    }
                },
            ),
        )

    @Suppress("ThrowsCount") // each bound is its own named refusal, collected before anything runs
    private fun validateSubmission(
        verdicts: List<SubmittedCase>,
        environment: TestEnvironment?,
    ) {
        val seen = mutableSetOf<String>()
        verdicts.forEach { submitted ->
            if (submitted.name.isBlank()) throw bodyInvalid("case_name_blank", "tests.cases.name")
            if (!seen.add(submitted.name)) throw bodyInvalid("duplicate_case", "tests.cases.${submitted.name.safeEcho()}")
            if ((submitted.notes?.length ?: 0) > SubmittedCase.MAX_NOTES) {
                throw bodyInvalid("notes_too_long", "tests.cases.${submitted.name.safeEcho()}.notes")
            }
        }
        environment?.let { env ->
            val fields = ArtifactJson.mapper.valueToTree<ObjectNodeAlias>(env)
            fields.fieldNames().forEach { field ->
                if (field !in TestEnvironment.FIELDS) throw bodyInvalid("environment_unknown", "environment.$field")
                val value = fields.get(field)
                if (!value.isNull && (!value.isTextual || value.asText().length > TestEnvironment.MAX_FIELD)) {
                    throw bodyInvalid("environment_field_invalid", "environment.$field")
                }
            }
        }
    }

    private fun bodyInvalid(
        reason: String,
        path: String,
    ) = DatapipelinesException(
        code = VisualizationErrorCodes.BODY_INVALID,
        message = "The submission is not storable: $reason at $path.",
        details = mapOf("reason" to reason, "path" to path),
    )

    private fun sessionNotFound() =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
            message = "No such test session for this visualization; its capabilities verify nothing.",
            details = mapOf("reason" to "session_unknown"),
        )

    private fun sessionExpired(reason: String) =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SESSION_EXPIRED,
            message = "The test session is expired or revoked.",
            details = mapOf("reason" to reason),
        )

    private fun screenshotInvalid(reason: String) =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SCREENSHOT_INVALID,
            message = "The screenshot is not a readable PNG or WebP image this run may store.",
            details = mapOf("reason" to reason),
        )

    private fun tooLarge() =
        DatapipelinesException(
            code = VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE,
            message = "The screenshot exceeds the 4 MiB cap.",
            details = mapOf("cap_bytes" to MAX_SCREENSHOT_BYTES),
        )

    private fun lifecycleNotFound(id: UUID) =
        DatapipelinesException(
            code = VisualizationErrorCodes.NOT_FOUND,
            message = "No such visualization, or it has no working version.",
            details = mapOf("id" to id.toString()),
        )

    companion object {
        /** The spec's §17 cap, mirrored by the schema's CHECK — enforced here BEFORE the database sees bytes. */
        const val MAX_SCREENSHOT_BYTES = 4 * 1024 * 1024

        private const val SECONDS_PER_MINUTE = 60L
    }
}

/** Jackson's ObjectNode, for the environment's closed-field walk. */
private typealias ObjectNodeAlias = com.fasterxml.jackson.databind.node.ObjectNode
