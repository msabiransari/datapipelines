package co.datapipelines.mcp

import java.io.File

/**
 * The entry point the `skillToolsDoc` Gradle task runs (095 §B).
 *
 * `./gradlew :modules:mcp-server:skillToolsDoc` → writes [SkillToolsDoc.PATH]. The committed
 * file is this task's output; `SkillToolsDocDriftTest` is what makes "committed" and
 * "rendered" the same thing.
 *
 * @param args optional single argument: the absolute path to write. The task passes one; run
 *   without it and the repository root is located the way the spec-drift tests locate it.
 */
fun main(args: Array<String>) {
    val target =
        if (args.isNotEmpty()) {
            File(args[0])
        } else {
            File(SpecFiles.root, SkillToolsDoc.PATH)
        }
    target.parentFile.mkdirs()
    target.writeText(SkillToolsDoc.render(realShippedTools()))
    println("skillToolsDoc: wrote ${target.absolutePath} (${target.length()} bytes)")
}
