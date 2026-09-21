package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeExactly
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.relativeTo

/**
 * 102's static pin: **no native `window.confirm` / `window.alert` anywhere in the app's
 * server-rendered UI** (ui-screens §4.3d). The four sites that carried them — the pipeline
 * editor's `draft.js` (three, including the pre-101 discard text that had become FALSE),
 * the template editor's inline `tplLifecycle`, and 106's plain-confirm verb wiring — are
 * gone, replaced by the §4.3d dialogs; a refusal is a Shape C toast, a success is Shape A.
 *
 * The whole resources tree is scanned, not just the four files: the rule is "the app never
 * blocks on a native dialog", and a new screen reintroducing one is exactly the drift a
 * fixed file list would miss. The key surfaces' `hx-confirm` is EXCLUDED BY PATH — it is
 * htmx's own attribute (an inline confirm string htmx renders), not a native dialog call;
 * excluding by pattern would ban the htmx feature itself, which is those screens' to use.
 *
 * The BROWSER tests' sources are scanned too: a Playwright helper falling back to
 * `page.onDialog` handling would be a test-side reintroduction of the same native path.
 */
class NativeDialogBanTest {
    @Test
    fun `no template, script or browser test calls a native confirm or alert`() {
        roots.flatMap { it.offenders() }.shouldBeEmpty()
    }

    /**
     * The scan half, extracted so the rule reads in one line: every template, script and
     * browser-test source under this root, minus the console's PATH exclusion, keeping the
     * files that carry a native call.
     */
    private fun Path.offenders(): List<String> {
        val sources =
            Files.walk(this).use { stream ->
                stream.filter(::isScannable).toList()
            }
        return sources
            // Excluded BY PATH (the prompt's rule): htmx's own attribute on the key surfaces
            // (moved off the API console in 179 when the keys card became the /api-keys page),
            // never a native call.
            .filter { it.relativeTo(this).toString() != "api/console.html" }
            .filter(::carriesNativeDialog)
            .map { it.relativeTo(this).toString() }
    }

    private fun isScannable(file: Path): Boolean =
        Files.isRegularFile(file) &&
            file.fileName.toString().let { it.endsWith(".html") || it.endsWith(".js") || it.endsWith(".kt") }

    /** One file's verdict: a native confirm/alert call anywhere in its text. */
    private fun carriesNativeDialog(file: Path): Boolean {
        val text = Files.readString(file)
        return text.contains("window.confirm(") || text.contains("window.alert(")
    }

    @Test
    fun `the exclusion is the key surfaces' htmx attributes - nothing else`() {
        // A floor, not a ceiling: if someone widens the exclusion to a directory, this pins
        // that the key screens carry exactly the htmx attributes the exclusion names — one
        // destructive confirm per surface (179 moved the console's to the /api-keys page's
        // Delete and the top bar chip's rotate).
        val keysPage = Files.readString(projectRoot().resolve("modules/web/src/main/resources/templates/api/keys.html"))
        Regex("hx-confirm").findAll(keysPage).count() shouldBeExactly 1
        val chip = Files.readString(projectRoot().resolve("modules/web/src/main/resources/templates/partials/mcp-key-chip.html"))
        Regex("hx-confirm").findAll(chip).count() shouldBeExactly 1
    }

    private val roots =
        listOf(
            "modules/web/src/main/resources/templates",
            "modules/web/src/main/resources/static/js",
            "tests/browser-tests/src",
        ).map { projectRoot().resolve(it) }

    private fun projectRoot(): Path {
        var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir.parent != null && !Files.exists(dir.resolve("settings.gradle.kts"))) dir = dir.parent
        return dir
    }
}
