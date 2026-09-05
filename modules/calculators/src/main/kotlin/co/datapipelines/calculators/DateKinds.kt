package co.datapipelines.calculators

import co.datapipelines.calculators.CalculatorInput.Arity
import co.datapipelines.calculators.CalculatorValues.date
import co.datapipelines.calculators.CalculatorValues.int
import co.datapipelines.calculators.CalculatorValues.intOr
import co.datapipelines.calculators.CalculatorValues.list
import co.datapipelines.calculators.CalculatorValues.string
import co.datapipelines.calculators.CalculatorValues.stringOr
import co.datapipelines.calculators.CalculatorValues.timestamp
import co.datapipelines.typesystem.LogicalType.DATE
import co.datapipelines.typesystem.LogicalType.INTEGER
import co.datapipelines.typesystem.LogicalType.STRING
import co.datapipelines.typesystem.LogicalType.TIMESTAMP
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields

/**
 * The calendar and time kinds (calculators design §0.4).
 *
 * Every one of them is config-free: what the draft carried as a per-kind `config` block is an
 * ordinary input here, so `"fiscal_start": "$org_fiscal_start_date"` reads the deployment's
 * setting and `"fiscal_start": "09-15"` pins this pipeline's own, with no config edit and no
 * second authority. The conventions the fiscal kinds follow — a fiscal year is labelled by the
 * calendar year it STARTS in, `02-29` resolves to 02-28 in a non-leap year — live in [Calendar].
 */
internal object DateKinds {
    private val FISCAL_START =
        input("fiscal_start", STRING, "The fiscal year's first day as `MM-DD` — usually `\$org_fiscal_start_date`.")

    private val WEEK_START =
        input(
            "week_start",
            STRING,
            "Which day a week starts on (`monday` … `sunday`) — usually `\$org_week_start`.",
            required = false,
            default = "monday",
        )

