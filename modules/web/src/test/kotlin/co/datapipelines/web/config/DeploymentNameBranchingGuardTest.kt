package co.datapipelines.web.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * C2's guard (versioning §5.5, 039's amendment): **no production code reads the deployment
 * name in a conditional — no production code reads it at all beyond its one consumer.**
 *
 * The rule that makes bundling `name` beside the capability safe rather than a regression:
 * a single-server user honestly writes "prod" as their label, and the first `if (name ==
 * "prod")` anywhere locks them out of authoring on the only server they have — exactly
 * what `authoring-enabled`'s capability naming exists to prevent (C1). The temptation is
 * RE-CREATED by putting the label next to the flag; this test is the pin.
 *
 * A grep cannot parse conditionals, so it over-approximates to something stronger and
 * trivially checkable: the key's literal spelling may appear in exactly ONE production
 * source file — [AuthoringStartupCheck], whose use is the startup posture LOG line. A file
 * that cannot NAME the key cannot branch on it; any new consumer (a UI banner, an /info
 * exposure) must either come through here or widen this allowlist deliberately, in review.
 *
 * ## Falsification (039's exit gate 3)
 *
 * Adding `if (environment.getProperty("datapipelines.deployment.name") == "prod") …` to
 * any production file makes this red: the key literal appears in a second src/main source.
 * Verified red in a scratch copy before landing (see the round's handback).
 *
 * Same repo-root location discipline as [TestRepoFiles][co.datapipelines.web.TestRepoFiles],
 * replicated locally because that helper keeps its root private: walk up to
 * `settings.gradle.kts`. The scan reads the real source tree — never the build output.
 */
class DeploymentNameBranchingGuardTest {
    @Test
    fun `the deployment name key is named by exactly one production source - its log consumer`() {
        val offenders = productionSources().filter { DEPLOYMENT_NAME_KEY in it.readText() }

        offenders.map { it.relativeTo(root).path } shouldContainExactly
            listOf("modules/web/src/main/kotlin/co/datapipelines/web/config/AuthoringStartupCheck.kt")
    }

    @Test
    fun `the scan sees the production tree - a guard over nothing guards nothing`() {
        // Non-vacuity (the BDT lesson): if the walk lost the source tree, the guard above
        // would pass on an empty scan. Name a file the walk must find, and floor the count.
        File(root, "modules/web/src/main/kotlin/co/datapipelines/web/config/AuthoringStartupCheck.kt").isFile shouldBe true
        productionSources().size shouldBeGreaterThan MODULE_FLOOR
    }

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    private fun productionSources(): List<File> =
        File(root, "modules")
            .walkTopDown()
            .onEnter { dir -> dir.name !in EXCLUDED_DIRS }
            .filter { it.isFile && it.extension == "kt" && it.absolutePath.contains(SOURCE_SET) }
            .toList()

    private companion object {
        const val DEPLOYMENT_NAME_KEY = "datapipelines.deployment.name"

        /** build/ and .git/ never hold shipping sources; entering them only slows the walk. */
        val EXCLUDED_DIRS = setOf("build", ".git")

        /** Production only — tests may name the key (this file does); `src/test` never ships. */
        const val SOURCE_SET = "/src/main/"

        /** 313 today; a floor, not a count — the walk must be over the real tree. */
        const val MODULE_FLOOR = 250
    }
}
