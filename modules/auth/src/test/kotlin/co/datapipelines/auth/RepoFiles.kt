package co.datapipelines.auth

import java.io.File

/**
 * Locates files relative to the repository root, regardless of which directory the
 * test task runs from. Used to execute app's real DDL directly in the integration
 * suites (domain modules never carry a Flyway dependency) and to read spec docs for
 * the spec-drift tests.
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

    /**
     * EVERY migration in the shipped directory, in version order — derived, never
     * hand-pinned (T3/D17). The discipline is the sibling modules' `ShippedMigrations`
     * (templates, pipeline-contract, datasources): a literal list goes stale the moment
     * a migration lands elsewhere (auth's V1/V4/V5 pins predated V6–V8 and drifted
     * silently), while "the suites would silently run a stale schema while production
     * Flyway applies it" is exactly the failure the derivation kills. Auth's suites
     * only read their own tables, so the extra DDL is inert — and a future auth-facing
     * migration reaches them the day it lands.
     */
    val MIGRATION_PATHS: List<String> =
        ShippedMigrations.paths()

    const val AUTH_SPEC_PATH = "docs/auth.md"
    const val PIPELINE_CONTRACT_PATH = "docs/pipeline-contract.md"
}

/**
 * The shipped migration directory as a version-ordered path list — the fourth sibling
 * of templates' / pipeline-contract's / datasources' `ShippedMigrations` (kept
 * per-module: test fixtures do not cross module boundaries). The grammar is enforced
 * rather than filtered: a `.sql` file that does not match `V<version>__<desc>.sql`
 * throws NAMING THE FILE instead of being silently skipped.
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    /** The real directory's migrations as repo-relative paths, version order. */
    fun paths(): List<String> = migrations(RepoFiles.file(DIR)).map { "$DIR/${it.second.name}" }

    /** The pure rule over any directory — injectable so the ordering itself is testable. */
    fun migrations(dir: File): List<Pair<Int, File>> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file -> versionOf(file) to file }
            .sortedBy { (version, _) -> version }

    /** The parsed version of one `.sql` file; anything else fails loud, naming the file. */
    private fun versionOf(file: File): Int {
        val match =
            requireNotNull(VERSION_PREFIX.find(file.name)) {
                "'${file.name}' does not match the accepted migration grammar V<version>__<description>.sql — the " +
                    "suites would silently run a stale schema while production Flyway applies it. Either " +
                    "rename it to the grammar or widen ShippedMigrations deliberately, applying suites included."
            }
        return requireNotNull(match.groupValues[1].toIntOrNull()) {
            "'${file.name}' carries a version number that does not fit an Int — widen ShippedMigrations deliberately."
        }
    }
}
