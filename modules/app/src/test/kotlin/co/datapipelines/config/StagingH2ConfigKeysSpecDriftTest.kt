package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The `datapipelines.staging.h2.*` keys as `application.yml` ships them (146 / #118;
 * configuration.md §3.3): every key carries its documented environment placeholder and default,
 * `max-connections` among them, and the blocks around the staging block still bind — a key added
 * at the wrong indent re-parents its neighbours silently and the keys that break are the ones
 * nobody touched (the `BootstrapConfigKeysSpecDriftTest` lesson, applied to this block).
 *
 * `scripts/compose-env-audit.sh` pins the same placeholders against `deploy/compose.yml` and the
 * two tracked env files; this test pins the yml itself, so the two guards meet in the middle.
 */
class StagingH2ConfigKeysSpecDriftTest {
    private val loaded: Map<String, Any?> by lazy {
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource(repoFile("modules/app/src/main/resources/application.yml")))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()
    }

    @Test
    fun `every staging key ships with its environment placeholder and documented default`() {
        loaded.keys.filter { it.startsWith("$PREFIX.") }.sorted() shouldContainExactly EXPECTED.keys.sorted()
        EXPECTED.forEach { (key, placeholder) -> loaded[key] shouldBe placeholder }
    }

    @Test
    fun `the neighbouring blocks still bind - the staging block re-parented nothing`() {
        loaded["datapipelines.workspaces.member-datasources-enabled"] shouldBe
            "\${DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED:false}"
        loaded["datapipelines.result.ttl-default-seconds"] shouldBe "\${DATAPIPELINES_RESULT_TTL_DEFAULT_SECONDS:300}"
        loaded["datapipelines.sse.heartbeat-interval-seconds"] shouldBe "\${DATAPIPELINES_SSE_HEARTBEAT_INTERVAL_SECONDS:15}"
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
        const val PREFIX = "datapipelines.staging.h2"

        val EXPECTED =
            mapOf(
                "$PREFIX.mode" to "\${DATAPIPELINES_STAGING_H2_MODE:PostgreSQL}",
                "$PREFIX.max-memory-mb" to "\${DATAPIPELINES_STAGING_H2_MAX_MEMORY_MB:1024}",
                "$PREFIX.insert-batch-size" to "\${DATAPIPELINES_STAGING_H2_INSERT_BATCH_SIZE:1000}",
                "$PREFIX.result-batch-size" to "\${DATAPIPELINES_STAGING_H2_RESULT_BATCH_SIZE:10000}",
                "$PREFIX.query-timeout-seconds" to "\${DATAPIPELINES_STAGING_H2_QUERY_TIMEOUT_SECONDS:60}",
                "$PREFIX.max-connections" to "\${DATAPIPELINES_STAGING_H2_MAX_CONNECTIONS:4}",
            )
    }
}
