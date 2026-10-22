package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The JDBC driver drop-in (#657, module-structure.md §5.4.1, deployment.md §3.5), pinned in the
 * places that have to agree: the `Dockerfile` (the artifact), module-structure §5.4.1 (the
 * authority that promises it) and deployment.md's drop-in paragraph (the operator's recipe).
 *
 * The spec promised `ENV LOADER_PATH=lib` for months while the image set nothing — the drift this
 * guard exists for. Since #8 lane 8a the value is a LIST, `/app/lib,/opt/graaljs`: the drop-in
 * directory, then the JavaScript isolate's (module-structure §5.15). One list in one ENV, never a
 * `-Dloader.path` beside it (owner ruling 2026-10-21): `PropertiesLauncher` reads the system
 * property before the environment, so a `-Dloader.path` for the isolate would silently drop the
 * drop-in (measured in lane 8a). Each entry is ABSOLUTE on purpose: `PropertiesLauncher` resolves a relative
 * `loader.path` entry against `loader.home`, which defaults to `${user.dir}`, and the image sets
 * no `WORKDIR`, so `lib` would put `/lib`, the OS library directory, on the classpath.
 *
 * The security half (deployment.md §9's `lib/` line: a writable drop-in directory is
 * code-execution-by-file-drop, and `LOADER_PATH` comes from the image, never from the deployment
 * environment): the directory is created and made `0555` before the image's `USER` line, so it is
 * root-owned and the app user cannot write it; and no compose file or chart template names
 * `LOADER_PATH` outside the chart's one render-time refusal, so a deployment cannot point the
 * launcher somewhere writable.
 *
 * Textual, the [HelmDuckDbValuesSpecDriftTest] shape: it reads the files, it does not build the
 * image. The built image's own proof (printenv, `touch` refused, the loader's debug classpath)
 * is the lane's A.4 evidence.
 */
class ImageLoaderPathSpecDriftTest {
    private val dockerfile: List<String> by lazy { repoFile("Dockerfile").readLines() }

    @Test
    fun `the Dockerfile sets LOADER_PATH exactly once to the absolute drop-in and isolate directories`() {
        val values = dockerfile.mapNotNull { ENV_LINE.matchEntire(it.trim())?.groupValues?.get(1) }

        withClue("Dockerfile `ENV LOADER_PATH=` lines") { values shouldContainExactly listOf(IMAGE_LOADER_PATH) }
        withClue("no instruction passes a `loader.path` system property, which would override the ENV") {
            dockerfile.filter { !it.trim().startsWith("#") && it.contains("-Dloader.path") }.shouldBeEmpty()
        }
    }

    @Test
    fun `the drop-in directory is created and made read-only before the image drops to its app user`() {
        val userLine = dockerfile.indexOfFirst { it.trim().startsWith("USER ") }
        val mkdirLine = dockerfile.indexOfFirst { MKDIR_LINE.containsMatchIn(it) }

        withClue("Dockerfile has a USER line") { (userLine >= 0) shouldBe true }
        withClue("a `mkdir -p $EXPECTED` line") { (mkdirLine >= 0) shouldBe true }
        withClue("the mkdir (line ${mkdirLine + 1}) runs as root, before USER (line ${userLine + 1})") {
            (mkdirLine < userLine) shouldBe true
        }
        withClue("the same RUN makes it 0555 (root-owned, not writable by the app user)") {
            dockerfile[mkdirLine] shouldContain "chmod 0555 $EXPECTED"
        }
        withClue("nothing hands the directory to the app user") {
            dockerfile.filter { CHOWN_LINE.containsMatchIn(it) }.shouldBeEmpty()
        }
    }

    @Test
    fun `module-structure section 5_4_1 quotes the image's value in its fence, its prose and its checklist`() {
        val spec = beforeChangeLog(repoFile("docs/module-structure.md").readText())

        for ((where, pattern) in SPEC_SITES) {
            val values = pattern.findAll(spec).map { it.groupValues[1] }.toList()
            withClue("module-structure.md $where") { values shouldContainExactly listOf(IMAGE_LOADER_PATH) }
        }
    }

    @Test
    fun `deployment's drop-in paragraph names the directory and the image's value`() {
        val paragraph =
            repoFile("docs/deployment.md").readLines().filter { it.startsWith(DROP_IN_PARAGRAPH) }

        withClue("deployment.md's one `$DROP_IN_PARAGRAPH` paragraph") { paragraph shouldHaveSize 1 }
        withClue("it names the mount point") { paragraph.single() shouldContain "`$EXPECTED/`" }
        withClue("it quotes the image's ENV") { paragraph.single() shouldContain "`ENV LOADER_PATH=$IMAGE_LOADER_PATH`" }
    }

