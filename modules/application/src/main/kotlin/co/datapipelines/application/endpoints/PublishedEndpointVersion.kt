package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.PipelineVersionStatus
import java.time.Instant
import java.util.UUID

/**
 * The versioned body of an API (dependency-graph record §5.3, §24): the EXACT pipeline pin — the portable pipeline
 * [pipelineName] and its exact [pipelineVersion], never "current" — plus the [timeoutSeconds] and the [description].
 * These four fields, and only these, are the body hash's inputs (A44); the path, the UUIDs, the enabled flag and the
 * key bindings are entity-level or deployment-local and never part of it.
 */
data class EndpointVersionBody(
    val pipelineName: String,
    val pipelineVersion: Int,
    val timeoutSeconds: Int,
    val description: String,
) {
    init {
        require(pipelineName.isNotBlank()) { "an API version pins a named pipeline" }
        require(pipelineVersion >= 1) { "an API version pins an exact pipeline version >= 1" }
    }
}

/**
 * One row of `published_endpoint_versions` (V58, record §24): immutable once read. A RELEASED body never changes; a
 * DRAFT body changes at the same [version] and its [bodyHash] is recomputed by the database's one expression
 * ([PublishedEndpointVersionRepository.API_BODY_HASH_EXPR]). [status] reuses the pipeline lifecycle vocabulary because
 * the eligibility rule is the same function ([PipelineVersionStatus.eligibleForPointer]).
 */
data class PublishedEndpointVersion(
    val endpointId: UUID,
    val version: Int,
    val status: PipelineVersionStatus,
    val body: EndpointVersionBody,
    val bodyHash: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant?,
    val releasedBy: UUID?,
)
