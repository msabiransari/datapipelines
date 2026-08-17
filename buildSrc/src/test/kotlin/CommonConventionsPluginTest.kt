import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Configuration-time proofs for the coverage-floor guard in
 * CommonConventionsPlugin (012/F6): the 009/F9 guard — a configuration-time
 * GradleException for any project missing from COVERAGE_FLOORS and
 * NO_COVERAGE_FLOOR_ALLOWLIST, plus the -Pkover.off skip logic — shipped with
 * ZERO tests because buildSrc had no test source set at all. A guard that
 * cannot be shown able to fail is decoration (house §16).
 *
 * Gradle TestKit drives real isolated builds that apply THIS buildSrc's
 * compiled plugin (withPluginClasspath() reads the pluginUnderTestMetadata
 * java-gradle-plugin generates onto the test classpath):
 *
 *  a. a project in neither COVERAGE_FLOORS nor the allowlist fails
 *     configuration with the documented message;
 *  b. the allowlisted project (:tests:integration-tests) configures cleanly;
 *  c. a floored project (:modules:web, floor 72) registers a floor rule that
 *     FAILS on absent coverage data — and -Pkover.off configures and verifies
 *     without it. (The check→koverVerify task wiring is Kover's own and stays
 *     either way; what the flag removes is the floor rule.)
 *
 * The probe projects reuse the repo's real version catalog — the plugin
 * resolves catalog aliases at apply time. Tests (a) and (b) stop at
 * configuration ("help"), but test (c) writes real sources and runs
 * :modules:web:koverVerify, which COMPILES Kotlin and runs JUnit inside the
 * probe — resolving the convention dependency set from the probe's own
 * `mavenCentral()`. Network truth (013/F6 — this KDoc previously claimed
 * "no network, no repositories", which was false): the tests need either
 * NETWORK or a warm Gradle TestKit cache. TestKit's default testKitDir
 * (`.gradle-test-kit-<user>` under the test JVM's temp dir, which Gradle
 * forks into `buildSrc/build/tmp/test/work`) is a CACHE, not a checkout
 * artifact — a fresh worktree or a cleaned buildSrc starts COLD and
 * re-downloads the probe dependencies on first run. `gate.sh` therefore
 * preflights the network before this stage and skips it fail-soft with the
 * cause named when offline. No TOOLCHAIN download ever happens: the probes
 * run on the pre-resolved JDK 21 handed in via `probe.jdk21`.
 */
class CommonConventionsPluginTest {

    @TempDir
    lateinit var probeDir: File

    private val catalogPath: String
        get() = System.getProperty("repo.catalog")
            ?: error("repo.catalog system property not set — run via buildSrc's test task")

    /** Probe settings reusing the repo catalog; optionally including subprojects. */
    private fun writeSettings(vararg includes: String) {
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
            ${includes.joinToString("\n") { "include(\"$it\")" }}
            """.trimIndent(),
        )
    }

    private fun writeBuild(relativePath: String) {
        probeDir.resolve(relativePath).apply { parentFile.mkdirs() }.writeText(
            """
            import org.gradle.api.artifacts.dsl.LockMode
            plugins { id("datapipelines.common-conventions") }
            repositories { mavenCentral() }
            // Probes carry no lockfile: the convention plugin's STRICT locking is
            // right for real modules but fails every probe resolution on missing
            // lock state (kotlinScriptDefExtensions under compileKotlin). LENIENT
            // with no lockfile present resolves normally — all asserted behavior
            // is the plugin's own configuration/verification, not locking.
            dependencyLocking { lockMode = LockMode.LENIENT }
            """.trimIndent() + "\n",
        )
    }

    private val jdk21Home: String
        get() = System.getProperty("probe.jdk21")
            ?: error("probe.jdk21 system property not set — run via buildSrc's test task")

    private fun runner(vararg args: String) = GradleRunner.create()
        .withPluginClasspath()
        .withProjectDir(probeDir)
        .withArguments(
            *args,
            // The probe's Kotlin compilation wants the JDK 21 toolchain; hand it
            // the installation the build resolved (see build.gradle.kts) instead
            // of relying on the probe settings' foojay resolver — the probes
            // never download a toolchain. (Dependency resolution is separate:
            // compile-bearing probes resolve from Maven Central — see KDoc.)
            "-Porg.gradle.java.installations.paths=$jdk21Home",
        )

    @Test
    fun `a project missing from floors and allowlist fails configuration with the documented message`() {
        writeSettings()
        // The probe ROOT carries path ":" — in neither COVERAGE_FLOORS nor the allowlist.
        writeBuild("build.gradle.kts")

        val failure = runCatching { runner("help").build() }.exceptionOrNull()
        assertTrue(failure is UnexpectedBuildFailure, "expected a configuration failure, got: $failure")
        val output = failure!!.message.orEmpty()
        assertTrue(
            ": has no entry in COVERAGE_FLOORS" in output,
            "guard message missing from build output:\n$output",
        )
        assertTrue(
            "NO_COVERAGE_FLOOR_ALLOWLIST" in output,
            "allowlist guidance missing from build output:\n$output",
        )
    }

    @Test
    fun `an allowlisted project configures cleanly`() {
        writeSettings(":tests:integration-tests")
        writeBuild("tests/integration-tests/build.gradle.kts")

        val result = runner("help").build()
        assertTrue("BUILD SUCCESSFUL" in result.output, result.output)
    }

    @Test
    fun `floored project registers a failing floor and -Pkover-off drops it`() {
        writeSettings(":modules:web")
        writeBuild("modules/web/build.gradle.kts")
        // One main class and one test that never touches it: real coverage
        // data at ~0% < the :modules:web floor (72) — the honest reproduction
        // of a floor VIOLATION, which "no sources at all" cannot produce
        // (Kover 0.9.9 skips verification over zero classes). This is the
        // guard's ability-to-FAIL proof (house §16); the second half proves
        // -Pkover.off removes the rule. The check→koverVerify task wiring is
        // NOT asserted: Kover wires it natively, flag or no flag (observed in
        // a dry-run); what the flag removes is the floor rule.
        probeDir.resolve("modules/web/src/main/kotlin/Probe.kt").apply { parentFile.mkdirs() }.writeText(
            """
            class Probe {
                fun covered(): Int = 42
            }
            """.trimIndent(),
        )
        probeDir.resolve("modules/web/src/test/kotlin/ProbeTest.kt").apply { parentFile.mkdirs() }.writeText(
            """
            import org.junit.jupiter.api.Test
            class ProbeTest {
                @Test fun untouched() { }
            }
            """.trimIndent(),
        )

        val floorsEngage = runCatching {
            runner(":modules:web:koverVerify").build()
        }.exceptionOrNull()
        assertTrue(floorsEngage is UnexpectedBuildFailure, "expected the floor rule to fail at ~0% coverage, got success")
        val floorOutput = (floorsEngage as UnexpectedBuildFailure).message.orEmpty()
        assertTrue(
            "line coverage must not drop below" in floorOutput,
            "floor-rule violation missing from output:\n$floorOutput",
        )

        val koverOff = runner(":modules:web:koverVerify", "-Pkover.off").build()
        assertTrue("BUILD SUCCESSFUL" in koverOff.output, koverOff.output)
    }
}
