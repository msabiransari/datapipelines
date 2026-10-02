package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The two DuckDB operator keys (#140, configuration.md §3.25), pinned in the three places they
 * have to agree: the doc (the authority), the reference Helm chart (`deploy/helm/datapipelines/`)
 * and the chart's own guard rails — each rendered env entry guarded by an `if` on ITS value, and
 * a refusal of the `extraEnv` double mapping.
 *
 * The trap the guard exists for: a Kubernetes `env` entry with an EMPTY value still SETS the
 * variable (to `""`), which overrides the image's own
 * `ENV DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY=/opt/duckdb/extensions` (the Dockerfile). The
 * app then reads "unset" (`ifBlank { null }` at wiring), the LAKE adapter falls back to its
 * `INSTALL`+`LOAD` pairs (§3.25 "Unset") — which need egress to `extensions.duckdb.org` AND a
 * writable home, under a `readOnlyRootFilesystem: true` pod. So both values default to the empty
 * string and each entry renders ONLY when its value is non-empty; a chart default restating the
 * image's path would drift the day the image moves it.
 *
 * The [MailConfigKeysSpecDriftTest] shape for the doc pin and the [ShutdownGraceArithmeticTest]
 * shape for the text-level template assertions. `helm` itself is not a dependency anywhere in
 * this repo, so the guard is textual — it reads the chart files, it does not render them.
 */
class HelmDuckDbValuesSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("deploy/helm/datapipelines/values.yaml") }
    private val template: String by lazy { repoFile(TEMPLATE).readText() }

    @Test
    fun `documented duckdb env vars, chart env entries and values camelCase keys are the same two`() {
        val documented =
            TABLE_ROW_REGEX
                .findAll(configurationSection())
                .map { it.groupValues[1] }
                .distinct()
                .sorted()
                .toList()
        val envEntries =
            ENV_ENTRY_REGEX
                .findAll(template)
                .map { it.groupValues[1] }
                .sorted()
                .toList()
        val valueKeys =
            shipped.keys
                .filter { it.startsWith("$VALUES_BLOCK.") }
                .map { it.removePrefix("$VALUES_BLOCK.") }
                .sorted()
                .toList()

        // The non-vacuity floor: exactly TWO members, never "non-empty" — a lane that deletes
        // one key must go red here, not silently pass a weaker "the sets agree" on a set of one.
        withClue("configuration.md §3.25's DATAPIPELINES_DUCKDB_* column") {
            documented shouldContainExactly EXPECTED_ENV_VARS
        }
        withClue("deployment.yaml's - name: DATAPIPELINES_DUCKDB_* entries (duplicates included)") {
            envEntries shouldContainExactly EXPECTED_ENV_VARS
        }
        withClue("values.yaml's camelCase keys under $VALUES_BLOCK: (each the mechanical map of an env var)") {
            valueKeys shouldContainExactly EXPECTED_KEYS
        }
    }

    @Test
    fun `both chart values default to the empty string - empty renders no entry so the image keeps its own directory`() {
        // An empty default is the FEATURE: it inherits the image's bundled extension directory.
        // A literal default of the image's path (compose-style) is the drift this refuses, and a
        // rendered `value: ""` entry would OVERRIDE the image's ENV and break every LAKE
        // datasource on the shipped image (the class KDoc's trap).
        shipped["$VALUES_BLOCK.extensionDirectory"] shouldBe ""
        shipped["$VALUES_BLOCK.memoryLimit"] shouldBe ""
    }

    @Test
    fun `each chart env entry's preceding line is an if guard on its own value key`() {
        val lines = template.lines()
        val entryIndexes =
            lines.withIndex().filter { it.value.trimStart().startsWith("- name: $ENV_PREFIX") }

        withClue("deployment.yaml duckdb env entries") { entryIndexes.size shouldBe EXPECTED_ENV_VARS.size }

        for ((index, entry) in entryIndexes) {
            val envVar = entry.trimStart().removePrefix("- name: ").trim()
            val key = camelCase(envVar)
            val preceding = lines[index - 1]
            val guardRegex = Regex("""^\s*\{\{-?\s+if\s+\.Values\.duckdb\.$key\s*\}\}\s*$""")
            val guardForm = if (preceding.contains("{{-")) "{{- if" else "{{ if"
            val clue =
                "the entry for $envVar must sit directly under an if guard on .Values.duckdb.$key " +
                    "(found guard form: $guardForm; preceding line: ${preceding.trim()})"
            withClue(clue) {
                guardRegex.matches(preceding) shouldBe true
            }
        }
    }

    @Test
    fun `both extraEnv double mappings are refused and the refusals name the env vars`() {
        for (envVar in EXPECTED_ENV_VARS) {
            val hasKeyLines =
                template
                    .lines()
                    .filter { it.contains("hasKey .Values.extraEnv \"$envVar\"") }
            withClue("exactly one extraEnv refusal guard for $envVar") { hasKeyLines.size shouldBe 1 }
        }
        val failMessages =
            FAIL_REGEX
                .findAll(template)
                .map { it.groupValues[1] }
                .toList()
        for (envVar in EXPECTED_ENV_VARS) {
            val clue =
                "a fail message naming $envVar (Kubernetes does not reject duplicate env names — " +
                    "the last one wins silently, so the chart must refuse the double mapping itself)"
            withClue(clue) {
                failMessages.any { it.contains(envVar) } shouldBe true
            }
        }
    }

    @Test
    fun `Chart_yml's version is semver`() {
        // Deliberately NOT pinned to the bumped number: the bump itself is proven by the lane's
        // diff, and a pin would drift on the next legitimate chart change. What must hold is
        // that the chart's version stays a parseable semver (Chart.yaml: bump on every change).
        val versions =
            CHART_VERSION_REGEX
                .findAll(repoFile(CHART).readText())
                .map { it.groupValues[1] }
                .toList()
        withClue("Chart.yaml must state exactly one version") { versions.size shouldBe 1 }
        withClue("Chart.yaml version ${versions.firstOrNull()}") {
            versions.single().matches(Regex("""\d+\.\d+\.\d+""")) shouldBe true
        }
    }

    /** §3.25's section text, bounded by the next §3.26 heading (the change log comes after and quotes history). */
    private fun configurationSection(): String {
        val text = repoFile("docs/configuration.md").readText()
        val start = text.indexOf("### 3.25 ")
        val end = text.indexOf("### 3.26 ")
        check(start >= 0 && end > start) { "configuration.md §3.25/§3.26 headings not found" }
        return text.substring(start, end)
    }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val TEMPLATE = "deploy/helm/datapipelines/templates/deployment.yaml"
        const val CHART = "deploy/helm/datapipelines/Chart.yaml"
        const val VALUES_BLOCK = "duckdb"
        const val ENV_PREFIX = "DATAPIPELINES_DUCKDB_"

        val EXPECTED_ENV_VARS =
            listOf(
                "DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY",
                "DATAPIPELINES_DUCKDB_MEMORY_LIMIT",
            )

        // The mechanical env-var → camelCase bridge: EXTENSION_DIRECTORY → extensionDirectory.
        val EXPECTED_KEYS = EXPECTED_ENV_VARS.map { camelCase(it) }

        fun camelCase(envVar: String): String {
            val words = envVar.removePrefix(ENV_PREFIX).lowercase().split('_')
            return words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }

        // §3.25's table rows: `| `datapipelines.duckdb.<key>` | `DATAPIPELINES_DUCKDB_*` | …`
        val TABLE_ROW_REGEX =
            Regex("""(?m)^\| `datapipelines\.duckdb\.[a-z0-9-]+` \| `?(DATAPIPELINES_DUCKDB_[A-Z_]+)`? \|""")

        val ENV_ENTRY_REGEX = Regex("""(?m)^\s*- name: (DATAPIPELINES_DUCKDB_[A-Z_]+)\s*$""")

        val FAIL_REGEX = Regex("""fail "([^"]+)"""")

        val CHART_VERSION_REGEX = Regex("""(?m)^version: (\d+\.\d+\.\d+)\s*$""")
    }
}
