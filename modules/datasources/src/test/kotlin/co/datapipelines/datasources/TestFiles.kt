package co.datapipelines.datasources

import java.io.File

/**
 * Locates a repository file by walking up from the working directory, so a test does not encode
 * how deep this module sits in the tree — the pattern `Fixtures.repoFile` established in
 * `pipeline-contract` and `ColumnSchemaSpecDriftTest` in `typesystem`.
 */
internal object TestFiles {
    fun repoFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }

    fun repoDirectory(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }
}

/**
 * The shipped Flyway migrations, in **version order** (numeric: V10 after V2), as
 * repo-relative paths ready for [TestFiles.repoFile]. Flyway and the scripts live in `app`
 * alone (module-structure §3.1 rule 2), so integration tests that need the shipped schema
 * apply them through plain JDBC — always through THIS list, never a hand-copied one: a
 * migration added to the directory but not to a hand-copied list runs that suite against a
 * stale schema (R4 F5 — the two lists had already drifted twice into duplication).
 *
 * ## Accepted grammar — everything else FAILS LOUD (R5 F4)
 *
 * `V<version>__<description>.sql` with a version that fits an [Int]. Any OTHER `.sql` file in
 * the directory — Flyway-legal repeatable `R__*.sql`, sub-versioned `V2_1__*.sql`, an
 * overflowing version — throws [IllegalArgumentException] NAMING THE FILE. The helper exists
 * to kill stale-schema drift, and a silent `mapNotNull` exclusion would relocate that drift
 * inside the guard: production Flyway would apply what the integration suites silently omit.
 * If a shape beyond `V<int>__` should ever be accepted (repeatables, sub-versions), widening
 * this grammar is a DELIBERATE change to make here and in the applying suites — not a
 * side effect of adding a file. Non-`.sql` files (notes, READMEs) stay ignored.
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    /** The real directory's migrations as repo-relative paths, version order. */
    fun paths(): List<String> = migrations(TestFiles.repoDirectory(DIR)).map { "$DIR/${it.second.name}" }

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
                    "suites would silently run a stale schema while production Flyway applies it (R5 F4). Either " +
                    "rename it to the grammar or widen ShippedMigrations deliberately, applying suites included."
            }
        return requireNotNull(match.groupValues[1].toIntOrNull()) {
            "'${file.name}' carries a version number that does not fit an Int — widen ShippedMigrations deliberately."
        }
    }
}

/** A base64-encoded 32-byte AES key usable across tests. */
internal fun test32ByteKeyBase64(): String =
    java.util.Base64
        .getEncoder()
        .encodeToString(ByteArray(32) { it.toByte() })
