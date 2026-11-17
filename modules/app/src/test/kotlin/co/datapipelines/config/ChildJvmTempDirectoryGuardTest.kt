package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Every child JVM a test starts keeps its temp files inside the checkout's test temp root (#998, DEVELOPMENT.md
 * §9.7). A system property is not inherited and a JVM ignores `$TMPDIR`, so a child started without the flag
 * extracts `libduckdb_java*.so` and makes Tomcat work folders in the shared default temp directory.
 *
 * The walk: every tracked `.kt` and `.java` file under `modules/<m>/src/test`, `tests/<m>/src/test` and
 * `buildSrc/src/test` (`git ls-files`). Comments, string literals, char literals and backticked names are masked
 * first, so a `ProcessBuilder(` or a flag spelled there is text, not code (a Kotlin string template's code stays
 * code). A file the masking cannot read to its end (an unclosed comment, string or name) is not trusted: each
 * `ProcessBuilder(` spelled anywhere in it counts as a JVM launch that fails.
 *
 * How the command of one launch is found — per launch, from that launch alone, never from its function:
 * - two or more arguments to `ProcessBuilder(`: they are the command (the varargs form);
 * - one argument that is a list built in place (`listOf(…)`, `mutableListOf(…)`, `arrayListOf(…)`, `arrayOf(…)`,
 *   `List.of(…)`, `Arrays.asList(…)`, also joined by `+` to further such lists): its leading elements;
 * - one argument that is one local name: the expression that declares that name in the innermost enclosing function
 *   with a block body, read as above. A parameter, a property, a name declared more than once or assigned again,
 *   a destructured name, and any other expression is unreadable;
 * - anything else (no argument, `.command(…)` after the call, a spread, a call) is unreadable.
 *
 * The verdict per launch: a first element that is exactly one string literal from [NON_JVM_PROGRAMS] is not a JVM.
 * Every other launch is a JVM launch and passes only when its SECOND element is exactly one string literal whose
 * text starts with `-Djava.io.tmpdir=` and names something (D1: the first argument after the `java` executable).
 * An unreadable command is a failing JVM launch. Each failure is named `path:line`, the line of `ProcessBuilder(`.
 *
 * Limits, read before trusting a green run: the guard does not check that the directory is BELOW the parent's
 * `java.io.tmpdir` (the launch sites derive it in two lines; review reads that); a launch through a helper in another
 * file, a `ProcessBuilder` reached through an aliased import, a constructor reference, reflection or
 * `Runtime.exec` is not seen; a list mutated after its declaration (`command.add(0, …)`), and a name shadowed by
 * a lambda parameter, are read from the declaration alone. A source text the masking mis-reads in some way it does
 * not detect is read as code. The floors fail a walk that found too little.
 */
class ChildJvmTempDirectoryGuardTest {
    @Test
    fun `every child JVM a test starts is given its own temp directory as its first argument`() {
        val scan = ChildJvmLaunches.scan(repoRoot())
        println(scan.render())
        withClue("child JVM launches without the flag first (DEVELOPMENT.md §9.7):\n${scan.problems().joinToString("\n")}") {
            scan.problems().shouldBeEmpty()
        }
    }

    @Test
    fun `control - an empty tree is red on the floors`(
        @TempDir dir: Path,
    ) {
        val problems = ChildJvmLaunches.scan(probeTree(dir, emptyMap())).problems()
        problems shouldContainExactly
            listOf(
                "FLOOR: JVM launches found 0 < ${ChildJvmLaunches.JVM_FLOOR}",
                "FLOOR: non-JVM launches found 0 < ${ChildJvmLaunches.NON_JVM_FLOOR}",
            )
    }

    @Test
    fun `control - two launches in one function are two verdicts`(
        @TempDir dir: Path,
    ) {
        val source =
            fn(
                """ProcessBuilder(java, "-Djava.io.tmpdir=/t/a", "-cp", cp, "A").start()""",
                """ProcessBuilder(java, "-cp", cp, "-Djava.io.tmpdir=/t/b", "B").start()""",
            )
        offenders(dir, source) shouldContainExactly listOf("$PROBE:5")
    }

