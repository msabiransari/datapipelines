package co.datapipelines.config

import co.datapipelines.web.bootstrap.BootstrapProperties
import co.datapipelines.web.config.DeploymentEnv

/**
 * §7 rules #224 added for the demo workspace's public API (configuration.md §3.18/§3.22) — a
 * rule file of their own, the same split 075 made for the posture rules when a companion
 * outgrew the size guard.
 */
internal object DemoApiRules {
    /** The §7 rules #224 added, as one entry so the validator's call site stays one line. */
    internal fun enforceDemoApiRules(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        checkDemoApiKey(snapshot, violations)
        checkEndpointKeyBudget(snapshot, violations)
    }

    /**
     * §7 / §3.18 — `datapipelines.bootstrap.demo-api-key`, the demo workspace's public
     * `api_caller` key.
     *
     * Blank is the kill switch and never a violation, and so is the COMMITTED DEFAULT: the
     * shipped `application.yml` resolves the key to that value in every deployment, so a
     * hardened boot that never heard of the demo API must not fail on it — "set" means the
     * OPERATOR chose a value. An operator-chosen value must be a well-formed `dpk_<id>.<secret>`
     * credential (auth.md §7.1's shape: `dpk_`, 12 RFC 4648 base32 chars, a dot, a non-empty
     * secret) — a malformed configured key would otherwise fail at first *use*, as a presented
     * credential the shape gate refuses, which is a worse moment and a worse message than a
     * refused boot. An operator-chosen value under `hardened` is refused outright, for the same
     * reason the demo flag is (§3.23): a public evaluation credential has no place in a
     * hardened deployment.
     *
     * The shape is spelled HERE rather than imported: auth's `ApiKeyCredential` is not on this
     * module's compile classpath (module-structure §4.2), and the regex is the documented
     * grammar both sides cite.
     */
    internal fun checkDemoApiKey(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val key = snapshot.bootstrapDemoApiKey
        if (key.isNullOrBlank() || key == BootstrapProperties.DEFAULT_DEMO_API_KEY) return
        val env = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        if (DeploymentEnv.resolvePosture(snapshot.posture, env) == DeploymentEnv.HARDENED) {
            violations +=
                "datapipelines.bootstrap.demo-api-key is set ${whereHardened(env)}. " +
                "The demo workspace's public API key is an out-of-the-box evaluation affordance: " +
                "blank the key, or run this environment as ${DeploymentEnv.DEVELOPMENT}."
        }
        val dot = key.indexOf('.')
        val id = if (dot < 0) "" else key.substring(0, dot)
        val malformed =
            key.length > DEMO_KEY_MAX_LENGTH ||
                !DEMO_KEY_ID_PATTERN.matches(id) ||
                dot < 0 ||
                dot == key.length - 1
        if (malformed) {
            violations +=
                "datapipelines.bootstrap.demo-api-key is not a well-formed API key (auth.md §7.1: " +
                "dpk_ + 12 base32 [A-Z2-7] chars, a dot, then the secret). Blank the key to turn " +
                "the demo API off, or set a well-formed value."
        }
    }

    /**
     * §7 / §3.22 — `datapipelines.endpoints.key-request-budget`, the serve path's per-key
     * request budget. The binder's `init` block refuses a bad pair as a binding failure naming
     * a Kotlin class; this names the KEYS, in the one report the operator reads. `max-requests:
     * 0` is legal — it turns the budget off, and with it the lake family's endpoint publishing.
     */
    internal fun checkEndpointKeyBudget(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val window = snapshot.endpointsKeyRequestBudgetWindowSeconds
        if (window != null && window < 1) {
            violations +=
                "datapipelines.endpoints.key-request-budget.window-seconds ($window) must be >= 1 (§7)."
        }
        val max = snapshot.endpointsKeyRequestBudgetMaxRequests
        if (max != null && max < 0) {
            violations +=
                "datapipelines.endpoints.key-request-budget.max-requests ($max) must be >= 0 " +
                "(zero = the budget is off) (§7)."
        }
    }

    /** The hardened-refusal's location phrase, the way PostureRules spells it. */
    private fun whereHardened(env: String): String = "under ${DeploymentEnv.POSTURE_KEY}=${DeploymentEnv.HARDENED} (env '$env', §3.23)"

    /** The `dpk_` key id's shape (auth.md §7.1), the grammar `checkDemoApiKey` cites. */
    private val DEMO_KEY_ID_PATTERN = Regex("^dpk_[A-Z2-7]{12}$")

    /** The credential cap the auth shape gate applies (ApiKeyCredential.MAX_CREDENTIAL_LENGTH). */
    private const val DEMO_KEY_MAX_LENGTH = 80
}
