package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

/**
 * The first-party client runtime and its adapters, versioned in the vendor manifest with their sha256 like every
 * vendored asset (the implementation spec's §10.1 convention, #10 L3a): their hashes are recomputed from the served
 * bytes, so a hand edit of a shipped file must move the manifest's `datapipelines-dashboard-runtime` block in the same
 * commit. Moved here from the vendored library's audit (#987 D9), which keeps only the library's own bytes.
 *
 * The block's file list is read whole and compared with [FILES]: a file added to or dropped from the runtime moves
 * both, so the pin can neither miss a shipped file nor keep one that no longer ships.
 */
class FirstPartyRuntimeAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val manifest: String =
        resolver
            .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
            .inputStream
            .readBytes()
            .decodeToString()

    private fun classpathBytes(path: String): ByteArray = resolver.getResource("classpath:static/$path").inputStream.readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** The runtime's block: from its key to the first closing brace that ends it at the block's own depth. */
    private fun block(): String {
        val start =
            Regex("\"$BLOCK\"\\s*:\\s*\\{").find(manifest)?.range?.last
                ?: error("the $BLOCK block is missing from the vendor manifest — the audit ran vacuously")
        var depth = 0
        for (end in start until manifest.length) {
            when (manifest[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return manifest.substring(start, end + 1)
            }
        }
        error("the $BLOCK block never closes")
    }

    @Test
    fun `the manifest's first-party file list is the runtime and its adapters, whole and in order`() {
        val files =
            Regex("\"files\"\\s*:\\s*\\[([^]]*)]").find(block())?.groupValues?.get(1)
                ?: error("the $BLOCK block has no files list")
        Regex("\"([^\"]+)\"").findAll(files).map { it.groupValues[1] }.toList() shouldBe FILES
    }

    @Test
    fun `the first-party runtime and adapters match the hashes the manifest records`() {
        val firstParty = block()
        FILES.forEach { file ->
            val declared = Regex("\"$file\"\\s*:\\s*\"([0-9a-f]{64})\"").find(firstParty)?.groupValues?.get(1)
            withClue("$file must be hash-pinned in the manifest (a PENDING placeholder is not a hash)") {
                (declared != null && !declared.startsWith("PENDING")) shouldBe true
            }
            withClue("$file: the served bytes' sha256 is the manifest's") { sha256(classpathBytes(file)) shouldBe declared }
        }
    }

    private companion object {
        const val BLOCK = "datapipelines-dashboard-runtime"
        val FILES =
            listOf(
                "js/datapipelines-dashboard.js",
                "js/datapipelines-dashboard-chart.js",
                "js/datapipelines-dashboard-table.js",
                "js/datapipelines-dashboard-kpi.js",
            )
    }
}
