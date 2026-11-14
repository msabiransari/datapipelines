package co.datapipelines.lake

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Two rules, one per deletion route (record §8b "One remover, one policy"):
 * - only the wrapper declares the FileIO deletion entry point (`deleteFile(s)`); library callers are checked at runtime too;
 * - only the remover's deletion point (`maintenance/remove/LakeObjectDeleter.kt`, brief 958 D9) names an S3 object delete —
 *   `deleteObject`, `deleteObjects`, `DeleteObjectRequest`, `DeleteObjectsRequest`, and any other spelling that contains
 *   `deleteObject` (an async or presigned variant) — in the main sources of EVERY module.
 */
class LakeDeletionArchitectureTest {
    @Test
    fun `production deletion references exist only in the FileIO wrapper`() {
        val root = LakeTestDb.repoFile("modules/lake")
        val sources = sourcesUnder(root)
        (sources.size > MINIMUM_SOURCES) shouldBe true
        val wrapper = sources.single { it.name == "DeferringFileIO.kt" }
        references(wrapper.readText(), DELETION).isNotEmpty() shouldBe true
        val forbidden = forbidden(sources, wrapper, DELETION, root)
        println("(799 deletion inventory) sources=${sources.size}, wrapper=1, forbidden=${forbidden.size}")
        forbidden shouldBe emptyList()
    }

    @Test
    fun `S3 object deletes exist only in the remover's deletion point, across every module's main sources`() {
        val root = LakeTestDb.repoFile("modules")
        val sources = sourcesUnder(root)
        (sources.size > MINIMUM_PRODUCT_SOURCES) shouldBe true
        val deleter = sources.single { it.invariantSeparatorsPath.endsWith(DELETER_PATH) }
        references(deleter.readText(), OBJECT_DELETION).isNotEmpty() shouldBe true
        val forbidden = forbidden(sources, deleter, OBJECT_DELETION, root)
        println("(958 object-delete inventory) sources=${sources.size}, deleter=1, forbidden=${forbidden.size}")
        forbidden shouldBe emptyList()
    }

    @Test
    fun `the source guard sees single bulk unqualified Java and escaped Kotlin deletion references`() {
        listOf("io.deleteFile(key)", "io.deleteFiles(keys)", "deleteFile(key)", "io::deleteFile", "io.`deleteFile`(key)")
            .forEach { references(it, DELETION) shouldBe listOf(1) }
    }

    @Test
    fun `the object-delete guard sees every S3 delete spelling and ignores tagging`() {
        listOf(
            "s3.deleteObject { it.bucket(b).key(k) }",
            "s3.deleteObjects(request)",
            "DeleteObjectRequest.builder()",
            "import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest",
            "s3::deleteObject",
            "s3.`deleteObjects`(request)",
            "async.deleteObjectsAsync(request)",
            "presigner.presignDeleteObject(request)",
        ).forEach { references(it, OBJECT_DELETION) shouldBe listOf(1) }
        references("s3.deleteObjectTagging(request)", OBJECT_DELETION) shouldBe emptyList()
    }

    @Test
    fun `a planted object delete in another main source is found, and the deletion point itself is allowed`(
        @TempDir dir: File,
    ) {
        val deleter = File(dir, "lake/src/main/kotlin/$DELETER_PATH").apply { parentFile.mkdirs() }
        deleter.writeText("class LakeObjectDeleter { fun d() = s3.deleteObjects(request) }\n")
        val other = File(dir, "web/src/main/kotlin/Cleanup.kt").apply { parentFile.mkdirs() }
        other.writeText("package x\n\nclass Cleanup {\n    fun go() = s3.deleteObject { it.key(k) }\n}\n")
        val test = File(dir, "web/src/test/kotlin/CleanupTest.kt").apply { parentFile.mkdirs() }
        test.writeText("fun t() = s3.deleteObject { it.key(k) }\n")
        val sources = sourcesUnder(dir)
        forbidden(sources, deleter, OBJECT_DELETION, dir) shouldBe listOf("web/src/main/kotlin/Cleanup.kt:4")
    }

    private fun sourcesUnder(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { it.name != "build" && it.name != ".claude" }
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { "/src/main/" in "/${it.relativeTo(root).invariantSeparatorsPath}" }
            .toList()

    private fun forbidden(
        sources: List<File>,
        allowed: File,
        pattern: Regex,
        root: File,
    ): List<String> =
        sources.filter { it != allowed }.flatMap { file ->
            references(file.readText(), pattern).map { "${file.relativeTo(root).invariantSeparatorsPath}:$it" }
        }

    private fun references(
        source: String,
        pattern: Regex,
    ): List<Int> =
        source
            .lineSequence()
            .mapIndexedNotNull { index, line ->
                (index + 1).takeIf { pattern.containsMatchIn(line) }
            }.toList()

    private companion object {
        const val MINIMUM_SOURCES = 10
        const val MINIMUM_PRODUCT_SOURCES = 500
        const val DELETER_PATH = "co/datapipelines/lake/maintenance/remove/LakeObjectDeleter.kt"
        val DELETION = Regex("\\bdeleteFiles?\\b")

        /** Case-insensitive, unanchored: `deleteObject(s)`, `DeleteObject(s)Request`, `presignDeleteObject`, `…Async`. */
        val OBJECT_DELETION = Regex("(?i)deleteObjects?(?!Tagging)")
    }
}
