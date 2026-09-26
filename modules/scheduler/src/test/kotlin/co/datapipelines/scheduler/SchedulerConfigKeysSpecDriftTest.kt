package co.datapipelines.scheduler

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.lang.reflect.Modifier

/**
 * The `datapipelines.scheduler.*` keys (configuration.md §3.29), pinned in the three places they
 * have to agree — the doc (the authority), `application.yml` (the shipped defaults) and
 * [SchedulerProperties] (the binding, which also enforces the bounds) — plus the `db-scheduler.*`
 * wiring that feeds the library FROM those keys, so its own defaults (ten threads, a 30-minute
 * shutdown wait) can never apply. The `TransformConfigKeysSpecDriftTest` shape.
 *
 * It also pins the NEIGHBOURS of the two YAML edits this lane made (MISTAKES.md, "a YAML block
 * inserted mid-file silently reparents its neighbours"): `management.health.diskspace.enabled`
 * beside the new `db-scheduler` health key, and the transform block the scheduler block follows.
 */
class SchedulerConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and SchedulerProperties name exactly the same keys`() {
        val documented =
            KEY_REGEX
                .findAll(SchedulerTestDb.repoFile("docs/configuration.md").readText())
                .map {
                    it.value
                }.distinct()
                .sorted()
                .toList()
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val bound =
            SchedulerProperties::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { "$PREFIX.${kebab(it.name)}" }
                .sorted()

        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        bound shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults are the binding's defaults`() {
        val defaults = SchedulerProperties()
        val byKey =
            SchedulerProperties::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .associate { field -> "$PREFIX.${kebab(field.name)}" to field.apply { isAccessible = true }.get(defaults).toString() }

        EXPECTED.forEach { key -> "$key=${defaultOf(key)}" shouldBe "$key=${byKey.getValue(key)}" }
    }

    @Test
    fun `db-scheduler is fed from the scheduler keys, and the missed-heartbeat limit is the binding's constant`() {
        shipped["db-scheduler.threads"] shouldBe "\${$PREFIX.threads}"
        shipped["db-scheduler.polling-interval"] shouldBe "\${$PREFIX.polling-interval-seconds}s"
        shipped["db-scheduler.heartbeat-interval"] shouldBe "\${$PREFIX.heartbeat-interval-seconds}s"
        shipped["db-scheduler.shutdown-max-wait"] shouldBe "\${$PREFIX.shutdown-wait-seconds}s"
        shipped["db-scheduler.missed-heartbeats-limit"] shouldBe SchedulerProperties.MISSED_HEARTBEATS_LIMIT
        shipped["db-scheduler.table-name"] shouldBe "scheduled_tasks"
    }

    @Test
    fun `the health key sits under management_health and its neighbours still bind where they did`() {
        shipped["management.health.db-scheduler.enabled"] shouldBe false
        shipped["management.health.diskspace.enabled"] shouldBe false
        shipped["datapipelines.transform.max-depth"] shouldBe "\${DATAPIPELINES_TRANSFORM_MAX_DEPTH:100}"
    }

    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }.groupValues[1]
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(SchedulerTestDb.repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    private fun kebab(camel: String): String = camel.replace(UPPER) { "-" + it.value.lowercase() }

    private companion object {
        const val PREFIX = "datapipelines.scheduler"
        val KEY_REGEX = Regex("""datapipelines\.scheduler\.[a-z0-9-]+""")
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}$""")
        val UPPER = Regex("[A-Z]")

        /** Written out — the oracle is never the code under test. */
        val EXPECTED =
            listOf(
                "$PREFIX.catch-up-max-age-seconds",
                "$PREFIX.enabled",
                "$PREFIX.heartbeat-interval-seconds",
                "$PREFIX.lateness-seconds",
                "$PREFIX.max-concurrent-runs",
                "$PREFIX.max-schedules-per-workspace",
                "$PREFIX.min-interval-seconds",
                "$PREFIX.polling-interval-seconds",
                "$PREFIX.shutdown-wait-seconds",
                "$PREFIX.threads",
                "$PREFIX.tick-interval-seconds",
            )
    }
}
