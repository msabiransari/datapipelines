package co.datapipelines.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.DurationStyle
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The Redis client bounds are stated twice ON PURPOSE — once in production config, once in the
 * test factory that must reproduce the production posture (#482) — so a guard reads both and
 * refuses the drift, the way `DbKeyProviderConfigKeysSpecDriftTest` pins the `db` block.
 *
 * Three places must agree:
 *
 *  1. the §3.14 bridge (`spring.data.redis.timeout` / `.connect-timeout`) — what Lettuce
 *     actually reads — binds the operator variables;
 *  2. the operator block (`datapipelines.redis.command-timeout` / `.connect-timeout`, §3.1)
 *     carries the SAME placeholders with the SAME defaults — the two blocks are one contract,
 *     and a default changed in one but not the other is a silent second authority;
 *  3. [TestRedis] builds every factory with exactly the shipped defaults. Before #482 the test
 *     factories mirrored production's MISSING timeout (Lettuce's 60 s silence) — the stopped-
 *     Redis cases took ~242 s each. When the fix landed, the factories deliberately carried it
 *     too; this class is what stops either side from drifting back.
 *
 * Red when either side changes alone: an `application.yml` default edited without [TestRedis]
 * fails `TestRedis builds its factories with the shipped defaults`; a [TestRedis] constant
 * edited without the yml fails the same test; a block re-pointed at another variable, or one
 * block's default changed without the other's, fails the placeholder test.
 */
class TestRedisTimeoutParityTest {
    private val app: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(repoFile(APP_YML).absolutePath))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()
    }

    @Test
    fun `the bridge and the operator block carry the SAME placeholder for each bound`() {
        defaultOf(BRIDGE_COMMAND, COMMAND_VAR) shouldBe defaultOf(OPERATOR_COMMAND, COMMAND_VAR)
        defaultOf(BRIDGE_CONNECT, CONNECT_VAR) shouldBe defaultOf(OPERATOR_CONNECT, CONNECT_VAR)
    }

    @Test
    fun `TestRedis builds its factories with the shipped defaults, not Lettuce's 60 s silence`() {
        TestRedis.COMMAND_TIMEOUT shouldBe DurationStyle.detectAndParse(defaultOf(BRIDGE_COMMAND, COMMAND_VAR))
        TestRedis.CONNECT_TIMEOUT shouldBe DurationStyle.detectAndParse(defaultOf(BRIDGE_CONNECT, CONNECT_VAR))
    }

    /**
     * The default text inside the key's `${VAR:default}` placeholder. Refuses when the key is
     * missing or binds ANY other variable — a bridge re-pointed away from the operator variable
     * is exactly the drift this refuses (the key would bind nothing an operator can set).
     */
    private fun defaultOf(
        key: String,
        varName: String,
    ): String {
        val text = app[key]?.toString() ?: ""
        val match = Regex("^\\$\\{" + Regex.escape(varName) + ":([^}]*)\\}$").find(text)
        require(match != null) { "$key = '$text' does not bind the operator variable $varName" }
        return match.groupValues[1]
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val APP_YML = "modules/app/src/main/resources/application.yml"
        const val COMMAND_VAR = "DATAPIPELINES_REDIS_COMMAND_TIMEOUT"
        const val CONNECT_VAR = "DATAPIPELINES_REDIS_CONNECT_TIMEOUT"
        const val BRIDGE_COMMAND = "spring.data.redis.timeout"
        const val BRIDGE_CONNECT = "spring.data.redis.connect-timeout"
        const val OPERATOR_COMMAND = "datapipelines.redis.command-timeout"
        const val OPERATOR_CONNECT = "datapipelines.redis.connect-timeout"
    }
}
