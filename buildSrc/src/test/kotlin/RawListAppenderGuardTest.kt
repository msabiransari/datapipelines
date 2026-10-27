import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #484 — a raw Logback `ListAppender` exposes a plain `ArrayList` that every logging thread appends
 * to, so a test that reads it while another thread can still log is a `ConcurrentModificationException`
 * or a torn read waiting for a slow runner (#362, #391). Every capture in the module and integration
 * test sources therefore either reads detached snapshots through its module's `SnapshotListAppender`
 * or is a raw capture marked `// raw-list-appender: synchronous — <reason>` — on its construction line
 * when the line stays within 120 columns, else alone on the line directly above it.
 * This scan refuses any other raw construction, naming it `path:line`, so the inventory cannot grow
 * unclassified again.
 *
 * A construction is the simple or fully-qualified name (the qualified one may be split across lines
 * by ktlint's chain wrapping, leaving `.ListAppender<…>()` on its own line) followed by its call, an
 * anonymous or named subclass included; an import alias or a typealias of the class is refused too,
 * since a construction through it would carry neither name. Type positions alone construct nothing.
 */
class RawListAppenderGuardTest {
    private val repoRoot: File
        get() =
            File(requireNotNull(System.getProperty("repo.catalog")) { "repo.catalog not set — run via buildSrc's test task" })
                .parentFile
                .parentFile

    private fun testSources(): List<File> =
        listOf("modules", "tests")
            .flatMap { parent -> childDirectories(repoRoot.resolve(parent)) }
            .map { it.resolve("src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun childDirectories(parent: File): List<File> = parent.listFiles { child -> child.isDirectory }.orEmpty().toList()

    @Test
    fun `every raw ListAppender construction in module and integration test sources is marked synchronous`() {
        val offenders =
            testSources().flatMap { file ->
                offenders(file.relativeTo(repoRoot).invariantSeparatorsPath, file.readText())
            }
        assertEquals(
            emptyList<String>(),
            offenders,
            "raw ListAppender captures with no class marker — read snapshots through the module's SnapshotListAppender, " +
                "or, when only the test thread logs between attach and the last read, mark the construction " +
                "'// $MARKER<reason>' on its line or alone on the line above (DEVELOPMENT.md §9.6)",
        )
    }

    @Test
    fun `the scan is not vacuous - it reads the files that hold the captures`() {
        val mentioning = testSources().count { it.readText().contains("ListAppender") }
        assertTrue(mentioning >= MENTIONING_FLOOR, "only $mentioning test sources mention ListAppender — is the glob wrong?")
    }

    @Test
    fun `an unmarked construction is one offender and a marked one is none`() {
        assertEquals(
            listOf("m/ProbeTest.kt:2"),
            offenders("m/ProbeTest.kt", "val a = 1\nval appender = ListAppender<ILoggingEvent>().apply { start() }\n"),
        )
        assertEquals(
            emptyList<String>(),
            offenders("m/ProbeTest.kt", "val appender = ListAppender<ILoggingEvent>() // ${MARKER}attached and read on the test thread\n"),
        )
        assertEquals(
            listOf("m/ProbeTest.kt:1"),
            offenders("m/ProbeTest.kt", "val appender = ListAppender<ILoggingEvent>() // ${MARKER.trimEnd()}\n"),
            "a marker with no reason is no marker",
        )
    }

    @Test
    fun `a marker alone on the line directly above marks the construction, two lines above does not`() {
        val above = "    // ${MARKER}attached and read on the test thread\n"
        val construction = "    private val appender = ListAppender<ILoggingEvent>().apply { start() }\n"
        assertEquals(emptyList<String>(), offenders("m/ProbeTest.kt", above + construction))
        assertEquals(listOf("m/ProbeTest.kt:3"), offenders("m/ProbeTest.kt", above + "\n" + construction))
        assertEquals(
            listOf("m/ProbeTest.kt:2"),
            offenders("m/ProbeTest.kt", "    val a = 1 // ${MARKER}x\n" + construction),
            "only a comment line above carries a marker for the line below it",
        )
    }

    @Test
    fun `the fully-qualified spelling is an offender, on one line or split by chain wrapping`() {
        assertEquals(
            listOf("m/ProbeTest.kt:1"),
            offenders("m/ProbeTest.kt", "val a = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()\n"),
        )
        assertEquals(
            listOf("m/ProbeTest.kt:3"),
            offenders(
                "m/ProbeTest.kt",
                "val a =\n    ch.qos.logback.core.read\n        .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()\n",
            ),
        )
    }

    @Test
    fun `subclasses, inferred type arguments and aliases are offenders`() {
        assertEquals(listOf("m/P.kt:1"), offenders("m/P.kt", "val a = object : ListAppender<ILoggingEvent>() {}\n"))
        assertEquals(listOf("m/P.kt:1"), offenders("m/P.kt", "val a: ListAppender<ILoggingEvent> = ListAppender()\n"))
        assertEquals(listOf("m/P.kt:1"), offenders("m/P.kt", "import ch.qos.logback.core.read.ListAppender as Capture\n"))
        assertEquals(listOf("m/P.kt:1"), offenders("m/P.kt", "typealias Capture = ListAppender<ILoggingEvent>\n"))
    }

    @Test
    fun `type positions, the snapshot helper, comments and the helper files are not offenders`() {
        val clean =
            """
            import ch.qos.logback.core.read.ListAppender
            fun lines(appender: ListAppender<ILoggingEvent>): List<String> = emptyList()
            val snapshot = SnapshotListAppender()
            // a ListAppender<ILoggingEvent>() in prose
             * KDoc: `ListAppender<ILoggingEvent>()` is the raw shape
            """.trimIndent()
        assertEquals(emptyList<String>(), offenders("m/ProbeTest.kt", clean))
        assertEquals(
            emptyList<String>(),
            offenders("m/SnapshotListAppender.kt", "class SnapshotListAppender : ListAppender<ILoggingEvent>() {\n"),
        )
    }

    private fun offenders(
        path: String,
        text: String,
    ): List<String> {
        if (path.substringAfterLast('/') == HELPER_FILE) return emptyList()
        val lines = text.lines()
        return lines.mapIndexedNotNull { index, line ->
            val code = line.trimStart()
            val comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            val raw = !comment && (CONSTRUCTION.containsMatchIn(line) || ALIAS.containsMatchIn(line))
            if (raw && !marked(lines, index)) "$path:${index + 1}" else null
        }
    }

    private fun marked(
        lines: List<String>,
        index: Int,
    ): Boolean {
        val above = lines.getOrNull(index - 1)?.trimStart().orEmpty()
        return MARKED.containsMatchIn(lines[index]) || (above.startsWith("//") && MARKED.containsMatchIn(above))
    }

    private companion object {
        const val MARKER = "raw-list-appender: synchronous — "
        const val HELPER_FILE = "SnapshotListAppender.kt"

        /** 51 files at #484's inventory; 40 survives migrations and deletions, and fails on a wrong glob. */
        const val MENTIONING_FLOOR = 40

        val CONSTRUCTION = Regex("""(?:ch\.qos\.logback\.core\.read\.)?\bListAppender(?:<[^<>()]*(?:<[^<>()]*>)?[^<>()]*>)?\s*\(""")
        val ALIAS = Regex("""^\s*import\s+(?:ch\.qos\.logback\.core\.read\.)?ListAppender\s+as\s|\btypealias\b.*\bListAppender\b""")
        val MARKED = Regex("""// ${Regex.escape(MARKER)}\S""")
    }
}
