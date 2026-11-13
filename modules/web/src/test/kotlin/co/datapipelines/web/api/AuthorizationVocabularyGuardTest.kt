package co.datapipelines.web.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * The retired authorization vocabulary guard (#796). Permissions-and-keys design §2 retired
 * `RestOperation`: handlers declare their catalog permission directly with
 * `@RequiredScope(Permission.X)`, MCP tools carry theirs in `McpToolCatalog.Entry(name, mutating,
 * permission)`, and the role cells live in `RolePermissions`. No `RestOperation` enum and no
 * `MCP_TOOL_MIN_SCOPE` / `MCP_TOOL_MIN_PERMISSION` map exists, so a line naming one sends its
 * reader to code that is not there. This guard fails on any such line outside three admissions.
 *
 * The scan reads every text file the git index tracks (`git ls-files -z`, the extension list and
 * the 4 MB cap of `ScriptVocabularyGuardTest`), with NO path filter: the ratified records under
 * `docs/superpowers/specs/` are scanned and admitted by name, so their admission can reach its
 * floor. An untracked file is not the tree, so a new file is added to the index before it counts.
 *
 * Admissions, each counting its hits:
 *
 *   - **(a) The auth Change Log** — `docs/auth.md` lines under a heading whose text matches
 *     `change\s*log` (docs-audit's heading flip, copied verbatim): history keeps the vocabulary
 *     of its day. A line under the flip is COUNTED first and only then kept out of the violations.
 *   - **(b) The ratified records** — every file under `docs/superpowers/specs/`: a frozen record
 *     is not rewritten.
 *   - **(c) This file** — its pattern and its self-check literals.
 *
 * An admission with fewer hits than its floor, or none at all, fails as STALE: an admission
 * cannot keep admitting a tree that no longer needs it. The scanned-file floor and the matcher
 * self-check keep the scan itself honest.
 */
class AuthorizationVocabularyGuardTest {
    @Test
    fun `no tracked line outside the admissions names the retired authorization vocabulary`() {
        val root = repoRoot()
        val scanned = scanTargets(root)
        println(
            "Authorization vocabulary scan: ${scanned.size} files (floor $SCAN_FLOOR), " +
                "admissions ${ADMISSIONS.size}",
        )
        if (scanned.size < SCAN_FLOOR) {
            fail("the scan saw only ${scanned.size} files (floor $SCAN_FLOOR) — the walk is broken")
        }
        val violations = mutableListOf<String>()
        val hits = mutableMapOf<String, Int>()
        for ((path, file) in scanned) {
            var inChangeLog = false
            file.readLines().forEachIndexed { index, line ->
                if (HEADING.containsMatchIn(line)) inChangeLog = CHANGE_LOG_HEADING.containsMatchIn(line)
                if (!RETIRED.containsMatchIn(line)) return@forEachIndexed
                val admitted = ADMISSIONS.filter { it.admits(path, inChangeLog) }
                if (admitted.isEmpty()) {
                    violations += "$path:${index + 1}: ${line.trim().take(160)}"
                } else {
                    admitted.forEach { hits.merge(it.name, 1, Int::plus) }
                }
            }
        }
        println("Authorization vocabulary admissions: " + ADMISSIONS.joinToString { "${it.name}=${hits[it.name] ?: 0}" })
        ADMISSIONS.forEach { admission ->
            val count = hits[admission.name] ?: 0
            if (count == 0 || count < admission.floor) {
                violations += "STALE admission ${admission.name}: $count hit(s), floor ${admission.floor} — ${admission.reason}"
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} retired authorization vocabulary violation(s). `RestOperation` and " +
                    "`MCP_TOOL_MIN_SCOPE`/`MCP_TOOL_MIN_PERMISSION` do not exist: a handler declares " +
                    "`@RequiredScope(Permission.X)`, a tool carries the `permission` of its " +
                    "`McpToolCatalog.Entry(name, mutating, permission)`, and the row is docs/auth.md §7.6:\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the matcher sees the retired names and leaves Spring's RestOperations alone`() {
        for (sample in MATCHED) {
            if (!RETIRED.containsMatchIn(sample)) fail("the matcher cannot see a retired name: $sample")
        }
        for (sample in NOT_MATCHED) {
            if (RETIRED.containsMatchIn(sample)) fail("the matcher sees a name that is not retired: $sample")
        }
        val flipped = CHANGE_LOG_FIXTURE.any { HEADING.containsMatchIn(it) && CHANGE_LOG_HEADING.containsMatchIn(it) }
        if (!flipped) fail("the change-log flip does not recognise the auth Change Log heading")
    }

    private fun scanTargets(root: File): List<Pair<String, File>> =
        trackedPaths(root)
            .filter { it.substringAfterLast('/').substringAfterLast('.', "") in EXTENSIONS }
            .map { it to File(root, it) }
            // a tracked path deleted in the working tree has nothing to read
            .filter { (_, file) -> file.isFile && file.length() < MAX_FILE_BYTES }

    /** The index's paths, NUL-separated so no file name is quoted or split; a git failure fails the suite. */
    private fun trackedPaths(root: File): List<String> {
        val errors = File.createTempFile("authorization-vocabulary-git", ".err")
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

    /** One admission: its name, its hit floor, the reason it exists and the line set it admits. */
    private class Admission(
        val name: String,
        val floor: Int,
        val reason: String,
        val admits: (path: String, inChangeLog: Boolean) -> Boolean,
    )

    private companion object {
        private val RETIRED = Regex("""\bRestOperation\b|\bMCP_TOOL_MIN_(?:SCOPE|PERMISSION)\b""")
        private val HEADING = Regex("^#{1,6}\\s")
        private val CHANGE_LOG_HEADING = Regex("change\\s*log", setOf(IGNORE_CASE))

        private const val AUTH_DOC = "docs/auth.md"
        private const val RECORDS_PREFIX = "docs/superpowers/specs/"
        private const val SELF = "modules/web/src/test/kotlin/co/datapipelines/web/api/AuthorizationVocabularyGuardTest.kt"

        /** 90% of the 3,937 tracked text files at the guard's base 914bf0099, rounded down to the hundred. */
        private const val SCAN_FLOOR = 3_500
        private const val MAX_FILE_BYTES = 4_000_000L

        private val EXTENSIONS =
            setOf(
                "kt",
                "kts",
                "java",
                "md",
                "html",
                "js",
                "mjs",
                "json",
                "yml",
                "yaml",
                "sql",
                "properties",
                "toml",
                "sh",
                "txt",
                "xml",
                "css",
            )

        private val ADMISSIONS: List<Admission> =
            listOf(
                Admission(
                    name = "(a) auth Change Log",
                    floor = 2,
                    reason = "$AUTH_DOC history rows keep the vocabulary of their day (2 at 914bf0099)",
                ) { path, inChangeLog -> path == AUTH_DOC && inChangeLog },
                Admission(
                    name = "(b) ratified records",
                    floor = 10,
                    reason = "frozen records under $RECORDS_PREFIX are not rewritten (15 lines in 7 records at 914bf0099)",
                ) { path, _ -> path.startsWith(RECORDS_PREFIX) },
                Admission(
                    name = "(c) this guard",
                    floor = 1,
                    reason = "the guard's own pattern and self-check literals",
                ) { path, _ -> path == SELF },
            )

        private val MATCHED =
            listOf(
                "[ScopeMatrix.RestOperation.MUTATE_LAKE_TABLES]",
                "its entries in `MCP_TOOL_MIN_SCOPE`",
                "`MCP_TOOL_MIN_PERMISSION` holds 20 `VIEW`",
            )

        private val NOT_MATCHED =
            listOf(
                "import org.springframework.web.client.RestOperations",
                "@RequiredScope(Permission.LAKE_TABLE_MANAGE)",
                "MCP_TOOL_MIN_SCOPES_TABLE",
            )

        private val CHANGE_LOG_FIXTURE = listOf("## Appendix A: Change Log")
    }
}
