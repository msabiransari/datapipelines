package co.datapipelines.mcp

import java.io.File

/**
 * Locates spec documents relative to the repository root, whichever directory the test task runs
 * from — the same walk-up `auth`'s `RepoFiles` uses for its spec-drift tests (a test source set is
 * not visible across modules, so the helper is duplicated rather than shared).
 */
object SpecFiles {
    const val MCP_SPEC_PATH: String = "docs/mcp-server.md"

    /**
     * The served manual's narrative core (242a): a packaged resource of `mcp-server`, read in
     * the repo tree. The SERVED text is its body — front matter stripped, placeholders
     * substituted by [co.datapipelines.mcp.docs.DocRenderer].
     */
    const val SKILL_CORE_RESOURCE: String = "modules/mcp-server/src/main/resources/skill/core.md"

    /** The narrative resources' directory in the repo tree (242a; the golden expectations mirror it). */
    const val SKILL_RESOURCES: String = "modules/mcp-server/src/main/resources/skill"

    /** Public since 095: the doc renderer and the packaging guards address files by repo path too. */
    val root: File by lazy {
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
