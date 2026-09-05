package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * §7's organisation rules (§3.21, 072 calculators), in their own suite.
 *
 * Split from [ConfigValidatorTest] for the reason 068 split the key-provider rules out: §7 keeps
 * growing, one class per rule family keeps each readable, and detekt's `LargeClass` is the thing
 * that says when. The baseline is the shared [ConfigSnapshots.valid] — one copy, three suites, so
 * a "valid production configuration" cannot quietly diverge between them.
 *
 * Every rule here is a value that lands in EVERY execution's Context, which is why each one is
 * worth a startup refusal rather than a default: a wrong fiscal start is a wrong number in every
 * report the deployment produces, and nothing downstream can tell.
 */
class ConfigValidatorOrgTest {
    private fun validSnapshot() = ConfigSnapshots.valid()

    @Test
    fun `a month name in fiscal-start-date is refused with a message naming MM-DD`() {
        val report = ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "SEP-15"))

        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.org.fiscal-start-date")
        report.violations.single().shouldContain("SEP-15")
        // The whole point of the message: an operator who typed a month name must be told the
        // shape, not that "text could not be parsed at index 0".
        report.violations.single().shouldContain("MM-DD")
        report.violations.single().shouldContain("Month names are not accepted")
    }

    @Test
    fun `a fiscal-start-date that is MM-DD but not a calendar day is refused`() {
        // Shape-valid, calendar-invalid: the regex passes and MonthDay is what refuses it.
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "02-30")).violations.shouldHaveSize(1)
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "13-01")).violations.shouldHaveSize(1)
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "1-1")).violations.shouldHaveSize(1)
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "")).violations.shouldHaveSize(1)

        // 02-29 IS a calendar day; the fiscal kinds resolve it to 02-28 in a non-leap year.
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "02-29")).violations.shouldBeEmpty()
        ConfigValidator.validate(validSnapshot().copy(orgFiscalStartDate = "09-15")).violations.shouldBeEmpty()
    }

    @Test
    fun `week-start is monday or sunday, case-insensitively`() {
        ConfigValidator.validate(validSnapshot().copy(orgWeekStart = "MONDAY")).violations.shouldBeEmpty()
        ConfigValidator.validate(validSnapshot().copy(orgWeekStart = "sunday")).violations.shouldBeEmpty()

        val report = ConfigValidator.validate(validSnapshot().copy(orgWeekStart = "tuesday"))
        report.violations.shouldHaveSize(1)
        report.violations.single().shouldContain("datapipelines.org.week-start")
        report.violations.single().shouldContain("monday | sunday")
    }

    @Test
    fun `timezone must be an IANA id - a fixed offset is not one`() {
        ConfigValidator.validate(validSnapshot().copy(orgTimezone = "Europe/London")).violations.shouldBeEmpty()

        // ZoneId.of accepts "+02:00", so `runCatching { ZoneId.of(..) }` would have passed it.
        // The check is membership of the tz database, which is what "IANA id" means.
        ConfigValidator.validate(validSnapshot().copy(orgTimezone = "+02:00")).violations.shouldHaveSize(1)
        ConfigValidator.validate(validSnapshot().copy(orgTimezone = "Mars/Olympus")).violations.shouldHaveSize(1)
        ConfigValidator.validate(validSnapshot().copy(orgTimezone = " ")).violations.shouldHaveSize(1)
    }

    @Test
    fun `every broken org value is reported in one pass`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(
                    orgFiscalStartDate = "SEP-15",
                    orgWeekStart = "tuesday",
                    orgTimezone = "Mars/Olympus",
                    orgCurrencyName = "  ",
                    orgCurrencySymbol = "",
                ),
            )

        report.violations.shouldHaveSize(5)
        report.violations.forEach { it.shouldContain("datapipelines.org.") }
    }
}
