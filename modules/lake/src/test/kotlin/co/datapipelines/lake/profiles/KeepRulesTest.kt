package co.datapipelines.lake.profiles

import co.datapipelines.lake.profiles.LakeProfileFixtures.document
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.TimeZone

/**
 * P5's `retention.keep` half (brief 956 D2; record call 42): every example written under D2's table, by name, on both
 * sides of its boundary; `n` of 0, a fraction and above its unit's range; the list's shape; and the verdict being the
 * same on any save date and in any zone.
 */
class KeepRulesTest {
    @ParameterizedTest(name = "{0} kept for {1} {2} passes")
    @MethodSource("passing")
    fun `every passing example of D2 passes`(
        period: String,
        n: Int,
        unit: String,
    ) {
        LakeProfileDocument.parse(keep(rule(period, n, unit))).keep shouldBe
            listOf(KeepRule(requireNotNull(KeepPeriod.fromWire(period)), KeepDuration(n, requireNotNull(KeepUnit.fromWire(unit)))))
    }

    @ParameterizedTest(name = "{0} kept for {1} {2} is refused {3}")
    @MethodSource("refused")
    fun `every refused example of D2 is refused naming the rule`(
        period: String,
        n: Int,
        unit: String,
        reason: String,
    ) {
        refusal(keep(rule(period, n, unit))) shouldBe Triple(KEEP, reason, 0)
    }

    @Test
    fun `maxDays is computed over the 400-year cycle - 1 month 31, 12 months and 1 year 366, 13 months 397`() {
        KeepRuleLimits.maxDays(KeepDuration(1, KeepUnit.MONTHS)) shouldBe 31
        KeepRuleLimits.maxDays(KeepDuration(12, KeepUnit.MONTHS)) shouldBe 366
        KeepRuleLimits.maxDays(KeepDuration(1, KeepUnit.YEARS)) shouldBe 366
        KeepRuleLimits.maxDays(KeepDuration(13, KeepUnit.MONTHS)) shouldBe 397
        KeepRuleLimits.maxDays(KeepDuration(7, KeepUnit.DAYS)) shouldBe 7
        KeepRuleLimits.liveKeeps(KeepRule(KeepPeriod.DAY, KeepDuration(13, KeepUnit.MONTHS))) shouldBe 397
    }

    @Test
    fun `n of 0, a fraction and above each unit's range are refused`() {
        refusal(keep(rule("day", 0, "days"))) shouldBe Triple(KEEP, LakeProfileDocument.OUT_OF_RANGE, 0)
        refusal(keep("""{"period": "day", "keep_for": {"n": 1.5, "unit": "days"}}""")) shouldBe
            Triple(KEEP, LakeProfileDocument.WHOLE_NUMBER, 0)
        refusal(keep(rule("year", 146_101, "days"))) shouldBe Triple(KEEP, LakeProfileDocument.OUT_OF_RANGE, 0)
        refusal(keep(rule("year", 4_801, "months"))) shouldBe Triple(KEEP, LakeProfileDocument.OUT_OF_RANGE, 0)
        refusal(keep(rule("year", 401, "years"))) shouldBe Triple(KEEP, LakeProfileDocument.OUT_OF_RANGE, 0)
        LakeProfileDocument.parse(keep(rule("year", 400, "years")))
        LakeProfileDocument.parse(keep(rule("year", 4_800, "months")))
    }

    @Test
    fun `the list's shape - one rule per period, known names, exact keys, the failing rule's index`() {
        LakeProfileDocument.parse(keep(rule("day", 7, "days"), rule("month", 12, "months"), rule("year", 7, "years"))).keep.size shouldBe 3
        refusal(keep(rule("day", 7, "days"), rule("day", 14, "days"))) shouldBe Triple(KEEP, "duplicate_period", 1)
        refusal(keep(rule("day", 7, "days"), rule("decade", 1, "years"))) shouldBe Triple(KEEP, "period", 1)
        refusal(keep(rule("week", 2, "weeks"))) shouldBe Triple(KEEP, "unit", 0)
        refusal(keep("""{"period": "day", "keep_for": {"n": 7, "unit": "days"}, "tag": "x"}""")) shouldBe
            Triple(KEEP, LakeProfileDocument.UNKNOWN_KEY, 0)
        refusal(keep("""{"period": "day", "keep_for": {"n": 7, "unit": "days", "zone": "UTC"}}""")) shouldBe
            Triple(KEEP, LakeProfileDocument.UNKNOWN_KEY, 0)
        refusal(keep("""{"period": "day"}""")) shouldBe Triple(KEEP, LakeProfileDocument.REQUIRED, 0)
        refusal(keep("\"day\"")) shouldBe Triple(KEEP, LakeProfileDocument.TYPE, 0)
        val notAList = shouldThrow<DatapipelinesException> { LakeProfileDocument.parse(document("retention.keep" to "{}")) }
        notAList.details shouldBe mapOf("path" to KEEP, "reason" to LakeProfileDocument.TYPE)
    }

