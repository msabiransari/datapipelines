import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The TestKit probes resolve under the repo's dependency verification (#742).
 *
 * The smallest probe that resolves the convention plugin's test set — one test source under
 * `:modules:web`, `compileTestKotlin` — runs twice: with the repo's metadata whose
 * `junit-jupiter-api-5.12.2.jar` sha256 has one hex digit changed, it must fail NAMING that jar
 * (never a bare non-zero exit); with the unaltered file it must compile. The first case is the
 * guard: drop [installRepoVerification]'s copy and the probe resolves unverified, compiles,
 * and this test is red.
 *
 * Runs on the DEFAULT TestKit dir, the one [CommonConventionsPluginTest] warms: measured on
 * 2026-10-27 (lane 742), Gradle re-verifies an artifact already in a warm cache against the
 * probe root's metadata — zero downloads, the same failure — so a warm cache is no blind spot
 * and the guard costs one compile, not a cold download.
 */
class ProbeVerificationTest {
    @TempDir
    lateinit var probeDir: File

    private fun writeProbe() {
        probeDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs { create("libs") { from(files("${System.getProperty("repo.catalog")}")) } }
            }
            rootProject.name = "verification-probe"
            include(":modules:web")
            """.trimIndent() + "\n",
        )
        probeDir.resolve("modules/web/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            import org.gradle.api.artifacts.dsl.LockMode
            plugins { id("datapipelines.common-conventions") }
            repositories { mavenCentral() }
            dependencyLocking { lockMode = LockMode.LENIENT }
            """.trimIndent() + "\n",
        )
        probeDir.resolve("modules/web/src/test/kotlin/ProbeTest.kt").apply { parentFile.mkdirs() }.writeText(
            "import org.junit.jupiter.api.Test\n\nclass ProbeTest {\n    @Test fun compiles() { }\n}\n",
        )
    }

    private fun runner() =
        GradleRunner
            .create()
            .withPluginClasspath()
            .withProjectDir(probeDir)
            .withArguments(COMPILE, "-Porg.gradle.java.installations.paths=${System.getProperty("probe.jdk21")}")

    @Test
    fun `a probe fails naming the artifact whose committed checksum does not match`() {
        writeProbe()
        installRepoVerification(probeDir) { metadata ->
            val anchors = JAR_SHA256.findAll(metadata).count()
            assertEquals(1, anchors, "expected exactly one $JAR checksum in the repo metadata")
            JAR_SHA256.replace(metadata) { match ->
                val digit = match.groupValues[2]
                match.groupValues[1] + (if (digit == "0") "1" else "0") + match.groupValues[3]
            }
        }

        val output = runner().buildAndFail().output
        assertTrue("Dependency verification failed" in output, output)
        assertTrue("One artifact failed verification: $JAR" in output, output)
    }

    @Test
    fun `a probe with the unaltered repo checksums compiles its tests`() {
        writeProbe()
        installRepoVerification(probeDir)

        val result = runner().build()
        assertEquals(TaskOutcome.SUCCESS, result.task(COMPILE)?.outcome, result.output)
    }

    private companion object {
        const val COMPILE = ":modules:web:compileTestKotlin"
        const val JAR = "junit-jupiter-api-5.12.2.jar"

        /** The jar's sha256 split as prefix, first hex digit, rest — so the edit changes exactly one digit. */
        val JAR_SHA256 = Regex("""(<artifact name="${Regex.escape(JAR)}">\s*<sha256 value=")([0-9a-f])([0-9a-f]{63}")""")
    }
}
