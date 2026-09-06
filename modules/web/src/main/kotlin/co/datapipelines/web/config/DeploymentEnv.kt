package co.datapipelines.web.config

/**
 * The two variables an organisation sets (075, [docs/environments.md]) and the one place
 * that resolves them.
 *
 * ## Why two, and why here
 *
 * **The environment's NAME belongs to the org; the POSTURE belongs to the product.** Orgs
 * have `dev`, `qa`, `uat`, `perf`, `sandbox-eu`, `prod` — any names, any count — and the
 * product must never branch on one (a single-server user honestly writes `prod` as their
 * label; the first `if (env == "prod")` locks them out of authoring on the only server they
 * have). What the product branches on is a closed, two-valued POSTURE with documented
 * semantics, and the org maps each of its environments onto one.
 *
 * This object is the single resolver so that `app`'s [ConfigValidator][co.datapipelines
 * .config.ConfigValidator] (which REFUSES a boot) and `web`'s [AuthoringStartupCheck]
 * (which LOGS the boot line) cannot disagree about what the effective values are. Two
 * readers of one config key that drift apart is exactly the class of defect the validator
 * exists to catch.
 *
 * ## The alias
 *
 * [ENV_KEY] replaces 039's [LEGACY_ENV_KEY] (`datapipelines.deployment.name`). The old key
 * survives ONE release: set alone it still names the deployment and startup WARNs; set
 * together with the new key and differing, startup refuses. It is deliberately NOT declared
 * in `application.yml` — declaring it would pin it on every deployment and make "the
 * operator set the alias" undetectable, exactly as with the executor's deprecated alias.
 */
object DeploymentEnv {
    /** configuration.md §3.23 — the org's label for this deployment (`DATAPIPELINES_ENV`). */
    const val ENV_KEY = "datapipelines.env"

    /** 039's spelling, deprecated by 075. One release, WARN-only when set alone. */
    const val LEGACY_ENV_KEY = "datapipelines.deployment.name"

    /** configuration.md §3.23 — the product's stance (`DATAPIPELINES_POSTURE`). */
    const val POSTURE_KEY = "datapipelines.posture"

    /** configuration.md §3.23 — the sample-data families to load (`DATAPIPELINES_DEMO`). */
    const val DEMO_KEY = "datapipelines.demo"

    /** The one env name that gets a posture for free — a laptop, and nothing else. */
    const val DEFAULT_ENV = "local"

    const val DEVELOPMENT = "development"
    const val HARDENED = "hardened"

    /** The two postures, in the order the docs table lists them. */
    val POSTURES = listOf(DEVELOPMENT, HARDENED)

    /** §3.23 — the env-name grammar. Lowercase so it is safe in a log key, a label and a URL. */
    val ENV_NAME = Regex("[a-z0-9][a-z0-9_-]{0,31}")

    /**
     * The effective env label: the new key, or the deprecated alias when the new key is
     * still sitting on its shipped default. Returning the alias is what makes it an alias
     * rather than a no-op; [aliasInUse] is the seam the WARN keys off.
     */
    fun resolveEnv(
        env: String?,
        legacy: String?,
    ): String {
        val declared = env?.trim().orEmpty()
        val alias = legacy?.trim().orEmpty()
        if (declared.isEmpty() || (declared == DEFAULT_ENV && alias.isNotEmpty())) {
            return alias.ifEmpty { DEFAULT_ENV }
        }
        return declared
    }

    /** True when the deprecated alias is the thing actually naming this deployment. */
    fun aliasInUse(
        env: String?,
        legacy: String?,
    ): Boolean {
        val declared = env?.trim().orEmpty()
        val alias = legacy?.trim().orEmpty()
        return alias.isNotEmpty() && (declared.isEmpty() || declared == DEFAULT_ENV)
    }

    /**
     * The effective posture, or **null** when it cannot be decided — a named environment
     * that declared no posture. Null is a REFUSAL, never a default: explicit beats guessed,
     * and the one exception is `local`, where `development` is the honest reading.
     */
    fun resolvePosture(
        posture: String?,
        env: String,
    ): String? {
        val declared = posture?.trim()?.lowercase().orEmpty()
        if (declared.isNotEmpty()) return declared
        return if (env == DEFAULT_ENV) DEVELOPMENT else null
    }

    /** The families [DEMO_KEY] names, `nyc,trade` → `[nyc, trade]`; empty = demo is off. */
    fun demoFamilies(demo: String?): List<String> =
        demo
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}
