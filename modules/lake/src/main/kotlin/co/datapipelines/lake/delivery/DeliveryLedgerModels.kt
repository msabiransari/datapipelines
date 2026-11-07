package co.datapipelines.lake.delivery

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

/** Permanent delivery states (enums.md §49, V55's CHECK). UNKNOWN is never a negative commit verdict. */
enum class LakeDeliveryState {
    RECEIVED,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    UNKNOWN,
}

/** Where the execution stopped before publication, or the publication itself (enums.md §50). */
enum class LakeDeliveryFailurePhase(
    @get:JsonValue val wire: String,
) {
    LISTING("listing"),
    STAGING("staging"),
    VALIDATION("validation"),
    PUBLISH("publish"),
}

/** One permanent attempt's outcome (enums.md §51); absent while it has no recorded outcome. */
enum class LakeDeliveryAttemptOutcome {
    PUBLISHED,
    FAILED,
    UNKNOWN,
}

/** The immutable identity and current owner of one ledger row. */
data class LakeDelivery(
    val id: UUID,
    val tableId: UUID,
    val key: DeliveryKey,
    val fingerprint: String,
    val state: LakeDeliveryState,
    val operationId: UUID,
    val executionId: UUID,
    val pipelineId: UUID,
    val receivedAt: Instant,
    val metadataLocationAfter: String?,
)

/** Admission inputs, already listed and fingerprinted by DP. The operation id is fresh per target table. */
data class ReceiveDelivery(
    val tableId: UUID,
    val manifest: DeliveryManifest,
    val operationId: UUID,
    val executionId: UUID,
    val pipelineId: UUID,
)

/** No retry is hidden here: settlement and changed-input refusals belong to the landing boundary. */
sealed interface DeliveryReceipt {
    val delivery: LakeDelivery

    data class Accepted(
        override val delivery: LakeDelivery,
    ) : DeliveryReceipt

    data class AlreadyPublished(
        override val delivery: LakeDelivery,
    ) : DeliveryReceipt

    data class Changed(
        override val delivery: LakeDelivery,
    ) : DeliveryReceipt

    data class NeedsSettlement(
        override val delivery: LakeDelivery,
    ) : DeliveryReceipt
}

/** A terminal stamp or an explicitly uncertain commit; never carries a raw exception or SQL text. */
sealed interface DeliveryConclusion {
    data class Published(
        val metadataLocation: String,
        val snapshotId: Long? = null,
    ) : DeliveryConclusion

    data class Failed(
        val phase: LakeDeliveryFailurePhase,
        val code: String?,
    ) : DeliveryConclusion

    data class Unknown(
        val code: String?,
    ) : DeliveryConclusion
}

/** Trigger-written durable evidence, independent of snapshot retention. */
data class DeliveryCommitEvidence(
    val operationId: UUID,
    val revisionSequence: Long,
    val metadataLocation: String,
)
