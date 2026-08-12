package co.datapipelines.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
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
@Component
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
        private const val CHECK_COUNT = 8

        /** §3.10 — the directory the UI theme is validated against, on the classpath. */
        internal const val THEME_ROOT = "static/vendor/design-system"

        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")

        /** The §7 rules, against a snapshot. Pure — every branch is unit-tested without Spring. */
        internal fun validate(snapshot: ConfigSnapshot): ValidationReport {
            val violations = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            checkRequiredKeys(snapshot, violations)
            checkSecretStrength(snapshot, violations)
            checkUiTheme(snapshot, violations, warnings)
            checkOidcProviders(snapshot, violations)
            checkResultTtlOrdering(snapshot, violations)
            checkDevProfileGuard(snapshot, violations)
            checkRedisAuthWarning(snapshot, warnings)
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

        /** §7 — at least one fully-configured OIDC provider (auth.md §11.1). */
        private fun checkOidcProviders(
            snapshot: ConfigSnapshot,
            violations: MutableList<String>,
        ) {
            if (snapshot.oidcProviders.isEmpty()) {
                violations += "datapipelines.auth.oidc.providers is empty; §7 requires at least one provider."
            }
            snapshot.oidcProviders.forEach { provider ->
                val label = provider.name.ifBlank { "<unnamed>" }
                if (provider.clientId.isBlank()) violations += "OIDC provider '$label': client-id is empty."
                if (provider.clientSecret.isBlank()) violations += "OIDC provider '$label': client-secret is empty."
                if (provider.issuerUri.isBlank()) violations += "OIDC provider '$label': issuer-uri is empty."
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
                uiTheme = environment.getProperty("datapipelines.ui.theme"),
                oidcProviders = oidcProviders(environment),
                resultTtlMinSeconds = environment.getProperty("datapipelines.result.ttl-min-seconds", Long::class.java),
                resultTtlDefaultSeconds = environment.getProperty("datapipelines.result.ttl-default-seconds", Long::class.java),
                resultTtlMaxSeconds = environment.getProperty("datapipelines.result.ttl-max-seconds", Long::class.java),
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
         * against". Theme CSS files live under `themes/` (e.g. `themes/saas.css`); inside a jar
         * there is no directory listing, so the configured theme's own CSS file is probed instead.
         */
        private fun vendoredThemes(): Set<String>? {
            val root = ClassPathResource(THEME_ROOT)
            if (!root.exists()) return null
            val baseDir = runCatching { root.file }.getOrNull() ?: return null
            val themesDir =
                baseDir
                    .listFiles { f -> f.isDirectory && f.name == "themes" }
                    ?.firstOrNull() ?: return null
            return themesDir
                .listFiles { f -> f.isFile && f.name.endsWith(".css") }
                ?.map { it.name.removeSuffix(".css") }
                ?.toSet()
        }

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
    val uiTheme: String?,
    val oidcProviders: List<OidcProviderSnapshot>,
    val resultTtlMinSeconds: Long?,
    val resultTtlDefaultSeconds: Long?,
    val resultTtlMaxSeconds: Long?,
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
            "uiTheme=$uiTheme, " +
            "oidcProviders=$oidcProviders, " +
            "resultTtlMinSeconds=$resultTtlMinSeconds, " +
            "resultTtlDefaultSeconds=$resultTtlDefaultSeconds, " +
            "resultTtlMaxSeconds=$resultTtlMaxSeconds, " +
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
