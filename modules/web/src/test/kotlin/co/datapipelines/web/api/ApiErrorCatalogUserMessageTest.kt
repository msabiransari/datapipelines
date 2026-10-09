package co.datapipelines.web.api

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.scheduler.ScheduleErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #280 — every schedule code the form places at a field (`static/js/schedules/model.js`
 * FIELD_BY_CODE, spelled out here) must resolve to its OWN `USER_MESSAGE_OVERRIDES` row, never
 * to the `schedule.validation.` family line: the family line ("Check the reported field and try
 * again.") is what the person saw while the server's refusal named the rule — the defect.
 *
 * The family line is read off the catalogue itself by probing a code no override exists for, so
 * this stays honest if the family sentence is ever reworded. The browser suite pins the words at
 * the field end to end (the slot's text flows catalogue → envelope → DOM).
 */
class ApiErrorCatalogUserMessageTest {
    @Test
    fun `every schedule code the form places at a field resolves to an override, not the family line`() {
        val fieldCodes =
            listOf(
                ScheduleErrorCodes.NAME_INVALID,
                ScheduleErrorCodes.NAME_TAKEN,
                ScheduleErrorCodes.CRON_INVALID,
                ScheduleErrorCodes.INTERVAL_TOO_SHORT,
                ScheduleErrorCodes.TIMEZONE_INVALID,
                ScheduleErrorCodes.PAYLOAD_INVALID,
                ScheduleErrorCodes.TARGET_NOT_FOUND,
                ScheduleErrorCodes.TARGET_NOT_RELEASED,
                ScheduleErrorCodes.EXECUTOR_UNKNOWN,
            )
        val family =
            ApiErrorCatalog.userMessageFor("schedule.validation.no_such_code_probe")
        fieldCodes.forEach { code ->
            withClue("$code resolves to the family line — add its USER_MESSAGE_OVERRIDES row") {
                ApiErrorCatalog.userMessageFor(code) shouldNotBe family
            }
        }
    }

    @Test
    fun `the two screenshot refusals speak about the image - never the family's start-a-new-session line`() {
        val family = ApiErrorCatalog.userMessageFor("visualization.test.no_such_code_probe")
        listOf(
            co.datapipelines.visualization.VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE,
            co.datapipelines.visualization.VisualizationErrorCodes.TEST_SCREENSHOT_INVALID,
        ).forEach { code ->
            withClue(code) { ApiErrorCatalog.userMessageFor(code) shouldNotBe family }
        }
        // The session codes keep the family line: restarting IS the remedy there.
        ApiErrorCatalog.userMessageFor(co.datapipelines.visualization.VisualizationErrorCodes.TEST_SESSION_EXPIRED) shouldBe family
    }

    @Test
    fun `the search-needle refusal speaks about the search term and its bound - never the document family's line`() {
        val family = ApiErrorCatalog.userMessageFor("parameter.validation.no_such_code_probe")
        val message = ApiErrorCatalog.userMessageFor(ParameterErrorCodes.QUERY_TOO_LONG)
        message shouldNotBe family
        message shouldContain "${ParameterSetService.MAX_QUERY_LENGTH} characters"
    }
}
