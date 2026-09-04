package co.datapipelines.datasources.crypto

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **The one suite every [KeyProvider] implementation must pass** (rule 04: one test suite covers
 * all implementations of a generic interface; the contract itself is datasources.md §7.1 and
 * `docs/key-providers.md` §2).
 *
 * An implementer of a KMS-backed provider — AWS, GCP, Azure, Vault — subclasses this and supplies
 * [newProvider]. `docs/key-providers.md` §4 names this class by name as a required step, so the
 * contract cannot be satisfied by a provider that was only smoke-tested by hand.
 *
 * Written as an abstract JUnit 5 class rather than a Kotest `Spec`: this tree runs Kotest for its
 * ASSERTIONS on the JUnit 5 platform and declares no `Spec` anywhere, and a shared suite that
 * needs a second runner to execute is a worse contract than one an implementer can subclass with
 * the tools already on the classpath (§18 — copy the house pattern).
 *
 * What it deliberately does NOT assert: how keys reach the process, how many versions exist, or
 * which version is current. Those are the implementation's business. What it pins is exactly the
 * four properties [CredentialEncryptor] relies on.
 */
abstract class KeyProviderContractTest {
    /**
     * A freshly built provider under test. Called once per test, so an implementation that
     * caches per-instance is exercised the way production builds it (once, at startup).
     */
    protected abstract fun newProvider(): KeyProvider

    @Test
    fun `the provider names itself with the config value that selects it`() {
        newProvider().name.isNotBlank() shouldBe true
    }

    @Test
    fun `current returns a 32-byte key with a version inside the single-byte range`() {
        val current = newProvider().current()

        current.bytes.size shouldBe DataKey.KEY_BYTES
        current.version shouldBeGreaterThanOrEqual DataKey.MIN_VERSION
        current.version shouldBeLessThanOrEqual DataKey.MAX_VERSION
    }

    @Test
    fun `byVersion of the current version returns the current key`() {
        val provider = newProvider()
        val current = provider.current()

        val looked = requireNotNull(provider.byVersion(current.version)) { "byVersion must answer for the current version" }

        looked.version shouldBe current.version
        looked.bytes.toList() shouldBe current.bytes.toList()
    }

    @Test
    fun `an unknown version is null, not an exception and not a substitute key`() {
        val provider = newProvider()
        // A version no deployment can legally have configured: 0 is reserved as "not a version"
        // (an all-zero page must not forge one), so no provider may answer for it.
        provider.byVersion(0).shouldBeNull()
    }

    @Test
    fun `current is stable across calls — the encryptor caches it at construction`() {
        val provider = newProvider()

        val first = provider.current()
        val second = provider.current()

        second.version shouldBe first.version
        second.bytes.toList() shouldBe first.bytes.toList()
    }

    @Test
    fun `the key bytes handed out are a copy — a caller cannot mutate the provider's key`() {
        val provider = newProvider()
        val version = provider.current().version

        provider.current().bytes.fill(0)

        requireNotNull(provider.byVersion(version)).bytes.all { it == 0.toByte() } shouldBe false
    }
}
