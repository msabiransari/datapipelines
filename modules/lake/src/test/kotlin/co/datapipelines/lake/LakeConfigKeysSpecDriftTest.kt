package co.datapipelines.lake

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.lang.reflect.Modifier

/**
 * The `datapipelines.lake.*` keys (configuration.md §3.36), pinned in the three places they have to agree — the
 * doc (the authority), `application.yml` (the shipped defaults) and [LakeProperties] (the binding) — the
 * `SchedulerConfigKeysSpecDriftTest` shape. The defaults are compared by BINDING the YAML's placeholder defaults
 * through Spring's own [Binder] and comparing the result with `LakeProperties()`, so `2GB`, `2h` and `02:00-05:00`
 * are read exactly as the running server reads them.
 */
class LakeConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and LakeProperties name exactly the same keys`() {
        val documented =
            KEY_REGEX
                .findAll(LakeTestDb.repoFile("docs/configuration.md").readText())
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val bound = keysOf(LakeProperties::class.java, PREFIX).sorted()

        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        bound shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults bind to exactly the binding's defaults`() {
        val defaults = EXPECTED.associateWith { defaultOf(it) }
        val bound = Binder(MapConfigurationPropertySource(defaults)).bind(PREFIX, LakeProperties::class.java).get()
        bound.toString() shouldBe LakeProperties().toString()
        // toString redacts the four credentials; their shipped defaults are empty, checked by value.
        CREDENTIALS.forEach { key -> "$key=${defaults[key]}" shouldBe "$key=" }
    }

    @Test
    fun `every key reads its own DATAPIPELINES_LAKE environment variable`() {
        val variables =
            EXPECTED.map { key ->
                val raw = shipped[key].toString()
                checkNotNull(PLACEHOLDER.find(raw)) { "$key is not a placeholder: $raw" }.groupValues[1]
            }
        variables.filterNot { it.startsWith("DATAPIPELINES_LAKE_") } shouldContainExactly emptyList()
        variables.distinct().size shouldBe EXPECTED.size
    }

    /** The binding's keys: each constructor-bound field, kebab-cased, recursing into LakeProperties' own nested classes. */
    private fun keysOf(
        type: Class<*>,
        prefix: String,
    ): List<String> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .flatMap { field ->
                val key = "$prefix.${kebab(field.name)}"
                if (field.type.declaringClass == LakeProperties::class.java) keysOf(field.type, key) else listOf(key)
            }

    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER.find(raw)) { "$key is not a placeholder with a default: $raw" }.groupValues[2]
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(LakeTestDb.repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    private fun kebab(camel: String): String = camel.replace(UPPER) { "-" + it.value.lowercase() }

    private companion object {
        const val PREFIX = LakeProperties.PREFIX
        val KEY_REGEX = Regex("""datapipelines\.lake\.[a-z0-9-]+(?:\.[a-z0-9-]+)*""")

        /** `${VAR:default}` — the default may itself contain `:` (`02:00-05:00`), so it runs to the closing brace. */
        val PLACEHOLDER = Regex("""^\$\{([A-Z0-9_]+):(.*)}$""")
        val UPPER = Regex("[A-Z]")

        val CREDENTIALS =
            listOf(
                "$PREFIX.storage.access-key",
                "$PREFIX.storage.secret-key",
                "$PREFIX.storage.reader-access-key",
                "$PREFIX.storage.reader-secret-key",
            )

        /** Written out — the oracle is never the code under test. */
        val EXPECTED =
            listOf(
                "$PREFIX.admission.job-timeout",
                "$PREFIX.admission.lease-wait-seconds",
                "$PREFIX.admission.max-concurrent-ingestions",
                "$PREFIX.admission.max-concurrent-maintenance",
                "$PREFIX.admission.max-delivery-bytes",
                "$PREFIX.admission.max-open-partitions-per-append",
                "$PREFIX.admission.scratch-bytes",
                "$PREFIX.admission.staging-expansion-factor",
                "$PREFIX.admission.staging-memory-limit",
                "$PREFIX.catalog.lock-timeout-seconds",
                "$PREFIX.catalog.statement-timeout-seconds",
                "$PREFIX.enabled",
                "$PREFIX.maintenance.max-bytes-rewritten-per-run",
                "$PREFIX.maintenance.window",
                "$PREFIX.mode",
                "$PREFIX.name",
                "$PREFIX.reads.egress-max-age-seconds",
                "$PREFIX.reads.egress-proxy",
                "$PREFIX.reads.freshness-tick-seconds",
                "$PREFIX.reads.max-generation-age-seconds",
                "$PREFIX.reads.memory-limit",
                "$PREFIX.retention.orphan-grace-hours",
                "$PREFIX.retention.protection-days",
                "$PREFIX.storage.access-key",
                "$PREFIX.storage.bucket",
                "$PREFIX.storage.endpoint",
                "$PREFIX.storage.kind",
                "$PREFIX.storage.landing-prefix",
                "$PREFIX.storage.path-style",
                "$PREFIX.storage.prefix",
                "$PREFIX.storage.reader-access-key",
                "$PREFIX.storage.reader-secret-key",
                "$PREFIX.storage.region",
                "$PREFIX.storage.secret-key",
            )
    }
}