    val ALL: List<CalculatorKind> =
        listOf(
            SimpleKind(
                kind = "quarter_of_year",
                displayName = "Quarter of year",
                description = "Which calendar quarter (1-4) a date falls in.",
                inputs = listOf(input("date", DATE, "The date to classify.")),
                output = INTEGER,
                example = example("date" to "2026-08-14", output = "3"),
            ) { values -> date(values, "date").get(IsoFields.QUARTER_OF_YEAR) },
            SimpleKind(
                kind = "fiscal_year",
                displayName = "Fiscal year",
                description =
                    "The fiscal year a date falls in, labelled by the calendar year the fiscal year STARTS in " +
                        "(with a 04-06 start, 2026-01-15 is fiscal year 2025).",
                inputs = listOf(input("date", DATE, "The date to classify."), FISCAL_START),
                output = INTEGER,
                example = example("date" to "2026-01-15", "fiscal_start" to "04-06", output = "2025"),
            ) { values ->
                Calendar.fiscalYearStart(date(values, "date"), Calendar.monthDay(string(values, "fiscal_start"), "fiscal_start")).year
            },
            SimpleKind(
                kind = "fiscal_quarter",
                displayName = "Fiscal quarter",
                description = "Which quarter (1-4) of its fiscal year a date falls in.",
                inputs = listOf(input("date", DATE, "The date to classify."), FISCAL_START),
                output = INTEGER,
                example = example("date" to "2026-08-14", "fiscal_start" to "09-15", output = "4"),
            ) { values ->
                Calendar.fiscalQuarter(date(values, "date"), Calendar.monthDay(string(values, "fiscal_start"), "fiscal_start"))
            },
            periodKind(
                kind = "period_start",
                displayName = "Period start",
                description = "The first day of the week, month, quarter or year containing a date.",
                exampleOutput = "2026-07-01",
            ) { date, unit, mode, fiscalStart, weekStart -> Calendar.periodStart(date, unit, mode, fiscalStart, weekStart) },
            periodKind(
                kind = "period_end",
                displayName = "Period end",
                description = "The last day of the week, month, quarter or year containing a date.",
                exampleOutput = "2026-09-30",
            ) { date, unit, mode, fiscalStart, weekStart -> Calendar.periodEnd(date, unit, mode, fiscalStart, weekStart) },
            SimpleKind(
                kind = "prior_period",
                displayName = "Prior period",
                description =
                    "The first day of the period `offset` periods before the one containing a date — " +
                        "the anchor a period-over-period comparison filters from.",
                inputs =
                    listOf(
                        input("date", DATE, "The date whose period the offset is counted back from."),
                        unitInput(Calendar.PERIOD_UNITS),
                        input("offset", INTEGER, "How many periods back.", required = false, default = "1"),
                        modeInput(),
                        FISCAL_START.copy(required = false, defaultDescription = "01-01"),
                        WEEK_START,
                    ),
                output = DATE,
                example = example("date" to "2026-08-14", "unit" to "quarter", "offset" to "1", output = "2026-04-01"),
            ) { values ->
                Calendar.priorPeriodStart(
                    date = date(values, "date"),
                    unit = Calendar.requireOneOf(string(values, "unit"), Calendar.PERIOD_UNITS, "unit"),
                    offset = intOr(values, "offset", 1),
                    mode = Calendar.requireOneOf(stringOr(values, "mode", Calendar.MODE_CALENDAR), Calendar.MODES, "mode"),
                    fiscalStart = Calendar.monthDay(stringOr(values, "fiscal_start", "01-01"), "fiscal_start"),
                    weekStart = Calendar.dayOfWeek(stringOr(values, "week_start", "monday"), "week_start"),
                )
            },
            SimpleKind(
                kind = "date_trunc",
                displayName = "Truncate date",
                description = "A date snapped back to the start of its day, week, month, quarter or year.",
                inputs =
                    listOf(
                        input("date", DATE, "The date to truncate."),
                        unitInput(Calendar.TRUNC_UNITS),
                        WEEK_START,
                    ),
                output = DATE,
                example = example("date" to "2026-08-14", "unit" to "month", output = "2026-08-01"),
            ) { values ->
                Calendar.periodStart(
                    date = date(values, "date"),
                    unit = Calendar.requireOneOf(string(values, "unit"), Calendar.TRUNC_UNITS, "unit"),
                    mode = Calendar.MODE_CALENDAR,
                    fiscalStart = java.time.MonthDay.of(1, 1),
                    weekStart = Calendar.dayOfWeek(stringOr(values, "week_start", "monday"), "week_start"),
                )
            },
            SimpleKind(
                kind = "iso_week",
                displayName = "ISO week",
                description = "The ISO-8601 week number (1-53) of a date.",
                inputs = listOf(input("date", DATE, "The date to classify.")),
                output = INTEGER,
                example = example("date" to "2026-01-01", output = "1"),
            ) { values -> date(values, "date").get(WeekFields.ISO.weekOfWeekBasedYear()) },
            SimpleKind(
                kind = "iso_year",
                displayName = "ISO week-based year",
                description =
                    "The ISO-8601 week-based year of a date — which differs from the calendar year " +
                        "in the days either side of New Year, and is why it is its own kind.",
                inputs = listOf(input("date", DATE, "The date to classify.")),
                output = INTEGER,
                example = example("date" to "2027-01-01", output = "2026"),
            ) { values -> date(values, "date").get(WeekFields.ISO.weekBasedYear()) },
            SimpleKind(
                kind = "day_of_week",
                displayName = "Day of week",
                description = "The day's position in the week (1-7), counting from `week_start`.",
                inputs = listOf(input("date", DATE, "The date to classify."), WEEK_START),
                output = INTEGER,
                example = example("date" to "2026-08-14", "week_start" to "monday", output = "5"),
            ) { values ->
                val weekStart = Calendar.dayOfWeek(stringOr(values, "week_start", "monday"), "week_start")
                (date(values, "date").dayOfWeek.value - weekStart.value + 7) % 7 + 1
            },
            SimpleKind(
                kind = "days_in_month",
                displayName = "Days in month",
                description = "How many days the date's calendar month has (28-31).",
                inputs = listOf(input("date", DATE, "Any date in the month.")),
                output = INTEGER,
                example = example("date" to "2028-02-10", output = "29"),
            ) { values -> date(values, "date").lengthOfMonth() },
            SimpleKind(
                kind = "date_diff",
                displayName = "Date difference",
                description =
                    "Whole units from one date to another; negative when `to` precedes `from`. " +
                        "Partial units are truncated, never rounded.",
                inputs =
                    listOf(
                        input("from", DATE, "The earlier date."),
                        input("to", DATE, "The later date."),
                        unitInput(Calendar.DIFF_UNITS),
                    ),
                output = INTEGER,
                example = example("from" to "2026-01-01", "to" to "2026-08-14", "unit" to "month", output = "7"),
            ) { values ->
                Calendar
                    .diff(
                        date(values, "from"),
                        date(values, "to"),
                        Calendar.requireOneOf(string(values, "unit"), Calendar.DIFF_UNITS, "unit"),
                    ).toInt()
            },
            SimpleKind(
                kind = "add_days",
                displayName = "Add days",
                description = "A date shifted by a whole number of calendar days; negative shifts back.",
                inputs = listOf(input("date", DATE, "The starting date."), input("days", INTEGER, "Days to add.")),
                output = DATE,
                example = example("date" to "2026-08-14", "days" to "-30", output = "2026-07-15"),
            ) { values -> date(values, "date").plusDays(int(values, "days").toLong()) },
            SimpleKind(
                kind = "add_months",
                displayName = "Add months",
                description =
                    "A date shifted by whole months, clamped to the target month's last day " +
                        "(2026-01-31 plus one month is 2026-02-28).",
                inputs = listOf(input("date", DATE, "The starting date."), input("months", INTEGER, "Months to add.")),
                output = DATE,
                example = example("date" to "2026-01-31", "months" to "1", output = "2026-02-28"),
            ) { values -> date(values, "date").plusMonths(int(values, "months").toLong()) },
            SimpleKind(
                kind = "add_business_days",
                displayName = "Add business days",
                description =
                    "A date shifted by working days, skipping the weekend days and the listed holidays. " +
                        "Negative counts step backwards; the starting date is never counted.",
                inputs =
                    listOf(
                        input("date", DATE, "The starting date."),
                        input("days", INTEGER, "Business days to add; negative steps back."),
                        input(
                            "weekend_days",
                            STRING,
                            "Day names that are not working days.",
                            required = false,
                            arity = Arity.LIST,
                            default = "[\"saturday\", \"sunday\"]",
                        ),
                        input(
                            "holidays",
                            DATE,
                            "Dates that are not working days, whatever day of the week they fall on.",
                            required = false,
                            arity = Arity.LIST,
                            default = "[]",
                        ),
                    ),
                output = DATE,
                example =
                    example(
                        "date" to "2026-08-14",
                        "days" to "1",
                        "holidays" to "[\"2026-08-17\"]",
                        output = "2026-08-18",
                    ),
            ) { values -> addBusinessDays(values) },
            SimpleKind(
                kind = "date_parse",
                displayName = "Parse date",
                description =
                    "A date read out of text with an explicit pattern. The grammar is Java's " +
                        "`DateTimeFormatter`, which is contract: `dd/MM/yyyy`, `yyyyMMdd`, `MMM d, yyyy`.",
                inputs =
                    listOf(
                        input("text", STRING, "The text to read."),
                        input("format", STRING, "A `DateTimeFormatter` pattern the text matches."),
                    ),
                output = DATE,
                example = example("text" to "14/08/2026", "format" to "dd/MM/yyyy", output = "2026-08-14"),
            ) { values -> parseDate(string(values, "text"), string(values, "format")) },
            SimpleKind(
                kind = "date_format",
                displayName = "Format date",
                description = "A date rendered as text with an explicit `DateTimeFormatter` pattern.",
                inputs =
                    listOf(
                        input("date", DATE, "The date to render."),
                        input("format", STRING, "A `DateTimeFormatter` pattern."),
                    ),
                output = STRING,
                example = example("date" to "2026-08-14", "format" to "yyyyMMdd", output = "20260814"),
            ) { values -> Calendar.formatter(string(values, "format"), "format").format(date(values, "date")) },
            SimpleKind(
                kind = "tz_shift",
                displayName = "Shift timezone",
                description =
                    "Re-reads a timestamp's wall-clock time from one zone in another: the clock face is " +
                        "kept and the instant moves. This is the kind for a timestamp that was stored " +
                        "under the wrong zone, not for displaying one — a timestamp is already absolute.",
                inputs =
                    listOf(
                        input("timestamp", TIMESTAMP, "The timestamp to re-read."),
                        input("from_zone", STRING, "The IANA zone whose wall-clock reading is kept."),
                        input("to_zone", STRING, "The IANA zone that reading is then interpreted in."),
                    ),
                output = TIMESTAMP,
                example =
                    example(
                        "timestamp" to "2026-06-01T12:00:00Z",
                        "from_zone" to "UTC",
                        "to_zone" to "Europe/Berlin",
                        output = "2026-06-01T10:00:00Z",
                    ),
            ) { values -> shiftZone(values) },
        )

