package co.datapipelines.lake.maintenance

/** The maintenance audit events (#957 D10; Enums §15), written outside any transaction at the end of an admitted run. */
object LakeMaintenanceAuditEvents {
    /** One per admitted run, at its end: the table, the run, its state and the parts' outcomes; actor = the system user. */
    const val RUN_FINISHED = "lake.maintenance.run_finished"
    val ALL: Set<String> = setOf(RUN_FINISHED)
}
