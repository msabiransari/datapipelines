package co.datapipelines.pipeline

import java.time.Instant
import java.util.UUID

/**
 * One row of the `pipelines` table (metadata-db §4.4) — the pipeline's **server-owned**
 * metadata.
 *
 * This is where the protected fields live: [id], [ownerId], [currentVersion], [isDeleted],
 * [createdAt], [updatedAt]. None of them appears on [Pipeline], the authorable body, so no
 * inbound payload can set them — the absent-field discipline, not an `@JsonIgnoreProperties`
 * filter that Jackson deserialization advisories are known to bypass.
 *
 * The body itself is not here: it lives in `pipeline_versions.body_json`, one immutable row
 * per version ([PipelineRepository.findVersionBody]). A surface that needs the §3.1 shape
 * composes a record with a body; nothing denormalizes one into the other.
 */
data class PipelineRecord(
    val id: UUID,
    val name: String,
    val displayName: String,
    val description: String,
    val ownerId: UUID,
    val currentVersion: Int,
    val isDeleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The metadata of one `pipeline_versions` row (metadata-db §4.5), without the body.
 *
 * Versions are immutable — never updated, only inserted — which is why the table carries no
 * `updated_at` and this type carries no modification time. `GET /pipelines/{id}/versions`
 * (§14) is the listing this shape serves.
 */
data class PipelineVersionRecord(
    val pipelineId: UUID,
    val version: Int,
    val createdAt: Instant,
    val createdBy: UUID,
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
