import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Replays #703 in one checkout: deletion must refresh findings while a second,
 * unchanged violation remains visible. Modified-only and unchanged runs protect
 * incrementality and up-to-date checks. Like [KtlintCacheBoundaryTest], this uses
 * the real conventions plugin, catalog and resolved JDK, with network dependency
 * resolution. Its TestKit home and retained probe logs live under this lane's build.
 */
class KtlintRemovedOnlyTest {
    private val probeDir: File
        get() = catalog.parentFile.parentFile.resolve("build/703-probes/project")

    private val catalog: File
        get() = File(requireNotNull(System.getProperty("repo.catalog")))

    private val evidence: File
        get() =
            catalog.parentFile.parentFile
                .resolve("build/703-probes/evidence")
                .apply { mkdirs() }

    private fun source(
        name: String,
        violating: Boolean,
    ): File {
        val value = if (violating) "x".repeat(160) else "clean"
        return probeDir.resolve("modules/web/src/main/kotlin/$name.kt").apply {
            parentFile.mkdirs()
            writeText("package probe\n\nval ${name.replaceFirstChar { it.lowercase() }} = \"$value\"\n")
        }
    }

    private fun run(
        name: String,
        fails: Boolean,
    ): BuildResult {
        val home = catalog.parentFile.parentFile.resolve("build/703-probes/gradle-user-home")
        val runner =
            GradleRunner
                .create()
                .withPluginClasspath()
                .withTestKitDir(home)
                .withProjectDir(probeDir)
                .withArguments(
                    ":modules:web:ktlintMainSourceSetCheck",
                    "--info",
                    "--build-cache",
                    "--no-parallel",
                    "--max-workers=1",
                    "-Porg.gradle.java.installations.paths=${System.getProperty("probe.jdk21")}",
                )
        val result = if (fails) runner.buildAndFail() else runner.build()
        evidence.resolve("$name.log").writeText(result.output)
        return result
    }

    @Test
    fun `removed sources refresh findings without losing live violations or incrementality`() {
        assertTrue(!probeDir.exists() || probeDir.deleteRecursively(), "cannot reset this test's own probe")
        assertTrue(probeDir.mkdirs())
        installRepoVerification(probeDir)
        probeDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs { create("libs") { from(files("${catalog.absolutePath}")) } }
            }
            rootProject.name = "removed-only-probe"
            include(":modules:web")
            buildCache { local { directory = file("cache") } }
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
        val removed = source("RemovedViolation", violating = true)
        source("RemainingViolation", violating = true)
        val clean = source("Clean", violating = false)

        val initial = run("1-initial-red", fails = true)
        assertEquals(TaskOutcome.FAILED, initial.task(REPORT)?.outcome, initial.output)
        assertTrue("RemovedViolation.kt" in initial.output, initial.output)
        assertTrue("RemainingViolation.kt" in initial.output, initial.output)

        assertTrue(removed.delete())
        val deleted = run("2-deleted", fails = true)
        assertTrue("RemainingViolation.kt" in deleted.output, deleted.output)
        // Assert the stale finding FIRST: reverting the input property must fail here.
        assertFalse("RemovedViolation.kt:" in deleted.output, deleted.output)
        assertEquals(TaskOutcome.SUCCESS, deleted.task(WORKER)?.outcome, deleted.output)
        assertTrue("Executing non-incrementally" in deleted.output, deleted.output)
        assertTrue("ktlintSourceInventory" in deleted.output, deleted.output)
        assertFalse("Skipping. No files to lint" in deleted.output, deleted.output)

        clean.appendText("\nval anotherCleanValue = 1\n")
        val modified = run("3-modified-red", fails = true)
        assertTrue("RemainingViolation.kt" in modified.output, modified.output)
        assertTrue("Executing incrementally" in modified.output, modified.output)
        assertEquals(TaskOutcome.SUCCESS, modified.task(WORKER)?.outcome, modified.output)

        source("RemainingViolation", violating = false)
        val repaired = run("4-repaired-green", fails = false)
        assertEquals(TaskOutcome.SUCCESS, repaired.task(WORKER)?.outcome, repaired.output)
        assertTrue("Executing incrementally" in repaired.output, repaired.output)

        val unchanged = run("5-unchanged-green", fails = false)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(WORKER)?.outcome, unchanged.output)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(REPORT)?.outcome, unchanged.output)

        assertTrue(clean.delete())
        val cleanDeletion = run("6-clean-deletion-green", fails = false)
        assertEquals(TaskOutcome.SUCCESS, cleanDeletion.task(WORKER)?.outcome, cleanDeletion.output)
        assertTrue("Executing non-incrementally" in cleanDeletion.output, cleanDeletion.output)
    }

    private companion object {
        const val WORKER = ":modules:web:runKtlintCheckOverMainSourceSet"
        const val REPORT = ":modules:web:ktlintMainSourceSetCheck"
    }
}
