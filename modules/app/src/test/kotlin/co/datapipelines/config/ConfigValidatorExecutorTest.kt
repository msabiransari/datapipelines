package co.datapipelines.config

import co.datapipelines.web.schedules.PipelineJobExecutor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * §7's lifecycle-write ceiling (§3.2, #325), in its own suite like the Redis bounds.
 *
 * `datapipelines.executor.lifecycle-write-timeout-seconds` bounds the RUNNING insert; the scheduler's start
 * barrier waits `PipelineJobExecutor.DEFAULT_START_WAIT` (30 s) for that row. A bound the barrier does not
 * outlast — with 5 s headroom for the launch's own setup — ends a scheduled run whose row cannot be written
 * `unknown` instead of `record_unwritable`, so boot refuses it.
 */
class ConfigValidatorExecutorTest {
    private fun violationsFor(raw: String?): List<String> =
        ConfigValidator.validate(ConfigSnapshots.valid().copy(executorLifecycleWriteTimeoutSeconds = raw)).violations

    @Test
    fun `the ceiling is the start wait less the headroom - 25 s`() {
        ExecutorRules.LIFECYCLE_WRITE_CEILING_SECONDS shouldBe 25L
        PipelineJobExecutor.DEFAULT_START_WAIT.seconds - ExecutorRules.START_WAIT_HEADROOM_SECONDS shouldBe 25L
    }

    @Test
    fun `the ceiling itself passes`() {
        violationsFor("25").shouldBeEmpty()
    }

    @Test
    fun `one second past the ceiling is refused naming the key, the value, the ceiling and the consequence`() {
        val violations = violationsFor("26")
        violations.shouldHaveSize(1)
        violations.single().shouldContain("datapipelines.executor.lifecycle-write-timeout-seconds is '26'")
        violations.single().shouldContain("caps it at 25 s")
        violations.single().shouldContain("30 s start wait")
        violations.single().shouldContain("'unknown', not 'record_unwritable'")
        violations.single().shouldContain("§3.2")
    }

    @Test
    fun `zero, negative, non-numeric and blank values are each refused naming the key and value`() {
        listOf("0", "-1", "abc", "", "  ", "2.5").forEach { raw ->
            val violations = violationsFor(raw)
            violations.shouldHaveSize(1)
            violations.single().shouldContain("datapipelines.executor.lifecycle-write-timeout-seconds is '$raw'")
            violations.single().shouldContain("at least 1")
        }
    }

    @Test
    fun `an unset key, the shipped 10 and the smallest bound pass`() {
        ConfigSnapshots.valid().executorLifecycleWriteTimeoutSeconds shouldBe null
        violationsFor(null).shouldBeEmpty()
        violationsFor("10").shouldBeEmpty()
        violationsFor("1").shouldBeEmpty()
    }
}
