package co.datapipelines.web.api

import co.datapipelines.scheduler.ScheduleErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldNotBe
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
}
