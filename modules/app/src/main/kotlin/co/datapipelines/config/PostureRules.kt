package co.datapipelines.config

import co.datapipelines.web.config.DeploymentEnv

/**
 * The §3.23 posture rules (075), lifted out of [ConfigValidator]'s companion so neither
 * grows past the size the build's own complexity guard allows — and because these four
 * belong together: they are the whole of what "the environment's NAME belongs to the org,
 * the POSTURE belongs to the product" means in enforcement.
 *
 * Pure functions of a [ConfigSnapshot], like every other §7 rule. `ConfigValidatorCheckCountTest`
 * counts `check*` declarations across BOTH files, so moving a rule here cannot quietly drop it
 * from the number the boot line quotes.
 */
internal object PostureRules {
    /** 039's profile name, renamed `development` by 075 — named so a stale manifest is refused, not ignored. */
    private const val LEGACY_DEV_PROFILE = "dev"

    /**
     * §7 / §3.23 (075) — the org's LABEL for this deployment.
     *
     * Two rules, and nothing else: the grammar (a label that is logged, written into a
     * promotion's `source_env` and read by a human under pressure is lowercase, short and
     * shell-quiet), and the deprecated alias. Nothing here — and nothing anywhere in
     * production code — reads the label's VALUE to decide behaviour; that is
     * `DeploymentNameBranchingGuardTest`'s pin, and the reason a posture exists at all.
     */
    fun checkEnvName(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val declared = snapshot.env?.trim().orEmpty()
        val alias = snapshot.deploymentName?.trim().orEmpty()
        if (DeploymentEnv.aliasConflicts(snapshot.env, snapshot.deploymentName)) {
            violations +=
                "${DeploymentEnv.ENV_KEY} ('$declared') and the deprecated " +
                "${DeploymentEnv.LEGACY_ENV_KEY} ('$alias') are both set and disagree (§3.23); " +
                "keep DATAPIPELINES_ENV and delete DATAPIPELINES_DEPLOYMENT_NAME."
        } else if (DeploymentEnv.aliasInUse(snapshot.env, snapshot.deploymentName)) {
            warnings +=
                "event=config.deployment_name_deprecated env=$alias " +
                "message=\"${DeploymentEnv.LEGACY_ENV_KEY} (DATAPIPELINES_DEPLOYMENT_NAME) was renamed " +
                "${DeploymentEnv.ENV_KEY} (DATAPIPELINES_ENV) in 075; the old name is honoured for one " +
                "release. Rename the variable (docs/environments.md).\""
        }
        val effective = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        if (!DeploymentEnv.ENV_NAME.matches(effective)) {
            violations +=
                "${DeploymentEnv.ENV_KEY} '$effective' is not a legal environment name (§3.23): " +
                "lowercase letters, digits, '_' and '-', starting with a letter or digit, at most 32 characters " +
                "(set DATAPIPELINES_ENV)."
        }
    }

    /**
     * §7 / §3.23 (075) — the POSTURE: `development` or `hardened`, and nothing else.
     *
     * **Explicit beats guessed.** A named environment with no posture does not start. The
     * one exception is `local`, where `development` is the honest reading and demanding a
     * second variable to run a laptop would be ceremony. Every other label — `dev`, `qa`,
     * `sandbox-eu`, `prod` — must say which stance it wants, because the product cannot
     * infer a stance from a name it is forbidden to branch on.
     */
    fun checkPosture(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val declared =
            snapshot.posture
                ?.trim()
                ?.lowercase()
                .orEmpty()
        if (declared.isNotEmpty() && declared !in DeploymentEnv.POSTURES) {
            violations +=
                "${DeploymentEnv.POSTURE_KEY} '$declared' is not a posture (§3.23): " +
                "DATAPIPELINES_POSTURE is ${DeploymentEnv.POSTURES.joinToString(" or ")}."
            return
        }
        val env = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        if (DeploymentEnv.resolvePosture(snapshot.posture, env) == null) {
            violations +=
                "${DeploymentEnv.ENV_KEY} is '$env' but ${DeploymentEnv.POSTURE_KEY} is not set (§3.23): " +
                "set DATAPIPELINES_POSTURE to ${DeploymentEnv.POSTURES.joinToString(" or ")}. " +
                "Only the environment named '${DeploymentEnv.DEFAULT_ENV}' gets a posture for free."
        }
    }

