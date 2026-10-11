package co.datapipelines.auth

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Auth knows one thing of pipeline** (#504b). The module may use `pipeline-contract` for exactly
 * one thing — the shared search-needle rule (`SearchNeedle` and its typed refusal, the same rule
 * every search-owning service applies at its door) — and nothing else of `pipeline`, `web`,
 * `application` or any other module at all. The Gradle table fences the modules; this fences the
 * ONE allowed edge to the one allowed pair of types, so the edge cannot quietly become pipeline
 * or web knowledge. Import lines are read from the main sources, fully-qualified uses included
 * (the lesson of 217a: a qualified spelling must not hide a use); the second check pins the
 * IMPORT lines themselves, so the two prose mentions of the allowed types in KDoc cannot be
 * widened into imports without this test going red.
 */
class AuthBoundaryTest {
    @Test
    fun `main sources name nothing of co_datapipelines outside this module typesystem persistence and the needle rule`() {
        val sources =
            repoFile("modules/auth/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
        sources.size shouldBeGreaterThan 20 // non-vacuity: the scan saw the module
        val offenders =
            sources.flatMap { file ->
                REFERENCE
                    .findAll(file.readText())
                    .map { it.value }
                    .filterNot {
                        it in ALLOWED || it.startsWith(OWN) ||
                            it.startsWith(TYPESYSTEM) || it.startsWith(PERSISTENCE)
                    }.map { "${file.name}: $it" }
            }
        offenders.shouldBeEmpty()
    }

    @Test
    fun `the only cross-module import lines are the needle rule`() {
        val offenders =
            repoFile("modules/auth/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
                .flatMap { file ->
                    file
                        .readLines()
                        .mapIndexedNotNull { index, line ->
                            val trimmed = line.trim()
                            if (forbiddenImport(trimmed)) {
                                "${file.name}:${index + 1}: $trimmed"
                            } else {
                                null
                            }
                        }
                }
        offenders.shouldBeEmpty()
    }

    private companion object {
        val REFERENCE = Regex("""co\.datapipelines\.[a-z]+(?:\.[A-Za-z0-9_]+)+""")
        const val OWN = "co.datapipelines.auth"
        const val TYPESYSTEM = "co.datapipelines.typesystem."
        const val PERSISTENCE = "co.datapipelines.persistence."

        // The needle rule (#504b) — the ONE pipeline-contract edge, prose mentions included.
        val ALLOWED = setOf("co.datapipelines.pipeline.SearchNeedle", "co.datapipelines.pipeline.SearchQueryTooLongException")
        val ALLOWED_IMPORTS = setOf("import co.datapipelines.pipeline.SearchNeedle")
    }

    private fun forbiddenImport(trimmed: String): Boolean =
        trimmed.startsWith("import co.datapipelines.") &&
            !trimmed.startsWith("import $OWN.") &&
            !trimmed.startsWith("import $TYPESYSTEM") &&
            !trimmed.startsWith("import $PERSISTENCE") &&
            trimmed !in ALLOWED_IMPORTS

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, relative)
            .also { require(it.exists()) { "missing: $relative" } }
    }
}
