package co.datapipelines.visualization

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The `visualization_test_runs` / `visualization_test_screenshots` persistence (V42 + V44, metadata-db
 * §4.32–§4.33) — every statement the session service and the release gate execute. Capability hashes are
 * the ONLY capability material here; the raw form never reaches a parameter of a persistence method.
 *
 * The single-use consumption is ONE guarded UPDATE (`consumeUploadCapability`) — the database's row lock,
 * not a JVM flag, is the replay fence, so it survives a process restart and serializes two competing
 * uploads with exactly one winner.
 */
class TestRunRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    // ---- writes ---------------------------------------------------------------------------------------

    /** Opens the session: a RUNNING run pinned to the exact version, its hash, its cases and its actor. */
    fun insertRunning(run: RunningRun) {
        jdbc.update(
            """
            INSERT INTO visualization_test_runs
                (id, visualization_id, version, body_hash, session_id, preview_token_hash, expires_at,
                 started_by, started_at, status)
            VALUES
                (:id, :visualizationId, :version, :bodyHash, :sessionId, :previewTokenHash, :expiresAt,
                 :startedBy, :startedAt, 'RUNNING')
            """.trimIndent(),
            mapOf(
                "id" to run.id,
                "visualizationId" to run.visualizationId,
                "version" to run.version,
                "bodyHash" to run.bodyHash,
                "sessionId" to run.sessionId,
                "previewTokenHash" to run.previewTokenHash,
                "expiresAt" to Timestamp.from(run.expiresAt),
                "startedBy" to run.startedBy,
                "startedAt" to Timestamp.from(run.startedAt),
            ),
        )
    }

    /**
     * Sweeps ONE due RUNNING session to EXPIRED and revokes its preview capability. The guard is the
     * deadline and the RUNNING status — a completed run is never touched, so an expiry can never
     * retrospectively erase a valid GREEN release record.
     */
    fun expireIfDue(
        id: UUID,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE visualization_test_runs
               SET status = 'EXPIRED', preview_token_hash = NULL
             WHERE id = :id AND status = 'RUNNING' AND expires_at <= :now
            """.trimIndent(),
            mapOf("id" to id, "now" to Timestamp.from(now)),
        ) == 1

    /**
     * Completes the run: the verdicts, the environment, the mechanical report, the preview revocation and
     * the upload capability's mint — one guarded statement. Zero rows means the run was no longer RUNNING
     * for this owner (expired, already submitted, or another actor's): the caller refuses without
     * overwriting anything, so a completed-result replay can never mint a replacement capability.
     */
    fun completeSubmission(submission: Submission): Boolean =
        jdbc.update(
            """
            UPDATE visualization_test_runs
               SET status = :status, completed_at = :completedAt,
                   cases_json = CAST(:casesJson AS jsonb), environment_json = CAST(:environmentJson AS jsonb),
                   mechanical_json = CAST(:mechanicalJson AS jsonb),
                   preview_token_hash = NULL,
                   upload_token_hash = :uploadTokenHash, upload_expires_at = :uploadExpiresAt
             WHERE id = :id AND status = 'RUNNING' AND started_by = :actor AND expires_at > :now
            """.trimIndent(),
            submission.toParams(),
        ) == 1

    /**
     * The single-use consumption: guarded by the exact hash, not consumed, not expired. One UPDATE, one
     * row lock — two competing uploads serialize here and exactly one sees 1. Called in the SAME
     * transaction as the screenshot INSERT, so a failed store consumes nothing.
     */
    fun consumeUploadCapability(
        id: UUID,
        uploadTokenHash: String,
        now: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE visualization_test_runs
               SET upload_consumed_at = NOW()
             WHERE id = :id AND upload_token_hash = :hash AND upload_consumed_at IS NULL AND upload_expires_at > :now
            """.trimIndent(),
            mapOf("id" to id, "hash" to uploadTokenHash, "now" to Timestamp.from(now)),
        ) == 1

    /** Stores the screenshot. One per run (the PK); ON CONFLICT DO NOTHING so the race's loser inserts nothing. */
    fun insertScreenshot(screenshot: StoredScreenshot): Boolean =
        jdbc.update(
            """
            INSERT INTO visualization_test_screenshots
                (run_id, media_type, bytes, sha256, width, height, depicted_case, uploaded_by, uploaded_at)
            VALUES (:runId, :mediaType, :bytes, :sha256, :width, :height, :depictedCase, :uploadedBy, :uploadedAt)
            ON CONFLICT (run_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "runId" to screenshot.runId,
                "mediaType" to screenshot.mediaType,
                "bytes" to screenshot.bytes,
                "sha256" to screenshot.sha256,
                "width" to screenshot.width,
                "height" to screenshot.height,
                "depictedCase" to screenshot.depictedCase,
                "uploadedBy" to screenshot.uploadedBy,
                "uploadedAt" to Timestamp.from(screenshot.uploadedAt),
            ),
        ) == 1

    /**
     * The DRAFT retention (the spec's §2.1, D35): at most one completed run of a DRAFT version keeps its
     * screenshot — the newest store deletes the version's other completed runs' rows. Refused (no-op) when
     * the version is no longer a DRAFT: the run that qualified a RELEASE keeps its screenshot for the
     * version's life.
     */
    fun supersedeDraftScreenshots(
        visualizationId: UUID,
        version: Int,
        keepRunId: UUID,
    ): Int =
        jdbc.update(
            """
            DELETE FROM visualization_test_screenshots s
             USING visualization_test_runs r, visualization_versions v
             WHERE s.run_id = r.id AND r.visualization_id = v.visualization_id AND r.version = v.version
               AND v.visualization_id = :visualizationId AND v.version = :version AND v.status = 'DRAFT'
               AND r.status IN ('GREEN', 'RED', 'INCOMPLETE') AND r.id <> :keepRunId
            """.trimIndent(),
            mapOf("visualizationId" to visualizationId, "version" to version, "keepRunId" to keepRunId),
        )

    /** The gate's candidate lock: the draft version's row, held FOR SHARE until the transaction ends. */
    fun lockCandidateDraft(
        visualizationId: UUID,
        version: Int,
    ): String? =
        jdbc
            .query(
                "SELECT body_hash FROM visualization_versions WHERE visualization_id = :id AND version = :version" +
                    " AND status = 'DRAFT' FOR SHARE",
                mapOf("id" to visualizationId, "version" to version),
            ) { rs, _ -> rs.getString("body_hash") }
            .singleOrNull()

    // ---- reads ----------------------------------------------------------------------------------------

    /** The run by session, workspace-scoped — the session id the caller holds. */
    fun findBySession(
        workspaceId: UUID,
        visualizationId: UUID,
        sessionId: UUID,
    ): TestRunRow? =
        jdbc
            .query(
                "$SELECT WHERE s.workspace_id = :workspaceId AND r.visualization_id = :visualizationId" +
                    " AND r.session_id = :sessionId",
                params(workspaceId, visualizationId) + ("sessionId" to sessionId),
                MAPPER,
            ).singleOrNull()

    /** The run by run id, workspace-scoped. */
    fun findById(
        workspaceId: UUID,
        visualizationId: UUID,
        id: UUID,
    ): TestRunRow? =
        jdbc
            .query(
                "$SELECT WHERE s.workspace_id = :workspaceId AND r.visualization_id = :visualizationId AND r.id = :id",
                params(workspaceId, visualizationId) + ("id" to id),
                MAPPER,
            ).singleOrNull()

    /** The runs of one visualization, newest first (the evidence read; #353 binds the transport). */
    fun listByVisualization(
        workspaceId: UUID,
        visualizationId: UUID,
        limit: Int = DEFAULT_LIMIT,
    ): List<TestRunRow> =
        jdbc.query(
            "$SELECT WHERE s.workspace_id = :workspaceId AND r.visualization_id = :visualizationId" +
                " ORDER BY r.started_at DESC, r.id DESC LIMIT :limit",
            params(workspaceId, visualizationId) + ("limit" to limit),
            MAPPER,
        )

    /**
     * The gate's evidence read: the version's LATEST run by start, any status (the spec's §11.4 judges THE
     * LATEST run — a newer red run refuses what an older green one passed).
     */
    fun latestRun(
        visualizationId: UUID,
        version: Int,
    ): TestRunRow? =
        jdbc
            .query(
                "$SELECT WHERE r.visualization_id = :id AND r.version = :version ORDER BY r.started_at DESC, r.id DESC LIMIT 1",
                mapOf("id" to visualizationId, "version" to version),
                MAPPER,
            ).singleOrNull()

    /** The run's screenshot, or null. */
    fun screenshotOf(runId: UUID): ScreenshotView? =
        jdbc
            .query(
                """
                SELECT run_id, media_type, sha256, width, height, depicted_case, uploaded_by, uploaded_at
                  FROM visualization_test_screenshots WHERE run_id = :runId
                """.trimIndent(),
                mapOf("runId" to runId),
            ) { rs, _ ->
                ScreenshotView(
                    runId,
                    rs.getString("media_type"),
                    rs.getString("sha256"),
                    rs.getInt("width"),
                    rs.getInt("height"),
                    rs.getString("depicted_case"),
                    rs.getObject("uploaded_by", UUID::class.java),
                    rs.getTimestamp("uploaded_at").toInstant(),
                )
            }.singleOrNull()

    private fun params(
        workspaceId: UUID,
        visualizationId: UUID,
    ): Map<String, Any> = mapOf("workspaceId" to workspaceId, "visualizationId" to visualizationId)

    /** A session start's INSERT payload — the minted preview hash and the deadline travel with it. */
    data class RunningRun(
        val id: UUID,
        val visualizationId: UUID,
        val version: Int,
        val bodyHash: String,
        val sessionId: UUID,
        val previewTokenHash: String,
        val expiresAt: Instant,
        val startedBy: UUID,
        val startedAt: Instant,
    )

    /** A completed submission's UPDATE payload. */
    data class Submission(
        val id: UUID,
        val actor: UUID,
        val now: Instant,
        val status: TestRunStatus,
        val completedAt: Instant,
        val casesJson: String,
        val environmentJson: String?,
        val mechanicalJson: String,
        val uploadTokenHash: String?,
        val uploadExpiresAt: Instant?,
    ) {
        fun toParams(): Map<String, Any?> =
            mapOf(
                "id" to id,
                "actor" to actor,
                "now" to Timestamp.from(now),
                "status" to status.name,
                "completedAt" to Timestamp.from(completedAt),
                "casesJson" to casesJson,
                "environmentJson" to environmentJson,
                "mechanicalJson" to mechanicalJson,
                "uploadTokenHash" to uploadTokenHash,
                "uploadExpiresAt" to uploadExpiresAt?.let { Timestamp.from(it) },
            )
    }

    /** A stored screenshot's INSERT payload — the validated bytes and their computed digest. */
    data class StoredScreenshot(
        val runId: UUID,
        val mediaType: String,
        val bytes: ByteArray,
        val sha256: String,
        val width: Int,
        val height: Int,
        val depictedCase: String?,
        val uploadedBy: UUID,
        val uploadedAt: Instant,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = runId.hashCode()
    }

    private companion object {
        const val DEFAULT_LIMIT = 100

        /**
         * The read joins the version row so a reader sees the version's CURRENT hash beside the run's —
         * [TestRunRow.effectiveStatus] projects EXPIRED for a run whose content moved on (the spec's §2.1).
         */
        val SELECT = """
            SELECT r.id, s.workspace_id, r.visualization_id, r.version, r.body_hash, r.session_id, r.preview_token_hash,
                   r.expires_at, r.started_by, r.started_at, r.completed_at, r.status,
                   r.cases_json, r.environment_json, r.mechanical_json,
                   r.upload_token_hash, r.upload_expires_at, r.upload_consumed_at,
                   v.body_hash AS version_body_hash
              FROM visualization_test_runs r
              JOIN visualizations s ON s.id = r.visualization_id
              JOIN visualization_versions v ON v.visualization_id = r.visualization_id AND v.version = r.version
        """.trimIndent()

        val MAPPER =
            RowMapper { rs: ResultSet, _ ->
                TestRunRow(
                    id = rs.getObject("id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    visualizationId = rs.getObject("visualization_id", UUID::class.java),
                    version = rs.getInt("version"),
                    bodyHash = rs.getString("body_hash"),
                    sessionId = rs.getObject("session_id", UUID::class.java),
                    previewTokenHash = rs.getString("preview_token_hash"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    startedBy = rs.getObject("started_by", UUID::class.java),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                    status = TestRunStatus.valueOf(rs.getString("status")),
                    casesJson = rs.getString("cases_json")?.let { ArtifactJson.mapper.readTree(it) },
                    environmentJson = rs.getString("environment_json")?.let { ArtifactJson.mapper.readTree(it) },
                    mechanicalJson = rs.getString("mechanical_json")?.let { ArtifactJson.mapper.readTree(it) },
                    uploadTokenHash = rs.getString("upload_token_hash"),
                    uploadExpiresAt = rs.getTimestamp("upload_expires_at")?.toInstant(),
                    uploadConsumedAt = rs.getTimestamp("upload_consumed_at")?.toInstant(),
                    versionBodyHash = rs.getString("version_body_hash"),
                )
            }
    }
}