    @Test
    fun `control - a flag the command does not use satisfies nothing`(
        @TempDir dir: Path,
    ) {
        val source = fn("""val flag = "-Djava.io.tmpdir=/t/a"""", """ProcessBuilder(java, "-cp", cp, "A").start()""")
        offenders(dir, source) shouldContainExactly listOf("$PROBE:5")
    }

    @Test
    fun `control - a flag after the entry point is a program argument`(
        @TempDir dir: Path,
    ) {
        val source =
            fn(
                """ProcessBuilder(java, "-jar", jar, "-Djava.io.tmpdir=/t/a").start()""",
                """ProcessBuilder(java, "-cp", cp, "Main", "-Djava.io.tmpdir=/t/b").start()""",
                """ProcessBuilder(java, Source.java, "-Djava.io.tmpdir=/t/c").start()""",
            )
        offenders(dir, source) shouldContainExactly listOf("$PROBE:4", "$PROBE:5", "$PROBE:6")
    }

    @Test
    fun `control - a list-built command is read from its declaration`(
        @TempDir dir: Path,
    ) {
        val green = fn("""val command = listOf(java, "-Djava.io.tmpdir=${'$'}{tmp("c")}") + listOf("-cp", cp) +""", "    args", LAUNCH)
        offenders(dir, green).shouldBeEmpty()
        val red = fn("""val command =""", """    listOf(java, "-Xmx256m") + listOf("-Djava.io.tmpdir=/t/a")""", LAUNCH)
        offenders(dir, red) shouldContainExactly listOf("$PROBE:6")
        val joined = fn("""val command = listOf(java) + listOf("-Djava.io.tmpdir=/t/a", "-cp", cp, "Main")""", LAUNCH)
        offenders(dir, joined).shouldBeEmpty()
    }

    @Test
    fun `control - a command the guard cannot read is a failing JVM launch`(
        @TempDir dir: Path,
    ) {
        val reassigned = fn("""var command = listOf(java, "-Djava.io.tmpdir=/t/a")""", """command = listOf(java)""", LAUNCH)
        offenders(dir, reassigned) shouldContainExactly listOf("$PROBE:6")
        val parameter = "class Probe {\n    fun run(command: List<String>) {\n        $LAUNCH\n    }\n}\n"
        offenders(dir, parameter) shouldContainExactly listOf("$PROBE:3")
        val property =
            "class Probe {\n    val command = listOf(java, \"-Djava.io.tmpdir=/t/a\")\n\n" +
                "    fun run() {\n        $LAUNCH\n    }\n}\n"
        offenders(dir, property) shouldContainExactly listOf("$PROBE:5")
        val built = fn("""ProcessBuilder().command(java, "-Djava.io.tmpdir=/t/a").start()""")
        offenders(dir, built) shouldContainExactly listOf("$PROBE:4")
        val program = fn("""ProcessBuilder(tool, "--version").start()""", """ProcessBuilder("git", "status").start()""")
        offenders(dir, program) shouldContainExactly listOf("$PROBE:4")
        val unclosed = fn("""ProcessBuilder("git", "status").start()""", "val s = \"open")
        offenders(dir, unclosed) shouldContainExactly listOf("$PROBE:4")
    }

    @Test
    fun `control - a list declaration that goes on past the admitted forms is unreadable`(
        @TempDir dir: Path,
    ) {
        val source =
            fn(
                """val command = listOf(java, "-Djava.io.tmpdir=/t/child", "Main")""",
                """    .filterNot { it.startsWith("-Djava.io.tmpdir=") }""",
                LAUNCH,
            )
        offenders(dir, source) shouldContainExactly listOf("$PROBE:6")
    }

    @Test
    fun `control - a list argument that goes on past the admitted forms is unreadable`(
        @TempDir dir: Path,
    ) {
        val list = """listOf(java, "-Djava.io.tmpdir=/t/child", "Main").filterNot { it.startsWith("-Djava.io.tmpdir=") }"""
        val source = fn("ProcessBuilder($list).start()")
        offenders(dir, source) shouldContainExactly listOf("$PROBE:4")
    }

    @Test
    fun `control - a command replaced in the launch's chain keeps no credit for the discarded flag`(
        @TempDir dir: Path,
    ) {
        val source = fn("""ProcessBuilder(java, "-Djava.io.tmpdir=/t/child")""", """    .command(java, "-cp", cp, "Main").start()""")
        offenders(dir, source) shouldContainExactly listOf("$PROBE:4")
    }

    @Test
    fun `control - a builder kept in a local is followed to its start`(
        @TempDir dir: Path,
    ) {
        val kept = """val pb = ProcessBuilder(java, "-Djava.io.tmpdir=/t/child", "-jar", jar).redirectErrorStream(true)"""
        val green = fn(kept, """pb.environment()["A"] = "b"""", """pb.environment().remove("C")""", """val p = pb.start()""")
        offenders(dir, green).shouldBeEmpty()
        offenders(dir, fn(kept, """pb.command(java, "-jar", jar)""", """pb.start()""")) shouldContainExactly listOf("$PROBE:4")
        offenders(dir, fn(kept, """pb.redirectErrorStream(true).command().removeAt(1)""", """pb.start()""")) shouldContainExactly
            listOf("$PROBE:4")
        offenders(dir, fn(kept, """launch(pb)""")) shouldContainExactly listOf("$PROBE:4")
        val replacedGit = fn("""val git = ProcessBuilder("git", "status")""", """git.command(java, "-jar", jar).start()""")
        offenders(dir, replacedGit) shouldContainExactly listOf("$PROBE:4")
        offenders(dir, fn("""launch(ProcessBuilder(java, "-Djava.io.tmpdir=/t/child", "-jar", jar))""")) shouldContainExactly
            listOf("$PROBE:4")
    }

    @Test
    fun `control - ordinary chains and an environment-only apply stay green`(
        @TempDir dir: Path,
    ) {
        val green =
            fn(
                """val p = ProcessBuilder(java, "-Djava.io.tmpdir=/t/a", "-cp", cp, "A")""",
                """    .directory(dir).redirectErrorStream(true).redirectOutput(log).start().also { children += it }""",
                """ProcessBuilder("node", script)""",
                """    .apply {""",
                """        environment()["PORT"] = "0"""",
                """    }.redirectErrorStream(true)""",
                """    .start()""",
            )
        offenders(dir, green).shouldBeEmpty()
        val replacing = fn("""ProcessBuilder("node", script).apply { command(java, "-jar", jar) }.start()""")
        offenders(dir, replacing) shouldContainExactly listOf("$PROBE:4")
        val handedOn = fn("""ProcessBuilder("node", script).apply { configure(this) }.start()""")
        offenders(dir, handedOn) shouldContainExactly listOf("$PROBE:4")
    }

    @Test
    fun `control - a spread before the flag's position leaves the position unknown`(
        @TempDir dir: Path,
    ) {
        offenders(dir, fn("""ProcessBuilder(*prefix, "-Djava.io.tmpdir=/t/a", "Main").start()""")) shouldContainExactly listOf("$PROBE:4")
        val listed = fn("""val command = listOf(*prefix) + listOf("-Djava.io.tmpdir=/t/a", "Main")""", LAUNCH)
        offenders(dir, listed) shouldContainExactly listOf("$PROBE:5")
    }

    @Test
    fun `control - a list removed from or rearranged after its read elements is unreadable`(
        @TempDir dir: Path,
    ) {
        val minus = fn("""val command = listOf(java, "-Djava.io.tmpdir=/t/a", "Main") - "-Djava.io.tmpdir=/t/a"""", LAUNCH)
        offenders(dir, minus) shouldContainExactly listOf("$PROBE:5")
        val inner = fn("""val command = listOf(java) + listOf("-Djava.io.tmpdir=/t/a", "Main").drop(1)""", LAUNCH)
        offenders(dir, inner) shouldContainExactly listOf("$PROBE:5")
        val nextLine = fn("""val command = listOf(java, "-Djava.io.tmpdir=/t/a", "Main") + rest""", """    .reversed()""", LAUNCH)
        offenders(dir, nextLine).shouldBeEmpty()
        val wholeReversed = fn("""val command = (listOf(java, "-Djava.io.tmpdir=/t/a") + rest).reversed()""", LAUNCH)
        offenders(dir, wholeReversed) shouldContainExactly listOf("$PROBE:5")
        val infix = fn("""val command = listOf(java, "-Djava.io.tmpdir=/t/a") + rest zip other""", LAUNCH)
        offenders(dir, infix) shouldContainExactly listOf("$PROBE:5")
    }

    @Test
    fun `control - comments and strings are text, not code`(
        @TempDir dir: Path,
    ) {
        val text =
            fn(
                """val s = "ProcessBuilder(java, \"-cp\")" // ProcessBuilder(java)""",
                """/* ProcessBuilder(java, /* nested */ "-cp") */ val r = ""${'"'}ProcessBuilder(x)""${'"'}""",
                """ProcessBuilder("git", "ls-files").start()""",
            )
        val counted = ChildJvmLaunches.scan(probeTree(dir, mapOf(PROBE to text)))
        counted.jvm shouldBe 0
        counted.nonJvm shouldBe 1
        val commented = fn("ProcessBuilder(", "    java,", "    // \"-Djava.io.tmpdir=/t/a\",", "    \"-cp\",", ").start()")
        offenders(dir, commented) shouldContainExactly listOf("$PROBE:4")
    }

