package co.datapipelines.pipeline.release

import java.util.UUID

/**
 * Told, INSIDE the metadata transaction that makes it so, that one version of a pipeline or a parameter set is now
 * RELEASED here — by a release (`ReleaseCoordinator`'s flips on both of its paths, `ParameterSetService.release`) or by
 * an import that landed it (`PipelineImportService`, `ParameterSetService.importValidated`: promotion, the REST import,
 * the bootstrap seeder). The lake reference scanner (#778, reference-scanner slice D-D) is the one implementation: it
 * indexes which lake tables the version reads, in the same transaction, so the index can never disagree with what is
 * released.
 *
 * An observer that throws rolls the release or the import back with it — that is the point of running inside the
 * transaction (the owner's 2026-11-02 seam ruling: a version whose scan failed is not released). An implementation
 * therefore turns every author-side problem (a render failure, SQL the parser refuses) into DATA — a scan status — and
 * throws only for a fault of the platform itself.
 *
 * Deliberately not a `fun interface`: the constructors that take it as their last parameter must never bind a caller's
 * trailing lambda to it by SAM conversion.
 */
interface ReleasedVersionObserver {
    fun released(version: ReleasedVersion)

    companion object {
        /** No observer — a directly constructed service in a test, and every path that indexes nothing. */
        val NONE: ReleasedVersionObserver =
            object : ReleasedVersionObserver {
                override fun released(version: ReleasedVersion) = Unit
            }
    }
}

/**
 * One version that became RELEASED: the workspace, the family ([ReleaseFamily.PIPELINE] or [ReleaseFamily.PARAMETER_SET]),
 * the entity's id, the number.
 */
data class ReleasedVersion(
    val workspaceId: UUID,
    val family: ReleaseFamily,
    val entityId: UUID,
    val version: Int,
)
