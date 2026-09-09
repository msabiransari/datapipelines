package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import java.time.Instant
import java.util.UUID

/**
 * One `template_versions` row's lifecycle metadata, without the body — the template-side
 * mirror of `PipelineVersionDetail` (versioning §6: same lifecycle, plus the pin rule).
 *
 * [bodyHash] is the SHA-256 of the canonical template body — the version-owned field
 * object `{engine, dialect, is_library, imports, body}` projected through JSONB and hashed
 * by the database with the one expression V6's backfill used. `display_name` /
 * `description` are NOT in the canonical body: they live on the index row `templates`
 * only and are not part of the versioned artifact (versioning §3.7 has no template-side
 * draft metadata to stage — the asymmetry is documented in v1.3).
 *
 * [discardedAt]/[discardedBy] are the discard stamps (§3.1, V19): both NULL unless the
 * status is DISCARDED; restore clears them and never touches `released_at`/`released_by`.
 */
data class TemplateVersionDetail(
    val templateId: String,
    val version: Int,
    val status: PipelineVersionStatus,
    val bodyHash: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant? = null,
    val releasedBy: UUID? = null,
    val discardedAt: Instant? = null,
    val discardedBy: UUID? = null,
    val updatedBy: UUID? = null,
    val updatedAt: Instant? = null,
    /** Which surface created the row (V20, 102) — the person is [createdBy], always. */
    val createdVia: String = co.datapipelines.pipeline.WriteSurface.SESSION.wire,
    /** Which surface made the last draft write (V20, 102). */
    val updatedVia: String = co.datapipelines.pipeline.WriteSurface.SESSION.wire,
)
