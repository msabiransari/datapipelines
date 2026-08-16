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
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    /** The real directory's migrations as repo-relative paths, version order. */
    fun paths(): List<String> = migrations(TestFiles.repoDirectory(DIR)).map { "$DIR/${it.second.name}" }

    /** The pure rule over any directory — injectable so the ordering itself is testable. */
    fun migrations(dir: File): List<Pair<Int, File>> =
        dir
            .listFiles { f -> f.isFile }
            .orEmpty()
            .mapNotNull { file ->
                val version =
                    VERSION_PREFIX
                        .find(file.name)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                version?.let { it to file }
            }.sortedBy { (version, _) -> version }
}

/** A base64-encoded 32-byte AES key usable across tests. */
internal fun test32ByteKeyBase64(): String =
    java.util.Base64
        .getEncoder()
        .encodeToString(ByteArray(32) { it.toByte() })
