package co.datapipelines.lake.profiles

import co.datapipelines.lake.profiles.LakeProfileFixtures.document
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalTime
import java.time.ZoneId

/**
 * P5 (brief 956 D2; record call 35's value table): every key's floor and ceiling from both sides, the two cross-key
 * rules on a save of either value, the closed key list (the two retention values the record keeps in configuration
 * among the refused keys), wrong types and fractions. Each refusal is `lake.profile.invalid` naming the key and rule.
 */
class LakeProfileDocumentTest {
    @Test
    fun `the built-in document parses to every value and writes back to the same values`() {
        val profile = LakeProfileFixtures.builtIn()
        profile.maintenanceWindow shouldBe MaintenanceWindow(LocalTime.of(2, 0), LocalTime.of(5, 0))
        profile.maxBytesRewrittenPerRun shouldBe 50L * GIB
        profile.targetFileBytes shouldBe 64 * MIB
        profile.compactMinSmallFiles shouldBe 5
        profile.compactSmallFileRatio shouldBe 0.75
        profile.rewriteManifestsMinCount shouldBe 100
        profile.cleanupIntervalHours shouldBe 24
        profile.rowGroupBytes shouldBe MIB
        profile.keep shouldBe emptyList()
        profile.keepTimeZone shouldBe ZoneId.of("UTC")
        profile.latenessSample shouldBe 20
        profile.latenessOverdueFactor shouldBe 2.0
        profile.latenessMinDeliveries shouldBe 5
        LakeProfileDocument.parse(LakeProfileDocument.toJson(profile)) shouldBe profile
    }

    @Test
    fun `a start equal to the end is the whole day`() {
        val window = LakeProfileDocument.parse(document("maintenance.window" to "\"00:00-00:00\"")).maintenanceWindow
        window.isWholeDay shouldBe true
        window.text shouldBe "00:00-00:00"
    }

    @ParameterizedTest(name = "{0} = {1} passes")
    @MethodSource("admitted")
    fun `a value on the admitted side of its boundary passes`(
        key: String,
        json: String,
    ) {
        LakeProfileDocument.parse(document(*boundaryDocument(key, json)))
    }

    @ParameterizedTest(name = "{0} = {1} is refused {2}")
    @MethodSource("refused")
    fun `a value past its boundary is refused naming the key and the rule`(
        key: String,
        json: String,
        reason: String,
    ) {
        refusal(document(*boundaryDocument(key, json))) shouldBe (key to reason)
    }

    @Test
    fun `the cap equal to the target is refused, twice the target passes, on a save of either value`() {
        val target = 64 * MIB
        refusal(document("maintenance.max_bytes_rewritten_per_run" to "$target")) shouldBe (CAP to "below_twice_target")
        refusal(document("maintenance.max_bytes_rewritten_per_run" to "${2 * target - 1}")) shouldBe (CAP to "below_twice_target")
        LakeProfileDocument.parse(document("maintenance.max_bytes_rewritten_per_run" to "${2 * target}"))
        // A save of the target alone: a cap of 256 MiB admits a 128 MiB target and refuses one byte more.
        val cap = "maintenance.max_bytes_rewritten_per_run" to "${256 * MIB}"
        LakeProfileDocument.parse(document(cap, "maintenance.target_file_bytes" to "${128 * MIB}"))
        refusal(document(cap, "maintenance.target_file_bytes" to "${128 * MIB + 1}")) shouldBe (CAP to "below_twice_target")
    }

    @Test
    fun `the row group may equal the target and not exceed it, on a save of either value`() {
        val target = "maintenance.target_file_bytes" to "${16 * MIB}"
        val cap = "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}"
        LakeProfileDocument.parse(document(target, cap, "write.row_group_bytes" to "${16 * MIB}"))
        refusal(document(target, cap, "write.row_group_bytes" to "${16 * MIB + 1}")) shouldBe ("write.row_group_bytes" to "above_target")
        // A save that lowers the target under the stored row group.
        val rowGroup = "write.row_group_bytes" to "${32 * MIB}"
        refusal(document(target, cap, rowGroup)) shouldBe ("write.row_group_bytes" to "above_target")
    }