    @Test
    fun `control - a Java launch and a template flag pass`(
        @TempDir dir: Path,
    ) {
        val java =
            "class Probe {\n    void run() throws Exception {\n" +
                "        new ProcessBuilder(java, \"-Djava.io.tmpdir=\" + tmp, \"-cp\", cp).start();\n" +
                "        new ProcessBuilder(java, \"-Djava.io.tmpdir=/t/a\", \"-cp\", cp).start();\n    }\n}\n"
        val kotlin = fn("""ProcessBuilder(java, "-Djava.io.tmpdir=${'$'}{Path.of(base, "c, d")}", "-cp", cp).start()""")
        val scan = ChildJvmLaunches.scan(probeTree(dir, mapOf("modules/a/src/test/java/Probe.java" to java, PROBE to kotlin)))
        scan.offenders shouldContainExactly listOf("modules/a/src/test/java/Probe.java:3")
        scan.jvm shouldBe 3
    }

    private fun offenders(
        dir: Path,
        source: String,
    ): List<String> = ChildJvmLaunches.scan(probeTree(dir, mapOf(PROBE to source))).offenders

    /** A temporary repository holding [files] in its index (the guard walks the index, as it does the checkout's). */
    private fun probeTree(
        dir: Path,
        files: Map<String, String>,
    ): File {
        val root = dir.toFile().resolve("repo-${dir.toFile().list().orEmpty().size}").apply { mkdirs() }
        files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        git(root, "init", "-q")
        if (files.isNotEmpty()) git(root, "add", "--", *files.keys.toTypedArray())
        return root
    }

