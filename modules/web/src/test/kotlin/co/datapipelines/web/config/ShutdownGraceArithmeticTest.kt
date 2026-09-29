package co.datapipelines.web.config

import co.datapipelines.persistence.BatchingConfig
import co.datapipelines.scheduler.SchedulerProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #277 — the stop grace the shipped deploy files give the container covers the whole shutdown
 * sequence at its CONFIGURED MAXIMUM, with room left for Tomcat (deployment §8.3.2, scheduler §8.2).
 *
 * On SIGTERM, in order: Helm's `preStop` sleep (compose has none); the scheduler's admission gate,
 * which waits up to `shutdown-wait-seconds` — at most [SchedulerProperties.MAX_SHUTDOWN_WAIT_SECONDS];
 * the execution drain's bounded flush ([ExecutionDrainLifecycle.DEFAULT_FLUSH_TIMEOUT_MILLIS]); then
 * Tomcat's graceful phase, given [TOMCAT_GRACEFUL_SECONDS]; then the persistence drain (#266) —
 * every batching writer flushes for at most `shutdown-drain-ms`, shipped as
 * [BatchingConfig.DEFAULT_SHUTDOWN_DRAIN_MILLIS] (the 316 merge's review found it missing here
 * and in deployment §8.3.2). The scheduled jobs' scheduler stops AFTER all of these (#316) and is
 * not in the sum: a tick in flight is awaited up to the phase timeout, but nothing the drains own
 * is lost if the runtime kills during that wait. A runtime that kills before the drain
 * finishes leaves executions RUNNING, the sweep records them lost, and a lost scheduled run BLOCKS
 * its schedule — so the grace is derived from the code's own bounds here, never restated.
 *
 * Until #277 both files said 40 s: at the gate's maximum the three scheduler phases alone reached
 * 40 s under Helm, leaving Tomcat nothing.
 */
class ShutdownGraceArithmeticTest {
    private val schedulerPhases =
        (SchedulerProperties.MAX_SHUTDOWN_WAIT_SECONDS + ExecutionDrainLifecycle.DEFAULT_FLUSH_TIMEOUT_MILLIS / MILLIS).toInt()
    private val persistenceDrain = (BatchingConfig.DEFAULT_SHUTDOWN_DRAIN_MILLIS / MILLIS).toInt()

    @Test
    fun `Helm's grace covers preStop, the admission gate at its maximum, the drain, Tomcat's phase and the persistence drain`() {
        val preStop = helmPreStopSeconds()
        val required = preStop + schedulerPhases + TOMCAT_GRACEFUL_SECONDS + persistenceDrain
        withClue(
            "values.yaml terminationGracePeriodSeconds against $preStop + $schedulerPhases + $TOMCAT_GRACEFUL_SECONDS + $persistenceDrain",
        ) {
            helmGraceSeconds() shouldBeGreaterThanOrEqual required
        }
    }

    @Test
    fun `compose's stop grace covers the admission gate at its maximum, the drain, Tomcat's phase and the persistence drain`() {
        withClue("deploy/compose.yml stop_grace_period against $schedulerPhases + $TOMCAT_GRACEFUL_SECONDS + $persistenceDrain") {
            composeGraceSeconds() shouldBeGreaterThanOrEqual schedulerPhases + TOMCAT_GRACEFUL_SECONDS + persistenceDrain
        }
    }

    @Test
    fun `every value deployment_md states for the grace is the chart's`() {
        // §6's Deployment bullet and §8.3.2's pod lifecycle snippet both state it; before #277
        // §8.3.2 still said 30 while the chart said 40. The change log is history — its rows
        // quote the values of their day — so the scan stops at its heading.
        val living = repoFile("docs/deployment.md").readText().substringBefore(CHANGE_LOG_HEADING)
        val documented =
            Regex("""terminationGracePeriodSeconds:\s*(\d+)""")
                .findAll(living)
                .map { it.groupValues[1].toInt() }
                .toList()
        withClue("deployment.md's terminationGracePeriodSeconds values") {
            documented.isNotEmpty() shouldBe true
            documented.distinct() shouldBe listOf(helmGraceSeconds())
        }
    }

    private fun helmGraceSeconds(): Int =
        captured("deploy/helm/datapipelines/values.yaml", Regex("""(?m)^terminationGracePeriodSeconds:\s*(\d+)"""))

    private fun helmPreStopSeconds(): Int =
        captured("deploy/helm/datapipelines/templates/deployment.yaml", Regex("""command:\s*\["sleep",\s*"(\d+)"]"""))

    private fun composeGraceSeconds(): Int = captured("deploy/compose.yml", Regex("""(?m)^\s+stop_grace_period:\s*(\d+)s\s*$"""))

    private fun captured(
        relative: String,
        pattern: Regex,
    ): Int {
        val matches = pattern.findAll(repoFile(relative).readText()).map { it.groupValues[1].toInt() }.toList()
        withClue("$relative must state exactly one value for $pattern") { matches.size shouldBe 1 }
        return matches.single()
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        /**
         * The share of the grace left for Tomcat's graceful phase once the scheduler's phases are
         * done — requests still in flight end in it. Spring bounds the phase itself at
         * `spring.lifecycle.timeout-per-shutdown-phase` (default 30 s); a request still running
         * past this allowance is cut by the runtime's kill, which costs that one request and none
         * of the execution bookkeeping the drain already wrote.
         */
        const val TOMCAT_GRACEFUL_SECONDS = 10
        const val MILLIS = 1_000L
        const val CHANGE_LOG_HEADING = "## Appendix C: Change Log"
    }
}
