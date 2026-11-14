package co.datapipelines.lake.maintenance.remove

import java.util.UUID

/**
 * The port the remover asks whether a run is over (D5): `true` = its run row is terminal (finished, failed or abandoned),
 * `false` = live, `null` = no row. Only `true` lets another run's old marker go. 962 ships the implementation over
 * `lake_maintenance_runs`.
 */
fun interface RunStates {
    fun isTerminal(runId: UUID): Boolean?
}
