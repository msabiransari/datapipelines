package co.datapipelines.web.ui

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import co.datapipelines.web.TestRepoFiles
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The client library is the concatenation of its sources (#1064 A.1, G1, G2; record §8 A6).
 *
 * The EXPECTED texts are built here from the repository's files — the engine's order file read as a file, each engine
 * file, then the vendor manifest's runtime block, each followed by one newline; the stylesheet from this test's own
 * literal list of six — and compared byte for byte with what the application serves ([ClientLibraryConfig.library]).
 * This test never asks the class for an order. A difference names the first source whose bytes differ.
 *
 * A byte comparison cannot see a builder that ignores the names it is handed (a copy of today's names gives today's
 * bytes), so G2 has two tests of its own: an input with one engine file the engine does not have, and a scan of the
 * module's main sources for any engine file's name.
 */
class ClientLibraryTest {
    @Test
    fun `the served script is the engine's files in load order, then the runtime's five, each followed by one newline`() {
        val names = loadOrder()
        val sources =
            names.map { "$ENGINE_DIR/$it" to TestRepoFiles.read("$ENGINE_DIR/$it") } +
                manifestRuntimeFiles().map { "$STATIC_DIR/$it" to TestRepoFiles.read("$STATIC_DIR/$it") }
        println("1064|A1|script|engine=${names.size} runtime=${sources.size - names.size} bytes=${ClientLibraryConfig.library.script.size}")
        withClue("non-vacuity: the order file lists the engine's files") { names.size shouldBeGreaterThanOrEqual ENGINE_FLOOR }
        firstDifference(sources, ClientLibraryConfig.library.script) shouldBe null
    }

    /** #1065 D1, A.3: the six sheets joined as before, then passed ONCE through the scoping under `.dp-scope`. */
    @Test
    fun `the served stylesheet is the six sheets in the example's order, each followed by one newline, scoped under dp-scope`() {
        val joined = SHEETS.joinToString("") { TestRepoFiles.read("$STATIC_DIR/$it") + "\n" }
        val expected = ScopedStylesheet.scope(joined, ".dp-scope").text.toByteArray(Charsets.UTF_8)
        println(
            "1064|A1|stylesheet|sheets=${SHEETS.size} joined=${joined.toByteArray().size} " +
                "bytes=${ClientLibraryConfig.library.stylesheet.size}",
        )
        withClue("the served stylesheet is the scoping of the join of the six sheets") {
            ClientLibraryConfig.library.stylesheet.contentEquals(expected) shouldBe true
        }
    }

    @Test
    fun `the runtime's five are the vendor manifest's runtime block, whole and in order`() {
        ClientLibrary.RUNTIME_FILES shouldBe manifestRuntimeFiles()
    }

    @Test
    fun `each text is built once - two reads return the same bytes and every source is read once`() {
        val reads = mutableMapOf<String, Int>()
        val counting: (String) -> String = { name ->
            reads.merge(name, 1, Int::plus)
            "/* $name */"
        }
        val library = ClientLibrary(listOf("a.js", "types/b.js"), counting, counting)
        val first = library.script
        library.script shouldBeSameInstanceAs first
        val sheet = library.stylesheet
        library.stylesheet shouldBeSameInstanceAs sheet
        println("1064|A1|built-once|reads=$reads")
        withClue("every source read exactly once across two reads of each text") {
            reads.keys shouldBe (listOf("a.js", "types/b.js") + ClientLibrary.RUNTIME_FILES + ClientLibrary.STYLESHEETS).toSet()
            reads.filterValues { it != 1 } shouldBe emptyMap()
        }
    }

    /**
     * G2 (i): a new engine file reaches the script with no edit. The input is made here and handed to the builder as
     * arguments (nothing is written under the engine's directory, no lazy value of the engine's object is replaced).
     */
    @Test
    fun `an engine file the engine does not have yet lands after the last engine file and before the runtime`() {
        val names = JsRenderedEngineFiles.names + NEW_ENGINE_FILE
        val reader: (String) -> String = { if (it == NEW_ENGINE_FILE) NEW_SOURCE else JsRenderedEngineFiles.sources.getValue(it) }
        val script = ClientLibrary.script(names, reader, ClientLibrary::staticText)
        val engine = ClientLibrary.join(JsRenderedEngineFiles.names, JsRenderedEngineFiles.sources::getValue)
        val runtime = ClientLibrary.join(ClientLibrary.RUNTIME_FILES, ClientLibrary::staticText)
        println("1064|G2|i|names=${names.size} found=${script.indexOf(NEW_SOURCE)} expectedAt=${engine.length}")
        withClue("the script holds $NEW_ENGINE_FILE's bytes after the last engine file and before the first runtime file") {
            script.indexOf(NEW_SOURCE) shouldBe engine.length
            script shouldBe engine + NEW_SOURCE + "\n" + runtime
        }
    }

    /** G2 (ii): the one call hands the engine's own list; no engine file's name is written in this module's main code. */
    @Test
    fun `no engine file's name is written in the web module's main Kotlin sources`() {
        val names = loadOrder()
        val sources =
            File(repoRoot(), MAIN_KOTLIN).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val found =
            sources.flatMap { file ->
                val text = file.readText()
                names.filter { text.contains("\"$it\"") || text.contains("/$it\"") }.map { "${file.name}: $it" }
            }
        println("1064|G2|ii|names-searched=${names.size} files=${sources.size} found=$found")
        withClue("names searched (floor $NAME_FLOOR) and files scanned") {
            names.size shouldBeGreaterThanOrEqual NAME_FLOOR
            sources.size shouldBeGreaterThanOrEqual 1
        }
        withClue("an engine file's name in the web module's main code — hand the builder the engine's own list") {
            found.shouldBeEmpty()
        }
    }

    /** The repository root: the nearest ancestor of the working directory with the settings file (TestRepoFiles' rule). */
    private fun repoRoot(): File =
        requireNotNull(
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() },
        ) { "no settings.gradle.kts above ${System.getProperty("user.dir")}" }

    private fun loadOrder(): List<String> =
        TestRepoFiles
            .read("$ENGINE_DIR/LOAD-ORDER.txt")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun manifestRuntimeFiles(): List<String> =
        ObjectMapper()
            .readTree(TestRepoFiles.read("$STATIC_DIR/vendor/design-system/vendor-manifest.json"))
            .path("datapipelines-dashboard-runtime")
            .path("files")
            .map { it.asText() }
            .also { check(it.isNotEmpty()) { "the manifest's runtime block lists no files" } }

    /**
     * Null when [actual] is every source's bytes in order, each followed by one newline, and nothing else; otherwise
     * the first source (by its repository path) whose bytes differ, or the trailing bytes that no source accounts for.
     */
    private fun firstDifference(
        sources: List<Pair<String, String>>,
        actual: ByteArray,
    ): String? {
        var offset = 0
        for ((path, text) in sources) {
            val expected = (text + "\n").toByteArray(Charsets.UTF_8)
            val end = offset + expected.size
            if (end > actual.size || !actual.copyOfRange(offset, end).contentEquals(expected)) {
                return "the first differing source is $path (expected ${expected.size} bytes at offset $offset)"
            }
            offset = end
        }
        return if (offset == actual.size) null else "${actual.size - offset} bytes after the last source ($offset expected in all)"
    }

    private companion object {
        const val ENGINE_DIR = "modules/js-rendered/src/main/resources/co/datapipelines/jsrendered/engine"
        const val STATIC_DIR = "modules/web/src/main/resources/static"
        const val MAIN_KOTLIN = "modules/web/src/main/kotlin"
        const val ENGINE_FLOOR = 27
        const val NAME_FLOOR = 27
        const val NEW_ENGINE_FILE = "types/zz-test-1064.js"
        const val NEW_SOURCE = "var zzTest1064 = \"zz-test-1064\";"

        /** The example's six sheets, in its order (#1064 D2), written here and not read from the class. */
        val SHEETS =
            listOf(
                "vendor/design-system/tokens.css",
                "vendor/design-system/themes/light.css",
                "vendor/design-system/base.css",
                "vendor/design-system/primitives.css",
                "css/app.css",
                "css/dashboards.css",
            )
    }
}
