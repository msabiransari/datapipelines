package co.datapipelines.lake.maintenance.remove

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier

/** What one delete call answered (D9a). */
sealed interface DeleteOutcome {
    /** The store answered: only [deleted] are gone; every other requested key was refused or not named, so it is left. */
    data class Answered(
        val deleted: Set<String>,
        val failed: Set<String>,
    ) : DeleteOutcome

    /** The call threw or its answer was lost: any requested key may or may not be gone. */
    data class Unknown(
        val cause: Throwable,
    ) : DeleteOutcome
}

/**
 * THE deletion point of the managed lake (D9, record §8b "one remover"): every S3 delete the product issues is in this
 * file, and `LakeDeletionArchitectureTest` fails when `deleteObject`, `deleteObjects`, `DeleteObjectRequest` or
 * `DeleteObjectsRequest` appears in any other main source. It decides nothing: [LakeRemover] hands it keys that passed every
 * test of §8a and §8b, at most [MAX_BATCH] at a time, under the lease and mode checks it made for that batch.
 *
 * A returned response is not proof (D9a): a key counts as deleted only when the response names it in `deleted()`.
 */
internal class LakeObjectDeleter(
    private val s3: S3Client,
    private val bucket: String,
) {
    fun delete(keys: Collection<String>): DeleteOutcome {
        require(keys.size in 1..MAX_BATCH) { "a delete batch holds 1..$MAX_BATCH keys, not ${keys.size}" }
        val requested = keys.toSet()
        val request =
            DeleteObjectsRequest
                .builder()
                .bucket(bucket)
                .delete(
                    Delete
                        .builder()
                        .objects(requested.map { ObjectIdentifier.builder().key(it).build() })
                        .quiet(false)
                        .build(),
                ).build()
        val response =
            try {
                s3.deleteObjects(request)
            } catch (
                // Whatever ends the call (an SDK failure, a lost answer, a client bug), no requested key is known to be
                // gone or kept, and nothing may throw out of a pass (D9a).
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                return DeleteOutcome.Unknown(e)
            }
        val deleted =
            response
                .deleted()
                .mapNotNull { it.key() }
                .filter { it in requested }
                .toSet()
        return DeleteOutcome.Answered(deleted = deleted, failed = requested - deleted)
    }

    companion object {
        /** S3's own ceiling for one multi-object delete, and the record's §8a (iv) batch bound. */
        const val MAX_BATCH = 1000
    }
}
