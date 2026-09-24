package co.datapipelines.mcp

import co.datapipelines.auth.Permission
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #215 slice (c) — every tool's KDoc says the catalog permission it declares, in the words the
 * catalog uses (`Permission: \`pipeline.read\``), and no KDoc still speaks of the retired key
 * scopes. [McpToolCatalog.Entry.permission] is the truth the dispatcher judges; the KDoc is
 * what a maintainer reads beside the tool, and before this guard 40 of them still said
 * `Scope: …` a slice after scopes were removed.
 *
 * How a tool is resolved to its KDoc: the ONE main source line `name = "<tool>",` (the
 * `McpTools.tool(name = …)` literal, the wire name the server ships), then the class that
 * encloses that line, then the KDoc directly above that class (annotations skipped). That KDoc
 * must name the tool in backticks and carry exactly one `Permission:` line, whose wire name is
 * the catalog entry's. A tool whose name literal appears twice, or whose class has no KDoc,
 * fails naming the tool.
 *
 * Also swept, over every main source of the module: no `Scope:` token at all (the match is the
 * token, so a `Scope:` at the end of a line, with its value wrapped onto the next, is caught
 * too), and every `Permission: \`…\`` anywhere names a real catalog permission.
 *
 * Falsified at birth (the lane's evidence): one tool's KDoc permission changed → red naming the
 * tool; a `Scope:` line planted → red naming the file and line.
 */
class McpToolKdocPermissionTest {
    private val sources: Map<File, List<String>> by lazy {
        mainSourceDir()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associateWith { it.readLines() }
    }

    @Test
    fun `every catalogued tool's KDoc states the catalog permission it declares`() {
        val defects =
            McpToolCatalog.ENTRIES.mapNotNull { entry ->
                val wire = entry.permission.wire
                when (val kdoc = kdocOf(entry.name)) {
                    is Resolution.Failed -> {
                        "${entry.name}: ${kdoc.reason}"
                    }

                    is Resolution.Found -> {
                        val stated = PERMISSION_LINE.findAll(kdoc.text).map { it.groupValues[1] }.toList()
                        when {
                            "`${entry.name}`" !in kdoc.text -> {
                                "${entry.name}: the KDoc of ${kdoc.where} does not name the tool in backticks"
                            }

                            stated != listOf(wire) -> {
                                "${entry.name}: the KDoc of ${kdoc.where} states $stated, the catalog declares [$wire]"
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            }
        println("event=mcp_tool_kdoc_permission.checked tools=${McpToolCatalog.ENTRIES.size} defects=${defects.size}")
        withClue("tools checked (non-vacuity floor)") { McpToolCatalog.ENTRIES.size shouldBeGreaterThanOrEqual MIN_TOOLS }
        withClue("tool KDocs that do not state their catalog permission:\n${defects.joinToString("\n")}") {
            defects.shouldBeEmpty()
        }
    }

    @Test
    fun `no main source still speaks of a key scope, and every stated permission is a catalog permission`() {
        val wires = Permission.entries.map { it.wire }.toSet()
        val scopeLines = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        sources.forEach { (file, lines) ->
            lines.forEachIndexed { i, line ->
                if (SCOPE_TOKEN.containsMatchIn(line)) scopeLines += "${file.name}:${i + 1}: ${line.trim()}"
                PERMISSION_LINE.findAll(line).map { it.groupValues[1] }.filterNot { it in wires }.forEach {
                    unknown += "${file.name}:${i + 1}: `$it` is not a catalog permission"
                }
            }
        }
        withClue("main sources swept (non-vacuity floor)") { sources.size shouldBeGreaterThanOrEqual MIN_FILES }
        withClue("`Scope:` survives in main sources:\n${scopeLines.joinToString("\n")}") { scopeLines.shouldBeEmpty() }
        withClue("KDoc permissions that name no catalog permission:\n${unknown.joinToString("\n")}") { unknown.shouldBeEmpty() }
    }

    private sealed interface Resolution {
        data class Found(
            val where: String,
            val text: String,
        ) : Resolution

        data class Failed(
            val reason: String,
        ) : Resolution
    }

    private fun kdocOf(tool: String): Resolution {
        val literal = "name = \"$tool\","
        val hits = sources.flatMap { (file, lines) -> lines.indices.filter { literal in lines[it] }.map { file to it } }
        if (hits.size != 1) return Resolution.Failed("expected one `$literal` in main sources, found ${hits.size}")
        val (file, nameLine) = hits.single()
        val lines = sources.getValue(file)
        val classLine =
            (nameLine downTo 0).firstOrNull { CLASS_DECLARATION.containsMatchIn(lines[it]) }
                ?: return Resolution.Failed("no class encloses ${file.name}:${nameLine + 1}")
        // The KDoc closes on the first line above the class that is not an annotation, and opens on the nearest `/**`.
        val end = (classLine - 1 downTo 0).firstOrNull { !lines[it].trim().startsWith("@") }?.takeIf { lines[it].trim().endsWith("*/") }
        val start = end?.let { closing -> (closing downTo 0).firstOrNull { lines[it].trim().startsWith("/**") } }
        return if (end == null || start == null) {
            Resolution.Failed("the class at ${file.name}:${classLine + 1} carries no KDoc")
        } else {
            Resolution.Found("${file.name}:${classLine + 1}", lines.subList(start, end + 1).joinToString("\n"))
        }
    }

    private fun mainSourceDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, MAIN_SOURCES).isDirectory) dir = dir.parentFile
        return File(checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds $MAIN_SOURCES" }, MAIN_SOURCES)
    }

    private companion object {
        /** From the repository root; the walk up from the working directory (the module, under Gradle) finds it. */
        const val MAIN_SOURCES = "modules/mcp-server/src/main/kotlin/co/datapipelines/mcp"

        /** 42 tools on the base (7b's `templates_evaluate` included); the floor sits under that. */
        const val MIN_TOOLS = 40

        /** The module's main sources number in the forties; a sweep that found a handful read the wrong tree. */
        const val MIN_FILES = 30

        val PERMISSION_LINE = Regex("""Permission: `([^`]+)`""")
        val SCOPE_TOKEN = Regex("""\bScope:""")
        val CLASS_DECLARATION = Regex("""^\s*(?:(?:internal|private|public|data|open)\s+)*class\s+\w+""")
    }
}
