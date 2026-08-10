package co.datapipelines.mcp

import java.io.File

/**
 * Locates spec documents relative to the repository root, whichever directory the test task runs
 * from — the same walk-up `auth`'s `RepoFiles` uses for its spec-drift tests (a test source set is
 * not visible across modules, so the helper is duplicated rather than shared).
 */
object SpecFiles {
    const val MCP_SPEC_PATH: String = "docs/mcp-server.md"

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    fun read(relativePath: String): String {
        val file = File(root, relativePath)
        require(file.exists()) { "Expected repo file not found: $relativePath (root=$root)" }
        return file.readText()
    }
}
