package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The management port's chart exposure (#712, configuration.md §3.14, the failure-flow record's
 * lane C1b), pinned in the places that have to agree: the chart's `management` values block (OFF
 * by default), the `deployment.yaml` entries that exist ONLY under an
 * `{{- if .Values.management.expose }}` guard, the two `extraEnv` double-mapping refusals, and the
 * three templates behind their own flag lines — plus the doc row that states the whole contract.
 *
 * The trap the guards exist for: the management surface is anonymous by ruling (§17 row 11 of the
 * failure-flow record — the port is the boundary), so the address opt-open
 * (`MANAGEMENT_SERVER_ADDRESS=0.0.0.0`) is the one switch that decides who can reach it. Kubernetes
 * honours the LAST duplicate `env` name silently, so an `extraEnv.MANAGEMENT_SERVER_ADDRESS` would
 * override the chart's guarded entry (or the loopback default) without any error — the same shape
 * the DuckDB and `script.js` refusals refuse. Both selectors of the NetworkPolicy empty is refused
 * at render time because an empty `from:` admits every source.
 *
 * The [HelmScriptJsValuesSpecDriftTest] shape: CI renders the chart with a pinned `helm`
 * (`scripts/helm-check.sh`, cases `(h)`–`(h5)`); this guard stays textual so the pregate, which has
 * no `helm`, still pins the shape — it reads the chart files, it does not render them.
 */
class HelmManagementValuesSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("deploy/helm/datapipelines/values.yaml") }
    private val template: String by lazy { repoFile(TEMPLATE).readText() }

    @Test
    fun `the management values block ships with the documented off-by-default values`() {
        // Every default OFF (explicit opt-open, never default-open — configuration.md §3.14):
        // deleting the block, flipping a default or renaming a key goes red here.
        shipped["management.expose"] shouldBe false
        shipped["management.port"] shouldBe 9090
        shipped["management.networkPolicy.enabled"] shouldBe false
        shipped["management.serviceMonitor.enabled"] shouldBe false
        shipped["management.serviceMonitor.interval"] shouldBe "30s"
        // The empty-map selectors may be absent from Spring's flattened view, so their keys are
        // pinned against the raw file text instead.
        val valuesText = repoFile(VALUES).readText()
        val selectorKeys =
            SELECTOR_KEY_REGEX
                .findAll(valuesText)
                .map { it.groupValues[1] }
                .distinct()
                .sorted()
                .toList()
        withClue("values.yaml must declare both scraper selector keys as (empty) maps") {
            selectorKeys shouldBe
                listOf("scraperNamespaceSelector", "scraperPodSelector")
        }
    }

    @Test
    fun `the deployment renders the management port and env entries only inside an expose guard`() {
        val lines = template.lines()
        val exposeIfs = lines.filter { it.contains("{{- if .Values.management.expose }}") }
        withClue("deployment.yaml must carry exactly two expose guards (the containerPort block and the env block)") {
            exposeIfs.size shouldBe 2
        }

        val nameLines =
            lines.withIndex().filter { it.value.trimStart().startsWith("- name: MANAGEMENT_SERVER_") }
        withClue("deployment.yaml must name both MANAGEMENT_SERVER_* env entries exactly once") {
            nameLines.map { it.value.trimStart() } shouldBe
                listOf("- name: MANAGEMENT_SERVER_PORT", "- name: MANAGEMENT_SERVER_ADDRESS")
        }

        // Each name line must sit between the SECOND expose guard and its matching end (the first
        // guard is the containerPort block; the env block holds no nested template directives, so
        // the next end closes it).
        val exposeIfIndexes =
            lines
                .mapIndexedNotNull { index, line ->
                    index.takeIf { line.contains("{{- if .Values.management.expose }}") }
                }
        val envIf = exposeIfIndexes[1]
        val envEnd = guardEnd(lines, envIf)
        for ((index, line) in nameLines) {
            withClue("$line must render inside the {{- if .Values.management.expose }} env block") {
                (index > envIf && index < envEnd) shouldBe true
            }
        }

        // The address is the opt-open switch: rendered as the literal 0.0.0.0, and NOWHERE else.
        val addressValue = lines.filter { it.contains("\"0.0.0.0\"") }
        withClue("the template must set the literal \"0.0.0.0\" exactly once (under the expose guard)") {
            addressValue.size shouldBe 1
        }
        withClue("the \"0.0.0.0\" line must also sit inside the env expose block") {
            val valueIndex = lines.indexOfFirst { it.contains("\"0.0.0.0\"") }
            (valueIndex > envIf && valueIndex < envEnd) shouldBe true
        }

        // The first guard is the containerPort block: the port named `management`, sized from the
        // same value the MANAGEMENT_SERVER_PORT entry pins, so the Service and the app agree.
        val portIf = exposeIfIndexes[0]
        val portEnd = guardEnd(lines, portIf)
        val portBlock = lines.subList(portIf, portEnd + 1)
        withClue("the containerPort expose block must render the management port from management.port") {
            portBlock.any { it.trim() == "- name: management" } shouldBe true
            portBlock.any { it.trim() == "containerPort: {{ .Values.management.port }}" } shouldBe true
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
                    "the last one wins silently, and the 0.0.0.0 address must come only from " +
                    "management.expose)"
            withClue(clue) {
                failMessages.any { it.contains(envVar) } shouldBe true
            }
        }
    }

    @Test
    fun `the three management templates exist and each begins with its own flag line`() {
        for ((file, flag) in TEMPLATE_FLAGS) {
            val first = templateFlagLine(file)
            withClue("$file must begin with its own `{{- if` flag line (found: $first)") {
                first.startsWith(flag) shouldBe true
            }
        }
    }

    @Test
    fun `the empty selector pair is refused at render time`() {
        // An empty from: admits every source — the refusal must exist textually (the engine-side
        // refusal is helm-check.sh's (h5) case).
        val refusal =
            repoFile(NETWORKPOLICY)
                .readLines()
                .filter { it.contains("fail ") }
        withClue("networkpolicy.yaml must refuse both selectors empty") { refusal.size shouldBe 1 }
        withClue("the refusal must name the reason (an empty from: admits every source)") {
            refusal.single().contains("admits every source") shouldBe true
        }
    }

    @Test
    fun `configuration md's management server address row still names the env var and the NetworkPolicy`() {
        // configuration.md §3.14's operator-tunable ROW (a table row, not the yaml excerpt's
        // comment further down) is the authority the chart implements
        // ("explicit opt-open, never default-open … paired with a NetworkPolicy").
        val row =
            repoFile("docs/configuration.md")
                .readLines()
                .filter { it.startsWith("|") && it.contains("MANAGEMENT_SERVER_ADDRESS") && it.contains("NetworkPolicy") }
        withClue("configuration.md's MANAGEMENT_SERVER_ADDRESS row must keep naming the NetworkPolicy") {
            row.size shouldBe 1
        }
    }

    /** The template file's first non-blank, non-comment line (a leading directive or content). */
    private fun templateFlagLine(relative: String): String =
        repoFile(relative)
            .readLines()
            .firstOrNull { it.isNotBlank() }
            ?: error("$relative is empty")

    /** The index of the `{{- end }}` line closing the expose guard that opens at [from]. */
    private fun guardEnd(
        lines: List<String>,
        from: Int,
    ): Int =
        lines
            .withIndex()
            .firstOrNull { it.index > from && it.value.trim() == "{{- end }}" }
            ?.index
            ?: error("an expose guard is never closed")

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
        const val VALUES = "deploy/helm/datapipelines/values.yaml"
        const val NETWORKPOLICY = "deploy/helm/datapipelines/templates/networkpolicy.yaml"

        val EXPECTED_ENV_VARS =
            listOf(
                "MANAGEMENT_SERVER_ADDRESS",
                "MANAGEMENT_SERVER_PORT",
            )

        // The three new templates and the flag line each must begin with — the whole object is
        // behind its own opt-in, so a template that renders unconditionally goes red here.
        val TEMPLATE_FLAGS =
            mapOf(
                "deploy/helm/datapipelines/templates/service-management.yaml" to
                    "{{- if .Values.management.expose }}",
                "deploy/helm/datapipelines/templates/networkpolicy.yaml" to
                    "{{- if and .Values.management.expose .Values.management.networkPolicy.enabled }}",
                "deploy/helm/datapipelines/templates/servicemonitor.yaml" to
                    "{{- if and .Values.management.expose .Values.management.serviceMonitor.enabled }}",
            )

        // values.yaml's two selector keys, declared as empty maps by default.
        val SELECTOR_KEY_REGEX = Regex("""(?m)^    (scraperNamespaceSelector|scraperPodSelector):""")

        val FAIL_REGEX = Regex("""fail "([^"]+)"""")
    }
}
