package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment

/** Auth configuration: domain allowlisting and safe diagnostic strings. */
class AuthPropertiesTest {
    @Test
    fun `a provider is enabled only with a non-blank client id`() {
        AuthProperties.Provider().isEnabled.shouldBeFalse()
        AuthProperties.Provider(clientId = "").isEnabled.shouldBeFalse()
        AuthProperties.Provider(clientId = " \t\n").isEnabled.shouldBeFalse()
        AuthProperties.Provider(clientId = "id").isEnabled.shouldBeTrue()
        AuthProperties.Provider(clientId = " id ").isEnabled.shouldBeTrue()
    }

    @Test
    fun `provider read timeout defaults to ten seconds and binds beside the providers list`() {
        AuthProperties().oidc.providerReadTimeoutSeconds shouldBe 10L
        val env =
            MockEnvironment()
                .withProperty("datapipelines.auth.oidc.provider-read-timeout-seconds", "7")
                .withProperty("datapipelines.auth.oidc.providers[0].name", "example")
        val bound = Binder.get(env).bind("datapipelines.auth", Bindable.of(AuthProperties::class.java)).get()
        bound.oidc.providerReadTimeoutSeconds shouldBe 7L
        bound.oidc.providers
            .single()
            .name shouldBe "example"
    }

    @Test
    fun `provider diagnostic strings redact the client secret directly and through parent properties`() {
        val secret = "provider-secret-regression-655"
        val provider = AuthProperties.Provider(name = "github", clientSecret = secret)
        val oidc = AuthProperties.Oidc(providers = listOf(provider))
        val props = AuthProperties(oidc = oidc)

        for (value in listOf(provider, oidc, props)) {
            value.toString() shouldNotContain secret
            value.toString() shouldContain "clientSecret=[REDACTED]"
        }
    }

    @Test
    fun `an empty allowlist permits any domain (open provisioning)`() {
        AuthProperties().isDomainAllowed("anyone@random.com").shouldBeTrue()
    }

    @Test
    fun `a configured allowlist admits only listed domains, case-insensitively`() {
        val props = AuthProperties(allowlist = AuthProperties.Allowlist(domains = listOf("company.com", "sub.co")))
        props.isDomainAllowed("alice@company.com").shouldBeTrue()
        props.isDomainAllowed("bob@COMPANY.COM").shouldBeTrue()
        props.isDomainAllowed("eve@evil.com").shouldBeFalse()
        props.isDomainAllowed("malformed-no-at").shouldBeFalse()
    }
}
