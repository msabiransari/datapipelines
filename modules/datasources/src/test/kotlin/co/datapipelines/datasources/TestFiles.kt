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
}

/** A base64-encoded 32-byte AES key usable across tests. */
internal fun test32ByteKeyBase64(): String =
    java.util.Base64
        .getEncoder()
        .encodeToString(ByteArray(32) { it.toByte() })
