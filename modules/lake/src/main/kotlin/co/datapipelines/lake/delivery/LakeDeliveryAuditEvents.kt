package co.datapipelines.lake.delivery

/** Delivery audit names, emitted in the same transaction as the conditional delivery/attempt stamps. */
object LakeDeliveryAuditEvents {
    const val PUBLISHED = "lake.delivery.published"
    const val FAILED = "lake.delivery.failed"
    const val UNKNOWN = "lake.delivery.unknown"
    const val SETTLED = "lake.delivery.settled"
    val ALL: Set<String> = setOf(PUBLISHED, FAILED, UNKNOWN, SETTLED)
}
