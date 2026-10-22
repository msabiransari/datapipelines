package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * #652 — the shell partition over the web module's sources: the four shell advices select
 * by [ShellPage] (`assignableTypes = [ShellPage::class]`), so the class headers must carry
 * the partition the selection relies on. Every class-level `@Controller` either implements
 * [ShellPage] in its header or is one of the NAMED exceptions, each with a source-checkable
 * reason; every `@RestController` stays outside [ShellPage]; the partition is non-vacuous.
 *
 * Shape follows the source-vocabulary guards: a source walk with a file floor, a violation
 * list that names file and line, and a matcher self-check. Falsified at birth (F2/F3 of the
 * lane): removing `: ShellPage` from one page controller, or planting a bare `@Controller`
 * without it, turns this red naming the class.
 *
 * Annotations are matched by OPTIONAL PACKAGE PREFIX (`@(?:[\w.]+\.)?Controller\b`) — a
 * fully-qualified `@org.springframework.stereotype.Controller` compiles, works, and must
 * not slip past the guard.
 */
class ShellPageGuardTest {
    @Test
    fun `the walk includes constructorless controllers and qualified handler mappings`(
        @TempDir directory: Path,
    ) {
        val source = directory.resolve("Controllers.kt").toFile()
        source.writeText(
            """
            @Controller
            class PageController : ShellPage {
            }
            @org.springframework.web.bind.annotation.RestController
            class RestController : ShellPage {
            }
            @Controller
            class SkillController {
                @GetMapping("/text")
                @ResponseBody
                fun text(): String = "text"
                @org.springframework.web.bind.annotation.RequestMapping("/page")
                fun page(): String = "page"
            }
            """.trimIndent(),
        )
        val controllers = controllersIn(source, directory.toFile())
        controllers.map { it.name } shouldBe listOf("PageController", "RestController", "SkillController")
        controllers.single { it.name == "PageController" }.implementsShellPage shouldBe true
        controllers.single { it.kind == Kind.REST_CONTROLLER }.implementsShellPage shouldBe true
        val violations = mutableListOf<String>()
        assertReason(controllers.single { it.name == "SkillController" }, violations)
        violations.size shouldBe 1
    }

