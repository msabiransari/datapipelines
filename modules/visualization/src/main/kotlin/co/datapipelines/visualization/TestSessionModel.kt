package co.datapipelines.visualization

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * The test-session wire and row shapes (the spec's §2.1, §11.2–§11.3; the owner's 2026-09-30 ruling).
 * Every DTO that leaves the service is REDACTED: a capability's presence is a boolean, never a hash,
 * never material — the raw form exists only in the minting call's answer.
 *
 * One case's verdict as the agent submits it. A case the submission omits has NO verdict (INCOMPLETE).
 */
enum class CaseVerdict(
    @JsonValue val wire: String,
) {
    GREEN("green"),
    RED("red"),
    ;

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
    }
}

/** One submitted case verdict. [notes] is bounded at [MAX_NOTES] characters; nothing else is stored. */
data class SubmittedCase(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("verdict") @get:JsonProperty("verdict") @param:JsonProperty("verdict")
    val verdict: CaseVerdict,
    @field:JsonProperty("notes") @get:JsonProperty("notes") @param:JsonProperty("notes")
    val notes: String? = null,
) {
    companion object {
        /** The per-case notes bound (the brief's §A; `cases_json` stores them verbatim under this cap). */
        const val MAX_NOTES = 2_000
    }
}

/**
 * The agent-reported environment (the spec's §2.1: theme, viewport, browser, locale, renderer version).
 * The five fields are the CLOSED set — an unknown field refuses the submission — each a bounded string.
 * These are claims, never server measurements; [dashboards.md] states that where they are shown.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TestEnvironment(
    @field:JsonProperty("theme") @get:JsonProperty("theme") @param:JsonProperty("theme")
    val theme: String? = null,
    @field:JsonProperty("viewport") @get:JsonProperty("viewport") @param:JsonProperty("viewport")
    val viewport: String? = null,
    @field:JsonProperty("browser") @get:JsonProperty("browser") @param:JsonProperty("browser")
    val browser: String? = null,
    @field:JsonProperty("locale") @get:JsonProperty("locale") @param:JsonProperty("locale")
    val locale: String? = null,
    @field:JsonProperty("renderer_version") @get:JsonProperty("renderer_version") @param:JsonProperty("renderer_version")
    val rendererVersion: String? = null,
) {
    companion object {
        val FIELDS: List<String> = listOf("theme", "viewport", "browser", "locale", "renderer_version")

        /** Each field is a short label; 120 characters bounds every one of them. */
        const val MAX_FIELD = 120
    }
}

/** A stored run row, as the repository answers it. [versionBodyHash] is the version's CURRENT hash. */
data class TestRunRow(
    val id: UUID,
    val workspaceId: UUID,
    val visualizationId: UUID,
    val version: Int,
    val bodyHash: String,
    val sessionId: UUID,
    val previewTokenHash: String?,
    val expiresAt: Instant,
    val startedBy: UUID,
    val startedAt: Instant,
    val completedAt: Instant?,
    val status: TestRunStatus,
    val casesJson: JsonNode?,
    val environmentJson: JsonNode?,
    val mechanicalJson: JsonNode?,
    val uploadTokenHash: String?,
    val uploadExpiresAt: Instant?,
    val uploadConsumedAt: Instant?,
    val versionBodyHash: String,
) {
    /**
     * The status a reader acts on: a completed run whose version's content moved on is EXPIRED on read
     * (the spec's §2.1) — projected, never written: the release gate judges the CURRENT hash directly.
     */
    val effectiveStatus: TestRunStatus
        get() =
            if (status == TestRunStatus.EXPIRED || status == TestRunStatus.RUNNING) {
                status
            } else if (versionBodyHash != bodyHash) {
                TestRunStatus.EXPIRED
            } else {
                status
            }

    val completed: Boolean get() = status != TestRunStatus.RUNNING && status != TestRunStatus.EXPIRED

    /**
     * The status the RELEASE GATE acts on at [now] (the 352 merge's F1/F6): a RUNNING row whose deadline passed and
     * that no read has swept yet IS expired — the sweep is a write the gate must not depend on (a submit's own
     * sweep rolled back with the refusal it caused, so an unswept due row was the common case).
     */
    fun statusAt(now: Instant): TestRunStatus =
        if (status == TestRunStatus.RUNNING && !expiresAt.isAfter(now)) TestRunStatus.EXPIRED else status
}

