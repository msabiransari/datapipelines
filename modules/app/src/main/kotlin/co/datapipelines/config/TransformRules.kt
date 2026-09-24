package co.datapipelines.config

/**
 * §7 / §3.28 (7b, #7) — the transform evaluation budgets: one case must fit inside its
 * suite (`evaluate-timeout-seconds` ≤ `suite-timeout-seconds`), the abandonment grace must
 * be at least one second (a zero grace would abandon every slow case at the instant its own
 * wall clock ends, with no room for the engine's between-steps check to report first —
 * record §4.5), the pool admits at least one running evaluation, and the admitted total is
 * never smaller than the running set. Every cap is ≥ 1. Missing values are not a violation:
 * application.yml always supplies them.
 *
 * Owns its file like `PostureRules` and `MailRules` (§3.23/§3.27) — the validator's
 * companion is the registry, not the home, of the §3.x families.
 */
internal object TransformRules {
    fun checkTransformBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val values =
            listOf(
                "evaluate-timeout-seconds" to snapshot.transformEvaluateTimeoutSeconds,
                "suite-timeout-seconds" to snapshot.transformSuiteTimeoutSeconds,
                "abandon-grace-seconds" to snapshot.transformAbandonGraceSeconds,
                "pool-size" to snapshot.transformPoolSize,
                "pool-queue" to snapshot.transformPoolQueue,
                "max-input-rows" to snapshot.transformMaxInputRows,
                "max-value-bytes" to snapshot.transformMaxValueBytes,
                "max-string-bytes" to snapshot.transformMaxStringBytes,
                "max-depth" to snapshot.transformMaxDepth,
            )
        // An unset block is not a violation (application.yml always supplies all nine);
        // a partial block checks what it has.
        if (values.all { (_, value) -> value == null }) return
        values.forEach { (key, value) ->
            if (value != null && value < 1) {
                violations += "datapipelines.transform.$key ($value) must be >= 1 (§7)."
            }
        }
        val evaluate = snapshot.transformEvaluateTimeoutSeconds
        val suite = snapshot.transformSuiteTimeoutSeconds
        if (evaluate != null && suite != null && evaluate > suite) {
            violations +=
                "datapipelines.transform.evaluate-timeout-seconds ($evaluate) must not exceed " +
                "datapipelines.transform.suite-timeout-seconds ($suite) — one case must fit inside its suite (§7)."
        }
        val poolSize = snapshot.transformPoolSize
        val poolQueue = snapshot.transformPoolQueue
        if (poolSize != null && poolQueue != null && poolQueue < poolSize) {
            violations +=
                "datapipelines.transform.pool-queue ($poolQueue) must be >= datapipelines.transform.pool-size " +
                "($poolSize) — the admitted total cannot be smaller than the running set (§7)."
        }
    }
}
