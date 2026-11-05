package co.datapipelines.lake

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Only the wrapper declares the FileIO deletion entry point; library callers are checked at runtime too. */
class LakeDeletionArchitectureTest {
    @Test
    fun `production deletion references exist only in the FileIO wrapper`() {
        val root = LakeTestDb.repoFile("modules/lake/src/main")
        val sources = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        (sources.size > MINIMUM_SOURCES) shouldBe true
        val wrapper = sources.single { it.name == "DeferringFileIO.kt" }
        deletionReferences(wrapper.readText()).isNotEmpty() shouldBe true
        val forbidden =
            sources.filter { it != wrapper }.flatMap { file ->
                deletionReferences(file.readText()).map { "${file.relativeTo(root)}:$it" }
            }
        println("(799 deletion inventory) sources=${sources.size}, wrapper=1, forbidden=${forbidden.size}")
        forbidden shouldBe emptyList()
    }

    @Test
    fun `the source guard sees single bulk unqualified Java and escaped Kotlin deletion references`() {
        listOf("io.deleteFile(key)", "io.deleteFiles(keys)", "deleteFile(key)", "io::deleteFile", "io.`deleteFile`(key)")
            .forEach { deletionReferences(it) shouldBe listOf(1) }
    }

    private fun deletionReferences(source: String): List<Int> =
        source
            .lineSequence()
            .mapIndexedNotNull { index, line ->
                (index + 1).takeIf { DELETION.containsMatchIn(line) }
            }.toList()

    private companion object {
        const val MINIMUM_SOURCES = 10
        val DELETION = Regex("\\bdeleteFiles?\\b")
    }
}
