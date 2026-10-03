package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No main source under `modules/web` builds a refusal reason that echoes the caller's input back —
 * the `(got '<raw>')` shape the pipeline and dashboard `?version=` parsers carried until #421.
 * The reason reaches a reader through the htmx toast and the exception message (logs, tests), so
 * an echoed value is reflected caller-controlled text; the one parse is [RequestedVersion], whose
 * reason is a constant.
 *
 * This is the guard for the COPY: the templates workspace (398) was briefed to copy the pipeline
 * parser, and a parser copied from any older family goes red here at its merge instead of
 * re-opening the finding. It reads sources as TEXT and the needle is assembled, never written,
 * so the guard does not flag itself. Its reach is the spelling `(got '` — the house shape for
 * this class of echo; a differently-spelled echo needs its own test, as
 * [RequestedVersionTest] gives the `?version=` family.
 */
class VersionReasonEchoGuardTest {
    @Test
    fun `no main source echoes its input into a refusal reason`() {
        val violations =
            mainSources().flatMap { file ->
                file.readText().lines().mapIndexedNotNull { index, line ->
                    if (ECHO_NEEDLE in line) "${file.relativeTo(repoRoot()).invariantSeparatorsPath}:${index + 1}" else null
                }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `the sweep is not vacuous - it sees the parsers it guards`() {
        val names = mainSources().map { it.name }.toSet()
        check(mainSources().size >= MIN_FILES) { "the sweep found only ${mainSources().size} files - it would pass by checking nothing" }
        check("RequestedVersion.kt" in names) { "the shared parser is not in the sweep" }
        check("VisualizationWorkspaceModel.kt" in names) { "the visualizations parser is not in the sweep" }
        check("PipelineWorkspaceModel.kt" in names) { "the pipeline parser is not in the sweep" }
    }

    private fun mainSources(): List<File> =
        File(repoRoot(), MAIN_SOURCES)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, MAIN_SOURCES).isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds $MAIN_SOURCES" }
    }

    private companion object {
        const val MAIN_SOURCES = "modules/web/src/main"

        /** Assembled, never written: the sweep reads sources as text and would flag this file. */
        const val ECHO_NEEDLE = "(got " + "'"

        /** A floor well under today's count: a sweep that lost the tree must fail, not pass. */
        const val MIN_FILES = 100
    }
}
