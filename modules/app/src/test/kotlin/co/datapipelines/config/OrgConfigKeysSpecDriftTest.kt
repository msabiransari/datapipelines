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
 * The `datapipelines.org.*` keys (072 calculators, configuration.md §3.21), pinned in the three
 * places they have to agree: the doc (the authority), `application.yml` (the shipped defaults)
 * and [ConfigValidator] (the §7 rules that read them).
 *
 * 075 removed the fourth: `application-dev.yml` used to spell the same five keys, and this test
 * read it. That file is now `application-development.yml`, a POSTURE file — and org facts are
 * not a posture (an org's currency does not change because a deployment is hardened), so the
 * block does not belong there and is gone. The §7 closing rule it enforced — the documented
 * setup must pass the PRODUCTION checks — now applies to the SHIPPED defaults, which is what a
 * deployment that sets nothing actually runs.
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
    fun `the shipped org defaults pass the production rules`() {
        // §7's closing rule, for this block: the setup this product SHIPS must satisfy the
        // production checks — a broken default is fixed at the data, never by weakening the
        // rule. The values are the placeholders' defaults, i.e. what a deployment that sets
        // none of the five variables runs on.
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(
                    orgCurrencyName = defaultOf("$PREFIX.currency.name"),
                    orgCurrencySymbol = defaultOf("$PREFIX.currency.symbol"),
                    orgFiscalStartDate = defaultOf("$PREFIX.fiscal-start-date"),
                    orgWeekStart = defaultOf("$PREFIX.week-start"),
                    orgTimezone = defaultOf("$PREFIX.timezone"),
                ),
            )
        report.violations shouldContainExactly emptyList()
    }

    /** The default out of a `${'$'}{VAR:default}` placeholder — what an unset variable resolves to. */
    private fun defaultOf(key: String): String {
        val raw = checkNotNull(shipped[key]?.toString()) { "application.yml has no $key" }
        return checkNotNull(PLACEHOLDER_DEFAULT.find(raw)) { "$key is not a placeholder with a default: $raw" }
            .groupValues[1]
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
        shipped["datapipelines.workspaces.member-datasources-enabled"] shouldBe
            "\${DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED:true}"
        shipped["datapipelines.jwt.secret"] shouldBe "\${DATAPIPELINES_JWT_SECRET}"

        // 075 appended env/posture/demo after the whole tree, for the same reason. If THAT
        // insert had gone in at the wrong depth, the block above it is what would have moved.
        shipped["datapipelines.endpoints.timeout-default-seconds"] shouldBe
            "\${DATAPIPELINES_ENDPOINTS_TIMEOUT_DEFAULT_SECONDS:30}"
        shipped["datapipelines.env"] shouldBe "\${DATAPIPELINES_ENV:local}"
        shipped["datapipelines.posture"] shouldBe "\${DATAPIPELINES_POSTURE:}"
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

        /** `${'$'}{VAR:default}` — group 1 is the default. */
        val PLACEHOLDER_DEFAULT = Regex("""^\$\{[A-Z0-9_]+:([^}]*)\}${'$'}""")

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
