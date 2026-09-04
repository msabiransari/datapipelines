package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The `datapipelines.db.*` keys, pinned in the three places they have to agree:
 * `docs/configuration.md` §3.20 (the authority), `application.yml` (the shipped defaults) and
 * [ConfigValidator] (the §7 rules that read them) — the sibling of
 * [BootstrapConfigKeysSpecDriftTest], applied to the block round 068 extended.
 *
 * ## Why this guard exists here specifically
 *
 * `datapipelines.db.encryption-key` was, until 068, the ONLY key in its block, and adding three
 * more to a `datapipelines.db:` block is exactly the shape MISTAKES.md's "a YAML block inserted
 * mid-file silently reparents its NEIGHBOURS" warns about: the keys that break are the ones
 * nobody touched. So this test loads the real file and asserts the new keys bind, that the
 * PRE-EXISTING `encryption-key` still binds, and that the blocks either side of `db:` are still
 * where they were.
 *
 * It also pins the two defaults that carry the round's compatibility promise: `key-provider`
 * defaults to `env`, and the rotation keys are ABSENT rather than declared empty (a declared
 * empty map would bind over an environment-supplied one).
 */
class DbKeyProviderConfigKeysSpecDriftTest {
    private val loaded: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()
    }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same db keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val shipped = loaded.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val readByValidator = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        // The rotation keys ship UNSET, so `application.yml` carries only the two with defaults.
        shipped shouldContainExactly SHIPPED_WITH_DEFAULTS
        readByValidator shouldContainExactly EXPECTED
    }

    @Test
    fun `the pre-existing encryption key still binds, unchanged`() {
        // The key every deployment already sets. If a mis-indented insert had re-parented it,
        // this is the assertion that goes red — and the failure would otherwise be a running
        // deployment whose stored credentials suddenly cannot be decrypted.
        loaded["$PREFIX.encryption-key"] shouldBe "\${DATAPIPELINES_DB_ENCRYPTION_KEY}"
    }

    @Test
    fun `key-provider defaults to env, so a deployment that predates the seam needs no config edit`() {
        loaded["$PREFIX.key-provider"] shouldBe "\${DATAPIPELINES_DB_KEY_PROVIDER:env}"
    }

    @Test
    fun `the rotation keys are absent, not declared empty`() {
        // A declared `encryption-keys: {}` would bind an EMPTY map over anything the environment
        // supplies, silently disabling rotation for an operator who set the env vars correctly.
        loaded.keys.none { it.startsWith("$PREFIX.encryption-keys") } shouldBe true
        loaded.containsKey("$PREFIX.encryption-key-current") shouldBe false
    }

    @Test
    fun `the neighbouring blocks still bind - the db block re-parented nothing`() {
        // `jwt:` sits immediately above `db:` and `auth:` immediately below — the two blocks a
        // wrongly-indented insert would have swallowed.
        loaded["datapipelines.jwt.secret"] shouldBe "\${DATAPIPELINES_JWT_SECRET}"
        loaded["datapipelines.auth.base-url"] shouldBe "\${DATAPIPELINES_AUTH_BASE_URL:}"
        loaded["datapipelines.auth.bootstrap-admin-email"] shouldBe "\${DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL:}"
        loaded["datapipelines.redis.host"] shouldBe "\${DATAPIPELINES_REDIS_HOST}"
    }

    private fun keysIn(text: String): List<String> =
        KEY_REGEX
            .findAll(text)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val PREFIX = "datapipelines.db"

        val EXPECTED =
            listOf(
                "$PREFIX.encryption-key",
                "$PREFIX.encryption-key-current",
                "$PREFIX.encryption-keys",
                "$PREFIX.key-provider",
            )

        val SHIPPED_WITH_DEFAULTS = listOf("$PREFIX.encryption-key", "$PREFIX.key-provider")

        val KEY_REGEX = Regex("""datapipelines\.db\.[a-z0-9-]+""")
    }
}