    @Test
    fun `every bare @Controller declares ShellPage or a named exception`() {
        val violations = mutableListOf<String>()
        for (candidate in sources().controllers.filter { it.kind == Kind.BARE_CONTROLLER }) {
            if (candidate.implementsShellPage) continue
            val reason = EXCEPTIONS[candidate.name]
            if (reason == null) {
                violations += "${candidate.file}:${candidate.line}: ${candidate.name} is a bare @Controller " +
                    "without ShellPage — either add `: ShellPage` (a page controller) or list the exception here, with its reason"
            } else {
                assertReason(candidate, violations)
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} unclassified @Controller header(s); every page controller implements " +
                    "ShellPage, every exception is listed with its reason:\n" + violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `every @RestController stays outside the shell partition`() {
        sources()
            .controllers
            .filter { it.kind == Kind.REST_CONTROLLER }
            .filter { it.implementsShellPage }
            .map { "${it.file}:${it.line}: ${it.name} is a @RestController and must never implement ShellPage" }
            .shouldBeEmpty()
    }

    @Test
    fun `the partition is non-vacuous`() {
        val seen = sources()
        seen.fileCount.shouldBeGreaterThanOrEqual(FILE_FLOOR)
        val controllers = seen.controllers
        controllers.size.shouldBeGreaterThanOrEqual(CONTROLLER_FLOOR)
        val implementors = controllers.count { it.implementsShellPage }
        println(
            "ShellPage partition: $implementors implementors (floor $IMPLEMENTOR_FLOOR) over " +
                "${controllers.size} controller classes; walk saw ${seen.fileCount} source files",
        )
        if (implementors < IMPLEMENTOR_FLOOR) {
            fail("only $implementors ShellPage implementors (floor $IMPLEMENTOR_FLOOR) — the marking regressed")
        }
    }

    private fun assertReason(
        candidate: ControllerHeader,
        violations: MutableList<String>,
    ) {
        val lines = candidate.sourceLines
        when (candidate.name) {
            "SkillController", "SitemapController", "LlmsTxtController" -> {
                assertEveryHandlerIsResponseBody(candidate, lines, violations)
            }

            "VisualizationPreviewController" -> {
                if (lines.none { it.contains("model.addAttribute(\"activeTheme\"") }) {
                    violations += "${candidate.file}:${candidate.line}: ${candidate.name} claims the own-theme " +
                        "exception but no longer self-supplies `activeTheme` — re-derive the exception"
                }
            }

            else -> {
                violations += "${candidate.file}:${candidate.line}: ${candidate.name} listed as an exception " +
                    "without a source check — every exception carries its own assertion"
            }
        }
    }

    /** A text controller's every handler mapping is followed by `@ResponseBody` within three lines. */
    private fun assertEveryHandlerIsResponseBody(
        candidate: ControllerHeader,
        lines: List<String>,
        violations: MutableList<String>,
    ) {
        var handlers = 0
        lines.forEachIndexed { index, line ->
            if (!MAPPING.containsMatchIn(line)) return@forEachIndexed
            handlers++
            val following = lines.drop(index + 1).take(3).map { it.trim() }
            if (following.none { it == "@ResponseBody" }) {
                violations += "${candidate.file}:${index + 1}: ${candidate.name} claims the text-only exception " +
                    "but a handler mapping has no @ResponseBody within three lines — re-derive the exception"
            }
        }
        if (handlers == 0) {
            violations += "${candidate.file}:${candidate.line}: ${candidate.name} claims the text-only " +
                "exception but no handler mapping was seen — the walk is broken"
        }
    }

    private enum class Kind { BARE_CONTROLLER, REST_CONTROLLER }

    private class ControllerHeader(
        val file: String,
        val line: Int,
        val name: String,
        val kind: Kind,
        val implementsShellPage: Boolean,
        val sourceLines: List<String>,
    )

    private class Sources(
        val fileCount: Int,
        val controllers: List<ControllerHeader>,
    )

    private fun sources(): Sources {
        val root = root()
        val files =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        return Sources(files.size, files.flatMap { controllersIn(it, root) })
    }

    /**
     * Column-0 class declarations; annotation lines accumulate from the previous
     * declaration's end and are consumed by the next one. The window is scoped PER FILE —
     * a file's trailing method annotations must never prefix the next file's first class —
     * and the walk is sorted, so the violation list is deterministic. A KDoc `@Controller`
     * mention never starts a line with `@`, so prose cannot enter the window.
     */
    private fun controllersIn(
        file: File,
        root: File,
    ): List<ControllerHeader> {
        val found = mutableListOf<ControllerHeader>()
        val lines = file.readLines()
        var pending = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimEnd()
            if (trimmed.startsWith("@")) {
                pending += trimmed
                return@forEachIndexed
            }
            val match = CLASS_HEADER.find(trimmed) ?: return@forEachIndexed
            val window = pending.joinToString("\n")
            val kind =
                when {
                    REST_ANNOTATION.containsMatchIn(window) -> Kind.REST_CONTROLLER
                    CONTROLLER_ANNOTATION.containsMatchIn(window) -> Kind.BARE_CONTROLLER
                    else -> null
                }
            if (kind != null) {
                found +=
                    ControllerHeader(
                        file = file.relativeTo(root).invariantSeparatorsPath,
                        line = index + 1,
                        name = match.groupValues[1],
                        kind = kind,
                        implementsShellPage = headerText(lines, index).contains(": ShellPage"),
                        sourceLines = lines,
                    )
            }
            pending = mutableListOf()
        }
        return found
    }

    /** The class header text: from the class line through the primary constructor's closing paren. */
    private fun headerText(
        lines: List<String>,
        start: Int,
    ): String {
        val out = StringBuilder(lines[start])
        var depth = lines[start].count { it == '(' } - lines[start].count { it == ')' }
        var j = start + 1
        while (depth > 0 && j < lines.size) {
            out.append('\n').append(lines[j])
            depth += lines[j].count { it == '(' } - lines[j].count { it == ')' }
            j++
        }
        return out.toString()
    }

    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
            .resolve("modules/web/src/main/kotlin")
    }

    private companion object {
        private val CLASS_HEADER = Regex("""^(?:(?:public|internal|abstract|open|final)\s+)*class\s+(\w+)\b""")
        private val MAPPING = Regex("""@(?:[\w.]+\.)?(?:Request|Get|Post|Put|Delete|Patch)Mapping\b""")
        private val CONTROLLER_ANNOTATION = Regex("""@(?:[\w.]+\.)?Controller\b""")
        private val REST_ANNOTATION = Regex("""@(?:[\w.]+\.)?RestController\b""")

        /** The walk at the lane's base saw 299 .kt files; the walk is broken below this. */
        private const val FILE_FLOOR = 250

        /** 84 web controller classes (58 @Controller + 26 @RestController) at the lane's tip. */
        private const val CONTROLLER_FLOOR = 80

        /** 54 page controllers implement ShellPage at the lane's tip; a floor catches mass unmarking. */
        private const val IMPLEMENTOR_FLOOR = 50

        /** The named exceptions, each with the reason its source check asserts. */
        private val EXCEPTIONS =
            mapOf(
                "SkillController" to "text-only: every handler @ResponseBody",
                "SitemapController" to "text-only: every handler @ResponseBody",
                "LlmsTxtController" to "text-only: every handler @ResponseBody",
                "VisualizationPreviewController" to "standalone page: session-less, capability-authenticated, self-supplied activeTheme",
            )
    }
}
