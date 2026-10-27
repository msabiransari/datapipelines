package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * deployment.md §3.1's image bullets vs. the `Dockerfile` that actually builds the image (#743).
 * The doc described a jammy multi-stage build with a UID-1000 user and a `$JAVA_OPTS` entrypoint
 * for months while the image is a single-stage `eclipse-temurin:21-jre` build with a `--system`
 * user and a fixed exec-form entrypoint — every stale bullet sent an operator hunting for a
 * `JAVA_OPTS` reader or a `-Duser.timezone` flag that no image carries. The timezone paragraph in
 * the same section is pinned too: the entrypoint names engine flags only, so §3.1 must not claim
 * the image bakes `-Duser.timezone=UTC` in — the flag stays normative, and every deployment passes
 * it itself.
 *
 * Textual, the [ImageLoaderPathSpecDriftTest] shape: it reads the files, it does not build the
 * image. Every clue names the §3.1 bullet AND the Dockerfile line, so a red names both sides of
 * the drift.
 */
class DeploymentImageBulletsSpecDriftTest {
    private val dockerfile: List<String> by lazy { repoFile("Dockerfile").readLines() }
    private val section: List<String> by lazy { deploymentSection() }

    @Test
    fun `the image builds one FROM stage and the base-image bullet names that tag`() {
        val fromLines = dockerfile.withIndex().filter { it.value.trim().startsWith("FROM ") }
        withClue("Dockerfile `FROM` instructions (the image is single-stage)") {
            fromLines.map { it.value } shouldHaveSize 1
        }
        val fromLine = fromLines.single()
        val parsedTag = FROM_LINE.matchEntire(fromLine.value.trim())?.groupValues?.get(1)
        val tag =
            requireNotNull(parsedTag) {
                "Dockerfile `FROM` line does not parse: ${fromLine.value.trim()}"
            }

        val bullet = bullet(BASE_IMAGE_BULLET)
        withClue(
            "deployment.md §3.1 `$BASE_IMAGE_BULLET` bullet vs Dockerfile " +
                "`${fromLine.value.trim()}` (line ${fromLine.index + 1})",
        ) {
            bullet shouldContain tag
        }
    }

    @Test
    fun `the entrypoint bullet quotes the image's single ENTRYPOINT array`() {
        val entrypointLines = dockerfile.withIndex().filter { it.value.trim().startsWith("ENTRYPOINT ") }
        withClue("Dockerfile `ENTRYPOINT` instructions") { entrypointLines.map { it.value } shouldHaveSize 1 }

        val entrypointLine = entrypointLines.single()
        val array = WHITESPACE.replace(entrypointLine.value.substringAfter("ENTRYPOINT ").trim(), " ")
        withClue("the `ENTRYPOINT` value is a JSON array") {
            (array.startsWith("[") && array.endsWith("]")) shouldBe true
        }

        val bullet = bullet(ENTRYPOINT_BULLET)
        withClue(
            "deployment.md §3.1 `$ENTRYPOINT_BULLET` bullet vs Dockerfile " +
                "`ENTRYPOINT` (line ${entrypointLine.index + 1})",
        ) {
            WHITESPACE.replace(bullet, " ") shouldContain array
        }
    }

    @Test
    fun `the user bullet names the image's USER account created with useradd --system`() {
        val userLines = dockerfile.withIndex().filter { it.value.trim().startsWith("USER ") }
        withClue("Dockerfile `USER` instructions") { userLines.map { it.value } shouldHaveSize 1 }
        val account =
            userLines
                .single()
                .value
                .trim()
                .substringAfter("USER ")
                .trim()

        val useraddLines =
            dockerfile.withIndex().filter {
                val trimmed = it.value.trim()
                trimmed.contains("useradd --system") && trimmed.contains(account)
            }
        withClue("Dockerfile `useradd --system` line creating `$account`") { useraddLines shouldHaveSize 1 }

        val bullet = bullet(USER_BULLET)
        withClue(
            "deployment.md §3.1 `$USER_BULLET` bullet vs Dockerfile `USER` " +
                "(line ${userLines.single().index + 1}) and `useradd` (line ${useraddLines.single().index + 1})",
        ) {
            bullet shouldContain account
            bullet shouldContain "useradd --system"
        }
    }

    @Test
    fun `section 3_1 carries none of the retired image claims`() {
        val text = WHITESPACE.replace(section.joinToString(" "), " ")
        for (retired in RETIRED) {
            withClue("deployment.md §3.1 retired claim `${retired.phrase}` — ${retired.where}") {
                text.contains(retired.phrase, ignoreCase = true) shouldBe false
            }
        }
    }

    /** The §3.1 slice: the lines between the `### 3.1` heading and the next `### ` heading. */
    private fun deploymentSection(): List<String> {
        val lines = repoFile("docs/deployment.md").readLines()
        val start = lines.indexOfFirst { it.startsWith(SECTION_HEADING) }
        check(start >= 0) { "deployment.md `$SECTION_HEADING` heading not found" }
        val next = lines.subList(start + 1, lines.size).indexOfFirst { it.startsWith("### ") }
        check(next >= 0) { "deployment.md has no further `### ` heading after `$SECTION_HEADING`" }
        return lines.subList(start + 1, start + 1 + next)
    }

    /** The one §3.1 bullet line with this prefix; the size assertion keeps an empty slice red. */
    private fun bullet(prefix: String): String {
        val matches = section.filter { it.startsWith(prefix) }
        withClue("deployment.md §3.1 lines starting `$prefix` (the section slice)") {
            matches shouldHaveSize 1
        }
        return matches.single()
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return dir
    }

    private fun repoFile(relative: String): File = File(repoRoot(), relative).also { check(it.isFile) { "missing $relative" } }

    private companion object {
        const val SECTION_HEADING = "### 3.1 Docker image"
        const val BASE_IMAGE_BULLET = "- **Base image**:"
        const val USER_BULLET = "- **User**:"
        const val ENTRYPOINT_BULLET = "- **Entrypoint**:"

        val FROM_LINE = Regex("""FROM\s+(\S+)""")
        val WHITESPACE = Regex("""\s+""")

        /** What §3.1 said before #743, and where each phrase lived — none of it may return. */
        data class Retired(
            val phrase: String,
            val where: String,
        )

        val RETIRED =
            listOf(
                Retired("jammy", "the base-image bullet named a `-jammy` tag the `FROM` never used"),
                Retired("multi-stage", "the layers bullet named a builder stage the image never had"),
                Retired("gradle:8.7", "the layers bullet pinned a builder image the build never runs"),
                Retired("JAVA_OPTS", "the entrypoint bullet named a variable nothing reads"),
                Retired("UID 1000", "the user bullet pinned a UID the `useradd --system` account does not have"),
                Retired("/app/app.jar", "the entrypoint bullet named a jar path the image does not use"),
                Retired("baked into the image entrypoint", "the timezone paragraph claimed the entrypoint sets the UTC flag"),
            )
    }
}
