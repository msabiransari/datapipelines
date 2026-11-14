package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * §7 / §3.29 (#957 R9) — with the managed lake and the scheduler enabled, `datapipelines.scheduler.threads` is at least
 * `max-concurrent-maintenance` + 2, the refusal naming both keys; the shipped defaults, read off `application.yml`, pass.
 */
class SchedulerThreadsRulesTest {
    private val lake: Map<String, String?> by lazy {
        ConfigSnapshots.lakeDefaults() + mapOf(LakeRules.ENABLED to "true", LakeRules.BUCKET to "dp-lake", LakeRules.REGION to "eu-west-1")
    }
    private val scheduler: Map<String, String?> by lazy { ConfigSnapshots.schedulerDefaults() }

    private fun violations(
        threads: String?,
        maintenance: String?,
        lakeOverrides: Map<String, String?> = emptyMap(),
        schedulerOverrides: Map<String, String?> = emptyMap(),
    ): List<String> {
        val lakeKeys = lake + listOfNotNull(maintenance?.let { LakeRules.MAX_CONCURRENT_MAINTENANCE to it }) + lakeOverrides
        val schedulerKeys = scheduler + listOfNotNull(threads?.let { SchedulerThreadsRules.KEY_THREADS to it }) + schedulerOverrides
        return ConfigValidator.validate(ConfigSnapshots.valid().copy(lake = lakeKeys, scheduler = schedulerKeys)).violations
    }

    @Test
    fun `threads 2 with maintenance 1 is refused naming both keys`() {
        val refusal = violations(threads = "2", maintenance = "1").single()
        refusal shouldStartWith SchedulerThreadsRules.KEY_THREADS
        refusal shouldContain LakeRules.MAX_CONCURRENT_MAINTENANCE
        refusal shouldContain "(2)"
        refusal shouldContain "= 3"
    }

    @Test
    fun `threads 3 with maintenance 1 passes`() {
        violations(threads = "3", maintenance = "1") shouldContainExactly emptyList()
    }

    @Test
    fun `threads 3 with maintenance 2 is refused`() {
        val refusal = violations(threads = "3", maintenance = "2").single()
        refusal shouldStartWith SchedulerThreadsRules.KEY_THREADS
        refusal shouldContain LakeRules.MAX_CONCURRENT_MAINTENANCE
    }

    @Test
    fun `the shipped defaults pass, read from application yml and not restated here`() {
        // Non-vacuity: the yml carries both numbers and the scheduler's switch, and the pair satisfies the rule.
        scheduler.filterValues { it == null }.keys shouldContainExactly emptySet()
        val threads = requireNotNull(scheduler[SchedulerThreadsRules.KEY_THREADS]).toLong()
        val maintenance = requireNotNull(lake[LakeRules.MAX_CONCURRENT_MAINTENANCE]).toLong()
        (threads >= maintenance + SchedulerThreadsRules.THREADS_BEYOND_MAINTENANCE) shouldBe true
        violations(threads = null, maintenance = null) shouldContainExactly emptyList()
    }

    @Test
    fun `a disabled lake or a disabled scheduler is not judged`() {
        violations(threads = "1", maintenance = "1", lakeOverrides = mapOf(LakeRules.ENABLED to "false")) shouldContainExactly emptyList()
        violations(
            threads = "1",
            maintenance = "1",
            schedulerOverrides = mapOf(SchedulerThreadsRules.KEY_ENABLED to "false"),
        ) shouldContainExactly emptyList()
    }

    @Test
    fun `an unset maintenance key is judged at its default of 1`() {
        val refusals =
            violations(threads = "2", maintenance = null, lakeOverrides = mapOf(LakeRules.MAX_CONCURRENT_MAINTENANCE to null))
                .filter { it.startsWith(SchedulerThreadsRules.KEY_THREADS) }
        refusals.single() shouldContain "${LakeRules.MAX_CONCURRENT_MAINTENANCE} (1)"
    }

    @Test
    fun `a malformed thread count is named once`() {
        violations(threads = "many", maintenance = "1").single() shouldStartWith SchedulerThreadsRules.KEY_THREADS
        violations(threads = "0", maintenance = "1").single() shouldStartWith SchedulerThreadsRules.KEY_THREADS
    }
}
