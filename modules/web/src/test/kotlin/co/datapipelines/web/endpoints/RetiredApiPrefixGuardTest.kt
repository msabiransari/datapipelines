package co.datapipelines.web.endpoints

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The old published-endpoint root is retired (172, ruling R-EP5): endpoints serve at
 * `/api/<category>/<version>/<path…>`, and the retired prefix may not survive anywhere a
 * reader would trust — main sources, docs, the site templates, or the shipped skill.
 *
 * (The retired spelling is assembled, never written, in this file: the sweep reads sources as
 * TEXT, and a guard that contained the string it hunts would flag itself.)
 *
 * The two places it may still appear are both HISTORY: the published-endpoints design doc
 * (R-EP1 is recorded there as superseded, and rewriting a ratified decision's wording would
 * falsify the record) and the V11 migration (Flyway validates its checksum against every
 * existing deployment — it cannot be edited). Those two files are the whole allowlist; a hit
 * anywhere else fails the build, so the next "just a comment" mention of the old root is a red
 * build, not a silent reintroduction.
 */
class RetiredApiPrefixGuardTest {
    @Test
    fun `no mention of the retired prefix survives outside the design doc's history`() {
        val violations =
            sweptFiles().flatMap { file ->
                file.readText().lines().mapIndexedNotNull { index, line ->
                    if (RETIRED_PREFIX in line) "${file.relativeTo(repoRoot())}:${index + 1}" else null
                }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `the sweep is not vacuous — it sees sources, docs, templates and the skill`() {
        val files = sweptFiles()
        check(files.size >= MIN_FILES) { "the sweep found only ${files.size} files — it would pass by checking nothing" }
        check(files.any { it.extension == "kt" }) { "no Kotlin sources in the sweep" }
        check(files.any { it.extension == "html" }) { "no templates in the sweep" }
        check(files.any { it.path.contains("/docs/") }) { "no docs in the sweep" }
        check(files.any { it.path.contains("/resources/skill/") }) { "the served manual's resources are not in the sweep" }
    }

    private fun sweptFiles(): List<File> {
        val root = repoRoot()
        return SWEPT_DIRS
            .flatMap { dir ->
                File(root, dir)
                    .walkTopDown()
                    .filter { it.isFile && it.extension in SWEPT_EXTENSIONS }
                    .filterNot { "build" in it.relativeTo(root).invariantSeparatorsPath.split("/") }
                    .toList()
            }.filterNot { it.relativeTo(root).invariantSeparatorsPath in ALLOWLIST }
            .sortedBy { it.path }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "modules/web").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds modules/web" }
    }

    private companion object {
        const val RETIRED_PREFIX = "/api/" + "x"

        /**
         * Everything that states or serves a URL: code, docs, templates, config, the manual
         * (the resources under modules/mcp-server since 242a, no longer .agents/skills).
         */
        val SWEPT_DIRS = listOf("modules", "docs", "deploy", "scripts", "tests", "plugins")
        val SWEPT_EXTENSIONS = setOf("kt", "kts", "md", "html", "yml", "yaml", "sql", "sh")

        /**
         * The design doc's history rows: R-EP1's original wording and the change record of its
         * supersession. Not packaged into the product (`docs/superpowers/` is excluded), and the
         * ONLY file exempt.
         */
        val ALLOWLIST =
            setOf(
                // R-EP1's original wording and the record of its supersession — a ratified
                // design doc's history is not rewritten.
                "docs/superpowers/specs/2026-09-05-published-endpoints-design.md",
                // A Flyway-migrated file is immutable (its checksum is validated against every
                // existing deployment); its comments describe the schema as of V11.
                "modules/app/src/main/resources/db/migration/V11__published_endpoints.sql",
            )

        /** A floor well under today's count: a sweep that lost a tree must fail, not pass. */
        const val MIN_FILES = 400
    }
}
