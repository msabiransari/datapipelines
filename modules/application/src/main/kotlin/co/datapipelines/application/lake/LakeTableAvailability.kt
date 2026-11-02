package co.datapipelines.application.lake

import co.datapipelines.datasources.LakeTableCatalog

/**
 * The promotion check's predicate (#778, slice D-E): may a promoted version read this lake table HERE? Implemented
 * today as "a `lake_tables` row is present" ([RegisteredLakeTableAvailability]); dp-lake P3 extends it with the table
 * lifecycle (an archived or purged table answers [LakeTableState.ARCHIVED]) without touching the check.
 */
fun interface LakeTableAvailability {
    fun stateOf(
        datasourceName: String,
        table: LakeTableIdentity,
    ): LakeTableState
}

/** What [LakeTableAvailability] answered; [wire] is the `state` word of `pipeline.promotion.lake_table_unavailable`. */
enum class LakeTableState(
    val wire: String,
    val available: Boolean,
) {
    AVAILABLE("available", available = true),
    ABSENT("absent", available = false),
}

/**
 * "Row present", over the datasource's registry as the LAKE connection sees it ([LakeTableCatalog] — the same read the
 * pool's view generation takes). Identifiers compare case-insensitively ([LakeTableIdentity.sameAs]).
 */
class RegisteredLakeTableAvailability(
    private val catalog: LakeTableCatalog,
) : LakeTableAvailability {
    override fun stateOf(
        datasourceName: String,
        table: LakeTableIdentity,
    ): LakeTableState =
        if (catalog.registeredTables(datasourceName).any { LakeTableIdentity(it.namespace, it.name).sameAs(table) }) {
            LakeTableState.AVAILABLE
        } else {
            LakeTableState.ABSENT
        }
}
