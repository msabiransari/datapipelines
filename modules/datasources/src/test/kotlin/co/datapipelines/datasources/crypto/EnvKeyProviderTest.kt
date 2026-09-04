package co.datapipelines.datasources.crypto

import co.datapipelines.datasources.test32ByteKeyBase64
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * [EnvKeyProvider]'s CONFIGURATION rules (configuration.md §3.20) — the fail-fast half that used
 * to live on `CredentialEncryptor.fromBase64Key`, moved with the code that owns it.
 *
 * Every one of these throws from the factory, and the factory runs at startup wiring, so each is
 * a "the application does not start" case. `ConfigValidator` reports the same defects as named
 * §7 violations BEFORE the wiring reaches here — two layers deliberately, because the validator
 * gives the operator the whole list at once and this one is the load-bearing refusal.
 */
class EnvKeyProviderTest {
    @Test
    fun `a missing primary key fails fast so the application cannot start`() {
        shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig(null) }
        shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig("   ") }
    }

    @Test
    fun `a malformed base64 key fails fast`() {
        shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig("not valid base64 @@@@") }
    }

    @Test
    fun `a key of the wrong length fails fast`() {
        val sixteenBytes = Base64.getEncoder().encodeToString(ByteArray(16))
        val fortyBytes = Base64.getEncoder().encodeToString(ByteArray(40))

        shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig(sixteenBytes) }
        shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig(fortyBytes) }
    }

    @Test
    fun `a rotation key of the wrong length fails fast and names the version`() {
        val thirtyOne = Base64.getEncoder().encodeToString(ByteArray(31))

        val thrown =
            shouldThrow<IllegalStateException> {
                EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to thirtyOne))
            }

        requireNotNull(thrown.message) shouldContain "datapipelines.db.encryption-keys[2]"
        requireNotNull(thrown.message) shouldContain "31"
    }

    @Test
    fun `no failure message can carry key material`() {
        // configuration.md's bearer-secret rule: a violation message reaches the logs. Every
        // message here names the CONFIG KEY and the defect, never the value — so a malformed
        // key that is nonetheless most of a real key does not get logged on the way out.
        val almostValid = Base64.getEncoder().encodeToString(ByteArray(31) { 0x5A })

        val messages =
            listOf(
                shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig(almostValid) }.message,
                shouldThrow<IllegalStateException> { EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to almostValid)) }.message,
            )

        messages.forEach { requireNotNull(it) shouldNotContain almostValid }
    }

    @Test
    fun `version 1 may not be redeclared in the rotation map — one version has one spelling`() {
        val thrown =
            shouldThrow<IllegalStateException> {
                EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(1 to test32ByteKeyBase64()))
            }

        requireNotNull(thrown.message) shouldContain "datapipelines.db.encryption-key"
    }

    @Test
    fun `a version outside the single-byte range is refused`() {
        shouldThrow<IllegalStateException> {
            EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(256 to test32ByteKeyBase64()))
        }
        shouldThrow<IllegalStateException> {
            EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(0 to test32ByteKeyBase64()))
        }
    }

    @Test
    fun `current defaults to the highest configured version`() {
        val provider = EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to SECOND, 5 to THIRD))

        provider.current().version shouldBe 5
    }

    @Test
    fun `current naming an unconfigured version is refused, listing what IS configured`() {
        val thrown =
            shouldThrow<IllegalStateException> {
                EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to SECOND), currentVersion = 3)
            }

        requireNotNull(thrown.message) shouldContain "datapipelines.db.encryption-key-current"
        requireNotNull(thrown.message) shouldContain "[1, 2]"
    }

    private companion object {
        val SECOND: String = Base64.getEncoder().encodeToString(ByteArray(DataKey.KEY_BYTES) { (it + 100).toByte() })
        val THIRD: String = Base64.getEncoder().encodeToString(ByteArray(DataKey.KEY_BYTES) { (it + 7).toByte() })
    }
}
