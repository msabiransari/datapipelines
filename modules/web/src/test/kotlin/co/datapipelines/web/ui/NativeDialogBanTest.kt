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
 * fixed file list would miss. `api/console.html`'s `hx-confirm` is EXCLUDED BY PATH — it is
 * htmx's own attribute (an inline confirm string htmx renders), not a native dialog call;
 * excluding by pattern would ban the htmx feature itself, which is the console's to use.
 *
 * The BROWSER tests' sources are scanned too: a Playwright helper falling back to
 * `page.onDialog` handling would be a test-side reintroduction of the same native path.
 */
class NativeDialogBanTest {
    @Test
    fun `no template, script or browser test calls a native confirm or alert`() {
        val offenders = mutableListOf<String>()
        roots.forEach { root ->
            Files.walk(root).use { stream ->
                stream
                    .filter { file ->
                        Files.isRegularFile(file) &&
                            file.fileName.toString().let { it.endsWith(".html") || it.endsWith(".js") || it.endsWith(".kt") }
                    }.forEach { file ->
                        // Excluded BY PATH (the prompt's rule): htmx's own attribute on the
                        // API console, never a native call.
                        if (file.relativeTo(root).toString() == "api/console.html") return@forEach
                        val text = Files.readString(file)
                        if (text.contains("window.confirm(") || text.contains("window.alert(")) {
                            offenders += file.relativeTo(root).toString()
                        }
                    }
            }
        }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `the exclusion is the console's single htmx attribute - nothing else`() {
        // A floor, not a ceiling: if someone widens the exclusion to a directory, this pins
        // that the console still carries exactly the one htmx attribute the exclusion names.
        val text = Files.readString(projectRoot().resolve("modules/web/src/main/resources/templates/api/console.html"))
        Regex("hx-confirm").findAll(text).count() shouldBeExactly 1
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
