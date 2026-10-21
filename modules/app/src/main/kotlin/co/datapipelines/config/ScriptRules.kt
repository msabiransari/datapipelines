package co.datapipelines.config

/**
 * §7 / §3.28 (7b, #7) — the script evaluation budgets: one case must fit inside its
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
internal object ScriptRules {
    /** The nine `datapipelines.transform.*` keys the rename removed (configuration.md §3.28). */
    internal val REMOVED_TRANSFORM_KEY_SUFFIXES =
        listOf(
            "evaluate-timeout-seconds",
            "suite-timeout-seconds",
            "pool-size",
            "pool-queue",
            "abandon-grace-seconds",
            "max-input-rows",
            "max-value-bytes",
            "max-string-bytes",
            "max-depth",
        )

    /**
     * §7 / §3.28 — the keys the TRANSFORM→SCRIPT node-type rename REMOVED, refused BY NAME
     * (script-node-rename lane, owner ruling 2026-10-20).
     *
     * The script knobs moved from `datapipelines.transform.*` to `datapipelines.script.*`
     * with no alias: the properties class binds the new tree, so a stale `transform` key
     * would bind NOTHING and the deployment would silently run on defaults — the exact
     * failure mode `checkRemovedWorkspaceKeys` refuses to allow. Any of the nine keys set
     * at all is refused, naming the old key and the replacement.
     */
    fun checkRemovedTransformKeys(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!snapshot.transformLegacyKeysSet) return
        violations +=
            "datapipelines.transform.* is set but was REMOVED by the TRANSFORM→SCRIPT node-type " +
            "rename (§3.28): the script knobs now live under datapipelines.script.* and there is " +
            "no alias — a datapipelines.transform.* key binds nothing. Rename the key(s): " +
            REMOVED_TRANSFORM_KEY_SUFFIXES.joinToString(", ") { "datapipelines.transform.$it" } + "."
    }

    fun checkScriptBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val values =
            listOf(
                "evaluate-timeout-seconds" to snapshot.scriptEvaluateTimeoutSeconds,
                "suite-timeout-seconds" to snapshot.scriptSuiteTimeoutSeconds,
                "abandon-grace-seconds" to snapshot.scriptAbandonGraceSeconds,
                "pool-size" to snapshot.scriptPoolSize,
                "pool-queue" to snapshot.scriptPoolQueue,
                "max-input-rows" to snapshot.scriptMaxInputRows,
                "max-value-bytes" to snapshot.scriptMaxValueBytes,
                "max-string-bytes" to snapshot.scriptMaxStringBytes,
                "max-depth" to snapshot.scriptMaxDepth,
            )
        // An unset block is not a violation (application.yml always supplies all nine);
        // a partial block checks what it has.
        if (values.all { (_, value) -> value == null }) return
        values.forEach { (key, value) ->
            if (value != null && value < 1) {
                violations += "datapipelines.script.$key ($value) must be >= 1 (§7)."
            }
        }
        val evaluate = snapshot.scriptEvaluateTimeoutSeconds
        val suite = snapshot.scriptSuiteTimeoutSeconds
        if (evaluate != null && suite != null && evaluate > suite) {
            violations +=
                "datapipelines.script.evaluate-timeout-seconds ($evaluate) must not exceed " +
                "datapipelines.script.suite-timeout-seconds ($suite) — one case must fit inside its suite (§7)."
        }
        val poolSize = snapshot.scriptPoolSize
        val poolQueue = snapshot.scriptPoolQueue
        if (poolSize != null && poolQueue != null && poolQueue < poolSize) {
            violations +=
                "datapipelines.script.pool-queue ($poolQueue) must be >= datapipelines.script.pool-size " +
                "($poolSize) — the admitted total cannot be smaller than the running set (§7)."
        }
    }
}
