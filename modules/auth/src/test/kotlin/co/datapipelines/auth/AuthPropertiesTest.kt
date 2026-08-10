package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

/** Email domain allowlist (auth.md §4.3): empty = open, otherwise case-insensitive match. */
class AuthPropertiesTest {
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
