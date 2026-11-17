package co.datapipelines.web.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * **A changed font gets a new name** (#1023 D4): browsers keep a font file for a year without asking again
 * ([StaticAssetCacheConfig.FONT_RULE]), so a name already served must never be served with other bytes.
 *
 * The ledger (`fonts/font-path-ledger.txt` in this module's test resources) has one line per font file ever served,
 * `<path under static/> <sha256>`. Two parts, one rule ([nowViolations] + [historyViolations]):
 * - **Now.** Every font file has exactly one line, and the line's hash is the file's. A line whose file is gone stays.
 * - **History.** Every line of every earlier version of the ledger in the git history of `HEAD` (every commit that
 *   changed it, merge commits included) is in the ledger now, character for character. So a landed line can be neither
 *   edited (bytes changed with the line "fixed" to match) nor deleted, whatever else agrees. Adding a line passes.
 *
 * The history needs the repository's history: a shallow checkout fails, saying so. A line is protected from the commit
 * that adds it. The build re-runs this test whenever the ledger's history changes (module web's `test` task input
 * `fontLedgerHistory`). The rule is tested in temporary git repositories too, so the reading of history is under test.
 */
class FontPathLedgerTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `every font file now has exactly one ledger line, with its hash`() {
        val root = repoRoot()
        val now = entries(File(root, LEDGER).readText())
        val files = fontFiles(File(root, STATIC))
        println("1023|ledger-now|lines=${now.size} font-files=${files.size}")
        val violations = nowViolations(now, files)
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `every line of every earlier version of the ledger is in the ledger now`() {
        val root = repoRoot()
        val now = entries(File(root, LEDGER).readText())
        val history = readHistory(root, LEDGER, isolated = false)
        println("1023|ledger-history|commands=${history.commands}")
        println("1023|ledger-history|versions-read=${history.versions.size} in-HEAD=${history.inHead}")
        if (!history.inHead) {
            println("1023|ledger-history|the ledger is not in HEAD yet: the history part had nothing to read")
        } else {
            val earlierLines = history.versions.flatMap { it.lines }.toSet()
            withClue("non-vacuity: the ledger is in HEAD, so its history must yield at least $FLOOR lines") {
                earlierLines.size shouldBeGreaterThanOrEqual FLOOR
            }
        }
        val violations = historyViolations(now, history.versions)
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `(a) a committed line edited to match changed bytes is red by history while now is green`() {
        val repo = seeded()
        repo.font(0, "upgraded bytes")
        repo.ledger(repo.lines().map { if (it.startsWith(repo.path(0))) repo.lineOf(0) else it })
        repo.commit("edit a line")
        val (now, history, files) = repo.read()
        nowViolations(now, files).shouldBeEmpty()
        historyViolations(now, history).single() shouldContain repo.seedCommit
    }

    @Test
    fun `(b) a committed line deleted with its file is red even with 20 or more lines left`() {
        val repo = seeded()
        repo.removeFont(0)
        repo.ledger(repo.lines().filterNot { it.startsWith(repo.path(0)) })
        repo.commit("retire a font and its line")
        val (now, history, files) = repo.read()
        now.size shouldBeGreaterThanOrEqual FLOOR
        nowViolations(now, files).shouldBeEmpty()
        historyViolations(now, history).single() shouldContain repo.path(0)
    }

    @Test
    fun `(c) a new file name with an appended line is green`() {
        val repo = seeded()
        repo.font(SEED_FONTS, "a new font")
        repo.ledger(repo.lines() + repo.lineOf(SEED_FONTS))
        repo.commit("add a font")
        val (now, history, files) = repo.read()
        (nowViolations(now, files) + historyViolations(now, history)).shouldBeEmpty()
    }

    @Test
    fun `(d) a file removed with its line kept is green`() {
        val repo = seeded()
        repo.removeFont(0)
        repo.commit("retire a font, keep its line")
        val (now, history, files) = repo.read()
        (nowViolations(now, files) + historyViolations(now, history)).shouldBeEmpty()
    }

    @Test
    fun `(e) an earlier version that arrived through a merge commit counts`() {
        val repo = seeded()
        repo.git("checkout", "-q", "-b", "side")
        repo.font(SEED_FONTS, "from the side branch")
        repo.ledger(repo.lines() + repo.lineOf(SEED_FONTS))
        val sideCommit = repo.commit("side adds a font")
        repo.git("checkout", "-q", "main")
        File(repo.dir, "README").writeText("main moves on\n")
        repo.commit("unrelated")
        repo.git("merge", "-q", "--no-ff", "--no-commit", "side")
        // An evil merge: the merge commit itself adds a line no parent has.
        repo.font(SEED_FONTS + 1, "added by the merge")
        repo.ledger(repo.lines() + repo.lineOf(SEED_FONTS + 1))
        val mergeCommit = repo.commit("merge side")
        repo.removeFont(SEED_FONTS)
        repo.removeFont(SEED_FONTS + 1)
        repo.ledger(repo.lines().filterNot { it.startsWith(repo.path(SEED_FONTS)) || it.startsWith(repo.path(SEED_FONTS + 1)) })
        repo.commit("drop both lines")
        val (now, history, _) = repo.read()
        val violations = historyViolations(now, history)
        violations.size shouldBeGreaterThanOrEqual 2
        violations.filter { repo.path(SEED_FONTS) in it }.shouldNotBeEmpty()
        violations.filter { repo.path(SEED_FONTS + 1) in it && mergeCommit in it }.shouldNotBeEmpty()
        println("1023|ledger-case-e|side=$sideCommit merge=$mergeCommit violations=${violations.size}")
    }

    @Test
    fun `(f) a shallow clone is refused with the reason`() {
        val repo = seeded()
        repo.font(SEED_FONTS, "one more")
        repo.ledger(repo.lines() + repo.lineOf(SEED_FONTS))
        repo.commit("second commit")
        val clone = tmp.resolve("shallow").toFile()
        git(tmp.toFile(), listOf("clone", "-q", "--depth", "1", "file://${repo.dir.absolutePath}", clone.path), isolated = true)
        val refusal = shouldThrow<IllegalStateException> { readHistory(clone, TEMP_LEDGER, isolated = true) }
        refusal.message.orEmpty() shouldContain SHALLOW_SENTENCE
    }

    private fun seeded(): TempRepo {
        val repo = TempRepo(tmp.resolve("repo").toFile())
        check(repo.dir.mkdirs()) { "could not create ${repo.dir}" }
        repo.git("init", "-q", "-b", "main")
        (0 until SEED_FONTS).forEach { repo.font(it, "seed font $it") }
        repo.ledger((0 until SEED_FONTS).map { repo.lineOf(it) })
        repo.seedCommit = repo.commit("seed")
        return repo
    }

    /** A temporary repository with the real layout below `static/`; every command carries its own identity. */
    private class TempRepo(
        val dir: File,
    ) {
        lateinit var seedCommit: String

        fun git(vararg args: String): String = git(dir, identity + args.toList(), isolated = true)

        fun path(i: Int) = "vendor/fonts/family/font-$i.woff2"

        fun font(
            i: Int,
            text: String,
        ) = File(dir, "static/${path(i)}").apply { parentFile.mkdirs() }.writeText(text)

        fun removeFont(i: Int) = check(File(dir, "static/${path(i)}").delete()) { "no font $i" }

        fun lineOf(i: Int) = "${path(i)} ${sha256(File(dir, "static/${path(i)}"))}"

        fun lines() = entries(File(dir, TEMP_LEDGER).readText())

        fun ledger(lines: List<String>) = File(dir, TEMP_LEDGER).writeText(lines.joinToString("\n", postfix = "\n"))

        fun commit(message: String): String {
            git("add", "-A")
            git("commit", "-q", "-m", message)
            return git("rev-parse", "HEAD").trim()
        }

        fun read(): Triple<List<String>, List<LedgerVersion>, Map<String, String>> =
            Triple(lines(), readHistory(dir, TEMP_LEDGER, isolated = true).versions, fontFiles(File(dir, "static")))

        private val identity =
            listOf("-c", "user.name=ledger-test", "-c", "user.email=ledger-test@example.invalid", "-c", "commit.gpgsign=false")
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    internal data class LedgerVersion(
        val commit: String,
        val lines: List<String>,
    )

    internal data class History(
        val versions: List<LedgerVersion>,
        val inHead: Boolean,
        val commands: List<String>,
    )

    internal companion object {
        const val LEDGER = "modules/web/src/test/resources/fonts/font-path-ledger.txt"
        const val STATIC = "modules/web/src/main/resources/static"

        /** A temporary repository's ledger sits at its root. */
        const val TEMP_LEDGER = "ledger.txt"
        const val FLOOR = 20
        const val SEED_FONTS = 21
        private val ENTRY = Regex("""^(vendor/fonts/\S+) ([0-9a-f]{64})$""")

        const val SENTENCE =
            "a font file's bytes changed under a name browsers keep for a year (Cache-Control max-age=31536000, " +
                "immutable): give the upgraded file a NEW name and ADD its line to font-path-ledger.txt; never edit or " +
                "delete a line (a retired file's line stays, so its name is never reused with other bytes)"

        const val SHALLOW_SENTENCE =
            "this checkout is shallow (git rev-parse --is-shallow-repository = true): without the ledger's git history " +
                "a changed or deleted line cannot be seen; fetch the full history (CI: actions/checkout fetch-depth: 0)"

        /** The ledger's entries: every line that is not blank and not a `#` comment. */
        fun entries(text: String): List<String> = text.lines().map(String::trimEnd).filter { it.isNotEmpty() && !it.startsWith("#") }

        /** Every file under the fonts directory below [staticDir], keyed by its path under `static/`, with its SHA-256. */
        fun fontFiles(staticDir: File): Map<String, String> =
            File(staticDir, "vendor/fonts")
                .walkTopDown()
                .filter(File::isFile)
                .associate { it.relativeTo(staticDir).invariantSeparatorsPath to sha256(it) }
                .toSortedMap()

        /** The "now" part: each line well-formed, at least [FLOOR] lines, each font file with exactly one matching line. */
        fun nowViolations(
            now: List<String>,
            files: Map<String, String>,
        ): List<String> {
            val malformed = now.filterNot(ENTRY::matches).map { "malformed ledger line `$it`: expected `<path under static/> <sha256>`" }
            val byPath = now.mapNotNull(ENTRY::matchEntire).groupBy({ it.groupValues[1] }, { it.groupValues[2] })
            val floor = if (now.size < FLOOR) listOf("the ledger has ${now.size} lines, fewer than its floor of $FLOOR") else emptyList()
            val perFile =
                files.mapNotNull { (path, hash) ->
                    val recorded = byPath[path].orEmpty()
                    when {
                        recorded.isEmpty() -> "$path has no ledger line; add `$path $hash`. Rule: $SENTENCE"
                        recorded.size > 1 -> "$path has ${recorded.size} ledger lines, exactly one is allowed. Rule: $SENTENCE"
                        recorded.single() != hash -> "$path is $hash but its ledger line says ${recorded.single()}. Rule: $SENTENCE"
                        else -> null
                    }
                }
            return malformed + floor + perFile
        }

        /** The "history" part: every line of every earlier version is in the ledger now, character for character. */
        fun historyViolations(
            now: List<String>,
            earlier: List<LedgerVersion>,
        ): List<String> {
            val present = now.toSet()
            val firstSeen = linkedMapOf<String, String>()
            earlier.forEach { version -> version.lines.forEach { firstSeen.putIfAbsent(it, version.commit) } }
            return firstSeen
                .filterKeys { it !in present }
                .map { (line, commit) -> "ledger line `$line` (in commit $commit) is missing from the ledger now. Rule: $SENTENCE" }
        }

        /**
         * Every earlier version of [ledger] in the history of `HEAD`, read with plumbing commands from [root]: the
         * commits that changed it (`rev-list --full-history`, merges included), the blob each one holds, its text.
         * A git failure fails; a shallow repository fails with [SHALLOW_SENTENCE].
         */
        fun readHistory(
            root: File,
            ledger: String,
            isolated: Boolean,
        ): History {
            val commands = mutableListOf<String>()

            fun run(vararg args: String): String = git(root, args.toList(), isolated).also { commands += "git ${args.joinToString(" ")}" }
            check(run("rev-parse", "--is-shallow-repository").trim() != "true") { SHALLOW_SENTENCE }
            val inHead = gitExit(root, listOf("cat-file", "-e", "HEAD:$ledger"), isolated) == 0
            commands += "git cat-file -e HEAD:$ledger"
            val commits = run("rev-list", "--full-history", "HEAD", "--", ledger).lines().filter(String::isNotBlank)
            val blobs = linkedMapOf<String, String>()
            for (commit in commits) {
                val entry = git(root, listOf("ls-tree", commit, "--", ledger), isolated).trim()
                if (entry.isNotEmpty()) blobs.putIfAbsent(entry.split(Regex("\\s+"))[2], commit)
            }
            if (commits.isNotEmpty()) commands += "git ls-tree <commit> -- $ledger (x${commits.size})"
            val versions = blobs.map { (blob, commit) -> LedgerVersion(commit, entries(git(root, listOf("cat-file", "blob", blob), isolated))) }
            if (blobs.isNotEmpty()) commands += "git cat-file blob <blob> (x${blobs.size})"
            return History(versions, inHead, commands)
        }

        fun git(
            dir: File,
            args: List<String>,
            isolated: Boolean,
        ): String {
            val result = exec(dir, args, isolated)
            check(result.first == 0) { "git ${args.joinToString(" ")} exited ${result.first} in $dir: ${result.third.trim()}" }
            return result.second
        }

        private fun gitExit(
            dir: File,
            args: List<String>,
            isolated: Boolean,
        ): Int = exec(dir, args, isolated).first

        /** Runs git with no inherited `GIT_*` variable; [isolated] also hides the machine's git configuration. */
        private fun exec(
            dir: File,
            args: List<String>,
            isolated: Boolean,
        ): Triple<Int, String, String> {
            val errors = File.createTempFile("font-ledger-git", ".err")
            try {
                val builder = ProcessBuilder(listOf("git") + args).directory(dir).redirectError(ProcessBuilder.Redirect.to(errors))
                val env = builder.environment()
                env.keys.filter { it.startsWith("GIT_") }.forEach(env::remove)
                if (isolated) {
                    env["GIT_CONFIG_NOSYSTEM"] = "1"
                    env["GIT_CONFIG_GLOBAL"] = "/dev/null"
                }
                val process = builder.start()
                val out = process.inputStream.bufferedReader().readText()
                return Triple(process.waitFor(), out, errors.readText())
            } finally {
                errors.delete()
            }
        }

        fun sha256(file: File): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    }
}
