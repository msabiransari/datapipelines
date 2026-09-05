package co.datapipelines.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.util.Base64

/**
 * Startup configuration validation (configuration.md §7 — the checklist below is that
 * section, no more and no less; §7 is also why this lives in `app`, the composition
 * root that binds every module's keys).
 *
 * A **violation** stops startup: `@PostConstruct` throws, the context fails to refresh,
 * and the log line names every offending key at once so the operator fixes one file,
 * not one key per restart. A **warning** is logged structured and startup continues —
 * §7 defines exactly one of those (passwordless Redis off loopback).
 *
 * The checks are pure functions of a [ConfigSnapshot] so the test suite can drive them
 * without a Spring context — including the §7 closing rule that the *documented dev
 * setup* (configuration.md §6 values) must pass these production rules.
 */
class ConfigValidator(
    private val environment: Environment,
) {
    private val log = LoggerFactory.getLogger(ConfigValidator::class.java)

    @PostConstruct
    fun validateOnStartup() {
        val report = validate(snapshotFrom(environment))
        report.warnings.forEach { log.warn(it) }
        if (report.violations.isNotEmpty()) {
            val message =
                buildString {
                    append("Invalid configuration — startup refused (configuration.md §7):")
                    report.violations.forEach { append("\n  - ").append(it) }
                }
            log.error(message)
            throw IllegalStateException(message)
        }
        log.info("Configuration validated ({} checks, configuration.md §7).", CHECK_COUNT)
    }

    companion object {
        /**
         * The number the startup line quotes. Hand-maintained — and therefore pinned by
         * `ConfigValidatorCheckCountTest`, which counts the `check*` functions below and
         * fails the build when the two disagree (021/F10: the literal had already drifted
         * once, and a number in a log line has no other reader to notice).
         */
        internal const val CHECK_COUNT = 18

        /** §3.17 — the legal `datapipelines.workspaces.provisioning-mode` wire values. */
        private val PROVISIONING_MODES = setOf("auto-per-user", "self-serve", "closed")

        /** §3.17 — the one mode that provisions the personal workspace D9 seeds into. */
        private const val SEEDING_MODE = "auto-per-user"

        /** How an absent mode reads in a violation: application.yml always supplies the default. */
        private const val SHIPPED_DEFAULT_MODE_NOTE = "unset, i.e. the shipped default self-serve"

        /**
         * `users.provider` values the system writes itself (`UserService.BOOTSTRAP_PROVIDER`,
         * `LOCAL_PROVIDER`, `SYSTEM_PROVIDER`). An OIDC provider may not be NAMED one of these
         * — see [checkReservedProviderNames].
         *
         * Spelled as literals, not imported: `auth` is not on this module's test compile
         * classpath, and a validator rule reads better as the literal an operator would type.
         * `SystemActorProvisioningIntegrationTest` asserts the row's stored `provider` against
         * `UserService.SYSTEM_PROVIDER`, and `ConfigValidatorTest` asserts this set refuses
         * that same spelling — the two meet at the string, which is the thing that matters.
         */
        private val RESERVED_PROVIDER_NAMES = setOf("bootstrap", "local", "system")

        /** §3.10 — the directory the UI theme is validated against, on the classpath. */
        internal const val THEME_ROOT = "static/vendor/design-system"

        /** §3.21 — the legal `datapipelines.org.week-start` values. */
        private val WEEK_STARTS = setOf("monday", "sunday")

        /** §3.21 — `MM-DD`, shape only; [isCalendarDay] then asks the calendar. */
        private val FISCAL_START_DATE = Regex("\\d{2}-\\d{2}")

        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")

        /** The §7 rules, against a snapshot. Pure — every branch is unit-tested without Spring. */
        internal fun validate(snapshot: ConfigSnapshot): ValidationReport {
            val violations = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            checkRequiredKeys(snapshot, violations)
            checkSecretStrength(snapshot, violations)
            checkKeyProvider(snapshot, violations)
            checkUiTheme(snapshot, violations, warnings)
            checkOidcProviders(snapshot, violations)
            checkResultTtlOrdering(snapshot, violations)
            checkDevProfileGuard(snapshot, violations)
            checkWorkspacesProvisioningMode(snapshot, violations)
            checkWorkspacesOpenJoinMode(snapshot, violations)
            checkExamplesSeederReachable(snapshot, violations)
            checkBootstrapActorConfigured(snapshot, violations)
            checkReservedProviderNames(snapshot, violations)
            checkPromotionTarget(snapshot, violations)
            checkLocalAuth(snapshot, violations)
            checkExecutorConcurrencyAlias(snapshot, violations, warnings)
            checkRedisAuthWarning(snapshot, warnings)
            checkOrgSettings(snapshot, violations)
            return ValidationReport(violations, warnings)
        }

        /** §2 — every required key present, all named in one pass. */
        private fun checkRequiredKeys(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            requirePresent(snapshot.datasourceUrl, "spring.datasource.url", "SPRING_DATASOURCE_URL", violations)
            requirePresent(snapshot.datasourceUsername, "spring.datasource.username", "SPRING_DATASOURCE_USERNAME", violations)
            requirePresent(snapshot.datasourcePassword, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD", violations)
            requirePresent(snapshot.redisHost, "datapipelines.redis.host", "DATAPIPELINES_REDIS_HOST", violations)
            requirePresent(snapshot.jwtSecret, "datapipelines.jwt.secret", "DATAPIPELINES_JWT_SECRET", violations)
            requirePresent(snapshot.dbEncryptionKey, "datapipelines.db.encryption-key", "DATAPIPELINES_DB_ENCRYPTION_KEY", violations)
        }

        /** §7 — secret strength, measured on the DECODED bytes. */
        private fun checkSecretStrength(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            decodedBytes(snapshot.jwtSecret, "datapipelines.jwt.secret", violations)?.let { bytes ->
                if (bytes.size < JWT_SECRET_MIN_BYTES) {
                    violations += "datapipelines.jwt.secret decodes to ${bytes.size} bytes; §7 requires >= $JWT_SECRET_MIN_BYTES."
                }
            }
            decodedBytes(snapshot.dbEncryptionKey, "datapipelines.db.encryption-key", violations)?.let { bytes ->
                if (bytes.size != AES_KEY_BYTES) {
                    violations +=
                        "datapipelines.db.encryption-key decodes to ${bytes.size} bytes; " +
                        "§7 requires exactly $AES_KEY_BYTES (AES-256)."
                }
            }
        }

        /**
         * §7 — the credential key provider and its own settings (datasources.md §7.1;
         * `docs/key-providers.md` is the implementation guide).
         *
         * Two questions, in order: is the selected provider one this build ships, and are that
         * provider's required settings present and well-formed? An unknown name short-circuits —
         * validating `env`'s keys against a deployment that asked for `aws-kms` would report
         * violations the operator cannot act on.
         *
         * For `env` (the default, so this is every deployment that has not opted out): version 1
         * is `datapipelines.db.encryption-key`, already covered by [checkRequiredKeys] and
         * [checkSecretStrength]; this check applies the SAME rules to every additional configured
         * version and settles `encryption-key-current`. Values are never echoed — a violation
         * message reaches the logs.
         *
         * A new provider adds its branch here. That is one of the steps `docs/key-providers.md`
         * §4 enumerates, and it is why [KNOWN_KEY_PROVIDERS] is spelled as literals rather than
         * imported: `datasources` is not on this module's compile classpath (the same reason
         * [RESERVED_PROVIDER_NAMES] is spelled out), and the operator types these strings.
         */
        private fun checkKeyProvider(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            val provider = snapshot.dbKeyProvider?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEY_PROVIDER
            if (provider !in KNOWN_KEY_PROVIDERS) {
                violations +=
                    "datapipelines.db.key-provider is '$provider'; this build ships ${KNOWN_KEY_PROVIDERS.sorted()}. " +
                    "Adding one is docs/key-providers.md."
                return
            }
            if (provider != ENV_KEY_PROVIDER) return
            val versions = mutableSetOf(ENV_PRIMARY_KEY_VERSION)
            snapshot.dbEncryptionKeys.forEach { (rawVersion, value) ->
                envRotationKey(rawVersion, value, violations)?.let { versions += it }
            }
            envCurrentVersion(snapshot.dbEncryptionKeyCurrent, versions, violations)
        }

        /**
         * One `datapipelines.db.encryption-keys` entry: its version, or null when the entry is
         * itself a violation. Split out of [checkKeyProvider] rather than nested inside it —
         * `NestedBlockDepth` is a real reader complaint about a validator, and the per-entry
         * rules read as one thing.
         *
         * The helper is deliberately NOT named `check…`: `ConfigValidatorCheckCountTest` counts
         * `check*` declarations against `CHECK_COUNT`, and this is a branch of one rule, not a
         * new §7 rule.
         */
        private fun envRotationKey(
            rawVersion: String,
            value: String,
            violations: MutableList<String>,
        ): Int? {
            val where = "datapipelines.db.encryption-keys.$rawVersion"
            val version = rawVersion.trim().toIntOrNull()
            if (version == null) {
                violations += "$where is not a key version; expected an integer $KEY_VERSION_MIN..$KEY_VERSION_MAX."
                return null
            }
            if (version == ENV_PRIMARY_KEY_VERSION) {
                violations +=
                    "$where redeclares version $ENV_PRIMARY_KEY_VERSION; that version is " +
                    "datapipelines.db.encryption-key and has exactly one spelling."
                return null
            }
            if (version !in KEY_VERSION_MIN..KEY_VERSION_MAX) {
                violations += "$where is version $version, outside $KEY_VERSION_MIN..$KEY_VERSION_MAX."
                return null
            }
            decodedBytes(value, where, violations)?.let { bytes ->
                if (bytes.size != AES_KEY_BYTES) {
                    violations += "$where decodes to ${bytes.size} bytes; §7 requires exactly $AES_KEY_BYTES (AES-256)."
                }
            }
            return version
        }

        /** `datapipelines.db.encryption-key-current`: numeric, and naming a version that exists. */
        private fun envCurrentVersion(
            raw: String?,
            versions: Set<Int>,
            violations: MutableList<String>,
        ) {
            val currentRaw = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val current = currentRaw.toIntOrNull()
            when {
                current == null -> {
                    violations += "datapipelines.db.encryption-key-current is '$currentRaw', not a key version."
                }

                current !in versions -> {
                    violations +=
                        "datapipelines.db.encryption-key-current is $current but no key is configured for that " +
                        "version (configured: ${versions.sorted()})."
                }
            }
        }

        /**
         * §7 — the UI theme names a vendored theme directory. The design-system assets land
         * with P8; until the directory exists there is nothing to validate against, and §3.10's
         * default ('saas') must not fail startup over assets that do not exist yet.
         */
        private fun checkUiTheme(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
            warnings: MutableList<String>,
        ) {
            val theme = snapshot.uiTheme?.trim().orEmpty()
            when {
                theme.isEmpty() -> {
                    violations += "datapipelines.ui.theme is empty; §3.10 requires a vendored theme name."
                }

                snapshot.vendoredThemes.isNullOrEmpty() -> {
                    warnings +=
                        "event=config.ui_theme_unverifiable theme=$theme " +
                        "message=\"vendored design-system assets are not present yet (P8); §7 theme check deferred\""
                }

                theme !in snapshot.vendoredThemes -> {
                    violations +=
                        "datapipelines.ui.theme '$theme' matches no vendored theme directory " +
                        "($THEME_ROOT/$theme); available: ${snapshot.vendoredThemes.sorted()}."
                }
            }
        }

        /**
         * §7 — at least one authentication method: a fully-configured OIDC provider
         * (auth.md §11.1) or local password accounts (§3.4, auth.md §5A).
         *
         * An entry with a blank `client-id` is IGNORED here exactly as at [OidcConfig]
         * (the stock `google` entry binds empty when its env vars are unset) — a typo'd
         * env var degrades one provider to that WARN, while "nothing to log in with at
         * all" stays a refusal. The per-field checks apply only to entries the operator
         * demonstrably meant to configure (a present client-id).
         */
        private fun checkOidcProviders(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.oidcProviders.none { it.clientId.isNotBlank() } && !snapshot.localEnabled) {
                violations +=
                    "no authentication method configured: datapipelines.auth.oidc.providers has no fully-configured " +
                    "provider and datapipelines.auth.local.enabled is not true; §7 requires at least one."
            }
            snapshot.oidcProviders.forEach { provider ->
                if (provider.clientId.isBlank()) return@forEach
                val label = provider.name.ifBlank { "<unnamed>" }
                if (provider.clientSecret.isBlank()) violations += "OIDC provider '$label': client-secret is empty."
                if (provider.issuerUri.isBlank()) violations += "OIDC provider '$label': issuer-uri is empty."
            }
        }

        /**
         * §7 / §3.4 — local password accounts (auth.md §5A). The seed keys configure the
         * FIRST ADMIN's initial credential only, and the checks keep that narrow:
         *
         *  - both seed forms set → ambiguous — name both keys;
         *  - a seed without `datapipelines.auth.local.enabled` → the operator meant one thing;
         *  - a seed without `datapipelines.auth.bootstrap-admin-email` → the §3.18 pattern:
         *    the credential has no account to land on, so startup refuses naming both keys;
         *  - the lockout bounds must be positive integers.
         *
         * The snapshot carries only PRESENCE flags for the seed values — the plaintext
         * password must never appear in a validation message or a logged snapshot.
         */
        private fun checkLocalAuth(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.localBootstrapPasswordSet && snapshot.localBootstrapPasswordHashSet) {
                violations +=
                    "datapipelines.auth.local.bootstrap-password and " +
                    "datapipelines.auth.local.bootstrap-password-hash are both set; choose one (§3.4) — " +
                    "the pre-computed hash form is preferred."
            }
            val seedSet = snapshot.localBootstrapPasswordSet || snapshot.localBootstrapPasswordHashSet
            if (seedSet && !snapshot.localEnabled) {
                violations +=
                    "a local bootstrap credential is set but datapipelines.auth.local.enabled is not true (§3.4); " +
                    "set enabled=true or remove the seed."
            }
            if (seedSet && snapshot.bootstrapAdminEmail.isNullOrBlank()) {
                val seedKey =
                    if (snapshot.localBootstrapPasswordHashSet) {
                        "datapipelines.auth.local.bootstrap-password-hash"
                    } else {
                        "datapipelines.auth.local.bootstrap-password"
                    }
                violations +=
                    "$seedKey is set but datapipelines.auth.bootstrap-admin-email is not (§3.4): " +
                    "the seeded credential lands on that user's account. Set both keys, or neither."
            }
            checkLocalLockoutBounds(snapshot, violations)
        }

        /** §7 / §3.4 — the lockout bounds must be positive integers (raw strings, named violations). */
        private fun checkLocalLockoutBounds(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            snapshot.localLockoutMaxFailures?.let { raw ->
                val parsed = raw.trim().toIntOrNull()
                if (parsed == null || parsed < 1) {
                    violations += "datapipelines.auth.local.lockout.max-failures '$raw' is not a positive integer (§3.4)."
                }
            }
            snapshot.localLockoutDurationMinutes?.let { raw ->
                val parsed = raw.trim().toLongOrNull()
                if (parsed == null || parsed < 1) {
                    violations += "datapipelines.auth.local.lockout.duration-minutes '$raw' is not a positive integer (§3.4)."
                }
            }
        }

        /** §7 — the result-TTL ordering. */
        private fun checkResultTtlOrdering(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            val min = snapshot.resultTtlMinSeconds
            val default = snapshot.resultTtlDefaultSeconds
            val max = snapshot.resultTtlMaxSeconds
            if (min == null || default == null || max == null) {
                violations += "datapipelines.result.ttl-{min,default,max}-seconds must all be set (§7 ordering check)."
            } else if (!(min <= default && default <= max)) {
                violations +=
                    "datapipelines.result TTLs out of order: ttl-min-seconds ($min) <= ttl-default-seconds ($default) " +
                    "<= ttl-max-seconds ($max) must hold (§7)."
            }
        }

        /** §7 — the dev-profile guard: dev convenience must never touch production infra. */
        private fun checkDevProfileGuard(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            val profiles = snapshot.activeProfiles.map { it.lowercase() }.toSet()
            if ("dev" !in profiles) return
            val reasons = mutableListOf<String>()
            if ("prod" in profiles || "production" in profiles) {
                reasons += "profiles ${profiles.filter { it == "prod" || it == "production" }} are also active"
            }
            if (!isLoopback(jdbcHost(snapshot.datasourceUrl))) {
                reasons += "spring.datasource.url points at non-localhost '${jdbcHost(snapshot.datasourceUrl)}'"
            }
            if (!isLoopback(snapshot.redisHost?.trim())) {
                reasons += "datapipelines.redis.host is non-localhost '${snapshot.redisHost}'"
            }
            if (reasons.isNotEmpty()) {
                violations +=
                    "The 'dev' profile is active against production indicators (${reasons.joinToString("; ")}). " +
                    "Dev convenience settings must never run against production infrastructure (§7)."
            }
        }

        /**
         * §7 / §3.17 — `datapipelines.workspaces.open-join` names a real mode (§3.17). A typo
         * here fails enum binding anyway; this names the offending value in the §7 format
         * instead of as a binder stack trace.
         */
        private fun checkWorkspacesProvisioningMode(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            val mode = snapshot.workspacesProvisioningMode?.trim()?.lowercase() ?: return
            if (mode !in PROVISIONING_MODES) {
                violations +=
                    "datapipelines.workspaces.provisioning-mode '$mode' is not one of " +
                    "${PROVISIONING_MODES.sorted()} (§3.17)."
            }
        }

        /**
         * §7 / §3.17 — `open-join: true` under `closed` provisioning. §3.17 defines
         * `open-join` as a `self-serve`-mode knob, and this is not dead-config hygiene:
         * the self-join branch in `WorkspaceService.addMember` gates on `open-join`
         * alone, so `closed` + `open-join: true` would let any authenticated user join
         * any workspace — the exact management monopoly `closed` exists to give the
         * admin. Refused, naming both keys.
         *
         * A misspelled mode is left to [checkWorkspacesProvisioningMode], which already
         * names it — two violations for one typo would point at the wrong key. An unset
         * mode is the shipped default (`self-serve`), where `open-join` is meaningful.
         */
        private fun checkWorkspacesOpenJoinMode(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (!snapshot.workspacesOpenJoin) return
            val mode = snapshot.workspacesProvisioningMode?.trim()?.lowercase()
            if (mode != null && mode !in PROVISIONING_MODES) return
            if (mode == "closed") {
                violations +=
                    "datapipelines.workspaces.open-join is true while datapipelines.workspaces.provisioning-mode " +
                    "is 'closed' (§3.17): open-join exists for self-serve provisioning, and under closed it " +
                    "would re-open the membership surface closed mode exists to keep admin-only. " +
                    "Set the mode to 'self-serve', or turn open-join off."
            }
        }

        /**
         * §7 / §3.18 — an examples file the seeder can never reach.
         *
         * `ExampleContentSeeder` runs from `WorkspaceService.ensurePersonalWorkspace`, and only
         * `auto-per-user` provisioning ever calls it (design §7). Under any other mode the bean
         * is still built and the file still read and structurally checked at startup — so a
         * deployment that sets `examples-file` and leaves the mode at its SHIPPED DEFAULT
         * (`self-serve`, §3.17) validates green, boots clean, and seeds nothing, with no line
         * anywhere saying why. That is the silent-config class [checkBootstrapActorConfigured]
         * exists to prevent, so it is refused the same way: name both keys, set both or neither.
         *
         * A mode that is unset is the shipped default, i.e. NOT the seeding mode; a mode that is
         * misspelled is left to [checkWorkspacesProvisioningMode], which already names it — two
         * violations for one typo would point the operator at the wrong key.
         */
        private fun checkExamplesSeederReachable(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.bootstrapExamplesFile.isNullOrBlank()) return
            val mode = snapshot.workspacesProvisioningMode?.trim()?.lowercase()
            if (mode != null && mode !in PROVISIONING_MODES) return
            if (mode == SEEDING_MODE) return
            violations +=
                "datapipelines.bootstrap.examples-file is set but datapipelines.workspaces.provisioning-mode " +
                "is '${mode ?: SHIPPED_DEFAULT_MODE_NOTE}' (§3.17): example seeding runs only when " +
                "'$SEEDING_MODE' provisions a personal workspace at first login, so the configured " +
                "examples would never be seeded and nothing would say so. Set the mode to " +
                "'$SEEDING_MODE', or unset the examples file."
        }

        /**
         * §7 / §3.18 — bootstrap datasource registration needs an actor.
         *
         * `datasources.created_by` is `NOT NULL REFERENCES users(id)` and registration runs
         * before anyone has logged in, so `datapipelines.bootstrap.datasources-file` without
         * `datapipelines.auth.bootstrap-admin-email` cannot work. Silence here would surface as
         * a foreign-key error mid-startup naming neither key; this names both.
         *
         * `examples-file` deliberately has NO such rule: seeding runs at first login, under the
         * identity of the user logging in, and needs no configured admin.
         */
        private fun checkBootstrapActorConfigured(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.bootstrapDatasourcesFile.isNullOrBlank()) return
            if (snapshot.bootstrapAdminEmail.isNullOrBlank()) {
                violations +=
                    "datapipelines.bootstrap.datasources-file is set but " +
                    "datapipelines.auth.bootstrap-admin-email is not (§3.18): bootstrap-registered " +
                    "datasources are created_by that user, and the row is pre-provisioned from that " +
                    "address before any login. Set both keys, or neither."
            }
        }

        /**
         * §7 / auth.md §4.4 — an OIDC provider may not be named after a system placeholder.
         *
         * `OidcSuccessHandler` writes the provider's configured NAME (the Spring registration
         * id) verbatim into `users.provider`, and the system writes two placeholder values of
         * its own there: `bootstrap` for the pre-provisioned admin (§6.1), `local` for an
         * admin-created password account (§5A), and `system` for the system service account
         * (§4.5, R7) — whose entire safety argument is that no external identity can link to
         * it. A provider named any one of them makes external
         * identities land on rows the system labels system-created — the two become
         * indistinguishable in `users`, in the audit trail and in the `(provider,
         * provider_subject)` uniqueness the schema enforces. Nothing else reserves the names
         * (021/F8), so startup does, at the loudest point available.
         *
         * The reservation is on the NAME alone: an entry with a blank `client-id` is ignored
         * everywhere else, but it is one env var away from being the provider that writes those
         * rows, and renaming it costs nothing today. Matched case-insensitively and trimmed, so
         * a near-miss spelling cannot be argued into the same namespace.
         */
        private fun checkReservedProviderNames(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            snapshot.oidcProviders.forEach { provider ->
                val name = provider.name.trim().lowercase()
                if (name in RESERVED_PROVIDER_NAMES) {
                    violations +=
                        "OIDC provider '${provider.name}': the name '$name' is reserved for the identities the " +
                        "system creates itself (users.provider = '$name'); an external provider under that name " +
                        "would be indistinguishable from them. Rename the provider."
                }
            }
        }

        /**
         * §7 / §3.19 — the SENDER half of promotion: a target named without its key.
         *
         * `datapipelines.deployment.promotion.target.base-url` without
         * `…target.server-key` is a promotion that cannot authenticate: every push would be
         * refused by the receiver with `auth.promotion.key_invalid`, at the end of a UI action
         * a human took, with nothing in the sender's own configuration to point at. The pair
         * is meaningless apart, so startup names both keys.
         *
         * The reverse is NOT a violation: a `server-key` with no target is an ordinary
         * RECEIVER, which is the common case ([Versioning §10.6](versioning.md) — a deployment
         * may receive, send, both, or neither). The receiver-that-also-authors combination is
         * a WARN, and it belongs to `AuthoringStartupCheck`, which can read the repositories.
         *
         * Presence, never values: the key is a bearer secret and must not reach a violation
         * message (the §7 report is logged).
         */
        private fun checkPromotionTarget(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.promotionTargetBaseUrl.isNullOrBlank()) return
            if (!snapshot.promotionTargetKeySet) {
                violations +=
                    "datapipelines.deployment.promotion.target.base-url is set but " +
                    "datapipelines.deployment.promotion.target.server-key is not (§3.19): the target's pre-shared " +
                    "key is what authenticates the push, so every promotion would be refused. Set both, or neither."
            }
        }

        /**
         * §7 / §3.2 — the executor concurrency key rename's one-release alias (050/R2).
         *
         * `max-concurrent-executions-global` is deprecated in favour of
         * `max-concurrent-executions-per-instance` (the limit has always been per JVM; the old
         * name was false at N replicas). Three behaviors, one place:
         *
         *  - **Alias alone** → one WARN naming the new key; the alias's value is what runs
         *    (the bridge `ExecutorProperties.effectiveMaxConcurrentExecutionsPerInstance`).
         *  - **Both set and differing** → refuse: two keys claiming different limits is an
         *    operator error the API must not silently resolve.
         *  - **Both set and equal** → the WARN only — no ambiguity exists.
         *
         * Presence honesty (and its one documented corner): the NEW key always resolves to a
         * value because application.yml supplies its default, so "set" is detected as "differs
         * from the documented default (100)". The corner — alias set AND the new key explicitly
         * set to exactly the default — is indistinguishable from "alias alone"; the alias wins
         * there and the WARN states the value in effect. Anything off the default differs
         * detectably and is refused as specified.
         */
        private fun checkExecutorConcurrencyAlias(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
            warnings: MutableList<String>,
        ) {
            val aliasRaw = snapshot.executorMaxConcurrentGlobal?.trim() ?: return
            val alias = aliasRaw.toIntOrNull() ?: return // a non-integer fails binding loudly
            val canonicalRaw = snapshot.executorMaxConcurrentPerInstance?.trim()
            val canonical = canonicalRaw?.toIntOrNull()
            warnings +=
                "event=config.executor_concurrency_alias_used alias_value=$alias " +
                "message=\"datapipelines.executor.max-concurrent-executions-global is deprecated; " +
                "its value ($alias) is in effect. Rename the setting to " +
                "datapipelines.executor.max-concurrent-executions-per-instance (removed next release)\""
            if (canonical != null && canonical != DEFAULT_CONCURRENT_EXECUTIONS && canonical != alias) {
                violations +=
                    "datapipelines.executor.max-concurrent-executions-global ($alias) and " +
                    "datapipelines.executor.max-concurrent-executions-per-instance ($canonical) are both " +
                    "set and differ (§3.2); remove the deprecated alias or make the values agree."
            }
        }

        /**
         * §7 / §3.21 — the organisation facts (calculators design §0.1).
         *
         * Every value here enters every execution's Context, so a bad one is not a startup
         * inconvenience: it is a wrong number in every report the deployment produces. All four
         * rules report together, like the rest of §7 — an operator fixing a fiscal start and a
         * timezone one restart at a time is the cost this validator exists to remove.
         *
         * `fiscal-start-date` is `MM-DD` and a real calendar day. `MonthDay` is the parser
         * precisely because it is the type: it refuses `02-30` and `13-01` on the calendar, and
         * it accepts `02-29`, which the fiscal kinds resolve to 02-28 in a non-leap year
         * (`MonthDay.atYear`). A month NAME (`SEP-15`) fails the same parse, and the message
         * names the `MM-DD` form because "text could not be parsed" sends nobody anywhere.
         */
        private fun checkOrgSettings(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            val fiscal = snapshot.orgFiscalStartDate?.trim()
            if (fiscal.isNullOrEmpty() || !FISCAL_START_DATE.matches(fiscal) || !isCalendarDay(fiscal)) {
                violations +=
                    "datapipelines.org.fiscal-start-date is '${fiscal.orEmpty()}' (§3.21); it must be MM-DD — " +
                    "a two-digit month and a two-digit day that exist on the calendar, e.g. 01-01 or 09-15. " +
                    "Month names are not accepted."
            }
            val weekStart = snapshot.orgWeekStart?.trim()?.lowercase()
            if (weekStart !in WEEK_STARTS) {
                violations +=
                    "datapipelines.org.week-start is '${snapshot.orgWeekStart.orEmpty()}' (§3.21); " +
                    "it must be one of ${WEEK_STARTS.joinToString(" | ")}."
            }
            val timezone = snapshot.orgTimezone?.trim()
            if (timezone.isNullOrEmpty() || !isIanaZone(timezone)) {
                violations +=
                    "datapipelines.org.timezone is '${timezone.orEmpty()}' (§3.21); it must be an IANA zone id, " +
                    "e.g. UTC or Europe/London."
            }
            if (snapshot.orgCurrencyName.isNullOrBlank()) {
                violations += "datapipelines.org.currency.name is blank (§3.21); it names the deployment's currency."
            }
            if (snapshot.orgCurrencySymbol.isNullOrBlank()) {
                violations += "datapipelines.org.currency.symbol is blank (§3.21); it is rendered beside amounts."
            }
        }

        /** True when `MM-DD` names a day the calendar has — `02-30` and `13-01` do not. */
        private fun isCalendarDay(value: String): Boolean = runCatching { java.time.MonthDay.parse("--$value") }.isSuccess

        /** True for a zone id this JVM's tz database knows; a fixed offset (`+02:00`) is not one. */
        private fun isIanaZone(value: String): Boolean = value in java.time.ZoneId.getAvailableZoneIds()

        /** §7 — passwordless Redis off loopback is a WARNING, not a refusal. */
        private fun checkRedisAuthWarning(
            snapshot: ConfigSnapshot,
            warnings: MutableList<String>,
        ) {
            val redisHost = snapshot.redisHost?.trim()
            if (snapshot.redisPassword.isNullOrBlank() && redisHost != null && !isLoopback(redisHost)) {
                warnings +=
                    "event=config.redis_no_password redis_host=$redisHost " +
                    "message=\"datapipelines.redis.password is empty and the host is not loopback; " +
                    "production Redis holds materialized caller results (deployment.md §9)\""
            }
        }

        /** Reads the snapshot out of the live [Environment] (relaxed binding, per module keys). */
        internal fun snapshotFrom(environment: Environment): ConfigSnapshot =
            ConfigSnapshot(
                datasourceUrl = environment.getProperty("spring.datasource.url"),
                datasourceUsername = environment.getProperty("spring.datasource.username"),
                datasourcePassword = environment.getProperty("spring.datasource.password"),
                redisHost = environment.getProperty("datapipelines.redis.host"),
                redisPassword = environment.getProperty("datapipelines.redis.password"),
                jwtSecret = environment.getProperty("datapipelines.jwt.secret"),
                dbEncryptionKey = environment.getProperty("datapipelines.db.encryption-key"),
                // §3.20 — the provider seam. The rotation keys are a MAP whose entries a property
                // lookup cannot enumerate, so they come through the same Binder the wiring uses
                // (DomainConfiguration.SpringKeyProviderConfig): two readers of one config key
                // that disagreed would be exactly the drift this validator exists to catch.
                dbKeyProvider = environment.getProperty("datapipelines.db.key-provider"),
                dbEncryptionKeys =
                    Binder
                        .get(environment)
                        .bind("datapipelines.db.encryption-keys", Bindable.mapOf(String::class.java, String::class.java))
                        .orElse(emptyMap()),
                dbEncryptionKeyCurrent = environment.getProperty("datapipelines.db.encryption-key-current"),
                uiTheme = environment.getProperty("datapipelines.ui.theme"),
                oidcProviders = oidcProviders(environment),
                resultTtlMinSeconds = environment.getProperty("datapipelines.result.ttl-min-seconds", Long::class.java),
                resultTtlDefaultSeconds = environment.getProperty("datapipelines.result.ttl-default-seconds", Long::class.java),
                resultTtlMaxSeconds = environment.getProperty("datapipelines.result.ttl-max-seconds", Long::class.java),
                workspacesProvisioningMode = environment.getProperty("datapipelines.workspaces.provisioning-mode"),
                workspacesOpenJoin = environment.getProperty("datapipelines.workspaces.open-join", Boolean::class.java) ?: false,
                bootstrapDatasourcesFile = environment.getProperty("datapipelines.bootstrap.datasources-file"),
                bootstrapExamplesFile = environment.getProperty("datapipelines.bootstrap.examples-file"),
                bootstrapAdminEmail = environment.getProperty("datapipelines.auth.bootstrap-admin-email"),
                localEnabled = environment.getProperty("datapipelines.auth.local.enabled", Boolean::class.java) ?: false,
                // Presence flags ONLY — the seed values are credentials and must never
                // reach a validation message or the logged snapshot.
                localBootstrapPasswordSet =
                    !environment.getProperty("datapipelines.auth.local.bootstrap-password").isNullOrBlank(),
                localBootstrapPasswordHashSet =
                    !environment.getProperty("datapipelines.auth.local.bootstrap-password-hash").isNullOrBlank(),
                localLockoutMaxFailures = environment.getProperty("datapipelines.auth.local.lockout.max-failures"),
                localLockoutDurationMinutes = environment.getProperty("datapipelines.auth.local.lockout.duration-minutes"),
                // 050/R2 §7 — the executor concurrency alias pair (raw values; presence is the signal).
                executorMaxConcurrentGlobal = environment.getProperty("datapipelines.executor.max-concurrent-executions-global"),
                executorMaxConcurrentPerInstance = environment.getProperty("datapipelines.executor.max-concurrent-executions-per-instance"),
                // §3.19 promotion (055). The base-url is an ordinary value; both keys are
                // bearer secrets and are carried as PRESENCE only — the §7 report is logged.
                promotionTargetBaseUrl = environment.getProperty("datapipelines.deployment.promotion.target.base-url"),
                promotionTargetKeySet =
                    !environment.getProperty("datapipelines.deployment.promotion.target.server-key").isNullOrBlank(),
                // §3.21 (calculators design §0.1) — org facts. Raw strings so a malformed value
                // becomes a NAMED violation here instead of a binder crash at bean creation.
                orgCurrencyName = environment.getProperty("datapipelines.org.currency.name"),
                orgCurrencySymbol = environment.getProperty("datapipelines.org.currency.symbol"),
                orgFiscalStartDate = environment.getProperty("datapipelines.org.fiscal-start-date"),
                orgWeekStart = environment.getProperty("datapipelines.org.week-start"),
                orgTimezone = environment.getProperty("datapipelines.org.timezone"),
                activeProfiles = environment.activeProfiles.toSet(),
                vendoredThemes = vendoredThemes(),
            )

        /**
         * The configured OIDC providers, read by index so a malformed entry (e.g. a missing
         * `name`) is *reported* by the §7 checks rather than silently skipped by a binder.
         */
        private fun oidcProviders(environment: Environment): List<OidcProviderSnapshot> {
            val providers = mutableListOf<OidcProviderSnapshot>()
            var index = 0
            while (true) {
                val prefix = "datapipelines.auth.oidc.providers[$index]"
                val name = environment.getProperty("$prefix.name")
                val clientId = environment.getProperty("$prefix.client-id")
                val clientSecret = environment.getProperty("$prefix.client-secret")
                val issuerUri = environment.getProperty("$prefix.issuer-uri")
                if (listOfNotNull(name, clientId, clientSecret, issuerUri).isEmpty()) break
                providers +=
                    OidcProviderSnapshot(
                        name = name.orEmpty(),
                        clientId = clientId.orEmpty(),
                        clientSecret = clientSecret.orEmpty(),
                        issuerUri = issuerUri.orEmpty(),
                    )
                index++
            }
            return providers
        }

        /**
         * The vendored theme names, or **null** when the design-system assets are not on the
         * classpath at all (pre-P8) — the difference between "validate" and "nothing to validate
         * against". Enumerated jar-safe through [VendoredThemes.names] (025 B1, the T21 class):
         * this used to resolve the directory through `ClassPathResource.file`, which returns
         * null inside every packaged deployment — the §7 theme startup check was silently
         * deferred in exactly the deployments that matter, the jars.
         */
        private fun vendoredThemes(): Set<String>? =
            co.datapipelines.web.ui.VendoredThemes
                .names()
                ?.toSet()

        private fun requirePresent(
            value: String?,
            key: String,
            envVar: String,
            violations: MutableList<String>,
        ) {
            if (value.isNullOrBlank()) violations += "$key is missing (set $envVar) — required by configuration.md §2."
        }

        /** Base64-decodes [value]; a malformed value is itself a violation (and yields null). */
        private fun decodedBytes(
            value: String?,
            key: String,
            violations: MutableList<String>,
        ): ByteArray? {
            if (value.isNullOrBlank()) return null // already reported by requirePresent
            return runCatching { Base64.getDecoder().decode(value.trim()) }
                .getOrElse {
                    violations += "$key is not valid base64; §7 measures its strength on the decoded bytes."
                    null
                }
        }

        /** The host of a `jdbc:postgresql://host[:port]/db` URL (first of a comma list). */
        internal fun jdbcHost(url: String?): String? {
            val afterScheme = url?.substringAfter("://", "")?.takeIf { it.isNotEmpty() } ?: return null
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore(',')
            val bracketed = authority.substringAfter('[', "").substringBefore(']', "")
            if (bracketed.isNotEmpty()) return bracketed // IPv6 literal
            return authority.substringBefore(':').ifEmpty { null }
        }

        internal fun isLoopback(host: String?): Boolean = host == null || host in LOOPBACK_HOSTS || host.startsWith("127.")

        private const val JWT_SECRET_MIN_BYTES = 32
        private const val AES_KEY_BYTES = 32

        /**
         * The `datapipelines.db.key-provider` values this build ships, and its default.
         *
         * Literals, not imports — `datasources` is not on this module's compile classpath, and
         * a validator rule reads better as the string an operator types (the precedent
         * [RESERVED_PROVIDER_NAMES] set). `KeyProviders.known()` is the code-side authority;
         * `ConfigValidatorTest` asserts this set refuses an unknown name and accepts `env`.
         */
        private const val ENV_KEY_PROVIDER = "env"
        private const val DEFAULT_KEY_PROVIDER = ENV_KEY_PROVIDER
        private val KNOWN_KEY_PROVIDERS = setOf(ENV_KEY_PROVIDER)

        /** `datapipelines.db.encryption-key` is version 1, forever (datasources.md §7.2). */
        private const val ENV_PRIMARY_KEY_VERSION = 1

        /** The version is the credential blob's first byte, so it is one unsigned byte. */
        private const val KEY_VERSION_MIN = 1
        private const val KEY_VERSION_MAX = 255

        /**
         * §3.2 — the documented default of `max-concurrent-executions-per-instance`. The
         * presence heuristic in [checkExecutorConcurrencyAlias] treats a resolved canonical
         * value equal to this as "operator did not set it" (the yml default masks true
         * presence); the corner is documented on that check.
         */
        private const val DEFAULT_CONCURRENT_EXECUTIONS = 100
    }
}

