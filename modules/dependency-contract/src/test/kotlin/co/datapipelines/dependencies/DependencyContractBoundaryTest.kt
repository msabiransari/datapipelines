package co.datapipelines.dependencies

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * **The contract is Kotlin/JDK only** (record §3.1). The empty §4.2 row fences the Gradle edges; this fences what a main
 * source may NAME — an import or a fully-qualified use (the lesson of 217a: a qualified spelling must not hide a use) of
 * Spring, `java.sql`, `jakarta`, or any family module.
 */
class DependencyContractBoundaryTest {
    @Test
    fun `main sources name no framework, JDBC or family package`() {
        val sources =
            RepoFiles
                .file("modules/dependency-contract/src/main/kotlin")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .toList()
        sources.size shouldBeGreaterThan 8 // non-vacuity: the scan saw the module
        val offenders =
            sources.flatMap { file ->
                FORBIDDEN.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList()
            }
        offenders.shouldBeEmpty()
    }

    private companion object {
        val FORBIDDEN =
            Regex(
                """\b(?:org\.springframework|java\.sql|javax\.sql|jakarta|""" +
                    """co\.datapipelines\.(?:pipeline|templates|scheduler|auth|datasources|""" +
                    """visualization|application|web|typesystem))\b[.\w]*""",
            )
    }
}
