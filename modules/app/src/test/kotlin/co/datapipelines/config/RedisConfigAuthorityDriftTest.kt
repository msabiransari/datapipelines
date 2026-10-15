package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Prevents production, deployment and test configuration from restoring a second Redis authority. */
class RedisConfigAuthorityDriftTest {
    @Test
    fun `only operator Redis keys occur in production deployment and tests`() {
        var root = File(".").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) {
            root = root.parentFile ?: error("settings.gradle.kts not found")
        }
        val files =
            listOf("modules", "tests", "deploy").flatMap { directory ->
                File(root, directory)
                    .walkTopDown()
                    .onEnter { it.name !in setOf("build", ".gradle", "node_modules", "__pycache__") }
                    .filter { it.isFile }
                    .toList()
            } + root.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("application.*\\.ya?ml")) }
        val kotlinTests = files.count { it.extension == "kt" && "/src/test/" in it.invariantSeparatorsPath }
        withClue("source inventory must include at least 100 Kotlin test files; scanned $kotlinTests") {
            (kotlinTests >= MIN_TEST_FILES) shouldBe true
        }
        // Split the forbidden spellings so the guard and the bypass acceptance test do not exempt themselves.
        val forbidden = listOf(listOf("spring", "data", "redis").joinToString("."), listOf("SPRING", "DATA", "REDIS").joinToString("_"))
        val offenders = files.filter { file -> forbidden.any { it in file.readText() } }.map { it.relativeTo(root).path }.sorted()
        println("Redis authority scan files=${files.size} kotlinTests=$kotlinTests offenders=${offenders.size}")
        withClue("framework Redis keys must never reach the client: $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    private companion object {
        const val MIN_TEST_FILES = 100
    }
}
