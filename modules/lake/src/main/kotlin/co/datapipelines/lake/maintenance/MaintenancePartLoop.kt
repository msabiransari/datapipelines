package co.datapipelines.lake.maintenance

/**
 * The part loop of one admitted run (#957 D6, D7): the parts in their order, each asked of the gate, then whether it is
 * due now, then run. [records] grows as the loop goes and [progress] is called after every part, so the row shows what
 * has happened so far while it stays `running`.
 *
 * A part answering `busy` or `deadline` ends the loop: no later part is started, and each later part that declared it
 * expected to be due is recorded with that outcome and `attempted: false`. The test is the declaration (database rows),
 * not `dueNow`: after the run has lost the table, nothing more is read from it. A part that throws is recorded `failed`
 * with its code and the throw leaves the loop.
 */
internal class MaintenancePartLoop(
    private val gate: MaintenanceGate,
    private val progress: (List<PartRecord>) -> Unit,
) {
    val records = mutableListOf<PartRecord>()

    fun run(
        run: MaintenanceRun,
        parts: List<MaintenancePart>,
        declared: Map<String, MaintenanceDeclaration>,
    ) {
        var ended: MaintenanceOutcome? = null
        for (part in parts) {
            val declaration = declared.getValue(part.name)
            if (ended == null) ended = endBefore(run)
            val stop = ended
            if (stop != null) {
                val outcome = if (declaration.expectsDue) stop else MaintenanceOutcome.NOTHING_TO_DO
                records += PartRecord(part.name, outcome, attempted = false)
            } else {
                ended = runOne(run, part, declaration)
            }
            progress(records.toList())
        }
    }

    /** Between parts (D7): an interrupt throws; a deadline the catalog's clock has reached ends the loop. */
    private fun endBefore(run: MaintenanceRun): MaintenanceOutcome? {
        run.checkInterrupt()
        return if (run.pastDeadline()) MaintenanceOutcome.DEADLINE else null
    }

    /** One part; answers the outcome that ends the loop, or null to go on. */
    private fun runOne(
        run: MaintenanceRun,
        part: MaintenancePart,
        declaration: MaintenanceDeclaration,
    ): MaintenanceOutcome? {
        when (val answer = gate.admit(run.table, part)) {
            is GateAnswer.Held -> {
                records +=
                    PartRecord(part.name, answer.result.outcome, attempted = false, until = answer.result.until, reason = answer.reason)
                return null
            }

            GateAnswer.Admit -> {
                // admitted: the checks below decide whether the part runs
            }
        }
        if (!part.dueNow(run)) {
            records += PartRecord(part.name, MaintenanceOutcome.NOTHING_TO_DO, attempted = false)
            return null
        }
        if (declaration.expectsDue && declaration.scratchBytes > 0 && run.scratchBytes(part) == 0L) {
            // D3 step 7: the reservation was refused; the part is not run and comes again with the table's next task.
            records += PartRecord(part.name, MaintenanceOutcome.CAPACITY, attempted = false, reason = REASON_SCRATCH_REFUSED)
            return null
        }
        @Suppress("TooGenericExceptionCaught") // recorded and rethrown unchanged: the worker ends the run `failed`.
        val result =
            try {
                part.run(run)
            } catch (failure: Exception) {
                records += PartRecord(part.name, MaintenanceOutcome.FAILED, attempted = true, errorCode = MaintenanceErrors.codeOf(failure))
                throw failure
            }
        records += PartRecord(part.name, result.outcome, attempted = true, figures = result.figures, until = result.until)
        return result.outcome.takeIf { it == MaintenanceOutcome.BUSY || it == MaintenanceOutcome.DEADLINE }
    }

    companion object {
        /** The reason of a part recorded `capacity` because the run's scratch reservation was refused. */
        const val REASON_SCRATCH_REFUSED = "scratch_refused"
    }
}
