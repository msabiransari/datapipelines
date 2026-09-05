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
 * The `datapipelines.org.*` keys (072 calculators, configuration.md §3.21), pinned in the four
 * places they have to agree: the doc (the authority), `application.yml` and `application-dev.yml`
 * (the shipped defaults) and [ConfigValidator] (the §7 rules that read them).
 *
 * Same shape and the same two targets as [BootstrapConfigKeysSpecDriftTest]:
 *
 * 1. **Doc drift** — `scripts/docs-audit.sh` enforces that a `datapipelines.*` key mentioned
 *    elsewhere is *defined* in configuration.md, but nothing checks the reverse, or that the
 *    code and the doc spell a key the same way.
 * 2. **YAML reparenting** — the org block was APPENDED after the whole `datapipelines:` tree
 *    precisely because a 2-space block inserted mid-tree closes its predecessor and silently
 *    re-parents whatever follows (MISTAKES.md). The keys that break are the ones nobody
 *    touched, so this test asserts the untouched neighbours still bind.
 */
class OrgConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }
    private val dev: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application-dev.yml") }

    @Test
    fun `configuration_md, application_yml and ConfigValidator name exactly the same org keys`() {
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
        shipped["$PREFIX.currency.name"] shouldBe "\${DATAPIPELINES_ORG_CURRENCY_NAME:Dollar}"
        shipped["$PREFIX.currency.symbol"] shouldBe "\${DATAPIPELINES_ORG_CURRENCY_SYMBOL:\$}"
        shipped["$PREFIX.fiscal-start-date"] shouldBe "\${DATAPIPELINES_ORG_FISCAL_START_DATE:01-01}"
        shipped["$PREFIX.week-start"] shouldBe "\${DATAPIPELINES_ORG_WEEK_START:monday}"
        shipped["$PREFIX.timezone"] shouldBe "\${DATAPIPELINES_ORG_TIMEZONE:UTC}"
    }

    @Test
    fun `the dev profile spells the same five keys and passes the production rules`() {
        dev.keys.filter { it.startsWith(PREFIX) }.sorted() shouldContainExactly EXPECTED

        // §7's closing rule, for this block: the documented dev setup must satisfy the
        // PRODUCTION checks — a broken dev value is fixed at the data, never by weakening
        // the rule. The dev file carries literals, so they can be validated directly.
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(
                    orgCurrencyName = dev["$PREFIX.currency.name"] as String?,
                    orgCurrencySymbol = dev["$PREFIX.currency.symbol"]?.toString(),
                    orgFiscalStartDate = dev["$PREFIX.fiscal-start-date"] as String?,
                    orgWeekStart = dev["$PREFIX.week-start"] as String?,
                    orgTimezone = dev["$PREFIX.timezone"] as String?,
                ),
            )
        report.violations shouldContainExactly emptyList()
    }

    @Test
    fun `the neighbouring blocks still bind - the org block re-parented nothing`() {
        // The block the org block was appended AFTER, and a spread of the ones above it. If the
        // insert had gone in at the wrong depth these are the keys that would have moved.
        shipped["datapipelines.bootstrap.datasources-file"] shouldBe "\${DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE:}"
        shipped["datapipelines.bootstrap.examples-file"] shouldBe "\${DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE:}"
        shipped["datapipelines.audit.retention-days"] shouldBe "\${DATAPIPELINES_AUDIT_RETENTION_DAYS:365}"
        shipped["datapipelines.observability.logging.format"] shouldBe "\${DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT:json}"
        shipped["datapipelines.ui.theme"] shouldBe "\${DATAPIPELINES_UI_THEME:saas}"
        shipped["datapipelines.executions.error-detail"] shouldBe "\${DATAPIPELINES_EXECUTIONS_ERROR_DETAIL:full}"
        shipped["datapipelines.workspaces.provisioning-mode"] shouldBe
            "\${DATAPIPELINES_WORKSPACES_PROVISIONING_MODE:self-serve}"
        shipped["datapipelines.jwt.secret"] shouldBe "\${DATAPIPELINES_JWT_SECRET}"

        // The dev file's org block was appended after ITS last block too.
        dev["datapipelines.observability.logging.format"] shouldBe "console"
        dev["datapipelines.ui.theme"] shouldBe "saas"
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
        const val PREFIX = "datapipelines.org"

        val EXPECTED =
            listOf(
                "$PREFIX.currency.name",
                "$PREFIX.currency.symbol",
                "$PREFIX.fiscal-start-date",
                "$PREFIX.timezone",
                "$PREFIX.week-start",
            )

        val KEY_REGEX = Regex("""datapipelines\.org\.[a-z0-9-]+(?:\.[a-z0-9-]+)?""")
    }
}
