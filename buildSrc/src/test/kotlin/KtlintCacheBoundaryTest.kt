import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The build-cache boundary for ktlint check tasks (#689), reproduced in
 * miniature with Gradle TestKit.
 *
 * A `BaseKtLintCheckTask`'s stored output is NOT a function of its inputs:
 * on a REMOVED-only source change the ktlint plugin's incremental path
 * (`skipExecution` over an empty changed set) returns before rewriting
 * `discoveredErrors`, and Gradle then stores the UNTOUCHED previous output
 * under the clean tree's cache key. Any other checkout whose relative
 * sources produce that same key — the cache key is path-sensitive RELATIVE
 * precisely so lanes can share entries — is served findings named by the
 * PRODUCING checkout's absolute paths. Reproduced at full scale on
 * 2026-10-24 (lane 689): a clean worktree's pregate failed lint on a scratch
 * test file that existed only in another worktree, restored FROM-CACHE.
 *
 * The probe is that incident shrunk to two directories sharing ONE cache:
 *
 *  - probe A lints a violating tree (the failure is asserted by name — the
 *    guard's ability-to-FAIL floor), deletes the violating file, and lints
 *    again: the removed-only change drives the skip path, whose output is
 *    whatever the previous execution left. NOTE the step order: the clean
 *    key must be UNOCCUPIED at the delete (probe A's first run compiles
 *    only), or Gradle would serve the honest clean entry FROM-CACHE and the
 *    poisoning would not happen — the same trap the full-scale replay hit.
 *  - probe B, the identical clean tree, then runs: `compileKotlin` IS
 *    `FROM_CACHE` (the cache still works — compile stored by probe A's
 *    first run), while `runKtlintCheckOverMainSourceSet` is never
 *    `FROM_CACHE` (CommonConventionsPlugin keeps ktlint check tasks out of
 *    the cache, #689) and the verdict is green with no path from probe A
 *    anywhere in the output.
 *
 * Falsification: remove the `cacheIf { false }` line for
 * `BaseKtLintCheckTask` in CommonConventionsPlugin — probe B's run task is
 * then served probe A's stale output FROM-CACHE and this guard is red at
 * the FROM_CACHE/failure assertion. (Verified 2026-10-24.)
 *
 * Same TestKit mechanics as [CommonConventionsPluginTest]: the probes apply
 * THIS buildSrc's compiled plugin via withPluginClasspath(), reuse the
 * repo's version catalog, resolve compile/ktlint dependencies from Maven
 * Central (network or a warm TestKit cache — see that class's KDoc), run on
 * the pre-resolved JDK 21 and never download a toolchain. Each probe gets
 * its own project directory; BOTH point `buildCache.local.directory` at one
 * shared directory — `file(...)` in a settings script resolves against the
 * probe project, so the absolute path is written in explicitly.
 */
class KtlintCacheBoundaryTest {

    @TempDir
    lateinit var laneDir: File

    private val catalogPath: String
        get() = System.getProperty("repo.catalog")
            ?: error("repo.catalog system property not set — run via buildSrc's test task")

    private val jdk21Home: String
        get() = System.getProperty("probe.jdk21")
            ?: error("probe.jdk21 system property not set — run via buildSrc's test task")

    /** A probe directory with the conventions plugin and the SHARED cache wired. */
    private fun newProbe(name: String, sharedCacheDir: File): File {
        val probeDir = laneDir.resolve(name).apply { mkdirs() }
        probeDir.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement {
                versionCatalogs {
                    create("libs") {
                        from(files("$catalogPath"))
                    }
                }
            }
            rootProject.name = "probe"
            include(":modules:web")
            buildCache { local { directory = file("${sharedCacheDir.absolutePath}") } }
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
        writeCleanSource(probeDir)
        return probeDir
    }

    private fun writeCleanSource(probeDir: File) {
        probeDir.resolve("modules/web/src/main/kotlin/Probe.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package co.datapipelines.probe

            class Probe {
                fun covered(): Int = 42
            }
            """.trimIndent() + "\n",
        )
    }

    /** A file that ktlint cannot pass: over the 140-column standard limit. */
    private fun writeViolatingSource(probeDir: File) {
        val line = "val veryLongString = \"${"x".repeat(160)}\""
        probeDir.resolve("modules/web/src/main/kotlin/Violation.kt").apply { parentFile.mkdirs() }.writeText(
            """
            package co.datapipelines.probe

            class Violation {
                fun hold(): Int {
                    $line
                    return veryLongString.length
                }
            }
            """.trimIndent() + "\n",
        )
    }

    private fun runner(probeDir: File, vararg args: String) = GradleRunner.create()
        .withPluginClasspath()
        .withProjectDir(probeDir)
        .withArguments(
            *args,
            "--build-cache",
            "--no-parallel",
            "--max-workers=1",
            "-Porg.gradle.java.installations.paths=$jdk21Home",
        )

    @Test
    fun `a foreign checkout is never served another checkout's ktlint findings from the build cache`() {
        val sharedCache = laneDir.resolve("shared-build-cache")
        val probeA = newProbe("probe-a", sharedCache)
        val probeB = newProbe("probe-b", sharedCache)

        // Probe A, run 1: the CLEAN tree, compile only. This stores the clean
        // compileKotlin entry (probe B's FROM_CACHE proof) while leaving the clean
        // ktlint key UNOCCUPIED — the delete below must find it unoccupied or the
        // skip path is never reached (see the class KDoc).
        val a1 = runner(probeA, ":modules:web:compileKotlin").build()
        assertEquals(
            TaskOutcome.SUCCESS,
            a1.task(":modules:web:compileKotlin")?.outcome,
            a1.output,
        )

        // Probe A, run 2: a violating file. The failure must name it — the
        // non-vacuity floor: this guard proves nothing if the probe cannot go red.
        writeViolatingSource(probeA)
        val a2 = runCatching {
            runner(probeA, ":modules:web:ktlintMainSourceSetCheck").build()
        }.exceptionOrNull()
        assertTrue(a2 is UnexpectedBuildFailure, "expected ktlint to fail on the violating file, got: $a2")
        val a2Output = (a2 as UnexpectedBuildFailure).message.orEmpty()
        assertTrue(
            "Violation.kt" in a2Output,
            "the red run must name the violating file:\n$a2Output",
        )

        // Probe A, run 3: the file DELETED — a removed-only change. The plugin's
        // incremental path finds no files to lint and leaves `discoveredErrors`
        // holding the previous run's findings. What happens NEXT is the whole
        // boundary under test: with the fix, nothing is stored; without it, the
        // stale output is stored under the clean tree's key. The in-worktree
        // verdict here is NOT asserted (the plugin's own incrementality, kept).
        probeA.resolve("modules/web/src/main/kotlin/Violation.kt").delete()
        runCatching {
            runner(probeA, ":modules:web:ktlintMainSourceSetCheck").build()
        }

        // Probe B: the identical clean tree, one shared cache. Compile is served
        // from the cache — the boundary fix must not cost compile its reuse.
        // The ktlint run task must have EXECUTED (never FROM_CACHE), the verdict
        // is green, and no path from probe A appears anywhere in the output.
        val b = runner(probeB, ":modules:web:compileKotlin", ":modules:web:ktlintMainSourceSetCheck").build()

        assertEquals(
            TaskOutcome.FROM_CACHE,
            b.task(":modules:web:compileKotlin")?.outcome,
            "the shared cache stopped working for compile:\n${b.output}",
        )
        val lintRun = b.task(":modules:web:runKtlintCheckOverMainSourceSet")
        assertTrue(
            lintRun != null && lintRun.outcome != TaskOutcome.FROM_CACHE,
            "a ktlint check task was served from the build cache — the #689 boundary is open: " +
                "outcome=${lintRun?.outcome}\n${b.output}",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            b.task(":modules:web:ktlintMainSourceSetCheck")?.outcome,
            "probe B's clean tree failed lint:\n${b.output}",
        )
        assertFalse(
            probeA.absolutePath in b.output,
            "probe B's lint verdict carries probe A's paths:\n${b.output}",
        )
    }
}
