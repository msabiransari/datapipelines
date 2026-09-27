package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import java.time.Instant
import java.util.UUID

/**
 * One `parameter_sets` row — the INDEX over a set's versions (record §8.1; metadata-db §4.26).
 * [displayName] / [description] index the CURRENT version's body (versioning §3.7's pipeline rule:
 * they are content and ride the release). [currentVersion] is the sticky pointer (D60), null until
 * the first release. There is no stored entity status: [ParameterSetService] derives it.
 */
data class ParameterSetRecord(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val displayName: String,
    val description: String,
    val currentVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
)

/** One `parameter_set_versions` row's lifecycle metadata, without the body (the `TemplateVersionDetail` twin). */
data class ParameterSetVersionDetail(
    val parameterSetId: UUID,
    val version: Int,
    val status: PipelineVersionStatus,
    /** SHA-256 (hex) of the JSONB text projection of the stored body — the precondition token (versioning §4). */
    val bodyHash: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant? = null,
    val releasedBy: UUID? = null,
    val discardedAt: Instant? = null,
    val discardedBy: UUID? = null,
    val updatedBy: UUID? = null,
    val updatedAt: Instant? = null,
    val createdVia: String = WriteSurface.SESSION.wire,
    val updatedVia: String = WriteSurface.SESSION.wire,
)

/** A stored version with its body and the index row it belongs to — what an authoring read answers. */
data class ParameterSetVersion(
    val record: ParameterSetRecord,
    val detail: ParameterSetVersionDetail,
    val body: ParameterSetBody,
) {
    /** The §3 document again — the name from the index row, the body from the version (record §8.1). */
    val document: ParameterSetDocument get() = ParameterSetDocument(record.name, body)
}

/**
 * One virtual folder of the set tree — a name prefix and nothing else (the `TemplateFolder` shape):
 * derived per request from the live sets beneath it, so [setCount] is always ≥ 1.
 */
data class ParameterSetFolder(
    val path: String,
    val segment: String,
    val setCount: Int,
)

/** A live set and the version its pointer names — the promoter lens's input (versioning §10.2's rule). */
data class CurrentParameterSetVersion(
    val id: UUID,
    val name: String,
    val displayName: String,
    val version: Int,
    val bodyHash: String,
)