    @Test
    fun `no compose file or chart file sets LOADER_PATH - the value comes from the image only`() {
        val root = repoRoot()
        val deployFiles =
            File(root, "deploy").listFiles { f -> f.isFile && COMPOSE_FILE.matches(f.name) }.orEmpty().toList() +
                File(root, CHART_DIR).walkTopDown().filter { it.isFile }.toList()
        val offenders =
            deployFiles.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { LOADER_PATH_TOKEN.containsMatchIn(it.value) }
                    .filterNot { file.relativeTo(root).path == TEMPLATE && ALLOWED_REFUSAL.matches(it.value.trim()) }
                    .map { "${file.relativeTo(root)}:${it.index + 1}" }
            }

        // The non-vacuity floor: the scan must have read the shipped compose file and the chart's
        // Deployment template, or "no offenders" proves only that it read nothing.
        withClue("files scanned") {
            deployFiles.map { it.relativeTo(root).path }.containsAll(SCAN_FLOOR) shouldBe true
        }
        withClue("deploy files naming LOADER_PATH (deployment.md §9; only the exact template refusal is admitted)") {
            offenders.shouldBeEmpty()
        }
        withClue("the template's exact LOADER_PATH refusal must exist exactly once") {
            repoFile(TEMPLATE).readLines().count { ALLOWED_REFUSAL.matches(it.trim()) } shouldBe 1
        }
    }

    /** The doc's text above its change log, which quotes history and must not be pinned. */
    private fun beforeChangeLog(text: String): String {
        val end = text.indexOf(CHANGE_LOG_HEADING)
        check(end > 0) { "module-structure.md `$CHANGE_LOG_HEADING` heading not found" }
        return text.substring(0, end)
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return dir
    }

    private fun repoFile(relative: String): File = File(repoRoot(), relative).also { check(it.isFile) { "missing $relative" } }

    private companion object {
        const val EXPECTED = "/app/lib"

        /** The image's whole `LOADER_PATH`: the drop-in directory, then the JavaScript isolate's (#8). */
        const val IMAGE_LOADER_PATH = "$EXPECTED,/opt/graaljs"
        const val CHART_DIR = "deploy/helm/datapipelines"
        const val TEMPLATE = "$CHART_DIR/templates/deployment.yaml"
        const val CHANGE_LOG_HEADING = "## Appendix A: Change Log"
        const val DROP_IN_PARAGRAPH = "**The `lib/` drop-in.**"

        val ENV_LINE = Regex("""ENV\s+LOADER_PATH=(\S*)""")
        val MKDIR_LINE = Regex("""^RUN\s+mkdir\s+-p\s+${Regex.escape(EXPECTED)}(\s|$)""")
        val CHOWN_LINE = Regex("""chown\b.*${Regex.escape(EXPECTED)}""")

        // The three §5.4.1 sites, each exactly once: the fenced `ENV` line, the "sets" sentence and
        // the readiness checklist. A counter-example in prose (`LOADER_PATH=lib` would put /lib…)
        // matches none of them by design.
        val SPEC_SITES =
            listOf(
                "fenced block (`ENV LOADER_PATH=`)" to Regex("""(?m)^ENV LOADER_PATH=(\S*)$"""),
                "prose (`and sets `LOADER_PATH=…``)" to Regex("""and sets `LOADER_PATH=([^`]*)`"""),
                "checklist (`the image's `LOADER_PATH=…``)" to Regex("""(?m)^- \[ ].*the image's `LOADER_PATH=([^`]*)`"""),
            )

        val COMPOSE_FILE = Regex("""compose(\..+)?\.ya?ml""")
        val LOADER_PATH_TOKEN = Regex("""\bLOADER_PATH\b|loader\.path""")
        val ALLOWED_REFUSAL =
            Regex
                .escape(
                    """{{- range ${'$'}name := keys .Values.extraEnv }}{{ if eq (upper (replace "." "_" ${'$'}name)) "LOADER_PATH" }}""" +
                        """{{ fail "LOADER_PATH comes from the image, never from extraEnv.LOADER_PATH (deployment.md §9)" }}""" +
                        """{{ end }}{{- end }}""",
                ).toRegex()
        val SCAN_FLOOR = listOf("deploy/compose.yml", "$CHART_DIR/templates/deployment.yaml", "$CHART_DIR/values.yaml")
    }
}
