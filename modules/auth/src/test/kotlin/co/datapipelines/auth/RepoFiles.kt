package co.datapipelines.auth

import java.io.File

/**
 * Locates files relative to the repository root, regardless of which directory the
 * test task runs from. Used to read the shipped `V1__initial_schema.sql` off disk
 * (the same discipline as the sibling `PipelineRepositoryIntegrationTest`: domain
 * modules never carry a Flyway dependency, so they execute app's real DDL directly)
 * and to read spec docs for the spec-drift tests.
 */
object RepoFiles {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    fun file(relativePath: String): File =
        File(root, relativePath).also {
            require(it.exists()) { "Expected repo file not found: $relativePath (root=$root)" }
        }

    fun read(relativePath: String): String = file(relativePath).readText()

    const val MIGRATION_PATH = "modules/app/src/main/resources/db/migration/V1__initial_schema.sql"
    const val AUTH_SPEC_PATH = "docs/auth.md"
    const val PIPELINE_CONTRACT_PATH = "docs/pipeline-contract.md"
}
