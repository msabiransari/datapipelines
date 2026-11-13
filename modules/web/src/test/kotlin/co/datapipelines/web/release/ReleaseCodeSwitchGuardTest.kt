package co.datapipelines.web.release

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * The §14 code switch's whole-tree guard (release-dependencies record §14, ruled 2026-10-18: no deprecation window; brief
 * A.6): the replaced codes `pipeline.release.…` and `parameter.release.…` with the old template-only reason are absent
 * from the tree from the switch on, and the replacements are present.
 *
 * The scan reads every text file the git index tracks (`git ls-files`, so an untracked scratch file, a build output or a
 * lane worktree is never the tree) under the brief's scan set — top-level `docs/` Markdown, `DEVELOPMENT.md`, `modules/`,
 * `tests/` and `scripts/` — and NOT `docs/superpowers/`, whose design records keep the old spelling as history. It fails
 * on any line carrying the old REASON token, which both old codes contain, so a bare reason in a comment or a template
 * is caught too. Non-vacuity: each replacement code must be found at least once, and a scanned-file floor keeps the walk
 * honest. This file spells the old token in two pieces, so the guard itself never matches it.
 */
class ReleaseCodeSwitchGuardTest {
    @Test
    fun `no tracked file carries the replaced codes and every replacement is present`() {
        val root = repoRoot()
        val scanned = scanTargets(root)
        val violations = mutableListOf<String>()
        val replacements = REPLACEMENTS.associateWith { 0 }.toMutableMap()
        scanned.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().forEachIndexed { index, line ->
                if (OLD_REASON in line) violations += "$relative:${index + 1}: ${line.trim().take(LINE_EXCERPT)}"
                REPLACEMENTS.forEach { code -> if (code in line) replacements.merge(code, 1, Int::plus) }
            }
        }
        println("Release code switch scan: ${scanned.size} files (floor $SCAN_FLOOR); old reason lines ${violations.size}; $replacements")
        if (scanned.size < SCAN_FLOOR) fail("the scan saw only ${scanned.size} files (floor $SCAN_FLOOR) — the walk is broken")
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} line(s) still carry the replaced reason (record §14, no deprecation window):\n" +
                    violations.joinToString("\n"),
            )
        }
        val absent = replacements.filterValues { it == 0 }.keys
        if (absent.isNotEmpty()) fail("non-vacuity: the replacement code(s) $absent appear nowhere in the scan set")
    }

    @Test
    fun `the matcher sees both replaced codes and admits the replacements`() {
        OLD_CODES.forEach { code -> if (OLD_REASON !in code) fail("the matcher cannot see $code") }
        REPLACEMENTS.forEach { code -> if (OLD_REASON in code) fail("the matcher would refuse the replacement $code") }
    }

    private fun scanTargets(root: File): List<File> =
        trackedPaths(root)
            .filter { path ->
                val inScope =
                    (path.startsWith("docs/") && path.count { it == '/' } == 1 && path.endsWith(".md")) ||
                        path == "DEVELOPMENT.md" ||
                        SCOPE_PREFIXES.any(path::startsWith)
                inScope && path.substringAfterLast('/').substringAfterLast('.', "") in EXTENSIONS && "/build/" !in path
            }.map { File(root, it) }
            // a tracked path deleted in the working tree has nothing to read
            .filter { it.isFile && it.length() < MAX_FILE_BYTES }

    /** The index's paths, NUL-separated so no file name is quoted or split; a git failure fails the suite. */
    private fun trackedPaths(root: File): List<String> {
        val errors = File.createTempFile("release-code-guard-git", ".err")
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
        /** The old template-only reason, in two pieces so this source never carries it whole. */
        private const val OLD_REASON = "template_not" + "_released"
        private val OLD_CODES = listOf("pipeline.release.$OLD_REASON", "parameter.release.$OLD_REASON")
        private val REPLACEMENTS = listOf("pipeline.release.dependency_not_released", "parameter.release.dependency_not_released")

        private val SCOPE_PREFIXES = listOf("modules/", "tests/", "scripts/")
        private val EXTENSIONS = "kt kts java md html js mjs json yml yaml sql properties toml sh txt xml css".split(' ').toSet()

        /** About two thirds of the scan set at the switch (2,992 tracked text files, 2026-10-24); a broken walk sees far fewer. */
        private const val SCAN_FLOOR = 2_000
        private const val MAX_FILE_BYTES = 4_000_000L
        private const val LINE_EXCERPT = 160
    }
}
