import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/** Offline fixtures exercise coverage refusals without network or Gradle resolution. */
class VendoredAssetNoticesTest {
    @TempDir
    lateinit var directory: File

    private val texts =
        mapOf(
            "MIT" to "Copyright 2026 Example\nPermission is hereby granted, free of charge",
            "ISC" to "Permission to use, copy, modify, and/or distribute\nprovided that the above copyright notice",
            "0BSD" to "Permission to use, copy, modify, and/or distribute",
            "OFL-1.1" to "SIL OPEN FONT LICENSE Version 1.1",
        )

    @Test
    fun `missing license_file names its block`() {
        failure(block("dagre", "MIT", file = false), "Missing license_file for vendored block dagre")
    }

    @Test
    fun `missing licence names its block`() {
        failure(block("dagre", "MIT").replace("\"license\":\"MIT\",", ""), "Missing license for vendored block dagre")
    }

    @Test
    fun `unknown id names its block`() {
        failure(block("dagre", "Mystery"), "Unknown licence Mystery for vendored block dagre")
    }

    @Test
    fun `missing and blank files fail closed`() {
        listOf<String?>(null, " \r\n").forEach { text ->
            val error =
                assertThrows(IllegalStateException::class.java) {
                    VendoredAssetNotices.render("{${block("dagre", "MIT", text = text.orEmpty())}}", emptySet()) { text?.toByteArray() }
                }
            assertTrue(error.message.orEmpty().contains("Missing or blank licence text for vendored block dagre"))
        }
    }

    @Test
    fun `wrong text for each claimed id names the block`() {
        texts.keys.forEach { id ->
            val error =
                assertThrows(IllegalStateException::class.java) {
                    VendoredAssetNotices.render(
                        "{${block("dagre", id, text = "unrelated licence")}}",
                        emptySet(),
                    ) { "unrelated licence".toByteArray() }
                }
            assertTrue(error.message.orEmpty().contains("Wrong licence text for vendored block dagre: expected $id"))
        }
    }

    @Test
    fun `ISC additionally requires the copyright condition`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                VendoredAssetNotices.render("{${block("icons", "ISC", text = texts.getValue("0BSD"))}}", emptySet()) {
                    texts.getValue("0BSD").toByteArray()
                }
            }
        assertTrue(error.message.orEmpty().contains("Wrong licence text for vendored block icons"))
    }

    @Test
    fun `unused allowance fails as STALE`() {
        failure(block("dagre", "MIT"), "STALE vendored licence allowlist entries (matched no block): [0BSD, ISC, OFL-1.1]")
    }

    @Test
    fun `first-party blocks need no licence file and output is sorted stable and verbatim`() {
        val blocks = texts.keys.mapIndexed { index, id -> block("asset-$index", id) }
        val first = render((blocks.reversed() + "\"own\":{}").joinToString(","), setOf("own"))
        val second = render((blocks + "\"own\":{}").joinToString(","), setOf("own"))
        assertEquals(first.toByteArray().toList(), second.toByteArray().toList())
        assertTrue(first.indexOf("asset-0 —") < first.indexOf("asset-3 —"))
        assertTrue(first.indexOf("===== asset-0") < first.indexOf("===== asset-3"))
        assertTrue(first.contains("Own code (no third-party licence requirement): own"))
        assertFalse(first.contains("===== own"))
        texts.forEach { (id, text) ->
            val index = texts.keys.indexOf(id)
            assertTrue(first.contains("===== asset-$index ($id) =====\n$text\n"))
        }
        assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(first.substringBefore("\n\n")))
    }

    @Test
    fun `wrapping and upstream case do not change the reproduced text`() {
        val wrapped = "sil open font license\r\nVersion 1.1"
        val blocks =
            texts.keys
                .mapIndexed { i, id ->
                    val text = if (id == "OFL-1.1") wrapped else texts.getValue(id)
                    block("asset-$i", id, text = text)
                }.joinToString(",")
        val output =
            VendoredAssetNotices.render("{$blocks}", emptySet()) {
                (if (it.endsWith("OFL-1.1")) wrapped else texts[it.removePrefix("vendor/")])?.toByteArray()
            }
        assertTrue(output.contains("sil open font license\nVersion 1.1\n"))
    }

    @Test
    fun `stale first-party names fail rather than silently dropping inventory`() {
        val error =
            assertThrows(IllegalStateException::class.java) {
                VendoredAssetNotices.render("{}", setOf("gone")) { null }
            }
        assertEquals("STALE first-party blocks: [gone]", error.message)
    }

    @Test
    fun `licence paths must stay in vendor`() {
        listOf("/etc/passwd", "vendor/../outside", "vendor/./LICENSE", "vendor//LICENSE").forEach { path ->
            failure(block("dagre", "MIT").replace("vendor/MIT", path), "Invalid license_file for vendored block dagre")
        }
    }

    @Test
    fun `missing checksum names its block`() {
        val original = block("dagre", "MIT")
        val digest = sha256(directory.resolve("MIT").readBytes())
        val blocks = original.replace("\"license_file_sha256\":\"$digest\",", "")
        failure(blocks, "Missing license_file_sha256 for vendored block dagre")
    }

    @Test
    fun `copyright-only edit fails with block path and both digests`() {
        val blocks = block("dagre", "MIT")
        val file = directory.resolve("MIT")
        val original = file.readBytes()
        file.writeText(file.readText().replace("2026", "2027"))
        failure(
            blocks,
            "Licence checksum mismatch for vendored block dagre: vendor/MIT " +
                "expected ${sha256(original)} actual ${sha256(file.readBytes())}",
        )
    }

    @Test
    fun `restoring original bytes after copyright edit passes`() {
        val blocks = texts.keys.mapIndexed { index, id -> block("asset-$index", id) }.joinToString(",")
        val file = directory.resolve("MIT")
        val original = file.readBytes()
        file.writeText(file.readText().replace("2026", "2027"))
        failure(blocks, "Licence checksum mismatch for vendored block asset-0: vendor/MIT")
        file.writeBytes(original)
        assertTrue(render(blocks).contains(texts.getValue("MIT")))
    }

    @Test
    fun `uppercase declaration of correct checksum fails`() {
        val blocks = block("dagre", "MIT")
        val digest = sha256(directory.resolve("MIT").readBytes())
        assertFalse(digest == digest.uppercase())
        failure(blocks.replace(digest, digest.uppercase()), "Licence checksum mismatch for vendored block dagre: vendor/MIT")
    }

    private fun failure(
        blocks: String,
        message: String,
    ) {
        val error = assertThrows(IllegalStateException::class.java) { render(blocks) }
        assertTrue(error.message.orEmpty().contains(message), error.message)
    }

    private fun render(
        blocks: String,
        own: Set<String> = emptySet(),
    ): String = VendoredAssetNotices.render("{$blocks}", own) { path -> directory.resolve(path.removePrefix("vendor/")).readBytes() }

    private fun block(
        name: String,
        id: String,
        file: Boolean = true,
        text: String = "  ${texts[id].orEmpty()}\r\n",
    ): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        directory.resolve(id).writeBytes(bytes)
        return "\"$name\":{\"license\":\"$id\"," +
            (if (file) "\"license_file\":\"vendor/$id\"," else "") +
            "\"license_file_sha256\":\"${sha256(bytes)}\"," +
            "\"version\":\"1\",\"source\":\"https://example.test/$name\"}"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
