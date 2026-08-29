package co.datapipelines.auth

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
        documented.getValue("datapipelines.auth.api-keys.default-scopes") shouldBe props.apiKeys.defaultScopes.single()
        documented.getValue("datapipelines.auth.rate-limit.login-per-minute") shouldBe props.rateLimit.loginPerMinute.toString()
    }

    @Test
    fun `workspaces property defaults match configuration-md section 3-17`() {
        val props = WorkspacesProperties()
        documented.getValue("datapipelines.workspaces.provisioning-mode") shouldBe props.provisioningMode.wire
        documented.getValue("datapipelines.workspaces.open-join") shouldBe props.openJoin.toString()
        documented.getValue("datapipelines.workspaces.member-datasources-enabled") shouldBe props.memberDatasourcesEnabled.toString()
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
                """^\|\s*`(datapipelines\.[a-z0-9.\-]+)`\s*\|\s*`?([A-Za-z0-9\-]+)`?\s*\|(?:[^|\n]|\\\|)*\|$""",
            )

        val CONFIG_SPEC_PATH = "docs/configuration.md"
    }
}
