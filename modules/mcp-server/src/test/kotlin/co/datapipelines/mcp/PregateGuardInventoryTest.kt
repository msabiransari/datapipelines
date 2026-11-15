package co.datapipelines.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The lane check's completeness guard (#995): every test class that reads beyond its own Gradle
 * module is in the lane check's guard registry (`GUARDS` in `scripts/pregate-plan-lib.sh`, stage 3,
 * run in EVERY lane check) or carries one line of intent in `scripts/pregate-guard-exemptions.txt`.
 *
 * Why: such a class goes red on a diff that never touches its module, and stage 2 runs only the
 * modules a diff touches. #962 went red at its lane check on lines #968 had added in another module,
 * because `ScriptVocabularyGuardTest` (a whole-tree walk) was in no registry; the registry's own
 * comments record five earlier gates that went red the same way (242a, 7d, 332, 353, 328).
 *
 * What counts (#995 decision 1): a class whose inputs include a tracked file outside its module's
 * directory — a walk of the tree, or one fixed file elsewhere (a doc, another module's source, a
 * build file, a script).
 *
 * Exemptions, two kinds never mixed (#995 decision 3, owner ruling 2026-11-14 on the waiver):
 *  - `own-module`: the class reaches the repository root but reads only its own module (one line,
 *    the reason). No coverage is given up.
 *  - `waived-for-cost`: the class IS such a guard and starts a container or an application context,
 *    or takes more than 120 s by itself in stage 3. The line names the evidence, and this check
 *    verifies it: the reason must name a container/context marker the class reaches, or
 *    `measured <N> s` with N > 120. The merge gate and CI still run these classes.
 *
 * THE METHOD (one implementation; the printed table is the inventory). The scan reads every tracked
 * Kotlin/Java file under `modules/<m>/src/test/` and `tests/<m>/src/test/` (`git ls-files`) — the
 * projects stage 3 can run — strips comments and backtick names, and takes the string
 * literals and the member declarations (top level, and members of classes and objects; a local
 * inside a function body belongs to its function). A declaration REACHES THE ROOT when its text
 * holds one of the idioms the 2026-11-15 test tree uses:
 *  - `marker` — the literal `settings.gradle.kts` (the house locator: walk up to the build root);
 *  - `git-ls-files` — the literal `ls-files` (a `git ls-files` walk of the index; always a walk);
 *  - `user.dir` — the working directory, the start of every walk-up;
 *  - `relative` — a literal starting `../` that, resolved from the module directory, is a tracked
 *    path (the test task's working directory is the module).
 * A helper is not a class Gradle can run, so it never becomes a row: references are resolved inside
 * the module (a top-level function, value or class by its name; an object's or companion's member
 * as `Owner.member`, so a test that uses only an object's constants does not inherit the object's
 * root walk), and a test class's row collects every declaration it reaches, directly or through
 * helpers of helpers. The paths a row reads are its reachable literals whose first segment is a
 * tracked top-level entry (`docs/…`, `modules/…`, `deploy/…`, `Dockerfile`, …) or a `relative`
 * literal, and that do not lie under the row's own module directory. A row with such a path, or
 * with a `git ls-files` walk, is CROSS-MODULE; a row that reaches the root without one is
 * UNCLASSIFIED (it may read only its own module, or build its paths at run time where the scan
 * cannot see them) and needs a line or a registration all the same.
 *
 * LIMITS, stated: `buildSrc` is a separate build the lane check runs no test of, and its results
 * directory is no root the lane check's evidence snapshot accepts, so its tests (which read the
 * repository through `repo.catalog`) are not scanned (follow-up issue named in #995's handback). A
 * test that reaches the root in a way not listed above (a new system property, a
 * path assembled from pieces none of which is a top-level entry, a classpath resource the build
 * copied from outside the module) is invisible here; a reference through an instance of an object
 * passed around, or through `with(Owner)`, is not followed; a literal naming a top-level entry in a
 * root-reaching class counts as a read even when it is a fixture path (an `own-module` line says
 * so). The container/context markers are the ones the 2026-11-15 tree uses ([CONTAINER_MARKERS]).
 * The row floor ([ROW_FLOOR]) catches a broken walk, never a narrowed one.
 *
 * Why it lives here: `:modules:mcp-server` is already in the registry and its tests start no
 * container, so registering this class costs stage 3 no compilation and seconds of run time; the
 * merge gate and CI run it as part of the module's tests.
 */
class PregateGuardInventoryTest {
    @TempDir
    lateinit var scratch: File

    @Test
    fun `every test class that reads beyond its module is registered in the lane check or exempted with intent`() {
        val root = repoRoot()
        val inventory = GuardInventory.scan(root, trackedFiles(root))
        val registry = GuardRegistry.parse(File(root, REGISTRY_PATH).readLines())
        val exemptions = GuardExemptions.parse(File(root, EXEMPTIONS_PATH).readLines())
        val verdict = GuardInventory.judge(inventory, registry, exemptions)
        println(verdict.table())
        if (inventory.rows.size < ROW_FLOOR) {
            fail("the inventory found ${inventory.rows.size} rows (floor $ROW_FLOOR) — the scan is broken, not the tree")
        }
        if (inventory.scannedFiles < SCANNED_FLOOR) {
            fail("the scan read ${inventory.scannedFiles} test sources (floor $SCANNED_FLOOR) — the walk is broken")
        }
        if (verdict.problems.isNotEmpty()) {
            fail(
                "${verdict.problems.size} problem(s) in the lane check's guard registry " +
                    "($REGISTRY_PATH GUARDS, $EXEMPTIONS_PATH):\n" + verdict.problems.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the scan sees every idiom the tree uses today`() {
        val root = repoRoot()
        val rows = GuardInventory.scan(root, trackedFiles(root)).rows.associateBy { it.className }
        val expected =
            mapOf(
                // The incident class: a git ls-files walk of the whole tree.
                "co.datapipelines.web.ui.ScriptVocabularyGuardTest" to "git-ls-files",
                // A fixed file read from the root (docs/auth.md), walking up from user.dir.
                "co.datapipelines.auth.SecurityConfigCsrfExemptionParityTest" to "marker",
                // Another module's tree through a cwd-relative path.
                "co.datapipelines.web.ui.InlineWidthAuditTest" to "relative",
            )
        for ((name, idiom) in expected) {
            val row = rows[name] ?: fail("$name is no longer an inventory row — the scan lost an idiom")
            if (idiom !in row.idioms || !row.crossModule) fail("$name: expected a cross-module row via $idiom, got $row")
        }
        // A helper is resolved to the classes that use it (auth's RepoFiles, the brief's example).
        val viaHelper = rows["co.datapipelines.auth.AuthErrorSpecDriftTest"] ?: fail("AuthErrorSpecDriftTest is not a row")
        if (viaHelper.helpers.none { it.startsWith("RepoFiles.") }) fail("AuthErrorSpecDriftTest's helper is not seen: $viaHelper")
    }

    @Test
    fun `a guard the check has never seen is found by its idiom, and the control is unclassified`() {
        val tree = ProbeTree(scratch)
        val inventory = GuardInventory.scan(scratch, tree.files())
        val bare = GuardInventory.judge(inventory, GuardRegistry(emptyMap()), GuardExemptions(emptyList()))
        val text = bare.problems.joinToString("\n")
        for (name in listOf(ProbeTree.WALK, ProbeTree.FIXED, ProbeTree.HELPER)) {
            val line = bare.problems.singleOrNull { name in it } ?: fail("$name is not named exactly once:\n$text")
            if (!line.startsWith("MISSING GUARD")) fail("$name must be a missing guard:\n$line")
        }
        val control = bare.problems.singleOrNull { ProbeTree.CONTROL in it } ?: fail("the control is not reported:\n$text")
        if (!control.startsWith("UNCLASSIFIED")) fail("the control must be unclassified, never a missing guard:\n$control")
        if (bare.problems.size != EXPECTED_PROBE_PROBLEMS) fail("expected exactly the four probe rows:\n$text")

        val registered =
            GuardRegistry(mapOf(":modules:alpha" to listOf(ProbeTree.WALK, ProbeTree.FIXED, ProbeTree.HELPER)))
        val owned =
            GuardExemptions.parse(
                listOf(
                    "own-module ${ProbeTree.CONTROL} reads only its own test resource",
                    "waived-for-cost ${ProbeTree.CONTAINER} starts GenericContainer",
                ),
            )
        val green = GuardInventory.judge(inventory, registered, owned)
        if (green.problems.isNotEmpty()) fail("registered + exempted must be green:\n${green.problems.joinToString("\n")}")
    }

    @Test
    fun `a stale, mixed or unevidenced exemption and a registry entry with no class are refused`() {
        val tree = ProbeTree(scratch)
        val inventory = GuardInventory.scan(scratch, tree.files())
        val registry =
            GuardRegistry(
                mapOf(
                    ":modules:alpha" to listOf(ProbeTree.WALK, ProbeTree.FIXED, "probe.GoneTest"),
                    ":modules:beta" to listOf(ProbeTree.HELPER),
                ),
            )
        val exemptions =
            GuardExemptions.parse(
                listOf(
                    "own-module ${ProbeTree.CONTROL} reads only its own test resource",
                    "own-module probe.VanishedTest the class was deleted",
                    "own-module ${ProbeTree.WALK} mixed with its registration",
                    "waived-for-cost ${ProbeTree.CONTAINER} it is slow, trust me",
                    "own-module ${ProbeTree.PLAIN} never reaches the root",
                ),
            )
        val problems = GuardInventory.judge(inventory, registry, exemptions).problems
        val text = problems.joinToString("\n")
        val expected =
            listOf(
                "REGISTRY ENTRY WITHOUT CLASS" to "probe.GoneTest",
                "REGISTERED IN THE WRONG MODULE" to ProbeTree.HELPER,
                "STALE EXEMPTION" to "probe.VanishedTest",
                "MIXED" to ProbeTree.WALK,
                "WAIVER WITHOUT EVIDENCE" to ProbeTree.CONTAINER,
                "NOT AN INVENTORY ROW" to ProbeTree.PLAIN,
            )
        for ((kind, name) in expected) {
            if (problems.none { it.startsWith(kind) && name in it }) fail("expected $kind naming $name in:\n$text")
        }
        val refusedLine = runCatching { GuardExemptions.parse(listOf("waived probe.X no kind")) }.exceptionOrNull()
        if (refusedLine == null) fail("a line with an unknown kind must be refused")
        val refusedRegistry = runCatching { GuardRegistry.parse(listOf("GUARDS[\":m\"]=\$(oops)")) }.exceptionOrNull()
        if (refusedRegistry == null) fail("a GUARDS line the parser cannot read must be refused, never skipped")
    }

    @Test
    fun `a waiver names a container the class reaches or measured seconds over 120`() {
        val tree = ProbeTree(scratch)
        val inventory = GuardInventory.scan(scratch, tree.files())
        val registry = GuardRegistry(mapOf(":modules:alpha" to listOf(ProbeTree.WALK, ProbeTree.FIXED, ProbeTree.HELPER)))

        fun problemsFor(reason: String) =
            GuardInventory
                .judge(
                    inventory,
                    registry,
                    GuardExemptions.parse(
                        listOf(
                            "own-module ${ProbeTree.CONTROL} reads only its own test resource",
                            "waived-for-cost ${ProbeTree.CONTAINER} $reason",
                        ),
                    ),
                ).problems
        if (problemsFor("starts GenericContainer").isNotEmpty()) fail("a reached marker is evidence")
        if (problemsFor("measured 150 s in stage 3").isNotEmpty()) fail("over 120 s is evidence")
        if (problemsFor("measured 90 s in stage 3").none { it.startsWith("WAIVER WITHOUT EVIDENCE") }) {
            fail("90 s is under the ruling's 120 s")
        }
        if (problemsFor("starts PostgreSQLContainer").none { it.startsWith("WAIVER WITHOUT EVIDENCE") }) {
            fail("a marker the class does not reach is no evidence")
        }
    }

    /** A temporary repository: the four cases of #995's never-seen gate, plus a container probe and a plain test. */
    private class ProbeTree(
        private val root: File,
    ) {
        init {
            write("settings.gradle.kts", "rootProject.name = \"probe\"\n")
            write("docs/spec.md", "# spec\n")
            write("modules/beta/src/main/resources/data.txt", "x\n")
            write("modules/alpha/src/test/resources/own.txt", "x\n")
            val locate =
                """
                private fun root(): File {
                    var dir = File(System.getProperty("user.dir")).absoluteFile
                    while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
                    return dir
                }
                """.trimIndent()
            write(
                "modules/alpha/src/test/kotlin/probe/WalkProbeTest.kt",
                "package probe\n\nclass WalkProbeTest {\n    @Test\n    fun walks() {\n" +
                    "        ProcessBuilder(\"git\", \"ls-files\", \"-z\").start()\n    }\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/FixedProbeTest.kt",
                "package probe\n\nclass FixedProbeTest {\n    @Test\n    fun reads() {\n" +
                    "        File(root(), \"docs/spec.md\").readText()\n    }\n\n$locate\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/ProbeFiles.kt",
                "package probe\n\nobject ProbeFiles {\n    const val NAME = \"x\"\n\n" +
                    "    fun read(path: String): String = File(root(), path).readText()\n\n$locate\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/HelperProbeTest.kt",
                "package probe\n\nclass HelperProbeTest {\n    @Test\n    fun reads() {\n" +
                    "        ProbeFiles.read(\"modules/beta/src/main/resources/data.txt\")\n    }\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/ControlProbeTest.kt",
                "package probe\n\nclass ControlProbeTest {\n    @Test\n    fun reads() {\n" +
                    "        File(root(), \"modules/alpha/src/test/resources/own.txt\").readText()\n    }\n\n$locate\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/ConstantsOnlyTest.kt",
                "package probe\n\nclass ConstantsOnlyTest {\n    @Test\n    fun names() {\n" +
                    "        check(ProbeFiles.NAME == \"x\")\n    }\n}\n",
            )
            write(
                "modules/alpha/src/test/kotlin/probe/ContainerProbeTest.kt",
                "package probe\n\nclass ContainerProbeTest {\n    private val box = GenericContainer(\"img\")\n\n" +
                    "    @Test\n    fun reads() {\n        ProbeFiles.read(\"docs/spec.md\")\n    }\n}\n",
            )
        }

        private fun write(
            path: String,
            text: String,
        ) {
            File(root, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        fun files(): List<String> =
            root
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()

        companion object {
            const val WALK = "probe.WalkProbeTest"
            const val FIXED = "probe.FixedProbeTest"
            const val HELPER = "probe.HelperProbeTest"
            const val CONTROL = "probe.ControlProbeTest"
            const val CONTAINER = "probe.ContainerProbeTest"
            const val PLAIN = "probe.ConstantsOnlyTest"
        }
    }

    companion object {
        const val REGISTRY_PATH = "scripts/pregate-plan-lib.sh"
        const val EXEMPTIONS_PATH = "scripts/pregate-guard-exemptions.txt"

        /** Rows measured at birth (2026-11-15): see the handback of #995. A broken walk falls far below. */
        const val ROW_FLOOR = 350

        /** Tracked test sources at birth (2026-11-15): 1995. */
        const val SCANNED_FLOOR = 1800
        const val EXPECTED_PROBE_PROBLEMS = 5

        private fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return requireNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
        }

        private fun trackedFiles(root: File): List<String> {
            val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(false).start()
            val listing = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) fail("git ls-files did not finish in $root")
            if (process.exitValue() != 0) fail("git ls-files exited ${process.exitValue()} in $root: ${errors.trim()}")
            return listing.split('\u0000').filter { it.isNotEmpty() }
        }

        private const val GIT_TIMEOUT_SECONDS = 60L
    }
}

/** The container and application-context markers the 2026-11-15 test tree uses (a waiver's evidence). */
internal val CONTAINER_MARKERS: Regex =
    Regex(
        "@(SpringBootTest|Testcontainers)\\b|\\b(GenericContainer|PostgreSQLContainer|MySQLContainer|" +
            "MSSQLServerContainer|SpringApplicationBuilder|ApplicationContextRunner|WebApplicationContextRunner|" +
            "AnnotationConfigApplicationContext|runApplication)\\b",
    )

/** A Gradle module that owns test sources: its directory and its project path. */
internal data class GradleModule(
    val dir: String,
    val path: String,
)

/** One inventory row: a runnable test class that reaches the repository root. */
internal data class InventoryRow(
    val className: String,
    val module: GradleModule,
    val idioms: Set<String>,
    val helpers: Set<String>,
    val crossPaths: Set<String>,
    val markers: Set<String>,
) {
    val walk: Boolean get() = "git-ls-files" in idioms
    val crossModule: Boolean get() = walk || crossPaths.isNotEmpty()
}

internal data class Inventory(
    val scannedFiles: Int,
    val testClasses: Map<String, GradleModule>,
    val rows: List<InventoryRow>,
)

/** `GUARDS` as `scripts/pregate-plan-lib.sh` declares it: module path → classes, in order. */
internal data class GuardRegistry(
    val modules: Map<String, List<String>>,
) {
    fun moduleOf(className: String): String? = modules.entries.firstOrNull { className in it.value }?.key

    companion object {
        private val LINE = Regex("""^GUARDS\["(:[A-Za-z0-9:-]+)"](\+?)="([A-Za-z0-9_. ]*)"(\s+#.*)?$""")

        /**
         * Reads every `GUARDS[...]=` / `+=` line; a line that assigns GUARDS in any other shape is
         * refused, so a registry the parser cannot read is a failure, never a silently shorter list.
         */
        fun parse(lines: List<String>): GuardRegistry {
            val modules = linkedMapOf<String, MutableList<String>>()
            for (line in lines) {
                if (!line.startsWith("GUARDS[")) continue
                val match = LINE.matchEntire(line.trimEnd()) ?: error("unreadable GUARDS line: $line")
                val (module, append, classes) = match.destructured
                val list = modules.getOrPut(module) { mutableListOf() }
                if (append.isEmpty()) list.clear()
                list += classes.split(' ').filter { it.isNotEmpty() }
            }
            return GuardRegistry(modules)
        }
    }
}

internal enum class ExemptionKind(
    val word: String,
) {
    OWN_MODULE("own-module"),
    WAIVED_FOR_COST("waived-for-cost"),
}

internal data class Exemption(
    val kind: ExemptionKind,
    val className: String,
    val reason: String,
)

/** `scripts/pregate-guard-exemptions.txt`: `<kind> <class> <reason>`, `#` comments, blank lines. */
internal data class GuardExemptions(
    val entries: List<Exemption>,
) {
    companion object {
        private val LINE = Regex("""^(\S+)\s+([A-Za-z_][\w.]*)\s+(\S.*)$""")

        fun parse(lines: List<String>): GuardExemptions =
            GuardExemptions(
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                    val match = LINE.matchEntire(line) ?: error("unreadable exemption line (kind class reason): $line")
                    val (word, name, reason) = match.destructured
                    val kind = ExemptionKind.entries.firstOrNull { it.word == word } ?: error("unknown exemption kind '$word': $line")
                    Exemption(kind, name, reason)
                },
            )
    }
}

internal data class Verdict(
    val rows: List<Pair<InventoryRow, String>>,
    val problems: List<String>,
) {
    /** The inventory as printed evidence: one row per class, then the counts. */
    fun table(): String =
        buildString {
            appendLine("GUARD INVENTORY (#995): class | module | idioms | helpers | reads | status")
            for ((row, status) in rows) {
                val reads =
                    when {
                        row.walk -> {
                            "walk"
                        }

                        row.crossPaths.isNotEmpty() -> {
                            "fixed: " +
                                row.crossPaths
                                    .sorted()
                                    .joinToString(" ")
                                    .take(READS_WIDTH)
                        }

                        else -> {
                            "-"
                        }
                    }
                appendLine(
                    "${row.className} | ${row.module.path} | ${row.idioms.sorted().joinToString(",")} | " +
                        "${row.helpers.sorted().joinToString(",").ifEmpty { "-" }} | $reads | $status",
                )
            }
            val counts = rows.groupingBy { it.second.substringBefore(' ') }.eachCount().toSortedMap()
            appendLine("rows=${rows.size} " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }

    private companion object {
        const val READS_WIDTH = 160
    }
}

/** The scanner and the judge. Pure functions over (root, tracked paths), so a temporary tree tests them. */
internal object GuardInventory {
    private const val WALK_IDIOM = "git-ls-files"
    private const val WAIVER_SECONDS = 120
    private val IDIOM_LITERALS =
        mapOf(
            "settings.gradle.kts" to "marker",
            "ls-files" to WALK_IDIOM,
            "user.dir" to "user.dir",
        )
    private val MEASURED = Regex("""measured (\d+) s""")

    fun scan(
        root: File,
        tracked: List<String>,
    ): Inventory {
        val tree = Tree(tracked)
        val sources =
            tracked
                .filter { it.endsWith(".kt") || it.endsWith(".java") }
                .mapNotNull { path -> moduleOf(path)?.let { SourceFile(path, it, Lexed.of(File(root, path).readText())) } }
        val decls = sources.flatMap { Declarations.of(it) }
        val facts = decls.associateWith { facts(it, tree) }
        val refs = References.resolve(decls)
        val testClasses = TestClasses.find(decls)
        val rows =
            testClasses.mapNotNull { decl ->
                val reached = closure(decl, refs)
                val idioms = reached.flatMap { facts.getValue(it).idioms }.toSet()
                if (idioms.isEmpty()) return@mapNotNull null
                InventoryRow(
                    className = decl.qualifiedName,
                    module = decl.file.module,
                    idioms = idioms,
                    helpers =
                        reached
                            .filter { it.file != decl.file && facts.getValue(it).idioms.isNotEmpty() }
                            .map { it.displayName }
                            .toSet(),
                    crossPaths = reached.flatMap { facts.getValue(it).crossPaths }.toSet(),
                    markers = reached.flatMap { facts.getValue(it).markers }.toSet(),
                )
            }
        return Inventory(
            scannedFiles = sources.size,
            testClasses = testClasses.associate { it.qualifiedName to it.file.module },
            rows = rows.sortedWith(compareBy({ it.module.path }, { it.className })),
        )
    }

    fun judge(
        inventory: Inventory,
        registry: GuardRegistry,
        exemptions: GuardExemptions,
    ): Verdict {
        val byClass = exemptions.entries.groupBy { it.className }
        val rowsByClass = inventory.rows.associateBy { it.className }
        val problems = mutableListOf<String>()
        byClass.filter { it.value.size > 1 }.forEach { (name, lines) -> problems += "DUPLICATE EXEMPTION: $name has ${lines.size} lines" }
        for ((module, classes) in registry.modules) classes.mapNotNullTo(problems) { registryProblem(it, module, inventory) }
        exemptions.entries.mapNotNullTo(problems) { exemptionProblem(it, rowsByClass[it.className], inventory, registry) }
        val statuses =
            inventory.rows.map { row ->
                val (status, problem) = rowStatus(row, registry, byClass[row.className]?.firstOrNull())
                problem?.let { problems += it }
                row to status
            }
        return Verdict(statuses, problems)
    }

    private fun registryProblem(
        name: String,
        module: String,
        inventory: Inventory,
    ): String? {
        val home = inventory.testClasses[name]
        return when {
            home == null -> "REGISTRY ENTRY WITHOUT CLASS: $name in GUARDS[\"$module\"] is no test class in the tree"
            home.path != module -> "REGISTERED IN THE WRONG MODULE: $name is in ${home.path}, not GUARDS[\"$module\"]"
            else -> null
        }
    }

    private fun exemptionProblem(
        entry: Exemption,
        row: InventoryRow?,
        inventory: Inventory,
        registry: GuardRegistry,
    ): String? {
        val waiver = entry.kind == ExemptionKind.WAIVED_FOR_COST
        return when {
            entry.className !in inventory.testClasses -> {
                "STALE EXEMPTION: ${entry.kind.word} ${entry.className} — no such test class in the tree"
            }

            row == null -> {
                "NOT AN INVENTORY ROW: ${entry.kind.word} ${entry.className} never reaches the root — remove the line"
            }

            registry.moduleOf(entry.className) != null -> {
                "MIXED: ${entry.className} is registered in GUARDS and carries a ${entry.kind.word} line"
            }

            waiver && !evidenced(entry, row) -> {
                val reached =
                    row.markers
                        .sorted()
                        .ifEmpty { listOf("none reached") }
                        .joinToString(", ")
                "WAIVER WITHOUT EVIDENCE: ${entry.className} — name a marker it reaches ($reached) or 'measured <N> s' over $WAIVER_SECONDS"
            }

            waiver && !row.crossModule -> {
                "WAIVER WITHOUT EVIDENCE: ${entry.className} reads nothing outside its module; a waiver is for such a guard only"
            }

            else -> {
                null
            }
        }
    }

    /** A row's status in the printed table, and the problem it raises when it is neither registered nor exempted. */
    private fun rowStatus(
        row: InventoryRow,
        registry: GuardRegistry,
        exemption: Exemption?,
    ): Pair<String, String?> {
        val reads =
            if (row.walk) {
                "a git ls-files walk"
            } else {
                row.crossPaths
                    .sorted()
                    .take(3)
                    .joinToString(", ")
            }
        return when {
            registry.moduleOf(row.className) != null -> {
                "registered" to null
            }

            exemption != null -> {
                "${exemption.kind.word} — ${exemption.reason}" to null
            }

            row.crossModule -> {
                "MISSING" to
                    "MISSING GUARD: ${row.className} (${row.module.path}) reads beyond its module ($reads) — " +
                    "register it in GUARDS[\"${row.module.path}\"] or waive it with evidence"
            }

            else -> {
                "UNCLASSIFIED" to
                    "UNCLASSIFIED: ${row.className} (${row.module.path}) reaches the root " +
                    "(${row.idioms.sorted().joinToString(",")}) with no path the scan can see — " +
                    "register it, or give it an own-module line saying why"
            }
        }
    }

    private fun evidenced(
        entry: Exemption,
        row: InventoryRow,
    ): Boolean {
        val seconds =
            MEASURED
                .find(entry.reason)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (seconds != null) return seconds > WAIVER_SECONDS
        return row.markers.any { it in entry.reason }
    }

    private fun closure(
        start: Decl,
        refs: Map<Decl, Set<Decl>>,
    ): Set<Decl> {
        val seen = linkedSetOf<Decl>()
        val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (seen.add(next)) refs[next]?.let { stack.addAll(it) }
        }
        return seen
    }

    private fun moduleOf(path: String): GradleModule? {
        val parts = path.split('/')
        return when {
            parts.size > 4 && parts[0] in setOf("modules", "tests") && parts[2] == "src" && parts[3] == "test" -> {
                GradleModule("${parts[0]}/${parts[1]}", ":${parts[0]}:${parts[1]}")
            }

            else -> {
                null
            }
        }
    }

    private data class Facts(
        val idioms: Set<String>,
        val crossPaths: Set<String>,
        val markers: Set<String>,
    )

    private fun facts(
        decl: Decl,
        tree: Tree,
    ): Facts {
        val idioms = mutableSetOf<String>()
        val cross = mutableSetOf<String>()
        val own = decl.file.module.dir
        decl.file.lexed.literals
            .filter { (offset, _) -> offset >= decl.start && offset < decl.end }
            .forEach { (_, value) -> literalFact(value, own, tree, idioms, cross) }
        val markers =
            CONTAINER_MARKERS
                .findAll(
                    decl.file.lexed.code
                        .substring(decl.start, decl.end),
                ).map { it.value }
                .toSet()
        return Facts(idioms, cross, markers)
    }

    /** One string literal: a root idiom, a `../` path resolving into the tree, or a tracked top-level path. */
    private fun literalFact(
        value: String,
        own: String,
        tree: Tree,
        idioms: MutableSet<String>,
        cross: MutableSet<String>,
    ) {
        val idiom = IDIOM_LITERALS[value]
        if (idiom == null && (value.isEmpty() || value.any { it.isWhitespace() })) return
        val outsideOwn = { path: String -> path != own && !path.startsWith("$own/") }
        when {
            idiom != null -> {
                idioms += idiom
            }

            value.startsWith("../") -> {
                val resolved = tree.normalize("$own/${value.substringBefore("\${").trimEnd('/')}")
                if (resolved != null && tree.exists(resolved)) {
                    idioms += "relative"
                    if (outsideOwn(resolved)) cross += value
                }
            }

            value.substringBefore('/') in tree.topLevel && value != "settings.gradle.kts" && outsideOwn(value) -> {
                cross += value
            }
        }
    }

    /** The tracked paths: files, the directories above them, the top-level entries. */
    private class Tree(
        tracked: List<String>,
    ) {
        private val files = tracked.toSet()
        private val dirs = tracked.flatMap { path -> path.indices.filter { path[it] == '/' }.map { path.substring(0, it) } }.toSet()
        val topLevel: Set<String> = tracked.map { it.substringBefore('/') }.toSet()

        fun exists(path: String) = path in files || path in dirs

        fun normalize(path: String): String? {
            val out = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeLast()
                    else -> out.addLast(part)
                }
            }
            return out.joinToString("/")
        }
    }
}

/** A test source: its path, its module and its lexed text. */
internal class SourceFile(
    val path: String,
    val module: GradleModule,
    val lexed: Lexed,
) {
    val packageName: String =
        Regex("""(?m)^\s*package\s+([\w.]+)""")
            .find(lexed.code)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}

/**
 * Kotlin/Java text with comments and string literals blanked to spaces (same length, same offsets),
 * and the literals' values. A `${…}` template inside a literal becomes `${}` in its value.
 */
internal class Lexed(
    val code: String,
    val literals: List<Pair<Int, String>>,
) {
    companion object {
        fun of(text: String): Lexed = Lexer(text).run()
    }
}

/** One pass over a source text; each method consumes one token kind from `at` and returns the next offset. */
private class Lexer(
    private val text: String,
) {
    private val n = text.length
    private val code = StringBuilder(n)
    private val literals = mutableListOf<Pair<Int, String>>()

    fun run(): Lexed {
        var i = 0
        while (i < n) {
            i =
                when {
                    text.startsWith("//", i) -> lineComment(i)
                    text.startsWith("/*", i) -> blockComment(i)
                    text.startsWith(RAW_QUOTE, i) -> rawString(i)
                    text[i] == '"' -> string(i)
                    text[i] == '`' -> backtickName(i)
                    text[i] == '\'' -> charLiteral(i)
                    else -> keep(i)
                }
        }
        check(code.length == n) { "lexer offset drift: ${code.length} != $n" }
        return Lexed(code.toString(), literals)
    }

    private fun blank(
        from: Int,
        to: Int,
    ) {
        for (k in from until to) code.append(if (text[k] == '\n') '\n' else ' ')
    }

    private fun keep(at: Int): Int {
        code.append(text[at])
        return at + 1
    }

    private fun lineComment(at: Int): Int {
        val end = text.indexOf('\n', at).let { if (it < 0) n else it }
        blank(at, end)
        return end
    }

    /** Kotlin block comments nest. */
    private fun blockComment(at: Int): Int {
        var depth = 1
        var k = at + 2
        while (k < n && depth > 0) {
            depth +=
                if (text.startsWith("/*", k)) {
                    1
                } else if (text.startsWith("*/", k)) {
                    -1
                } else {
                    0
                }
            k += if (text.startsWith("/*", k) || text.startsWith("*/", k)) 2 else 1
        }
        val end = minOf(k, n)
        blank(at, end)
        return end
    }

    private fun rawString(at: Int): Int {
        var close = text.indexOf(RAW_QUOTE, at + 3)
        if (close < 0) {
            literals += at to text.substring(at + 3)
            blank(at, n)
            return n
        }
        while (close + 3 < n && text[close + 3] == '"') close++
        literals += at to text.substring(at + 3, close)
        code.append('"')
        blank(at + 1, close + 2)
        code.append('"')
        return close + 3
    }

    /** A one-line string; an escape keeps its character, a template becomes an empty template. */
    private fun string(at: Int): Int {
        var k = at + 1
        val value = StringBuilder()
        while (k < n && text[k] != '"' && text[k] != '\n') {
            k =
                when {
                    text[k] == '\\' -> {
                        if (k + 1 < n) value.append(text[k + 1])
                        k + 2
                    }

                    text.startsWith(TEMPLATE_OPEN, k) -> {
                        value.append(TEMPLATE_OPEN).append('}')
                        templateEnd(k + 2)
                    }

                    else -> {
                        value.append(text[k])
                        k + 1
                    }
                }
        }
        literals += at to value.toString()
        code.append('"')
        blank(at + 1, minOf(k, n))
        if (k < n && text[k] == '"') {
            code.append('"')
            return k + 1
        }
        return minOf(k, n)
    }

    private fun templateEnd(from: Int): Int {
        var depth = 1
        var m = from
        while (m < n && depth > 0) {
            depth +=
                if (text[m] == '{') {
                    1
                } else if (text[m] == '}') {
                    -1
                } else {
                    0
                }
            m++
        }
        return m
    }

    /** A backtick name (a test's sentence) is no identifier: its words never resolve. */
    private fun backtickName(at: Int): Int {
        val close = text.indexOf('`', at + 1)
        val newline = text.indexOf('\n', at)
        val end = if (close < 0 || newline in 0 until close) at + 1 else close + 1
        code.append('`')
        blank(at + 1, end - 1)
        if (end - 1 > at) code.append('`')
        return end
    }

    private fun charLiteral(at: Int): Int {
        val match = CHAR_LITERAL.matchAt(text, at) ?: return keep(at)
        blank(at, at + match.value.length)
        return at + match.value.length
    }

    private companion object {
        const val RAW_QUOTE = "\"\"\""
        const val TEMPLATE_OPEN = "\${"
        val CHAR_LITERAL = Regex("""'(\\.[^']*|[^'\\])'""")
    }
}

/** A member declaration (top level, or a member of a class/object/interface) and its text extent. */
internal class Decl(
    val file: SourceFile,
    val kind: String,
    val name: String,
    val modifiers: String,
    val start: Int,
    var end: Int,
    val depth: Int,
    var parent: Decl?,
) {
    val qualifiedName: String get() = if (file.packageName.isEmpty()) name else "${file.packageName}.$name"
    val owner: String? get() = parent?.let { if (it.name == COMPANION) it.parent?.name else it.name }
    val displayName: String get() = owner?.let { "$it.$name" } ?: name

    fun encloses(other: Decl) = other.file == file && other.start >= start && other.start < end

    companion object {
        const val COMPANION = "Companion"
    }
}

internal object Declarations {
    private val DECL =
        Regex(
            """(?m)^[ \t]*(?:@[\w.]+(?:\([^)\n]*\))?\s+)*((?:(?:private|internal|public|protected|abstract|open|data|sealed|inner|""" +
                """enum|annotation|override|const|lateinit|inline|suspend|companion|value|final|static)\s+)*)""" +
                """(class|object|interface|fun|val|var)\b\s*(?:<[^>\n]*>\s*)?(?:[\w.]+\.)?(\w+|`[^`\n]*`)?""",
        )
    private val CLASS_LIKE = setOf("class", "object", "interface")

    fun of(file: SourceFile): List<Decl> {
        val code = file.lexed.code
        val depth = braceDepths(code)
        val all = DECL.findAll(code).mapNotNull { declOf(file, it, depth) }.toList()
        for ((index, decl) in all.withIndex()) decl.end = extentEnd(all, index, depth, code.length)
        for ((index, decl) in all.withIndex()) {
            decl.parent =
                all.subList(0, index).lastOrNull { it.depth < decl.depth && it.end > decl.start && it.kind in CLASS_LIKE }
        }
        return all.filter { it.depth == 0 || it.parent?.depth == it.depth - 1 }
    }

    /** The brace depth at every offset of blanked code: a `{` counts from the next offset, a `}` from its own. */
    private fun braceDepths(code: String): IntArray {
        val depth = IntArray(code.length)
        var d = 0
        for (i in code.indices) {
            if (code[i] == '}') d--
            depth[i] = d
            if (code[i] == '{') d++
        }
        return depth
    }

    private fun declOf(
        file: SourceFile,
        match: MatchResult,
        depth: IntArray,
    ): Decl? {
        val keyword = match.groups[2] ?: return null
        val modifiers = match.groupValues[1]
        val companion = keyword.value == "object" && "companion" in modifiers
        val name = match.groups[3]?.value ?: Decl.COMPANION.takeIf { companion } ?: return null
        val at = keyword.range.first
        return Decl(file, keyword.value, name, modifiers, at, file.lexed.code.length, depth[at], null)
    }

    /** A declaration ends at the next one at its depth or above, or where its enclosing block closes. */
    private fun extentEnd(
        all: List<Decl>,
        index: Int,
        depth: IntArray,
        length: Int,
    ): Int {
        val decl = all[index]
        val next = (index + 1 until all.size).firstOrNull { all[it].depth <= decl.depth }?.let { all[it].start } ?: length
        return (decl.start until next).firstOrNull { depth[it] < decl.depth } ?: next
    }
}

/** Resolves which declaration's text refers to which, inside one module's test sources. */
internal object References {
    private val TOKEN = Regex("""[A-Za-z_]\w*""")

    fun resolve(decls: List<Decl>): Map<Decl, Set<Decl>> {
        val tokens =
            decls.associateWith {
                TOKEN
                    .findAll(it.file.lexed.code, it.start)
                    .takeWhile { m ->
                        m.range.first < it.end
                    }.map { m -> m.value }
                    .toSet()
            }
        val index = mutableMapOf<Pair<String, String>, MutableList<Decl>>()
        for ((decl, words) in tokens) for (word in words) index.getOrPut(decl.file.module.path to word) { mutableListOf() } += decl
        val refs = mutableMapOf<Decl, MutableSet<Decl>>()
        // A top-level main is an entry point nobody calls; its name is every test sentence's word.
        for (target in decls.filterNot { it.parent == null && it.name == "main" }) {
            val qualified = target.owner?.let { Regex("""\b${Regex.escape(it)}\s*\.\s*${Regex.escape(target.name)}\b""") }
            index[target.file.module.path to target.name]
                .orEmpty()
                .filter { user -> user !== target && !target.encloses(user) && !user.encloses(target) }
                .filter { user -> reaches(user, target, qualified, tokens.getValue(user)) }
                .forEach { user -> refs.getOrPut(user) { mutableSetOf() } += target }
        }
        return refs
    }

    /**
     * Same file and inside the target's scope: lexical. Otherwise a top-level fun/val/class by its
     * bare name, a top-level object never by its name alone, a member only as `Owner.member`.
     */
    private fun reaches(
        user: Decl,
        target: Decl,
        qualified: Regex?,
        userWords: Set<String>,
    ): Boolean {
        val parent = target.parent
        return when {
            user.file == target.file && (parent == null || parent.encloses(user)) -> {
                true
            }

            parent == null -> {
                target.kind != "object"
            }

            qualified == null || target.owner !in userWords -> {
                false
            }

            else -> {
                qualified.containsMatchIn(
                    user.file.lexed.code
                        .substring(user.start, user.end),
                )
            }
        }
    }
}

/** Runnable test classes: top-level, not abstract, holding a JUnit test or a Kotest spec, or extending one. */
internal object TestClasses {
    private val TEST_ANNOTATION = Regex("""@(Test|ParameterizedTest|TestFactory|RepeatedTest|TestTemplate)\b""")
    private val KOTEST_SPEC =
        Regex(
            """:\s*(FunSpec|StringSpec|DescribeSpec|BehaviorSpec|ShouldSpec|WordSpec|FreeSpec|ExpectSpec|""" +
                """FeatureSpec|AnnotationSpec)\s*\(""",
        )
    private val WORD = Regex("""\w+""")
    private val SUPERTYPES = Regex("""\)?\s*:\s*([\w.<>, ()]+?)\s*\{""")

    fun find(decls: List<Decl>): List<Decl> {
        val classes = decls.filter { it.kind == "class" && it.parent == null }
        val bearing =
            classes
                .filter {
                    val text =
                        it.file.lexed.code
                            .substring(it.start, it.end)
                    TEST_ANNOTATION.containsMatchIn(text) || KOTEST_SPEC.containsMatchIn(text)
                }.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (decl in classes) {
                if (decl in bearing) continue
                val header =
                    decl.file.lexed.code.substring(
                        decl.start,
                        minOf(
                            decl.end,
                            decl.file.lexed.code.indexOf('{', decl.start).let {
                                if (it <
                                    0
                                ) {
                                    decl.end
                                } else {
                                    it + 1
                                }
                            },
                        ),
                    )
                val supertypes =
                    WORD
                        .findAll(
                            SUPERTYPES
                                .find(header)
                                ?.groupValues
                                ?.get(1)
                                .orEmpty(),
                        ).map { it.value }
                        .toSet()
                if (bearing.any { it.file.module == decl.file.module && it.name in supertypes }) {
                    bearing += decl
                    grew = true
                }
            }
        }
        return bearing.filter { "abstract" !in it.modifiers }.sortedBy { it.qualifiedName }
    }
}