    /** `period_start` and `period_end` differ only in which boundary they take. */
    private fun periodKind(
        kind: String,
        displayName: String,
        description: String,
        exampleOutput: String,
        boundary: (LocalDate, String, String, java.time.MonthDay, DayOfWeek) -> LocalDate,
    ): CalculatorKind =
        SimpleKind(
            kind = kind,
            displayName = displayName,
            description = description,
            inputs =
                listOf(
                    input("date", DATE, "Any date inside the period."),
                    unitInput(Calendar.PERIOD_UNITS),
                    modeInput(),
                    FISCAL_START.copy(required = false, defaultDescription = "01-01"),
                    WEEK_START,
                ),
            output = DATE,
            example = example("date" to "2026-08-14", "unit" to "quarter", output = exampleOutput),
        ) { values ->
            boundary(
                date(values, "date"),
                Calendar.requireOneOf(string(values, "unit"), Calendar.PERIOD_UNITS, "unit"),
                Calendar.requireOneOf(stringOr(values, "mode", Calendar.MODE_CALENDAR), Calendar.MODES, "mode"),
                Calendar.monthDay(stringOr(values, "fiscal_start", "01-01"), "fiscal_start"),
                Calendar.dayOfWeek(stringOr(values, "week_start", "monday"), "week_start"),
            )
        }

