package co.datapipelines.pipeline

import java.time.Instant
import java.util.UUID

/**
 * In-process evidence that an execute entry point checked the effective principal's lake.append
 * permission. Never bound from a caller payload; a composed child inherits its parent's grant.
 */
data class LakeAppendGrant(
    val grantedBy: UUID,
    val principalKind: String,
    val at: Instant,
)
