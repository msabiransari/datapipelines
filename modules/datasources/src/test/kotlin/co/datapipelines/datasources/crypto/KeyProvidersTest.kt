package co.datapipelines.datasources.crypto

import co.datapipelines.datasources.test32ByteKeyBase64
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.Base64

/**
 * [KeyProviders] — the `datapipelines.db.key-provider` registry, and [EnvKeyProvider.from], the
 * factory entry it dispatches to.
 *
 * This is the seam an AWS/GCP/Azure/Vault implementation plugs into (`docs/key-providers.md` §4),
 * so what is pinned here is the dispatch contract itself: the default that keeps every existing
 * deployment working with no config edit, the refusal that names what this build ships, and the
 * fact that a provider reads its own settings through the [KeyProviderConfig] port rather than
 * through Spring — which is what keeps `datasources` on `typesystem` alone.
 */
class KeyProvidersTest {
    /** A [KeyProviderConfig] over plain maps — the shape `SpringKeyProviderConfig` implements. */
    private class MapConfig(
        private val strings: Map<String, String> = emptyMap(),
        private val maps: Map<String, Map<String, String>> = emptyMap(),
    ) : KeyProviderConfig {
        override fun string(key: String): String? = strings[key]

        override fun map(key: String): Map<String, String> = maps[key] ?: emptyMap()
    }

    private val envOnly = MapConfig(strings = mapOf("datapipelines.db.encryption-key" to test32ByteKeyBase64()))

    @Test
    fun `an absent or blank provider name selects the default, so no deployment needs a config edit`() {
        assertAll(
            { KeyProviders.DEFAULT shouldBe EnvKeyProvider.NAME },
            { KeyProviders.create(null, envOnly).name shouldBe "env" },
            { KeyProviders.create("  ", envOnly).name shouldBe "env" },
            { KeyProviders.create(" env ", envOnly).name shouldBe "env" },
        )
    }

    @Test
    fun `the shipped registry is exactly the env provider`() {
        assertAll(
            { KeyProviders.known() shouldContain "env" },
            { KeyProviders.known().size shouldBe 1 },
            { KeyProviders.PROPERTY shouldBe "datapipelines.db.key-provider" },
        )
    }

    @Test
    fun `an unknown provider is refused, naming what this build ships and where to implement one`() {
        val thrown = shouldThrow<IllegalStateException> { KeyProviders.create("aws-kms", envOnly) }

        assertAll(
            { requireNotNull(thrown.message) shouldContain "datapipelines.db.key-provider 'aws-kms'" },
            { requireNotNull(thrown.message) shouldContain "env" },
            { requireNotNull(thrown.message) shouldContain "docs/key-providers.md" },
        )
    }

    @Test
    fun `the env factory reads all three of its settings through the config port`() {
        val second = Base64.getEncoder().encodeToString(ByteArray(DataKey.KEY_BYTES) { (it + 100).toByte() })
        val provider =
            KeyProviders.create(
                "env",
                MapConfig(
                    strings =
                        mapOf(
                            "datapipelines.db.encryption-key" to test32ByteKeyBase64(),
                            "datapipelines.db.encryption-key-current" to " 2 ",
                        ),
                    maps = mapOf("datapipelines.db.encryption-keys" to mapOf(" 2 " to second)),
                ),
            ) as EnvKeyProvider

        assertAll(
            { provider.configuredVersions shouldBe listOf(1, 2) },
            { provider.current().version shouldBe 2 },
            { requireNotNull(provider.byVersion(1)).version shouldBe 1 },
        )
    }

    @Test
    fun `a non-numeric rotation version is refused by NAME, not swallowed`() {
        val thrown =
            shouldThrow<IllegalStateException> {
                KeyProviders.create(
                    "env",
                    MapConfig(
                        strings = mapOf("datapipelines.db.encryption-key" to test32ByteKeyBase64()),
                        maps = mapOf("datapipelines.db.encryption-keys" to mapOf("two" to test32ByteKeyBase64())),
                    ),
                )
            }

        requireNotNull(thrown.message) shouldContain "'two'"
    }

    @Test
    fun `a non-numeric current version is refused by NAME`() {
        val thrown =
            shouldThrow<IllegalStateException> {
                KeyProviders.create(
                    "env",
                    MapConfig(
                        strings =
                            mapOf(
                                "datapipelines.db.encryption-key" to test32ByteKeyBase64(),
                                "datapipelines.db.encryption-key-current" to "latest",
                            ),
                    ),
                )
            }

        requireNotNull(thrown.message) shouldContain "datapipelines.db.encryption-key-current"
    }

    @Test
    fun `a blank current version is treated as unset, not as a malformed one`() {
        val provider =
            KeyProviders.create(
                "env",
                MapConfig(
                    strings =
                        mapOf(
                            "datapipelines.db.encryption-key" to test32ByteKeyBase64(),
                            "datapipelines.db.encryption-key-current" to "   ",
                        ),
                ),
            )

        provider.current().version shouldBe 1
    }

    @Test
    fun `an empty config reaches the env provider's missing-key failure, not a crash`() {
        assertAll(
            { KeyProviderConfig.EMPTY.string("anything").shouldBeNull() },
            { KeyProviderConfig.EMPTY.map("anything").shouldBeEmpty() },
            { shouldThrow<IllegalStateException> { KeyProviders.create(null, KeyProviderConfig.EMPTY) } },
        )
    }

    @Test
    fun `a data key never renders its bytes, and hands out copies`() {
        val key = DataKey(3, ByteArray(DataKey.KEY_BYTES) { 0x41 })

        assertAll(
            { key.toString() shouldBe "DataKey(version=3, bytes=<redacted>)" },
            { key.toString() shouldContain "redacted" },
            // Mutating what was handed out must not reach the key itself.
            { key.bytes.also { it.fill(0) }.let { key.bytes.all { b -> b == 0x41.toByte() } } shouldBe true },
        )
    }

    @Test
    fun `a data key refuses a wrong length or an out-of-range version`() {
        assertAll(
            { shouldThrow<IllegalArgumentException> { DataKey(1, ByteArray(31)) } },
            { shouldThrow<IllegalArgumentException> { DataKey(0, ByteArray(DataKey.KEY_BYTES)) } },
            { shouldThrow<IllegalArgumentException> { DataKey(256, ByteArray(DataKey.KEY_BYTES)) } },
        )
    }
}