    private fun git(
        root: File,
        vararg args: String,
    ) {
        val process = ProcessBuilder("git", *args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(GIT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed: $output" }
    }

    /** A probe file: one function whose body lines start at line 4. */
    private fun fn(vararg body: String): String =
        "package probe\nclass Probe {\n    fun run() {\n" + body.joinToString("") { "        $it\n" } + "    }\n}\n"

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }
    }

    private companion object {
        const val PROBE = "modules/a/src/test/kotlin/probe/Probe.kt"
        const val LAUNCH = "ProcessBuilder(command).start()"
        const val GIT_SECONDS = 60L
    }
}

/** The scan: every launch in the tracked test sources, its verdict, and the floors. */
private object ChildJvmLaunches {
    /** Measured at birth (2026-11-16): 14 JVM launches in 12 files. */
    const val JVM_FLOOR = 14

    /** Measured at birth (2026-11-16): 14 non-JVM launches (`git` 10, `bash` 2, `node` 2). */
    const val NON_JVM_FLOOR = 11

    private val NON_JVM_PROGRAMS = setOf("git", "bash", "node")
    private const val FLAG = "-Djava.io.tmpdir="
    private val SCANNED = Regex("""^(?:modules/[^/]+/src/test/|tests/[^/]+/src/test/|buildSrc/src/test/).*\.(?:kt|java)$""")
    private val LAUNCH = Regex("""(?<![\w$])ProcessBuilder\s*\(""")
    private val LIST =
        Regex("""\G\s*(?:listOf|mutableListOf|arrayListOf|arrayOf|(?:java\.util\.)?List\.of|(?:java\.util\.)?Arrays\.asList)\s*\(""")
    private val JOIN = Regex("""\G\s*\+""")
    private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val FUN = Regex("""\bfun\b""")
    private const val GIT_SECONDS = 60L

