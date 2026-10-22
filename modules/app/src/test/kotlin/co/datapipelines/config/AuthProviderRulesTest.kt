package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.env.MockEnvironment

/**
 * §7 / §3.4 (#655) — each configured provider entry against its protocol (F4), the snapshot that
 * feeds the rule (read through the Binder, as `AuthProperties` is), and the hardened posture's
 * "a provider is configured" for an OAuth 2.0 entry.
 */
class AuthProviderRulesTest {
    /** The shipped `github` entry's shape (application.yml), credentials set. */
    private fun github() =
        OidcProviderSnapshot(
            name = "github",
            clientId = "gh-id",
            clientSecret = "gh-secret",
            protocol = "oauth2",
            authorizationUri = "https://github.com/login/oauth/authorize",
            tokenUri = "https://github.com/login/oauth/access_token",
            userInfoUri = "https://api.github.com/user",
            userNameAttribute = "id",
            scopes = listOf("read:user", "user:email"),
            emailsUri = "https://api.github.com/user/emails",
        )

    private fun violations(vararg providers: OidcProviderSnapshot): List<String> =
        ConfigValidator.validate(ConfigSnapshots.valid().copy(oidcProviders = providers.toList())).violations

    @Test
    fun `the shipped github entry validates, and so does a loopback test stub`() {
        violations(github()).shouldBeEmpty()
        val stub = "http://127.0.0.1:8099"
        violations(
            github().copy(
                authorizationUri = "$stub/authorize",
                tokenUri = "http://localhost:8099/token",
                userInfoUri = "$stub/user",
                emailsUri = "$stub/emails",
            ),
        ).shouldBeEmpty()
    }

    @Test
    fun `F4 - an oauth2 entry without token-uri is refused naming the provider and the key`() {
        val refused = violations(github().copy(tokenUri = ""))

        refused shouldHaveSize 1
        refused.single() shouldContain "'github'"
        refused.single() shouldContain "token-uri"
    }

    @Test
    fun `F4 - an oauth2 entry that still sets issuer-uri is refused - the misleading entry #655 replaced`() {
        val refused = violations(github().copy(issuerUri = "https://github.com/login/oauth"))

        refused shouldHaveSize 1
        refused.single() shouldContain "'github'"
        refused.single() shouldContain "issuer-uri is set"
    }

    @Test
    fun `F4 - a protocol outside oidc and oauth2 is refused`() {
        val refused = violations(github().copy(protocol = "saml"))

        refused shouldHaveSize 1
        refused.single() shouldContain "protocol 'saml'"
    }

    @Test
    fun `F4 - plain http to a non-loopback host is refused, on every endpoint including emails-uri`() {
        violations(github().copy(tokenUri = "http://evil.example/token")).single() shouldContain
            "token-uri 'http://evil.example/token' must be https://"
        violations(github().copy(emailsUri = "http://evil.example/emails")).single() shouldContain "emails-uri"
        violations(github().copy(userInfoUri = "ftp://api.github.com/user")).single() shouldContain "user-info-uri"
        violations(github().copy(authorizationUri = "https:///no-host")).single() shouldContain "authorization-uri"
    }

    @Test
    fun `every missing oauth2 key is named in one pass - endpoints, subject attribute and scopes`() {
        val refused =
            violations(
                github().copy(authorizationUri = "", tokenUri = "", userInfoUri = "", userNameAttribute = "", scopes = emptyList()),
            )

        refused shouldHaveSize 5
        listOf("authorization-uri", "token-uri", "user-info-uri", "user-name-attribute", "scopes").forEach { key ->
            refused.filter { it.contains(key) } shouldHaveSize 1
        }
    }

    @Test
    fun `an oauth2 entry asking for openid is refused - that is the oidc protocol`() {
        violations(github().copy(scopes = listOf("openid", "read:user"))).single() shouldContain "'openid'"
    }