/** What a session start answers. [previewToken] is the raw capability — shown once, never stored. */
data class TestSessionStarted(
    val runId: UUID,
    val sessionId: UUID,
    val visualizationId: UUID,
    val version: Int,
    val bodyHash: String,
    val expiresAt: Instant,
    /** The preview capability's material (base64url). The server keeps only its hash. */
    val previewToken: String,
    /** The case inventory the submission must answer — the body's case names, in order. */
    val cases: List<String>,
)

/** What a results submission answers. [uploadToken] is the raw capability — shown once, never stored. */
data class TestSessionSubmitted(
    val runId: UUID,
    val sessionId: UUID,
    val status: TestRunStatus,
    val completedAt: Instant,
    /** The mechanical outcome recorded on the run (the §11.3 report, verbatim). */
    val mechanical: JsonNode,
    /** The upload capability's material (base64url) — null on an INCOMPLETE/RED run: evidence of failure needs no screenshot. */
    val uploadToken: String?,
    /** The capability's deadline — no later than the session's original one. */
    val uploadExpiresAt: Instant?,
)

/** A run as any read answers it — every capability reduced to presence. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TestRunView(
    @field:JsonProperty("run_id") @get:JsonProperty("run_id") @param:JsonProperty("run_id")
    val runId: UUID,
    @field:JsonProperty("session_id") @get:JsonProperty("session_id") @param:JsonProperty("session_id")
    val sessionId: UUID,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int,
    @field:JsonProperty("body_hash") @get:JsonProperty("body_hash") @param:JsonProperty("body_hash")
    val bodyHash: String,
    @field:JsonProperty("status") @get:JsonProperty("status") @param:JsonProperty("status")
    val status: TestRunStatus,
    @field:JsonProperty("started_at") @get:JsonProperty("started_at") @param:JsonProperty("started_at")
    val startedAt: Instant,
    @field:JsonProperty("completed_at") @get:JsonProperty("completed_at") @param:JsonProperty("completed_at")
    val completedAt: Instant?,
    @field:JsonProperty("expires_at") @get:JsonProperty("expires_at") @param:JsonProperty("expires_at")
    val expiresAt: Instant,
    @field:JsonProperty("cases") @get:JsonProperty("cases") @param:JsonProperty("cases")
    val cases: JsonNode?,
    @field:JsonProperty("environment") @get:JsonProperty("environment") @param:JsonProperty("environment")
    val environment: JsonNode?,
    @field:JsonProperty("mechanical") @get:JsonProperty("mechanical") @param:JsonProperty("mechanical")
    val mechanical: JsonNode?,
    @field:JsonProperty("preview_revoked") @get:JsonProperty("preview_revoked") @param:JsonProperty("preview_revoked")
    val previewRevoked: Boolean,
    @field:JsonProperty("upload_capability") @get:JsonProperty("upload_capability") @param:JsonProperty("upload_capability")
    val uploadCapability: UploadCapabilityView?,
) {
    /** The upload capability's REDACTED shape — presence and stamps, never its hash or material. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class UploadCapabilityView(
        @field:JsonProperty("expires_at") @get:JsonProperty("expires_at") @param:JsonProperty("expires_at")
        val expiresAt: Instant,
        @field:JsonProperty("consumed_at") @get:JsonProperty("consumed_at") @param:JsonProperty("consumed_at")
        val consumedAt: Instant?,
    )
}

/** A stored screenshot, as reads answer it: the bytes only on the dedicated bytes read. */
data class ScreenshotView(
    val runId: UUID,
    val mediaType: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val depictedCase: String?,
    val uploadedBy: UUID,
    val uploadedAt: Instant,
)

/** A run row plus its screenshot, for the retention and evidence reads. */
data class RunEvidence(
    val run: TestRunRow,
    val screenshot: ScreenshotView?,
)
