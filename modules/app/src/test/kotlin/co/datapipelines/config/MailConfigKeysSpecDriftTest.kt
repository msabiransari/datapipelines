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
 * The `datapipelines.mail.*` keys (137, configuration.md §3.27), pinned in the three places
 * they have to agree: the doc (the authority), `application.yml` (the shipped placeholders)
 * and the §7 rules that read them ([MailRules] — its constants spell every key it names).
 *
 * The [OrgConfigKeysSpecDriftTest] shape, for the same two reasons: `docs-audit.sh` proves a
 * key mentioned elsewhere is DEFINED in configuration.md but not the reverse, and the mail
 * block was APPENDED after the whole `datapipelines:` tree because a block inserted mid-tree
 * silently re-parents whatever follows (MISTAKES.md) — the keys that break are the ones
 * nobody touched, so the untouched neighbours are asserted too.
 */
class MailConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }

    @Test
    fun `configuration_md, application_yml and MailRules name exactly the same mail keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val readByRules = keysIn(repoFile("modules/app/src/main/kotlin/co/datapipelines/config/MailRules.kt").readText())

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        // The rules read every key but reply-to and message-stream (nothing to validate: a
        // blank reply-to means `from`, a blank stream means no header).
        readByRules shouldContainExactly EXPECTED - "$PREFIX.reply-to" - "$PREFIX.message-stream"
    }

    @Test
    fun `the shipped placeholders carry the documented defaults - and NO enabled key exists`() {
        shipped["$PREFIX.host"] shouldBe "\${DATAPIPELINES_MAIL_HOST:}"
        shipped["$PREFIX.port"] shouldBe "\${DATAPIPELINES_MAIL_PORT:587}"
        shipped["$PREFIX.starttls"] shouldBe "\${DATAPIPELINES_MAIL_STARTTLS:true}"
        shipped["$PREFIX.from"] shouldBe "\${DATAPIPELINES_MAIL_FROM:}"
        shipped["$PREFIX.ops-to"] shouldBe "\${DATAPIPELINES_MAIL_OPS_TO:}"
        // Enabled is DERIVED (host and from) — a flag that could disagree with them is the
        // YAML-boolean trap; the doc and the yml must never grow one.
        shipped.containsKey("$PREFIX.enabled") shouldBe false
        repoFile("docs/configuration.md").readText().contains("`$PREFIX.enabled`") shouldBe false
    }

    @Test
    fun `the shipped mail defaults - mail off - pass the production rules`() {
        val report =
            ConfigValidator.validate(
                ConfigSnapshots.valid().copy(
                    mail =
                        MailSnapshot(
                            host = "",
                            port = "587",
                            username = "",
                            passwordSet = false,
                            starttls = "true",
                            from = "",
                            opsTo = "",
                        ),
                ),
            )
        report.violations shouldContainExactly emptyList()
    }

    @Test
    fun `the neighbouring blocks still bind - the mail block re-parented nothing`() {
        // The block the mail block was appended AFTER, and the ones above it that would have
        // moved had the insert gone in at the wrong depth.
        shipped["datapipelines.datasources.retire-ceiling-seconds"] shouldBe
            "\${DATAPIPELINES_DATASOURCES_RETIRE_CEILING_SECONDS:}"
        shipped["datapipelines.duckdb.extension-directory"] shouldBe "\${DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY:}"
        shipped["datapipelines.env"] shouldBe "\${DATAPIPELINES_ENV:local}"
        shipped["datapipelines.posture"] shouldBe "\${DATAPIPELINES_POSTURE:}"
        shipped["datapipelines.demo"] shouldBe "\${DATAPIPELINES_DEMO:}"
        shipped["datapipelines.auth.base-url"] shouldBe "\${DATAPIPELINES_AUTH_BASE_URL:}"
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
        const val PREFIX = "datapipelines.mail"

        val EXPECTED =
            listOf(
                "$PREFIX.from",
                "$PREFIX.host",
                "$PREFIX.message-stream",
                "$PREFIX.ops-to",
                "$PREFIX.password",
                "$PREFIX.port",
                "$PREFIX.reply-to",
                "$PREFIX.starttls",
                "$PREFIX.username",
            )

        val KEY_REGEX = Regex("""datapipelines\.mail\.[a-z0-9-]+""")
    }
}
