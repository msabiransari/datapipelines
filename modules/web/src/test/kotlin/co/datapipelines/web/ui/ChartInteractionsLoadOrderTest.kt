package co.datapipelines.web.ui

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * The interaction layer is loaded wherever the chart adapter is (brief 990 D1, G12): ONE rule over the whole tree
 * instead of a list of pages. Every file the git index tracks is read; in each one, every script tag that loads the
 * adapter's file (`src` or `th:src` naming `datapipelines-dashboard-chart.js`) must be DIRECTLY preceded by a script tag
 * that loads `datapipelines-chart-interactions.js`. The adapter refuses to draw without the layer, so a page that loads
 * one without the other ships charts that never draw.
 *
 * The count of such files is printed, with a floor of 3 (the board, the preview and the workspace templates): a scan
 * that finds fewer is not reading the tree.
 */
class ChartInteractionsLoadOrderTest {
    @Test
    fun `every tracked file that loads the chart adapter loads the interaction layer directly before it`() {
        val root = repoRoot()
        val loaders = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        for (path in trackedPaths(root)) {
            val file = File(root, path)
            if (!file.isFile || file.length() > MAX_BYTES) continue
            val text = file.readText()
            if (!text.contains(ADAPTER)) continue
            val tags = SCRIPT_TAG.findAll(text).map { it.groupValues[1] }.toList()
            val adapterAt = tags.indices.filter { tags[it].endsWith(ADAPTER) }
            if (adapterAt.isEmpty()) continue
            loaders += path
            adapterAt.forEach { i ->
                val before = tags.getOrNull(i - 1)
                if (before == null || !before.endsWith(LAYER)) wrong += "$path: the script before the adapter is ${before ?: "none"}"
            }
        }
        println("ChartInteractionsLoadOrderTest: ${loaders.size} tracked files load the chart adapter as a script: $loaders")
        if (loaders.size < FLOOR) fail("only ${loaders.size} files load the adapter (floor $FLOOR): the scan is not reading the tree")
        if (wrong.isNotEmpty()) fail("the interaction layer must load directly before the chart adapter:\n${wrong.joinToString("\n")}")
    }

    @Test
    fun `the matcher reads both spellings of a script tag and refuses a page without the layer`() {
        val good = """<script th:src="@{/js/$LAYER}"></script>${"\n"}<script th:src="@{/js/$ADAPTER}"></script>"""
        val bad = """<script src="https://dp.example.co/js/datapipelines-dashboard.js"></script><script src="https://dp.example.co/js/$ADAPTER"></script>"""
        val goodTags = SCRIPT_TAG.findAll(good).map { it.groupValues[1] }.toList()
        val badTags = SCRIPT_TAG.findAll(bad).map { it.groupValues[1] }.toList()
        if (goodTags.size != 2 || !goodTags[0].endsWith(LAYER) || !goodTags[1].endsWith(ADAPTER)) fail("th:src not read: $goodTags")
        if (badTags.size != 2 || badTags[0].endsWith(LAYER)) fail("src not read: $badTags")
    }

    /** The index's paths, NUL-separated so no file name is quoted or split; a git failure fails the suite. */
    private fun trackedPaths(root: File): List<String> {
        val errors = File.createTempFile("chart-interactions-git", ".err")
        try {
            val process =
                ProcessBuilder("git", "ls-files", "-z")
                    .directory(root)
                    .redirectError(ProcessBuilder.Redirect.to(errors))
                    .start()
            val listing = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) fail("git ls-files exited $exit in $root: ${errors.readText().trim()}")
            return listing.split('\u0000').filter { it.isNotEmpty() }
        } finally {
            errors.delete()
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        const val ADAPTER = "datapipelines-dashboard-chart.js"
        const val LAYER = "datapipelines-chart-interactions.js"
        const val FLOOR = 3
        const val MAX_BYTES = 4L * 1024 * 1024

        /** A script tag's source, `src` or Thymeleaf's `th:src` (`@{...}` unwrapped by the trailing name match). */
        val SCRIPT_TAG = Regex("""<script\b[^>]*?\s(?:th:)?src="([^"]+?)\}?"""")
    }
}
