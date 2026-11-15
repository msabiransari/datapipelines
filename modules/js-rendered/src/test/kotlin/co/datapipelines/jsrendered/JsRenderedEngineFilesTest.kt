package co.datapipelines.jsrendered

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [JsRenderedEngineFiles] lists exactly the lines of `LOAD-ORDER.txt`, every listed file exists, and the concatenation
 * is the files in that order (brief 985 A.1). The source tree is read directly, so a file present in the tree but
 * absent from the classpath, or the other way round, is a failure here.
 */
class JsRenderedEngineFilesTest {
    private val engineDir = File(moduleDir(), "src/main/resources/${JsRenderedEngineFiles.ROOT}")

    private val lines =
        File(engineDir, JsRenderedEngineFiles.LOAD_ORDER)
            .readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    @Test
    fun `the names are the lines of LOAD-ORDER, in order`() {
        lines.size shouldBeGreaterThan MIN_FILES
        JsRenderedEngineFiles.names shouldContainExactly lines
    }

    @Test
    fun `every listed file exists and is what the classpath serves`() {
        lines.forEach { name ->
            val onDisk = File(engineDir, name)
            onDisk.isFile shouldBe true
            JsRenderedEngineFiles.sources.getValue(name) shouldBe onDisk.readText(Charsets.UTF_8)
        }
    }

    @Test
    fun `every JavaScript file of the engine is listed`() {
        val present =
            engineDir
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(".js") }
                .map { it.relativeTo(engineDir).invariantSeparatorsPath }
                .toSortedSet()
        present shouldBe lines.toSortedSet()
    }

    @Test
    fun `the concatenation is every file in load order, each ended by a newline`() {
        val expected = lines.joinToString(separator = "") { File(engineDir, it).readText(Charsets.UTF_8) + "\n" }
        JsRenderedEngineFiles.concatenation shouldBe expected
        JsRenderedEngineFiles.concatenation shouldStartWith JsRenderedEngineFiles.sources.getValue(lines.first())
        JsRenderedEngineFiles.concatenation shouldEndWith JsRenderedEngineFiles.sources.getValue(lines.last()) + "\n"
    }

    private fun moduleDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return File(checkNotNull(dir) { "settings.gradle.kts not found above the working directory" }, "modules/js-rendered")
    }

    private companion object {
        /** The namespace, the layout, render, both writers and one template at the least. */
        const val MIN_FILES = 5
    }
}
