package co.datapipelines.pipeline

import java.time.Instant
import java.util.UUID

/**
 * One row of the `pipelines` table (metadata-db §4.4) — the pipeline's **server-owned**
 * metadata.
 *
 * This is where the protected fields live: [id], [ownerId], [currentVersion],
 * [createdAt], [updatedAt]. None of them appears on [Pipeline], the authorable body, so no
 * inbound payload can set them — the absent-field discipline, not an `@JsonIgnoreProperties`
 * filter that Jackson deserialization advisories are known to bypass.
 *
 * The body itself is not here: it lives in `pipeline_versions.body_json`, one immutable row
 * per version ([PipelineRepository.findVersionBody]). A surface that needs the §3.1 shape
 * composes a record with a body; nothing denormalizes one into the other.
 *
 * There is **no entity status field, deliberately** (versioning §3.2, since V19): a
 * pipeline is ACTIVE while any version is DRAFT or RELEASED and DISCARDED when every
 * version is — a derivation over rows this record does not carry. Readers that need it
 * ask the repository ([PipelineRepository.hasLiveVersion]); a stored flag is exactly the
 * drift the derivation exists to prevent.
 */
data class PipelineRecord(
    val id: UUID,
    val name: String,
    val displayName: String,
    val description: String,
    val ownerId: UUID,
    /**
     * The **sticky pointer** (D60, since 101): the version every pointer-following
     * dependent runs. NULL when nothing has been released (D55, V18) or when the release
     * it named was discarded with no eligible survivor (§3.4). It moves only on the events
     * §3.4 lists — it is no longer a derived "latest released" fact.
     *
     * Every read that means "what would a dependent run" (published endpoints, promotion,
     * schedules) uses THIS; every read that means "what is the pipeline right now" uses
     * [PipelineService.workingVersion] instead.
     */
    val currentVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The metadata of one `pipeline_versions` row (metadata-db §4.5), without the body.
 *
 * Since V6 a version carries its lifecycle status and content hash (versioning §3.1/§4);
 * `GET /pipelines/{id}/versions` (§14) is the listing this shape serves. RELEASED and
 * DISCARDED rows are never UPDATEd; a DRAFT row may be — the one bounded exception to
 * append-only, per versioning §3.1's immutability discipline.
 */
data class PipelineVersionRecord(
    val pipelineId: UUID,
    val version: Int,
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    val bodyHash: String = "",
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant? = null,
    /** Which surface created the row (V20, 102) — `'session' | 'api_key' | 'mcp'`. */
    val createdVia: String = WriteSurface.SESSION.wire,
    /** Which surface made the last draft write (V20, 102) — what a DRAFT row should show. */
    val updatedVia: String = WriteSurface.SESSION.wire,
)

/**
 * The row-shaped input for creating a pipeline; the body travels beside it as JSON.
 *
 * [id] is supplied rather than left to the database default because §11.3's promotion flow
 * re-imports a pipeline into another environment **under its original id** — a
 * server-generated id would break the identity that makes promotion work.
 */
data class NewPipeline(
    val id: UUID,
    val name: String,
    val displayName: String,
    val description: String,
    val ownerId: UUID,
) {
    companion object {
        /**
         * Derives the row fields from a validated body, minting a fresh id.
         *
         * `name`, `display_name` and `description` exist in both the row and `body_json`;
         * deriving them here is what keeps the two copies from diverging on create.
         */
        fun from(
            pipeline: Pipeline,
            ownerId: UUID,
            id: UUID = UUID.randomUUID(),
        ): NewPipeline =
            NewPipeline(
                id = id,
                name = pipeline.name,
                displayName = pipeline.displayName,
                description = pipeline.description,
                ownerId = ownerId,
            )
    }
}
