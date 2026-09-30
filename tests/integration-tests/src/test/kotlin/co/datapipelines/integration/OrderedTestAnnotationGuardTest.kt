package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import java.io.File

/**
 * **Every `@Order`-annotated method in the TEST sources carries a JUnit test annotation** (#10 L1c-b).
 *
 * JUnit executes only methods annotated `@Test`, `@ParameterizedTest`, `@RepeatedTest`,
 * `@TestFactory` or `@TestTemplate`. An `@Order` without one of those is a method the class
 * reports as green WITHOUT running — the exact failure the L1c handback shipped: Orders 60–61
 * of `PromotionTwoDeploymentE2eTest` carried `@Order` without `@Test` (18 `@Test` / 20 `@Order`
 * in the file), the suite reported 18/18, and "the REAL sender path is proven by Orders 60–61"
 * sat in the handback while the two methods never executed. Their sender path was broken
 * (the O1 dashboard arm) and their fixtures carried three further latent defects; all four
 * surfaced on the first honest run. A rule no test enforces is a wish — this guard is the
 * enforcement.
 *
 * Deliberately a text scan in the `ArchitectureGuardTest` style, not a Konsist walk: the failure
 * names file and line, the scan is cheap enough to sit on every `check`, and the annotation's
 * QUALIFIED spelling (`@org.junit.jupiter.api.Order`) is matched by the optional package prefix,
 * the lesson of the qualified-`@Controller` gap. The window is the contiguous annotation block
 * around the `@Order` line (JUnit annotations may be written in either order), and the block must
 * be followed by a `fun` declaration — an `@Order` on a class or object is named too (JUnit's
 * class ordering is `@TestClassOrder`, and a bare `@Order` there orders nothing).
 *
 * A Spring `@Order` on a `@Bean` method inside a TEST configuration would be named by this guard
 * as well; the tree holds none (234 sites at birth, every one a test method), and one that
 * appears is worth the qualifier it will have to earn.
 */
class OrderedTestAnnotationGuardTest {
    @Test
    fun `every @Order in the test sources sits on a JUnit test method`() {
        val offenders = offenders()

        withClue(
            "An @Order without a JUnit test annotation is a method the suite reports green WITHOUT " +
                "running — give it @Test (or @ParameterizedTest/@RepeatedTest/@TestFactory/@TestTemplate) " +
                "or delete it; see the L1c handback's Orders 60–61.",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    /** The offender list, and the non-vacuity floor the scan's scope must clear. */
    private fun offenders(): List<String> {
        val bare = mutableListOf<String>()
        var sites = 0
        testSourceFiles().forEach { file ->
            val lines = file.readLines()
            lines.withIndex().forEach { (index, line) ->
                if (!ORDER_ANNOTATION.containsMatchIn(line)) return@forEach
                sites++
                // The contiguous annotation block around the @Order line (annotations in either order).
                var start = index
                while (start - 1 >= 0 && ANNOTATION_LINE.containsMatchIn(lines[start - 1])) start--
                var end = index + 1
                while (end < lines.size && ANNOTATION_LINE.containsMatchIn(lines[end])) end++
                val block = lines.subList(start, end).joinToString(" ") { it.trim() }
                var at = end
                while (at < lines.size && lines[at].isBlank()) at++
                val declaration = lines.getOrNull(at)?.trim().orEmpty()
                when {
                    FUN_DECLARATION.containsMatchIn(declaration) && !TEST_ANNOTATION.containsMatchIn(block) -> {
                        bare += "${file.path}:${index + 1}: @Order without a JUnit test annotation"
                    }

                    !DECLARATION.containsMatchIn(declaration) -> {
                        bare += "${file.path}:${index + 1}: @Order's block is followed by '$declaration'"
                    }
                }
            }
        }
        withClue("No @Order found in the test sources — the scan is vacuous, not clean") {
            sites shouldBeGreaterThanOrEqual ORDER_FLOOR
        }
        return bare
    }

    /**
     * Every test source under `modules/` and `tests/` at the repository root — resolved by walking
     * UP from the working directory (a Gradle test task runs in its own module's directory, where a
     * bare relative path finds nothing), the `ArchitectureGuardTest.modulesDirectory` pattern.
     */
    private fun testSourceFiles(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "modules/pipeline-contract").isDirectory)) dir = dir.parentFile
        val root = checkNotNull(dir) { "repository root not found walking up from ${File("").absolutePath}" }
        return listOf("modules", "tests").flatMap { top ->
            val base = File(root, top)
            if (!base.isDirectory) return@flatMap emptyList<File>()
            base
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("${File.separator}src${File.separator}test${File.separator}") }
                .filterNot { it.path.contains("${File.separator}build${File.separator}") }
                .toList()
        }
    }

    private companion object {
        /** The tree held 234 `@Order` sites at the guard's birth, every one a test method. */
        const val ORDER_FLOOR = 200

        /** The annotation, also written fully qualified — a package prefix must not hide a site. */
        val ORDER_ANNOTATION = Regex("^\\s*@(?:[\\w.]+\\.)?Order\\b")

        val ANNOTATION_LINE = Regex("^\\s*@")

        val TEST_ANNOTATION = Regex("@(?:[\\w.]+\\.)?(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b")

        val FUN_DECLARATION = Regex("^(?:open |internal |private |protected |override |suspend |inline )*fun\\b")

        val DECLARATION =
            Regex(
                "^(?:open |internal |private |protected |override |suspend |inline )*(?:fun|class|object|val|var)\\b",
            )
    }
}
