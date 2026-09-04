package co.datapipelines.datasources.crypto

import co.datapipelines.datasources.test32ByteKeyBase64
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * [EnvKeyProvider] against [KeyProviderContractTest] — the same suite `docs/key-providers.md` §4
 * requires of an AWS/GCP/Azure/Vault implementation.
 *
 * Run TWICE, over the two shapes an `env` deployment can be in: the single configured key every
 * deployment starts with, and a rotated deployment carrying `{1, 2}` with `current = 2`. A
 * contract suite that only ever saw one key would not have exercised [KeyProvider.byVersion]'s
 * real job.
 */
class EnvKeyProviderContractTest {
    @Nested
    inner class SingleConfiguredKey : KeyProviderContractTest() {
        override fun newProvider(): KeyProvider = EnvKeyProvider.fromConfig(test32ByteKeyBase64())

        @Test
        fun `the single configured key is version 1 and is current`() {
            val provider = newProvider() as EnvKeyProvider

            provider.configuredVersions shouldBe listOf(1)
            provider.current().version shouldBe 1
            provider.name shouldBe "env"
        }
    }

    @Nested
    inner class RotatedToVersionTwo : KeyProviderContractTest() {
        override fun newProvider(): KeyProvider =
            EnvKeyProvider.fromConfig(test32ByteKeyBase64(), mapOf(2 to SECOND_KEY), currentVersion = 2)

        @Test
        fun `both versions remain decryptable while current is the newer one`() {
            val provider = newProvider() as EnvKeyProvider

            provider.configuredVersions shouldBe listOf(1, 2)
            provider.current().version shouldBe 2
            requireNotNull(provider.byVersion(1)).version shouldBe 1
        }
    }

    private companion object {
        val SECOND_KEY: String = Base64.getEncoder().encodeToString(ByteArray(DataKey.KEY_BYTES) { (it + 100).toByte() })
    }
}