    @Test
    fun `the minimum deliveries may equal the sample and not exceed it`() {
        LakeProfileDocument.parse(document("reporting.lateness_sample" to "5", "reporting.lateness_min_deliveries" to "5"))
        refusal(document("reporting.lateness_sample" to "5", "reporting.lateness_min_deliveries" to "6")) shouldBe
            ("reporting.lateness_min_deliveries" to "above_sample")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownKeys")
    fun `every key outside the closed list is refused, the two retention values kept in configuration among them`(
        key: String,
        changes: List<Pair<String, String>>,
    ) {
        refusal(document(*changes.toTypedArray())) shouldBe (key to LakeProfileDocument.UNKNOWN_KEY)
    }

    @Test
    fun `every key of the closed list is required`() {
        LakeProfileDocument.KEYS.forEach { (group, keys) ->
            refusal(document(group to null)) shouldBe (group to LakeProfileDocument.REQUIRED)
            keys.forEach { key ->
                val path = LakeProfileDocument.path(group, key)
                refusal(document(path to null)) shouldBe (path to LakeProfileDocument.REQUIRED)
            }
        }
    }

    @Test
    fun `a wrong type, a fraction, a duplicate key and a non-object are refused`() {
        refusal(document("maintenance.window" to "200")) shouldBe ("maintenance.window" to LakeProfileDocument.TYPE)
        refusal(document("maintenance.target_file_bytes" to "\"64MB\"")) shouldBe (TARGET to LakeProfileDocument.TYPE)
        refusal(document("maintenance.target_file_bytes" to "67108864.5")) shouldBe (TARGET to LakeProfileDocument.WHOLE_NUMBER)
        refusal(document("maintenance.compact_small_file_ratio" to "\"0.5\"")) shouldBe
            ("maintenance.compact_small_file_ratio" to LakeProfileDocument.TYPE)
        refusal(document("maintenance.cleanup_interval_hours" to "99999999999999999999")) shouldBe
            ("maintenance.cleanup_interval_hours" to LakeProfileDocument.OUT_OF_RANGE)
        refusal(document("retention.keep_time_zone" to "1")) shouldBe ("retention.keep_time_zone" to LakeProfileDocument.TYPE)
        refusal(document("write" to "[]")) shouldBe ("write" to LakeProfileDocument.TYPE)
        refusal(LakeProfileFixtures.BUILT_IN.replaceFirst("{", "{\"write\": {\"row_group_bytes\": 1},")) shouldBe ("$" to "json")
        refusal("[]") shouldBe ("$" to LakeProfileDocument.TYPE)
        refusal("not json") shouldBe ("$" to "json")
    }

    @Test
    fun `the refusal is the catalogued code with the key as details path`() {
        val failure = shouldThrow<DatapipelinesException> { LakeProfileDocument.parse(document("write.row_group_bytes" to "1")) }
        failure.code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
        failure.details shouldBe mapOf("path" to "write.row_group_bytes", "reason" to LakeProfileDocument.OUT_OF_RANGE)
    }

    companion object {
        private const val KIB = 1024L
        private const val MIB = 1024L * KIB
        private const val GIB = 1024L * MIB
        private const val TARGET = "maintenance.target_file_bytes"
        private const val CAP = "maintenance.max_bytes_rewritten_per_run"

        /** A target change carries a cap of 1 TiB, so a target boundary is judged alone. */
        private fun boundaryDocument(
            key: String,
            json: String,
        ): Array<Pair<String, String?>> =
            when (key) {
                TARGET -> arrayOf(key to json, CAP to "${1024 * GIB}")
                "reporting.lateness_sample" -> arrayOf(key to json, "reporting.lateness_min_deliveries" to "2")
                else -> arrayOf(key to json)
            }

        private fun refusal(json: String): Pair<String, String> {
            val failure = shouldThrow<DatapipelinesException> { LakeProfileDocument.parse(json) }
            failure.code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
            return failure.details["path"] as String to failure.details["reason"] as String
        }

        @JvmStatic
        fun admitted(): List<Array<Any>> =
            listOf(
                arrayOf(TARGET, "${16 * MIB}"),
                arrayOf(TARGET, "${512 * MIB}"),
                arrayOf("write.row_group_bytes", "${64 * KIB}"),
                arrayOf("maintenance.compact_min_small_files", "2"),
                arrayOf("maintenance.rewrite_manifests_min_count", "2"),
                arrayOf("maintenance.cleanup_interval_hours", "1"),
                arrayOf("maintenance.compact_small_file_ratio", "0.01"),
                arrayOf("maintenance.compact_small_file_ratio", "0.99"),
                arrayOf("maintenance.window", "\"23:59-00:00\""),
                arrayOf("retention.keep_time_zone", "\"America/New_York\""),
                arrayOf("reporting.lateness_sample", "2"),
                arrayOf("reporting.lateness_sample", "200"),
                arrayOf("reporting.lateness_min_deliveries", "2"),
                arrayOf("reporting.lateness_overdue_factor", "1.01"),
                arrayOf("reporting.lateness_overdue_factor", "100"),
            )

        @JvmStatic
        fun refused(): List<Array<Any>> =
            listOf(
                arrayOf(TARGET, "${16 * MIB - 1}", "out_of_range"),
                arrayOf(TARGET, "${512 * MIB + 1}", "out_of_range"),
                arrayOf("write.row_group_bytes", "${64 * KIB - 1}", "out_of_range"),
                arrayOf("maintenance.compact_min_small_files", "1", "out_of_range"),
                arrayOf("maintenance.rewrite_manifests_min_count", "1", "out_of_range"),
                arrayOf("maintenance.cleanup_interval_hours", "0", "out_of_range"),
                arrayOf(CAP, "0", "out_of_range"),
                arrayOf("maintenance.compact_small_file_ratio", "0", "out_of_range"),
                arrayOf("maintenance.compact_small_file_ratio", "1", "out_of_range"),
                arrayOf("maintenance.window", "\"2am-5am\"", "grammar"),
                arrayOf("maintenance.window", "\"24:00-01:00\"", "grammar"),
                arrayOf("retention.keep_time_zone", "\"Mars/Olympus_Mons\"", "time_zone"),
                arrayOf("retention.keep_time_zone", "\"+02:00\"", "time_zone"),
                arrayOf("reporting.lateness_sample", "1", "out_of_range"),
                arrayOf("reporting.lateness_sample", "201", "out_of_range"),
                arrayOf("reporting.lateness_min_deliveries", "1", "out_of_range"),
                arrayOf("reporting.lateness_overdue_factor", "1", "out_of_range"),
                arrayOf("reporting.lateness_overdue_factor", "100.01", "out_of_range"),
            )

        @JvmStatic
        fun unknownKeys(): List<Array<Any>> =
            listOf(
                arrayOf("retention.protection_days", listOf("retention.protection_days" to "7")),
                arrayOf("retention.orphan_grace_hours", listOf("retention.orphan_grace_hours" to "24")),
                arrayOf("maintenance.pause", listOf("maintenance.pause" to "{}")),
                arrayOf("write.compression", listOf("write.compression" to "\"zstd\"")),
                arrayOf("admission", listOf("admission" to "{}")),
            )
    }
}
