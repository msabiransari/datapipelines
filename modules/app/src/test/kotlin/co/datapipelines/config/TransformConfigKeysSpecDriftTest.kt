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
 * The `datapipelines.transform.*` keys (7b, #7, configuration.md §3.28), pinned in the three
 * places they have to agree: the doc (the authority), `application.yml` (the shipped defaults)
 * and [ConfigValidator] (the §7 rule that reads them). Same shape and the same two targets as
 * [OrgConfigKeysSpecDriftTest]: doc drift and YAML reparenting — the transform block is
 * APPENDED after the whole `datapipelines:` tree for the same reason.
 */
class TransformConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same transform keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val inYaml = shipped.keys.filter { it.startsWith(PREFIX) }.sorted()
        val readByValidator = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        readByValidator shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults are the documented ones`() {
        shipped["$PREFIX.evaluate-timeout-seconds"] shouldBe "\${DATAPIPELINES_TRANSFORM_EVALUATE_TIMEOUT_SECONDS:10}"
        shipped["$PREFIX.suite-timeout-seconds"] shouldBe "\${DATAPIPELINES_TRANSFORM_SUITE_TIMEOUT_SECONDS:60}"
        shipped["$PREFIX.pool-size"] shouldBe "\${DATAPIPELINES_TRANSFORM_POOL_SIZE:4}"
        shipped["$PREFIX.pool-queue"] shouldBe "\${DATAPIPELINES_TRANSFORM_POOL_QUEUE:64}"
        shipped["$PREFIX.abandon-grace-seconds"] shouldBe "\${DATAPIPELINES_TRANSFORM_ABANDON_GRACE_SECONDS:30}"
        shipped["$PREFIX.max-input-rows"] shouldBe "\${DATAPIPELINES_TRANSFORM_MAX_INPUT_ROWS:100000}"
        shipped["$PREFIX.max-value-bytes"] shouldBe "\${DATAPIPELINES_TRANSFORM_MAX_VALUE_BYTES:1048576}"
        shipped["$PREFIX.max-string-bytes"] shouldBe "\${DATAPIPELINES_TRANSFORM_MAX_STRING_BYTES:1048576}"
        shipped["$PREFIX.max-depth"] shouldBe "\${DATAPIPELINES_TRANSFORM_MAX_DEPTH:100}"
    }

    @Test
    fun `the shipped transform defaults pass the production rules`() {
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(
                    transformEvaluateTimeoutSeconds = defaultOf("$PREFIX.evaluate-timeout-seconds").toLong(),
                    transformSuiteTimeoutSeconds = defaultOf("$PREFIX.suite-timeout-seconds").toLong(),
                    transformAbandonGraceSeconds = defaultOf("$PREFIX.abandon-grace-seconds").toLong(),
                    transformPoolSize = defaultOf("$PREFIX.pool-size").toLong(),
                    transformPoolQueue = defaultOf("$PREFIX.pool-queue").toLong(),
                    transformMaxInputRows = defaultOf("$PREFIX.max-input-rows").toLong(),
                    transformMaxValueBytes = defaultOf("$PREFIX.max-value-bytes").toLong(),
                    transformMaxStringBytes = defaultOf("$PREFIX.max-string-bytes").toLong(),
                    transformMaxDepth = defaultOf("$PREFIX.max-depth").toLong(),
                ),
            )
        report.violations shouldContainExactly emptyList()
    }

    @Test
    fun `the bounds check refuses an evaluate above its suite and a sub-second grace`() {
        val inverted =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(transformEvaluateTimeoutSeconds = 61, transformSuiteTimeoutSeconds = 60),
            )
        inverted.violations.any { it.startsWith("datapipelines.transform.evaluate-timeout-seconds") } shouldBe true

        val zeroGrace =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(transformAbandonGraceSeconds = 0),
            )
        zeroGrace.violations.any { it.startsWith("datapipelines.transform.abandon-grace-seconds") } shouldBe true
    }

    /** The default out of a `${'$'}{VAR:default}` placeholder — what an unset variable resolves to. */
    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }
            .groupValues[1]
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

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
        const val PREFIX = "datapipelines.transform"

        /** `${'$'}{VAR:default}` — group 1 is the default. */
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}${'$'}""")

        val EXPECTED =
            listOf(
                "$PREFIX.abandon-grace-seconds",
                "$PREFIX.evaluate-timeout-seconds",
                "$PREFIX.max-depth",
                "$PREFIX.max-input-rows",
                "$PREFIX.max-string-bytes",
                "$PREFIX.max-value-bytes",
                "$PREFIX.pool-queue",
                "$PREFIX.pool-size",
                "$PREFIX.suite-timeout-seconds",
            )

        val KEY_REGEX = Regex("""datapipelines\.transform\.[a-z0-9-]+""")
    }
}