    @Test
    fun `an oidc entry carrying oauth2-only keys is refused - discovery would silently ignore them`() {
        val refused =
            violations(
                OidcProviderSnapshot(
                    name = "google",
                    clientId = "id",
                    clientSecret = "secret",
                    issuerUri = "https://accounts.google.com",
                    tokenUri = "https://example.com/token",
                ),
            )

        refused.single() shouldContain "token-uri is set, but only protocol: oauth2 reads it"
    }

    @Test
    fun `an entry with a blank client-id is skipped whatever its shape - the stock entries ship unset`() {
        val unset = github().copy(clientId = "", protocol = "saml", tokenUri = "")

        violations(ConfigSnapshots.valid().oidcProviders.single(), unset).shouldBeEmpty()
    }

    @Test
    fun `the snapshot reads the provider list through the Binder - yaml lists, comma lists and the whole-list rule`() {
        val environment =
            MockEnvironment()
                .withProperty("datapipelines.auth.oidc.providers[0].name", "github")
                .withProperty("datapipelines.auth.oidc.providers[0].protocol", "oauth2")
                .withProperty("datapipelines.auth.oidc.providers[0].client-id", "gh-id")
                .withProperty("datapipelines.auth.oidc.providers[0].scopes[0]", "read:user")
                .withProperty("datapipelines.auth.oidc.providers[0].scopes[1]", "user:email")
                .withProperty("datapipelines.auth.oidc.providers[0].emails-uri", "https://api.github.com/user/emails")
                .withProperty("datapipelines.auth.oidc.providers[1].name", "okta")
                .withProperty("datapipelines.auth.oidc.providers[1].scopes", "a,b")

        val providers = ConfigValidator.snapshotFrom(environment).oidcProviders

        providers shouldHaveSize 2
        providers[0].protocol shouldBe "oauth2"
        providers[0].scopes shouldBe listOf("read:user", "user:email")
        providers[0].emailsUri shouldBe "https://api.github.com/user/emails"
        providers[1].protocol shouldBe "oidc"
        providers[1].scopes shouldBe listOf("a", "b")
    }

    @Test
    fun `a higher-precedence source that re-declares the list replaces it whole - no key leaks in from application yml`() {
        // The shape ApplicationSmokeTest and the e2e suites use: they re-declare providers[1] as an
        // OIDC entry over application.yml's github entry. An index-by-index lookup would have read
        // the yml's `protocol: oauth2` into it; the application's own binding never does.
        val environment = MockEnvironment()
        environment.propertySources.addLast(
            MapPropertySource(
                "application.yml",
                mapOf(
                    "datapipelines.auth.oidc.providers[1].name" to "github",
                    "datapipelines.auth.oidc.providers[1].protocol" to "oauth2",
                    "datapipelines.auth.oidc.providers[1].token-uri" to "https://github.com/login/oauth/access_token",
                ),
            ),
        )
        environment
            .withProperty("datapipelines.auth.oidc.providers[0].name", "google")
            .withProperty("datapipelines.auth.oidc.providers[1].name", "microsoft")
            .withProperty("datapipelines.auth.oidc.providers[1].issuer-uri", "https://login.example/v2.0")

        val microsoft = ConfigValidator.snapshotFrom(environment).oidcProviders[1]

        microsoft.name shouldBe "microsoft"
        microsoft.protocol shouldBe "oidc"
        microsoft.tokenUri shouldBe ""
    }

    @Test
    fun `a hardened deployment whose only provider is the oauth2 github entry needs no local-only acknowledgement`() {
        val hardened = ConfigSnapshots.hardened().copy(oidcProviders = listOf(github()))

        ConfigValidator
            .validate(hardened)
            .violations
            .filter { it.contains("no OIDC provider is fully configured") }
            .shouldBeEmpty()
        ConfigValidator
            .validate(hardened.copy(oidcProviders = listOf(github().copy(tokenUri = ""))))
            .violations
            .filter { it.contains("no OIDC provider is fully configured") } shouldHaveSize 1
    }
}