    /**
     * The verdict takes no clock: the three files that decide it never read the time (the scan below), and the same
     * documents give the same verdicts with the JVM's default zone and the profile's zone set to January-, leap-year-
     * February- and daylight-saving-shaped zones. (A save has no clock to set; the source scan is what pins it.)
     */
    @Test
    fun `the same document gets the same verdict on any save date and in any zone`() {
        val sources = listOf("KeepRuleLimits.kt", "KeepRulesReader.kt", "LakeProfileDocument.kt")
        sources.forEach { name ->
            val text = File(PROFILES_SOURCES, name).readText()
            CLOCK_READS.filter { it.containsMatchIn(text) } shouldBe emptyList()
        }
        val documents = (passing() + refused()).map { keep(rule(it[0] as String, it[1] as Int, it[2] as String)) }
        val original = TimeZone.getDefault()
        val verdicts =
            try {
                ZONES.map { zone ->
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    documents.map { verdict(it.replace("\"UTC\"", "\"$zone\"")) }
                }
            } finally {
                TimeZone.setDefault(original)
            }
        verdicts.distinct().size shouldBe 1
        verdicts.first().count { it == null } shouldBe passing().size
    }

    private fun verdict(json: String): String? =
        try {
            LakeProfileDocument.parse(json)
            null
        } catch (failure: DatapipelinesException) {
            failure.details["reason"] as String
        }

    companion object {
        private const val KEEP = "retention.keep"
        private val PROFILES_SOURCES = File("src/main/kotlin/co/datapipelines/lake/profiles")
        private val CLOCK_READS =
            listOf(Regex("""\bnow\s*\("""), Regex("""\bClock\b"""), Regex("""currentTimeMillis"""), Regex("""\bInstant\b"""))

        /** UTC; a southern zone whose January is summer; a zone with daylight saving (New York); a leap-day-ready zone. */
        private val ZONES = listOf("UTC", "Australia/Sydney", "America/New_York", "Pacific/Kiritimati")

        private fun rule(
            period: String,
            n: Int,
            unit: String,
        ): String = """{"period": "$period", "keep_for": {"n": $n, "unit": "$unit"}}"""

        private fun keep(vararg rules: String): String = document("retention.keep" to rules.joinToString(",", "[", "]"))

        private fun refusal(json: String): Triple<String, String, Int?> {
            val failure = shouldThrow<DatapipelinesException> { LakeProfileDocument.parse(json) }
            failure.code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
            return Triple(failure.details["path"] as String, failure.details["reason"] as String, failure.details["index"] as Int?)
        }

        /** D2's passing examples, by name. */
        @JvmStatic
        fun passing(): List<Array<Any>> =
            listOf(
                arrayOf("month", 31, "days"),
                arrayOf("month", 1, "months"),
                arrayOf("year", 366, "days"),
                arrayOf("year", 12, "months"),
                arrayOf("year", 1, "years"),
                arrayOf("day", 13, "months"),
                arrayOf("day", 400, "days"),
                arrayOf("month", 400, "months"),
                arrayOf("week", 2_800, "days"),
                arrayOf("day", 1, "days"),
                arrayOf("week", 7, "days"),
                arrayOf("week", 1, "months"),
                arrayOf("quarter", 92, "days"),
                arrayOf("quarter", 3, "months"),
                arrayOf("quarter", 1, "years"),
            )

        /** D2's refused examples, by name, each the other side of a passing one. */
        @JvmStatic
        fun refused(): List<Array<Any>> =
            listOf(
                arrayOf("month", 30, "days", "keep_for_shorter_than_period"),
                arrayOf("year", 365, "days", "keep_for_shorter_than_period"),
                arrayOf("year", 11, "months", "keep_for_shorter_than_period"),
                arrayOf("day", 7, "years", "too_many_keeps"),
                arrayOf("day", 401, "days", "too_many_keeps"),
                arrayOf("month", 401, "months", "too_many_keeps"),
                arrayOf("week", 2_801, "days", "too_many_keeps"),
                arrayOf("week", 6, "days", "keep_for_shorter_than_period"),
                arrayOf("quarter", 91, "days", "keep_for_shorter_than_period"),
                arrayOf("quarter", 2, "months", "keep_for_shorter_than_period"),
            )
    }
}
