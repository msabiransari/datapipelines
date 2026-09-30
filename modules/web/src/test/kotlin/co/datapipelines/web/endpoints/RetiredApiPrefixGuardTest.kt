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
 * The ONE place it may still appear is the V11 migration: Flyway validates an applied
 * migration's checksum against every existing deployment, so it cannot be edited, and its
 * comments describe the schema as of V11. That file is the whole allowlist; a hit anywhere else
 * fails the build, so the next "just a comment" mention of the old root is a red build, not a
 * silent reintroduction. (Until 2026-09-30 the published-endpoints design doc was exempt too;
 * its title, §1 example, R-EP1 row and §5 heading now state R-EP5's shape, and it is swept.)
 */
class RetiredApiPrefixGuardTest {
    @Test
    fun `no mention of the retired prefix survives outside the immutable V11 migration`() {
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
         * The ONE file exempt: an applied Flyway migration is immutable — Flyway validates its
         * checksum on every existing deployment, so a changed comment would stop them booting —
         * and V11's comments describe the schema as of V11. The published-endpoints design doc
         * was exempt until 2026-09-30, when its title, §1 example, R-EP1 row and §5 heading were
         * rewritten to R-EP5's shape; the guard covers it now.
         */
        val ALLOWLIST =
            setOf(
                "modules/app/src/main/resources/db/migration/V11__published_endpoints.sql",
            )

        /** A floor well under today's count: a sweep that lost a tree must fail, not pass. */
        const val MIN_FILES = 400
    }
}