    private fun unitInput(allowed: List<String>) = input("unit", STRING, "One of ${allowed.joinToString(" | ") { "`$it`" }}.")

    private fun modeInput() =
        input(
            "mode",
            STRING,
            "`calendar` or `fiscal` — whether quarters and years follow the calendar or `fiscal_start`. " +
                "A month is a month in both.",
            required = false,
            default = "calendar",
        )

    private fun parseDate(
        text: String,
        format: String,
    ): LocalDate =
        runCatching { LocalDate.parse(text, Calendar.formatter(format, "format")) }
            .getOrElse { e ->
                if (e is CalculatorEvaluationException) throw e
                throw CalculatorEvaluationException("text", "Text '$text' does not match the pattern '$format'.", e)
            }

    private fun shiftZone(values: Map<String, Any?>): java.time.Instant {
        val from = Calendar.zone(string(values, "from_zone"), "from_zone")
        val to = Calendar.zone(string(values, "to_zone"), "to_zone")
        return timestamp(values, "timestamp")
            .atZone(from)
            .toLocalDateTime()
            .atZone(to)
            .toInstant()
    }

    /**
     * Steps one day at a time rather than computing whole weeks.
     *
     * Holidays fall wherever they fall — a closed-form week count has to special-case every one
     * of them anyway, and the loop is the version whose correctness a reader can see. It is
     * bounded by the caller's own `days`, and a pipeline asking for a million business days has a
     * bigger problem than this loop.
     */
    private fun addBusinessDays(values: Map<String, Any?>): LocalDate {
        val weekend =
            list(values, "weekend_days")
                .map { Calendar.dayOfWeek(it as? String ?: "$it", "weekend_days") }
                .ifEmpty { listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
                .toSet()
        val holidays = list(values, "holidays").map { holiday(it) }.toSet()
        val step = if (int(values, "days") < 0) -1L else 1L
        var remaining = kotlin.math.abs(int(values, "days"))
        var current = date(values, "date")
        while (remaining > 0) {
            current = current.plusDays(step)
            if (current.dayOfWeek !in weekend && current !in holidays) remaining--
        }
        return current
    }

    private fun holiday(value: Any?): LocalDate =
        when (value) {
            is LocalDate -> {
                value
            }

            // A literal `["2026-08-17"]` arrives as text when the author wrote it inline; the
            // validator types LIST literals element-wise, so this is the inline-authoring path.
            is String -> {
                runCatching { LocalDate.parse(value) }.getOrElse {
                    throw CalculatorEvaluationException("holidays", "Holiday '$value' is not an ISO date (yyyy-MM-dd).", it)
                }
            }

            else -> {
                throw CalculatorEvaluationException("holidays", "Holiday '$value' is not a date.")
            }
        }
}