    /**
     * §7 / §3.23 (075) — the posture and the Spring profile must be the same fact.
     *
     * `application.yml` derives `spring.profiles.active` from `DATAPIPELINES_POSTURE`, so
     * the two agree by construction — until an operator sets `SPRING_PROFILES_ACTIVE`
     * themselves (a manifest carried over from before 075, a habit, a copy-paste). An
     * environment variable outranks the file, so the posture's DEFAULTS would then come
     * from one profile while every posture RULE below judged the other. That is silent,
     * and it is exactly the drift this validator exists to refuse.
     *
     * The old `dev` profile is named here too: it was renamed `development` by 075 and no
     * longer exists, so a deployment still asking for it would load NO posture file at all
     * and get the base defaults with no sign that anything was ignored.
     */
    fun checkPostureProfileAlignment(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val profiles = snapshot.activeProfiles.map { it.lowercase() }.toSet()
        if (LEGACY_DEV_PROFILE in profiles) {
            violations +=
                "the Spring profile '$LEGACY_DEV_PROFILE' is active, and 075 renamed it " +
                "'${DeploymentEnv.DEVELOPMENT}' (§3.23). Set DATAPIPELINES_POSTURE instead of " +
                "SPRING_PROFILES_ACTIVE — the posture derives the profile."
        }
        val posturesActive = profiles.filter { it in DeploymentEnv.POSTURES }
        val env = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        val effective = DeploymentEnv.resolvePosture(snapshot.posture, env) ?: return
        if (posturesActive.isEmpty()) {
            // An ABSENT profile is only safe for `development`, whose column IS the base
            // file's defaults. `hardened` with no `hardened` profile is the silent case this
            // whole check exists for: §7 would judge the hardened rules while every hardened
            // DEFAULT — authoring off, Secure cookies — quietly stayed at the other column's.
            if (effective == DeploymentEnv.HARDENED) {
                violations +=
                    "${DeploymentEnv.POSTURE_KEY} resolves to '${DeploymentEnv.HARDENED}' but the " +
                    "'${DeploymentEnv.HARDENED}' Spring profile is not active (§3.23), so that posture's " +
                    "defaults did not load. Set DATAPIPELINES_POSTURE (the profile is derived from it) " +
                    "rather than the property alone."
            }
            return
        }
        if (posturesActive.size > 1 || posturesActive.single() != effective) {
            violations +=
                "spring.profiles.active names ${posturesActive.sorted()} while " +
                "${DeploymentEnv.POSTURE_KEY} resolves to '$effective' (§3.23): the posture derives the " +
                "profile, so unset SPRING_PROFILES_ACTIVE or make it match DATAPIPELINES_POSTURE."
        }
    }

    /**
     * §7 / §3.23 (075) — the `hardened` posture's refusals, each naming the variable and
     * the posture (the posture table in docs/environments.md is this function, in prose).
     *
     * Every rule here is a REFUSAL rather than a silent downgrade: a deployment that asked
     * for the hardened stance and got a quietly-relaxed one is worse than a deployment that
     * did not start. The loopback rule is 039's dev-profile guard, inverted and reused —
     * "dev convenience must never touch production infrastructure" is the same fact as
     * "a hardened deployment does not run against a laptop's database".
     */
    fun checkHardenedPosture(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val env = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        if (DeploymentEnv.resolvePosture(snapshot.posture, env) != DeploymentEnv.HARDENED) return
        val where = "under ${DeploymentEnv.POSTURE_KEY}=${DeploymentEnv.HARDENED} (env '$env', §3.23)"

        if (DeploymentEnv.demoFamilies(snapshot.demo).isNotEmpty()) {
            violations +=
                "${DeploymentEnv.DEMO_KEY} is set to '${snapshot.demo}' $where. Demo is a flag for " +
                "out-of-the-box evaluation in a development environment: it registers sample datasources " +
                "and seeds example content. Unset DATAPIPELINES_DEMO, or run this environment as " +
                "${DeploymentEnv.DEVELOPMENT}."
        }
        if (snapshot.localBootstrapPasswordSet || snapshot.localBootstrapPasswordHashSet) {
            violations +=
                "a local bootstrap credential (datapipelines.auth.local.bootstrap-password" +
                "${if (snapshot.localBootstrapPasswordHashSet) "-hash" else ""}) is set $where. " +
                "A seeded credential has no place in a hardened deployment — local accounts themselves " +
                "stay allowed; create the first admin with an admin reset instead."
        }
        val dbHost = ConfigValidator.jdbcHost(snapshot.datasourceUrl)
        if (ConfigValidator.isLoopback(dbHost) && !snapshot.datasourceUrl.isNullOrBlank()) {
            violations +=
                "spring.datasource.url points at loopback ('$dbHost') $where. " +
                "A hardened deployment does not run its metadata database on the same loopback " +
                "interface as the process (SPRING_DATASOURCE_URL)."
        }
        val redisHost = snapshot.redisHost?.trim()
        if (redisHost != null && ConfigValidator.isLoopback(redisHost)) {
            violations +=
                "datapipelines.redis.host is loopback ('$redisHost') $where " +
                "(DATAPIPELINES_REDIS_HOST)."
        }
        val hasOidc = snapshot.oidcProviders.any { it.clientId.isNotBlank() && it.clientSecret.isNotBlank() && it.issuerUri.isNotBlank() }
        if (!hasOidc) {
            if (!snapshot.authAllowLocalOnly) {
                violations +=
                    "no OIDC provider is fully configured $where. Configure one (auth.md §5.2), or set " +
                    "DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY=true to acknowledge that this hardened deployment " +
                    "authenticates with local password accounts only."
            } else {
                warnings +=
                    "event=config.auth_local_only env=$env posture=${DeploymentEnv.HARDENED} " +
                    "message=\"DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY=true: this hardened deployment has no OIDC " +
                    "provider and authenticates with local password accounts only (acknowledged explicitly).\""
            }
        }
    }
}
