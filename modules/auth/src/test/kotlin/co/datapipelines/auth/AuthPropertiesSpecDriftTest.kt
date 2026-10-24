package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The configuration.md drift guard for THIS module's `@ConfigurationProperties`
 * (025 D3): the sibling web module has one for its keys; `AuthProperties` and
 * `WorkspacesProperties` had none, so a default edited on either side of the
 * doc/code boundary drifted silently. configuration.md §1 is the single authority
 * for config keys — a binding class that disagrees with it is a second authority.
 *
 * The row regex consumes the WHOLE row (description cell included, escaped pipes
 * honored) so a table whose column count changes makes its keys VANISH from the
 * parse — `getValue` then fails loudly — instead of silently reading the wrong
 * cell. Same hardening applied to the web module's copy.
 */
class AuthPropertiesSpecDriftTest {
    private val documented: Map<String, String> by lazy {
        RepoFiles
            .read(CONFIG_SPEC_PATH)
            .lineSequence()
            .mapNotNull { ROW_REGEX.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `auth property defaults match configuration-md section 3-4`() {
        val props = AuthProperties()
        documented.getValue("datapipelines.auth.jwt.ttl-hours") shouldBe props.jwt.ttlHours.toString()
        documented.getValue("datapipelines.auth.api-keys.cache-ttl-seconds") shouldBe props.apiKeys.cacheTtlSeconds.toString()
        documented.getValue("datapipelines.auth.rate-limit.login-per-minute") shouldBe props.rateLimit.loginPerMinute.toString()
        documented.getValue("datapipelines.auth.oidc.provider-read-timeout-seconds") shouldBe
            props.oidc.providerReadTimeoutSeconds.toString()
    }

    @Test
    fun `every documented provider default matches the default constructed provider`() {
        val provider = AuthProperties.Provider()
        val defaults =
            mapOf(
                "protocol" to provider.protocol,
                "authorization-uri" to provider.authorizationUri,
                "token-uri" to provider.tokenUri,
                "user-info-uri" to provider.userInfoUri,
                "user-name-attribute" to provider.userNameAttribute,
                "scopes" to provider.scopes,
                "emails-uri" to provider.emailsUri,
                "trust-email-without-verified-claim" to provider.trustEmailWithoutVerifiedClaim,
                "picture-hosts" to provider.pictureHosts,
            )
        val rows = documented.filterKeys { it.startsWith(PROVIDER_PREFIX) }
        withClue("providers rows parsed from configuration.md §3.4") { rows.size shouldBeGreaterThanOrEqual 9 }
        rows.forEach { (key, value) ->
            val suffix = key.removePrefix(PROVIDER_PREFIX)
            check(defaults.containsKey(suffix)) { "Unknown documented provider row: $key" }
            withClue(key) { value shouldBe renderDefault(defaults.getValue(suffix)).removeSurrounding("`") }
        }
    }

    private fun renderDefault(value: Any?): String =
        when {
            value == null -> "(none)"
            value == "" || (value is List<*> && value.isEmpty()) -> "(empty)"
            else -> "`$value`"
        }

    @Test
    fun `workspaces property defaults match configuration-md section 3-17`() {
        val props = WorkspacesProperties()
        // `provisioning-mode` and `open-join` were REMOVED in RBAC round 1 (D-R11) and are gone
        // from §3.17 and from the properties class both, so there is nothing left to compare
        // for them — `ConfigValidatorTest` asserts the other half: that SETTING either refuses
        // startup by name.
        documented.getValue("datapipelines.workspaces.member-datasources-enabled") shouldBe props.memberDatasourcesEnabled.toString()
    }

    @Test
    fun `mail property defaults match configuration-md section 3-27`() {
        val props = MailProperties()
        documented.getValue("datapipelines.mail.port") shouldBe props.port.toString()
        documented.getValue("datapipelines.mail.starttls") shouldBe props.starttls.toString()
    }

    @Test
    fun `persistence property defaults match configuration-md section 3-32`() {
        // #266: the batching writers' bounds. Every key, because a default that drifts here is a
        // queue bound or a wait that operators read one value for and run with another.
        val props = PersistenceProperties()
        documented.getValue("datapipelines.persistence.enabled") shouldBe props.enabled.toString()
        documented.getValue("datapipelines.persistence.audit.enabled") shouldBe props.audit.enabled.toString()
        documented.getValue("datapipelines.persistence.batch-max-events") shouldBe props.batchMaxEvents.toString()
        documented.getValue("datapipelines.persistence.batch-max-bytes") shouldBe props.batchMaxBytes.toString()
        documented.getValue("datapipelines.persistence.linger-ms") shouldBe props.lingerMs.toString()
        documented.getValue("datapipelines.persistence.queue-max-events") shouldBe props.queueMaxEvents.toString()
        documented.getValue("datapipelines.persistence.queue-max-bytes") shouldBe props.queueMaxBytes.toString()
        documented.getValue("datapipelines.persistence.record-max-wait-ms") shouldBe props.recordMaxWaitMs.toString()
        documented.getValue("datapipelines.persistence.writers") shouldBe props.writers.toString()
        documented.getValue("datapipelines.persistence.shutdown-drain-ms") shouldBe props.shutdownDrainMs.toString()
    }

    @Test
    fun `audit property defaults match configuration-md section 3-12`() {
        documented.getValue("datapipelines.audit.retention-days") shouldBe AuditProperties().retentionDays.toString()
    }

    private companion object {
        /**
         * A full `| \`datapipelines.*\` | \`default\` | description |` row of the §3 tables:
         * the description cell runs to end-of-line and may contain ESCAPED pipes (`\|`),
         * never a bare one — so a row with a different column count does not match at all
         * and its key disappears (fail-loud) rather than being read from the wrong cell.
         */
        val ROW_REGEX =
            Regex(
                """^\|\s*`(datapipelines\.[a-z0-9.\-\[\]]+)`\s*\|\s*`?([A-Za-z0-9\-]+|\(empty\)|\(none\))`?\s*\|(?:[^|\n]|\\\|)*\|$""",
            )

        const val CONFIG_SPEC_PATH = "docs/configuration.md"
        const val PROVIDER_PREFIX = "datapipelines.auth.oidc.providers[]."
    }
}
