package co.datapipelines.application.dependencies

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #786 R3 — the schedule pin reader's Kotlin parse of the stored `version` number's text admits exactly the pipeline
 * executor's grammar (a positive JSON integer that fits an Int) and answers null for everything else without throwing.
 * The SQL side, against a real PostgreSQL, is [ApiAndScheduleConsumersIntegrationTest].
 */
class SchedulePinGrammarTest {
    @Test
    fun `every positive Int reads as its pin, nine and ten digits and Int MAX_VALUE included`() {
        SchedulePipelineConsumers.exactPin("1") shouldBe 1
        SchedulePipelineConsumers.exactPin("999999999") shouldBe 999_999_999
        SchedulePipelineConsumers.exactPin("1000000000") shouldBe 1_000_000_000
        SchedulePipelineConsumers.exactPin("2147483647") shouldBe Int.MAX_VALUE
    }

    @Test
    fun `past Int's range, non-positive or not a plain decimal reads as no pin and never throws`() {
        listOf("2147483648", "4294967297", "99999999999999999999999", "0", "-1", "01", "2.5", "1e3", " 2", "", null)
            .forEach { withClue(it) { SchedulePipelineConsumers.exactPin(it) shouldBe null } }
    }
}
