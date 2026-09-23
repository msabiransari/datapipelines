package co.datapipelines.scripting

/**
 * The evaluation pool could not accept the submission — its capacity for running AND
 * waiting evaluations is exhausted (transform-nodes design §4.3: `pool_exhausted`,
 * 503 on the tool/route, a node failure in a run).
 *
 * Deliberately a sibling of the other script refusals rather than a subclass: the
 * failure is the CALLER's admission control, not the script's behaviour. Carries the
 * `pipeline.transform.pool_exhausted` code.
 */
class ScriptPoolExhaustedException(
    val runningCapacity: Int,
    val queueCapacity: Int,
) : ScriptingException(
        CODE,
        "evaluation pool exhausted (capacity $runningCapacity running / $queueCapacity admitted)",
        mapOf("running_capacity" to runningCapacity, "queue_capacity" to queueCapacity),
    ) {
    companion object {
        /** pipeline-contract §13.18 — the pool row (record §7). */
        const val CODE = "pipeline.transform.pool_exhausted"
    }
}