    fun scan(root: File): Scan {
        val launches =
            trackedSources(root).flatMap { path ->
                val source = File(root, path).readText()
                val lexed = SourceMask.of(source, kotlin = path.endsWith(".kt"))
                if (lexed == null) {
                    LAUNCH.findAll(source).map { Launch(path, line(source, it.range.first), jvm = true, flagged = false) }.toList()
                } else {
                    LAUNCH.findAll(lexed.code).map { judge(path, source, lexed, it.range.first, it.range.last) }.toList()
                }
            }
        return Scan(launches)
    }

    private fun judge(
        path: String,
        source: String,
        lexed: SourceMask,
        start: Int,
        open: Int,
    ): Launch {
        val elements = command(lexed, start, open)
        val program = elements.getOrNull(0)?.let { lexed.literal(source, it) }
        if (program != null && program in NON_JVM_PROGRAMS) return Launch(path, line(source, start), jvm = false, flagged = false)
        val flag = elements.getOrNull(1)?.let { lexed.literal(source, it) }
        val flagged = flag != null && flag.startsWith(FLAG) && flag.length > FLAG.length
        return Launch(path, line(source, start), jvm = true, flagged = flagged)
    }

    /** The command's leading elements as source ranges; empty when the command cannot be read. */
    private fun command(
        lexed: SourceMask,
        start: Int,
        open: Int,
    ): List<IntRange> {
        val code = lexed.code
        val arguments = arguments(code, open)
        if (arguments.size != 1) return arguments
        val only = arguments.single()
        val text = code.substring(only.first, only.last + 1)
        if (!NAME.matches(text)) return listElements(code, only.first)
        val initializer = declaration(code, start, text) ?: return emptyList()
        return listElements(code, initializer)
    }

    /** The leading elements of in-place lists joined by `+`, from [at]; the first other term ends the read. */
    private fun listElements(
        code: String,
        at: Int,
    ): List<IntRange> {
        val elements = mutableListOf<IntRange>()
        var cursor = at
        while (true) {
            val list = LIST.find(code, cursor)?.takeIf { it.range.first == cursor } ?: return elements
            elements += arguments(code, list.range.last)
            cursor = closing(code, list.range.last) + 1
            cursor = JOIN.find(code, cursor)?.takeIf { it.range.first == cursor }?.let { it.range.last + 1 } ?: return elements
        }
    }

    /** Where the single declaration of [name] in the function enclosing [at] starts its value; null when unreadable. */
    private fun declaration(
        code: String,
        at: Int,
        name: String,
    ): Int? {
        val (parameters, body) = enclosingFunction(code, at) ?: return null
        if (Regex("""(?<![\w$])$name\s*:""").containsMatchIn(code.substring(parameters))) return null
        val text = code.substring(body)
        val declarations = Regex("""\b(?:val|var)\s+$name\b\s*(?::[^=\n]*)?=(?!=)""").findAll(text).toList()
        val javaDeclarations =
            Regex("""(?<=[\w$>\]])\s+$name\s*=(?!=)""")
                .findAll(text)
                .filterNot { hit -> Regex("""\b(?:val|var|return)$""").containsMatchIn(text.substring(0, hit.range.first)) }
                .toList()
        val writes =
            Regex("""(?<![\w$.])$name\s*[-+*/]?=(?!=)""")
                .findAll(text)
                .filterNot { hit -> Regex("""(?:\b(?:val|var)\s+|[\w$>\]]\s+)$""").containsMatchIn(text.substring(0, hit.range.first)) }
                .toList()
        val all = declarations.map { it.range.last + 1 } + javaDeclarations.map { it.range.last + 1 }
        if (all.size != 1 || writes.isNotEmpty()) return null
        val value = body.first + all.single()
        return value.takeIf { it < at }?.let { cursor -> cursor + code.substring(cursor).indexOfFirst { !it.isWhitespace() } }
    }

