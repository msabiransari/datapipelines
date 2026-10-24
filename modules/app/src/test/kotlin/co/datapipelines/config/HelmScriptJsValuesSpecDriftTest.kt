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
 * The nine JavaScript runtime keys (#8 lane 8b, #688, configuration.md §3.28), pinned in the
 * places they have to agree: the doc (the authority), the reference Helm chart
 * (`deploy/helm/datapipelines/`), the image's launcher-path list (the `Dockerfile`'s
 * `ENV LOADER_PATH=` line) and the chart's own guard rails — each rendered env entry guarded by
 * an `if` on ITS value, a refusal of the `extraEnv` double mapping, and a render-time refusal of
 * an isolate directory outside the image's launcher-path entries.
 *
 * The [HelmDuckDbValuesSpecDriftTest] shape, for the same reason that test's KDoc gives: an env
 * entry with an EMPTY value still SETS the variable, which would override the image's own
 * `ENV DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR=/opt/graaljs` and fail the startup agreement
 * (ScriptRules' isolateAgreement: the directory must be one entry of the launcher's loader path
 * and contain js-isolate.jar). So all nine values default to the empty string and each entry
 * renders ONLY when its value is non-empty. Unlike the DuckDB pair, one key is NOT an arbitrary
 * path: `script.js.isolateDir` must name one of the image's own launcher-path entries, so the
 * chart refuses anything else at render time, and this test pins the refusal's two-entry list to
 * the `Dockerfile` — a chart entry that drifts from the image's list would refuse a legal value
 * or admit an illegal one.
 *
 * CI renders the chart with a pinned `helm` (`scripts/helm-check.sh`); this guard stays textual
 * so the pregate, which has no `helm`, still pins the shape — it reads the chart files, it does
 * not render them.
 */
class HelmScriptJsValuesSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("deploy/helm/datapipelines/values.yaml") }
    private val template: String by lazy { repoFile(TEMPLATE).readText() }

    @Test
    fun `documented script js keys, chart env entries and values camelCase keys are the same nine`() {
        val documented =
            TABLE_ROW_REGEX
                .findAll(configurationSection())
                .map { it.groupValues[1] }
                .map { key -> "$ENV_PREFIX${key.replace('-', '_').uppercase()}" }
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

        // The non-vacuity floor: exactly NINE members, never "non-empty" — a lane that deletes
        // one key must go red here, not silently pass a weaker "the sets agree" on a smaller set.
        // EXPECTED_ENV_VARS is in application.yml order; the comparisons are on sorted copies.
        withClue("configuration.md §3.28's datapipelines.script.js.* rows") {
            documented shouldContainExactly EXPECTED_ENV_VARS.sorted()
        }
        withClue("deployment.yaml's - name: DATAPIPELINES_SCRIPT_JS_* entries (duplicates included)") {
            envEntries shouldContainExactly EXPECTED_ENV_VARS.sorted()
        }
        withClue("values.yaml's camelCase keys under script.js: (each the mechanical map of an env var)") {
            valueKeys shouldContainExactly EXPECTED_KEYS.sorted()
        }
    }

    @Test
    fun `all nine chart values default to the empty string - empty renders no entry so the image keeps its own settings`() {
        // An empty default is the FEATURE: the image sets its own ISOLATE_DIR and the app derives
        // isolateMemoryMb (pool-size × maxHeapMb + 256 MiB). A rendered "" entry would OVERRIDE
        // the image's ENV — the class KDoc's trap, the HelmDuckDbValuesSpecDriftTest trap again.
        for (key in EXPECTED_KEYS) {
            withClue("values.yaml script.js.$key") { shipped["$VALUES_BLOCK.$key"] shouldBe "" }
        }
    }

    @Test
    fun `each chart env entry's preceding line is an if guard on its own value key`() {
        val lines = template.lines()
        val entryIndexes =
            lines.withIndex().filter { it.value.trimStart().startsWith("- name: $ENV_PREFIX") }

        withClue("deployment.yaml script.js env entries") { entryIndexes.size shouldBe EXPECTED_ENV_VARS.size }

        for ((index, entry) in entryIndexes) {
            val envVar = entry.trimStart().removePrefix("- name: ").trim()
            val key = camelCase(envVar)
            val preceding = lines[index - 1]
            val guardRegex = Regex("""^\s*\{\{-?\s+if\s+\.Values\.script\.js\.$key\s*\}\}\s*$""")
            val guardForm = if (preceding.contains("{{-")) "{{- if" else "{{ if"
            val clue =
                "the entry for $envVar must sit directly under an if guard on .Values.script.js.$key " +
                    "(found guard form: $guardForm; preceding line: ${preceding.trim()})"
            withClue(clue) {
                guardRegex.matches(preceding) shouldBe true
            }
        }
    }

    @Test
    fun `all nine extraEnv double mappings are refused and the refusals name the env vars`() {
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
    fun `the isolateDir refusal's two entries are exactly the image's LOADER_PATH list`() {
        // The image's list is the authority (the Dockerfile, read the way
        // ImageLoaderPathSpecDriftTest reads it): the refusal must admit exactly its entries —
        // a drifted chart list would refuse a legal isolate directory or admit an illegal one.
        val dockerfileEntries =
            repoFile(DOCKERFILE)
                .readLines()
                .mapNotNull { DOCKERFILE_ENV_LINE.matchEntire(it.trim())?.groupValues?.get(1) }
                .single()
                .split(',')
                .map { it.trim() }
                .sorted()
        val refusalLine =
            template
                .lines()
                .filter { it.contains("script.js.isolateDir") }
                .filter { ISOLATE_REFUSAL_LIST_REGEX.containsMatchIn(it) }
                .single()
        val chartEntries =
            ISOLATE_REFUSAL_LIST_REGEX
                .findAll(refusalLine)
                .flatMap { LIST_ENTRY_REGEX.findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
                .sorted()
                .toList()

        withClue("the Dockerfile's ENV LOADER_PATH= entries") {
            dockerfileEntries.size shouldBe 2
        }
        withClue("the refusal's admitted list vs the image's launcher-path entries") {
            chartEntries shouldContainExactly dockerfileEntries
        }
    }

    @Test
    fun `the env block's or-clause names every value key an entry is guarded by`() {
        // The env: block renders only when the or-clause is true; a guard whose key is missing
        // from the or-clause renders NO entry for a set value (the (b2-single) render case in
        // scripts/helm-check.sh catches it live; this catches it textually, in the pregate).
        val orLine = template.lines().filter { OR_CLAUSE_REGEX.containsMatchIn(it) }.single()
        val orKeys =
            VALUE_KEY_REGEX.findAll(orLine).map { it.groupValues[1] }.sorted().toList()
        val lines = template.lines()
        val guardedKeys =
            lines
                .withIndex()
                .filter { GUARD_REGEX.containsMatchIn(it.value) }
                .filter { lines[it.index + 1].contains("- name: DATAPIPELINES_") }
                .flatMap { VALUE_KEY_REGEX.findAll(it.value).map { m -> m.groupValues[1] } }
                .sorted()
                .toList()

        withClue("keys guarded by an if directly before an env entry") { guardedKeys.size shouldBe 11 }
        withClue("the or-clause must name every guarded value key (and vice versa)") {
            orKeys shouldContainExactly guardedKeys
        }
    }

    /** §3.28's section text, bounded by the next §3.29 heading (the change log comes after and quotes history). */
    private fun configurationSection(): String {
        val text = repoFile("docs/configuration.md").readText()
        val start = text.indexOf("### 3.28 ")
        val end = text.indexOf("### 3.29 ")
        check(start >= 0 && end > start) { "configuration.md §3.28/§3.29 headings not found" }
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
        const val DOCKERFILE = "Dockerfile"
        const val VALUES_BLOCK = "script.js"
        const val ENV_PREFIX = "DATAPIPELINES_SCRIPT_JS_"

        // application.yml's order (configuration.md §3.28's rows).
        val EXPECTED_ENV_VARS =
            listOf(
                "DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR",
                "DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB",
                "DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB",
                "DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS",
                "DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH",
                "DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES",
                "DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES",
                "DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB",
                "DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB",
            )

        // The mechanical env-var → camelCase bridge: MAX_HEAP_MB → maxHeapMb.
        val EXPECTED_KEYS = EXPECTED_ENV_VARS.map { camelCase(it) }

        fun camelCase(envVar: String): String {
            val words = envVar.removePrefix(ENV_PREFIX).lowercase().split('_')
            return words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
        }

        // §3.28's table rows: `| `datapipelines.script.js.<key>` | <default> | …` (no env column —
        // the env name is the mechanical bridge application.yml's placeholders already use).
        val TABLE_ROW_REGEX = Regex("""(?m)^\| `datapipelines\.script\.js\.([a-z0-9-]+)` \|""")

        val ENV_ENTRY_REGEX = Regex("""(?m)^\s*- name: (DATAPIPELINES_SCRIPT_JS_[A-Z_]+)\s*$""")

        val FAIL_REGEX = Regex("""fail "([^"]+)"""")

        // The image's ENV line, read the way ImageLoaderPathSpecDriftTest reads it.
        val DOCKERFILE_ENV_LINE = Regex("""^ENV\s+LOADER_PATH=(\S*)$""")

        // The render-time refusal line, and the (list "…" "…") inside it.
        val ISOLATE_REFUSAL_LIST_REGEX = Regex("""\(list ((?:"[^"]*"\s*)+)\)""")
        val LIST_ENTRY_REGEX = Regex(""""([^"]+)"""")

        // The env block's opening or-clause, and a `.Values.<block>.<key>` token in it.
        val OR_CLAUSE_REGEX = Regex("""^\s*\{\{-\s+if\s+or\s+\.Values\.extraEnv\b""")

        val VALUE_KEY_REGEX = Regex("""\.Values\.(?:duckdb|script\.js)\.([A-Za-z]+)""")
        val GUARD_REGEX = Regex("""^\s*\{\{-?\s+if\s+\.Values\.(?:duckdb|script\.js)\.[A-Za-z]+\s*\}\}\s*$""")
    }
}
