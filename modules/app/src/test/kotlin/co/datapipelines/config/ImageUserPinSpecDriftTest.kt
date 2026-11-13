package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The image's `datapipelines` account ids vs. the reference chart's application pod
 * `securityContext` vs. deployment.md §3.1's User bullet (#753).
 *
 * The image created its account with `useradd --system` and no ids (it got 999 from the base
 * image's free system range) while the chart pinned `runAsUser`/`runAsGroup`/`fsGroup` to 1000 —
 * a pod that ran the application as an account the image does not have. The Dockerfile now fixes
 * both ids, the chart names the same number, and §3.1 states it; this guard holds the three equal.
 *
 * Textual, the [DeploymentImageBulletsSpecDriftTest] shape: it reads the files, it does not build
 * the image or render the chart. Every regex must match exactly once (the non-vacuity floor), and
 * every clue names the files and the numbers on both sides of a drift. Only the application
 * `Deployment` template is read: the egress proxy's renderer runs this image as 65532 on purpose
 * (`egress-proxy.yaml`, §3.1).
 */
class ImageUserPinSpecDriftTest {
    private val dockerfile: List<String> by lazy {
        repoFile(DOCKERFILE).readLines().filterNot { it.trim().startsWith("#") }
    }
    private val deployment: List<String> by lazy { repoFile(DEPLOYMENT_TEMPLATE).readLines() }

    @Test
    fun `the Dockerfile creates the datapipelines group and user with one fixed id`() {
        imageId()
    }

    @Test
    fun `the chart's application pod runs as the image's id and refuses root`() {
        val id = imageId()
        for (key in listOf("runAsUser", "runAsGroup", "fsGroup")) {
            val value = single(deployment, Regex("""^\s*$key:\s*(\S+)\s*$"""), "$DEPLOYMENT_TEMPLATE `$key:`")
            withClue("$DEPLOYMENT_TEMPLATE `$key: $value` vs $DOCKERFILE account id $id") {
                value shouldBe id.toString()
            }
        }
        single(deployment, Regex("""^\s*runAsNonRoot:\s*(true)\s*$"""), "$DEPLOYMENT_TEMPLATE `runAsNonRoot: true`")
    }

    @Test
    fun `the section 3_1 user bullet states the image's UID and GID`() {
        val id = imageId()
        val bullets = deploymentSection().filter { it.startsWith(USER_BULLET) }
        withClue("deployment.md §3.1 lines starting `$USER_BULLET`") { bullets shouldHaveSize 1 }
        withClue("deployment.md §3.1 `$USER_BULLET` bullet vs $DOCKERFILE account id $id") {
            bullets.single() shouldContain "UID $id"
            bullets.single() shouldContain "GID $id"
        }
    }

    /** The one id both the `groupadd` and the `useradd` line give the `datapipelines` account. */
    private fun imageId(): Int {
        val gid = single(dockerfile, GROUPADD, "$DOCKERFILE `groupadd --system --gid <N> $ACCOUNT`").toInt()
        val uid = single(dockerfile, USERADD, "$DOCKERFILE `useradd --system --uid <M> --gid … $ACCOUNT`").toInt()
        withClue("$DOCKERFILE `groupadd --gid $gid` vs `useradd --uid $uid` (the chart pins one number for both)") {
            uid shouldBe gid
        }
        return uid
    }

    /** Group 1 of the one line matching [regex]; zero or several matches is red. */
    private fun single(
        lines: List<String>,
        regex: Regex,
        what: String,
    ): String {
        val matches = lines.mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
        withClue("lines matching $what (exactly one)") { matches shouldHaveSize 1 }
        return matches.single()
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
        const val DOCKERFILE = "Dockerfile"
        const val DEPLOYMENT_TEMPLATE = "deploy/helm/datapipelines/templates/deployment.yaml"
        const val SECTION_HEADING = "### 3.1 Docker image"
        const val USER_BULLET = "- **User**:"
        const val ACCOUNT = "datapipelines"

        val GROUPADD = Regex("""groupadd\s+--system\s+--gid\s+(\d+)\s+$ACCOUNT(?:\s|$)""")
        val USERADD = Regex("""useradd\s+--system\s+--uid\s+(\d+)\s+--gid\s+\S+\s+$ACCOUNT(?:\s|$)""")
    }
}