/** Everything §7 checks, lifted out of the `Environment` so the rules are testable as data. */
internal data class ConfigSnapshot(
    val datasourceUrl: String?,
    val datasourceUsername: String?,
    val datasourcePassword: String?,
    val redisHost: String?,
    val redisPassword: String?,
    val jwtSecret: String?,
    val dbEncryptionKey: String?,
    /** §3.20 — which [co.datapipelines.datasources.crypto.KeyProvider] supplies data keys; unset = `env`. */
    val dbKeyProvider: String? = null,
    /** §3.20 — the optional rotation keys, `version -> base64`, raw so a bad version is a NAMED violation. */
    val dbEncryptionKeys: Map<String, String> = emptyMap(),
    /** §3.20 — which configured version new encryptions use; unset = the highest configured. */
    val dbEncryptionKeyCurrent: String? = null,
    val uiTheme: String?,
    val oidcProviders: List<OidcProviderSnapshot>,
    val resultTtlMinSeconds: Long?,
    val resultTtlDefaultSeconds: Long?,
    val resultTtlMaxSeconds: Long?,
    val workspacesProvisioningMode: String?,
    /** §3.17 — read here only for the open-join/closed cross-key rule; auth owns its semantics. */
    val workspacesOpenJoin: Boolean = false,
    /** §3.18 — unset (or blank) = bootstrap datasource registration is off. */
    val bootstrapDatasourcesFile: String?,
    /** §3.18 — unset (or blank) = example seeding is off. Carried so the §7 log reports it. */
    val bootstrapExamplesFile: String?,
    /** §3.4 — read here only for the §3.18 cross-key rule; auth owns its semantics. */
    val bootstrapAdminEmail: String?,
    /** §3.4 — local password accounts enabled (auth.md §5A). */
    val localEnabled: Boolean = false,
    /** Presence ONLY (§7 checkLocalAuth) — the seed values are credentials, never carried. */
    val localBootstrapPasswordSet: Boolean = false,
    val localBootstrapPasswordHashSet: Boolean = false,
    /** Raw strings so a non-numeric value becomes a NAMED violation instead of a binder crash. */
    val localLockoutMaxFailures: String? = null,
    val localLockoutDurationMinutes: String? = null,
    /** §3.2/§7 — raw values; ALIAS presence is the deprecation signal (050/R2). */
    val executorMaxConcurrentGlobal: String? = null,
    val executorMaxConcurrentPerInstance: String? = null,
    /** §3.19 (055) — the promotion SENDER's target, if any. */
    val promotionTargetBaseUrl: String? = null,
    /** §3.19 (055) — presence ONLY: the target's pre-shared key is a bearer secret. */
    val promotionTargetKeySet: Boolean = false,
    /** §3.21 — org facts, raw. Never secrets; they are in every Context and every report. */
    val orgCurrencyName: String? = null,
    val orgCurrencySymbol: String? = null,
    val orgFiscalStartDate: String? = null,
    val orgWeekStart: String? = null,
    val orgTimezone: String? = null,
    val activeProfiles: Set<String>,
    /** Null = no vendored theme assets on the classpath yet (pre-P8) — the §7 theme check defers. */
    val vendoredThemes: Set<String>?,
) {
    override fun toString() =
        "ConfigSnapshot(" +
            "datasourceUrl=$datasourceUrl, " +
            "datasourceUsername=$datasourceUsername, " +
            "datasourcePassword=<redacted>, " +
            "redisHost=$redisHost, " +
            "redisPassword=<redacted>, " +
            "jwtSecret=<redacted>, " +
            "dbEncryptionKey=<redacted>, " +
            "dbKeyProvider=$dbKeyProvider, " +
            "dbEncryptionKeys=<redacted, versions=${dbEncryptionKeys.keys.sorted()}>, " +
            "dbEncryptionKeyCurrent=$dbEncryptionKeyCurrent, " +
            "uiTheme=$uiTheme, " +
            "oidcProviders=$oidcProviders, " +
            "resultTtlMinSeconds=$resultTtlMinSeconds, " +
            "resultTtlDefaultSeconds=$resultTtlDefaultSeconds, " +
            "resultTtlMaxSeconds=$resultTtlMaxSeconds, " +
            "workspacesProvisioningMode=$workspacesProvisioningMode, " +
            "workspacesOpenJoin=$workspacesOpenJoin, " +
            "bootstrapDatasourcesFile=$bootstrapDatasourcesFile, " +
            "bootstrapExamplesFile=$bootstrapExamplesFile, " +
            "bootstrapAdminEmail=$bootstrapAdminEmail, " +
            "localEnabled=$localEnabled, " +
            "localBootstrapPasswordSet=$localBootstrapPasswordSet, " +
            "localBootstrapPasswordHashSet=$localBootstrapPasswordHashSet, " +
            "localLockoutMaxFailures=$localLockoutMaxFailures, " +
            "localLockoutDurationMinutes=$localLockoutDurationMinutes, " +
            "executorMaxConcurrentGlobal=$executorMaxConcurrentGlobal, " +
            "executorMaxConcurrentPerInstance=$executorMaxConcurrentPerInstance, " +
            "orgCurrencyName=$orgCurrencyName, " +
            "orgCurrencySymbol=$orgCurrencySymbol, " +
            "orgFiscalStartDate=$orgFiscalStartDate, " +
            "orgWeekStart=$orgWeekStart, " +
            "orgTimezone=$orgTimezone, " +
            "activeProfiles=$activeProfiles, " +
            "vendoredThemes=$vendoredThemes)"
}

/** One `datapipelines.auth.oidc.providers[]` entry (auth.md §11.1), bound by relaxed binding. */
internal data class OidcProviderSnapshot(
    val name: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val issuerUri: String = "",
) {
    override fun toString() =
        "OidcProviderSnapshot(" +
            "name=$name, " +
            "clientId=$clientId, " +
            "clientSecret=<redacted>, " +
            "issuerUri=$issuerUri)"
}

internal data class ValidationReport(
    val violations: List<String>,
    val warnings: List<String>,
)