    /** The parameter list and the block body of the innermost `fun` whose body holds [at]; null for none. */
    private fun enclosingFunction(
        code: String,
        at: Int,
    ): Pair<IntRange, IntRange>? =
        FUN
            .findAll(code.substring(0, at))
            .mapNotNull { keyword ->
                val open = code.indexOf('(', keyword.range.last)
                if (open < 0 || !Regex("""[\w\s.<>,?*]*""").matches(code.substring(keyword.range.last + 1, open))) return@mapNotNull null
                val close = closing(code, open)
                val brace = code.indexOf('{', close)
                if (brace < 0 || !Regex("""\s*(?::[^{}=]*)?""").matches(code.substring(close + 1, brace))) return@mapNotNull null
                if (Regex("""\b(?:fun|val|var|class|object|init)\b""").containsMatchIn(code.substring(close + 1, brace))) {
                    return@mapNotNull null
                }
                val end = closing(code, brace)
                Pair(open + 1 until close, brace + 1 until end).takeIf { at in brace..end }
            }.lastOrNull()

    /** The top-level arguments of the call whose `(` is at [open], as trimmed source ranges. */
    private fun arguments(
        code: String,
        open: Int,
    ): List<IntRange> {
        val close = closing(code, open)
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var start = open + 1
        for (index in open + 1..close) {
            when (code[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if ((code[index] == ',' && depth == 0) || index == close) {
                trimmed(code, start, index)?.let { ranges += it }
                start = index + 1
            }
        }
        return ranges
    }

    private fun trimmed(
        code: String,
        from: Int,
        to: Int,
    ): IntRange? {
        var first = from
        var last = to - 1
        while (first <= last && code[first].isWhitespace()) first++
        while (last >= first && code[last].isWhitespace()) last--
        return (first..last).takeIf { first <= last }
    }

    /** The index of the bracket closing the one at [open]; the end of the text when it never closes. */
    private fun closing(
        code: String,
        open: Int,
    ): Int {
        val pair = if (code[open] == '(') '(' to ')' else '{' to '}'
        var depth = 0
        for (index in open until code.length) {
            if (code[index] == pair.first) depth++
            if (code[index] == pair.second && --depth == 0) return index
        }
        return code.length - 1
    }

    private fun line(
        text: String,
        offset: Int,
    ): Int = text.take(offset).count { it == '\n' } + 1

    private fun trackedSources(root: File): List<String> {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(false).start()
        val listing = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) { "git ls-files did not finish in $root" }
        check(process.exitValue() == 0) { "git ls-files exited ${process.exitValue()} in $root: ${errors.trim()}" }
        return listing.split('\u0000').filter { SCANNED.matches(it) }.sorted()
    }

    data class Launch(
        val path: String,
        val line: Int,
        val jvm: Boolean,
        val flagged: Boolean,
    ) {
        val where: String get() = "$path:$line"
    }

    class Scan(
        val launches: List<Launch>,
    ) {
        val jvm: Int get() = launches.count { it.jvm }
        val nonJvm: Int get() = launches.count { !it.jvm }
        val offenders: List<String> get() = launches.filter { it.jvm && !it.flagged }.map { it.where }

        fun problems(): List<String> =
            listOfNotNull(
                "FLOOR: JVM launches found $jvm < $JVM_FLOOR".takeIf { jvm < JVM_FLOOR },
                "FLOOR: non-JVM launches found $nonJvm < $NON_JVM_FLOOR".takeIf { nonJvm < NON_JVM_FLOOR },
            ) + offenders.map { "$it: a child JVM without -Djava.io.tmpdir=<its own directory> as its first argument" }

        fun render(): String =
            (
                listOf("#998 child-JVM launches: jvm=$jvm non-jvm=$nonJvm offenders=${offenders.size}") +
                    launches.map { "${if (it.jvm) "JVM" else "non-JVM"} ${if (it.jvm && !it.flagged) "FAIL" else "ok"} ${it.where}" }
            ).joinToString("\n")
    }
}

/**
 * A source text with comments, string and char literals and backticked names masked (offsets and newlines kept; a
 * string's text becomes `#`, so an argument's range keeps its literal whole; a Kotlin template's code stays code),
 * plus the range of every string literal. [of] answers null for a text it cannot read to its end.
 */
private class SourceMask private constructor(
    val code: String,
    private val literals: Map<Int, Int>,
) {
    /** The text of the single string literal that is exactly [range]; null for anything else. */
    fun literal(
        source: String,
        range: IntRange,
    ): String? {
        val end = literals[range.first] ?: return null
        if (end != range.last + 1) return null
        val quotes = if (source.startsWith("\"\"\"", range.first)) 3 else 1
        return source.substring(range.first + quotes, end - quotes)
    }

    private class Unreadable : RuntimeException()

    private class Lexer(
        private val src: String,
        private val kotlin: Boolean,
    ) {
        val out = src.toCharArray()
        val literals = HashMap<Int, Int>()

        /** Lexes code from [start]; inside a template, returns the index of the `}` that closes it. */
        fun code(
            start: Int,
            template: Boolean,
        ): Int {
            var i = start
            var braces = 0
            while (i < src.length) {
                if (src[i] == '}' && template && braces == 0) return i
                braces +=
                    when (src[i]) {
                        '{' -> 1
                        '}' -> -1
                        else -> 0
                    }
                i = next(i)
            }
            if (template) throw Unreadable()
            return i
        }

        /** Masks the token that starts at [i], if any; the index after it. */
        private fun next(i: Int): Int =
            when {
                src.startsWith("//", i) -> blank(i, src.indexOf('\n', i).let { if (it < 0) src.length else it })
                src.startsWith("/*", i) -> blank(i, comment(i))
                src.startsWith("\"\"\"", i) -> string(i, raw = true)
                src[i] == '"' -> string(i, raw = false)
                src[i] == '\'' -> blank(i + 1, char(i) - 1) + 1
                kotlin && src[i] == '`' -> name(i)
                else -> i + 1
            }

        private fun comment(start: Int): Int {
            var depth = 0
            var i = start
            while (i < src.length) {
                if (src.startsWith("/*", i) && (kotlin || depth == 0)) {
                    depth++
                    i += 2
                } else if (src.startsWith("*/", i)) {
                    depth--
                    i += 2
                    if (depth == 0) return i
                } else {
                    i++
                }
            }
            throw Unreadable()
        }

        private fun string(
            start: Int,
            raw: Boolean,
        ): Int {
            var i = start + if (raw) RAW_QUOTES else 1
            var textFrom = start
            while (true) {
                if (i >= src.length || (!raw && src[i] == '\n')) throw Unreadable()
                val end = closingQuotes(i, raw)
                if (end > 0) {
                    blank(textFrom, end, TEXT)
                    literals[start] = end
                    return end
                }
                if (kotlin && src.startsWith("\${", i)) {
                    blank(textFrom, i + 2, TEXT)
                    textFrom = code(i + 2, template = true)
                    i = textFrom + 1
                } else {
                    i += if (src[i] == '\\' && (!raw || !kotlin)) 2 else 1
                }
            }
        }

        /** The index after the quotes that close the string at [i]; 0 when the string does not close there. */
        private fun closingQuotes(
            i: Int,
            raw: Boolean,
        ): Int {
            if (!raw) return if (src[i] == '"') i + 1 else 0
            if (!src.startsWith("\"\"\"", i)) return 0
            var end = i + RAW_QUOTES
            while (end < src.length && src[end] == '"') end++
            return end
        }

        private fun char(start: Int): Int {
            var i = start + 1
            while (i < src.length && src[i] != '\'' && src[i] != '\n') i += if (src[i] == '\\') 2 else 1
            if (i >= src.length || src[i] != '\'') throw Unreadable()
            return i + 1
        }

        private fun name(start: Int): Int {
            val end = src.indexOf('`', start + 1)
            if (end < 0 || src.substring(start, end).contains('\n')) throw Unreadable()
            for (index in start + 1 until end) out[index] = 'x'
            return end + 1
        }

        private fun blank(
            from: Int,
            to: Int,
            fill: Char = ' ',
        ): Int {
            for (index in from until to) if (out[index] != '\n') out[index] = fill
            return to
        }
    }

    companion object {
        /** A string literal's text, masked: not whitespace (an argument's range keeps its literal whole), not code. */
        private const val TEXT = '#'
        private const val RAW_QUOTES = 3

        fun of(
            source: String,
            kotlin: Boolean,
        ): SourceMask? =
            try {
                val lexer = Lexer(source, kotlin)
                lexer.code(0, template = false)
                SourceMask(String(lexer.out), lexer.literals)
            } catch (_: Unreadable) {
                null
            }
    }
}
