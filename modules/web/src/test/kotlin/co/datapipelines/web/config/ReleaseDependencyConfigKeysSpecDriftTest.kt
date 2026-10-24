package co.datapipelines.web.config

import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ByteArrayResource
import java.lang.reflect.Modifier

/**
 * The `datapipelines.release.dependencies.*` keys (configuration.md §3.35), pinned in the four places they have to
 * agree — the doc's table (the authority), the doc's §5 template, `application.yml` (the shipped defaults) and
 * [ReleaseDependencyProperties] (the binding, which also enforces the ranges). The `SchedulerConfigKeysSpecDriftTest`
 * shape. It also pins the NEIGHBOUR of the YAML edit (a block inserted mid-file can silently reparent the keys around
 * it): the dashboard runtime's last key, which the release block follows.
 */
class ReleaseDependencyConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load(TestRepoFiles.read("modules/app/src/main/resources/application.yml")) }
    private val configurationMd: String by lazy { TestRepoFiles.read("docs/configuration.md") }

    @Test
    fun `configuration_md, application_yml and ReleaseDependencyProperties name exactly the same keys`() {
        val documented =
            KEY_REGEX
                .findAll(section(configurationMd, "### 3.35 ", "\n---\n"))
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val bound =
            ReleaseDependencyProperties::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { "$PREFIX.${kebab(it.name)}" }
                .sorted()

        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        bound shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults, the documented defaults, the section 5 template and the binding agree`() {
        val defaults = ReleaseDependencyProperties()
        val bound =
            ReleaseDependencyProperties::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .associate { "$PREFIX.${kebab(it.name)}" to it.apply { isAccessible = true }.get(defaults).toString() }
        val template = load(section(configurationMd, "## 5. ", "\n## 6").substringAfter("```yaml").substringBefore("```"))

        EXPECTED.forEach { key ->
            val documentedDefault =
                checkNotNull(Regex("""^\| `${Regex.escape(key)}` \| `([^`]+)` \|""", RegexOption.MULTILINE).find(configurationMd)) {
                    "§3.35 has no row for $key"
                }.groupValues[1]
            "$key=${defaultOf(shipped, key)}" shouldBe "$key=${bound.getValue(key)}"
            "$key=$documentedDefault" shouldBe "$key=${bound.getValue(key)}"
            "$key=${template[key]}" shouldBe "$key=${shipped[key]}"
        }
    }

    @Test
    fun `every env var follows the DATAPIPELINES_RELEASE_DEPENDENCIES_ convention`() {
        EXPECTED.forEach { key ->
            val env = "DATAPIPELINES_RELEASE_DEPENDENCIES_" + key.removePrefix("$PREFIX.").uppercase().replace('-', '_')
            shipped[key].toString() shouldContain "\${$env:"
        }
    }

    @Test
    fun `the release block did not reparent its neighbour`() {
        shipped["datapipelines.dashboards.timeouts.render-seconds"] shouldBe "\${DATAPIPELINES_DASHBOARDS_RENDER_SECONDS:20}"
    }

    @Test
    fun `each bound is range-checked naming its key, and toBounds hands the core the same numbers`() {
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(maxDepth = 0) }.message shouldContain "$PREFIX.max-depth"
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(maxVersions = 100_001) }.message shouldContain
            "$PREFIX.max-versions"
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(maxEdges = 0) }.message shouldContain "$PREFIX.max-edges"
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(maxBlockers = 1_001) }.message shouldContain
            "$PREFIX.max-blockers"
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(maxWireBytes = 1_023) }.message shouldContain
            "$PREFIX.max-wire-bytes"
        shouldThrow<IllegalArgumentException> { ReleaseDependencyProperties(lockWaitSeconds = 61) }.message shouldContain
            "$PREFIX.lock-wait-seconds"

        val bounds =
            ReleaseDependencyProperties(
                maxDepth = 7,
                maxVersions = 8,
                maxEdges = 9,
                maxBlockers = 2,
                maxWireBytes = 2048,
                lockWaitSeconds = 3,
            ).toBounds()
        bounds shouldBe ReleaseBounds(7, 8, 9, 2, 2048, 3)
    }

    private fun section(
        text: String,
        start: String,
        end: String,
    ): String {
        val from = text.indexOf(start).also { check(it >= 0) { "configuration.md has no '$start'" } }
        val to = text.indexOf(end, from + start.length).let { if (it < 0) text.length else it }
        return text.substring(from, to)
    }

    private fun defaultOf(
        source: Map<String, Any?>,
        key: String,
    ): String {
        val raw = checkNotNull(source[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }.groupValues[1]
    }

    private fun load(yaml: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load("yaml", ByteArrayResource(yaml.toByteArray()))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    private fun kebab(camel: String): String = camel.replace(UPPER) { "-" + it.value.lowercase() }

    private companion object {
        const val PREFIX = "datapipelines.release.dependencies"
        val KEY_REGEX = Regex("""datapipelines\.release\.dependencies\.[a-z0-9-]+""")
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""")
        val UPPER = Regex("[A-Z]")

        /** Written out — the oracle is never the code under test. */
        val EXPECTED =
            listOf(
                "$PREFIX.lock-wait-seconds",
                "$PREFIX.max-blockers",
                "$PREFIX.max-depth",
                "$PREFIX.max-edges",
                "$PREFIX.max-versions",
                "$PREFIX.max-wire-bytes",
            )
    }
}
