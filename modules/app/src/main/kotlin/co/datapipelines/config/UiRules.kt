package co.datapipelines.config

/**
 * §7 / §3.10 (#598b) — the UI's numeric bound. Owns its file like `RedisRules` and
 * `RequestLimitsRules` — the validator's companion is the registry, not the home, of the §3.x
 * families, and this keeps the companion under the size the static analysis allows. The theme
 * check stays in the companion, where it was written.
 */
internal object UiRules {
    /**
     * The Pipelines home's actively developed window is a whole number of days, at least one:
     * zero would count nobody and a negative window is meaningless. Judged on the raw value, so
     * a bad one is this named violation rather than a binding stack trace; unset uses the yml
     * default.
     */
    fun checkUiActivelyDevelopedDays(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val raw = snapshot.uiActivelyDevelopedDays ?: return
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null || parsed < 1) {
            violations += "datapipelines.ui.actively-developed-days '$raw' must be an integer ≥ 1; §3.10."
        }
    }
}
