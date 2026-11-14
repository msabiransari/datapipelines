package co.datapipelines.lake.profiles

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId

/**
 * The one reader and writer of a profile's `values_json` (lake.md "Profiles"; brief 956 D1, D2). [parse] refuses,
 * before anything is written, an unknown key (`retention.protection_days` and `retention.orphan_grace_hours` among
 * them: one value per deployment, record call 35), a missing key, a wrong type and a value outside its rule, each as
 * `lake.profile.invalid` naming the key and the rule. The key list is closed. [toJson] writes the canonical document.
 *
 * The document is nested by the key's dotted prefix: `{"maintenance": {"window": …}, "write": {…}, "retention": {…},
 * "reporting": {…}}`. Byte sizes are whole numbers of bytes.
 */
@Suppress("TooManyFunctions") // one reader per value type, each naming its key in its refusal
object LakeProfileDocument {
    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build()

    /** The closed key list: group → keys. */
    val KEYS: Map<String, List<String>> =
        linkedMapOf(
            MAINTENANCE to
                listOf(
                    WINDOW,
                    MAX_BYTES_REWRITTEN,
                    TARGET_FILE_BYTES,
                    COMPACT_MIN_SMALL_FILES,
                    COMPACT_SMALL_FILE_RATIO,
                    REWRITE_MANIFESTS_MIN_COUNT,
                    CLEANUP_INTERVAL_HOURS,
                ),
            WRITE to listOf(ROW_GROUP_BYTES),
            RETENTION to listOf(KEEP, KEEP_TIME_ZONE),
            REPORTING to listOf(LATENESS_SAMPLE, LATENESS_OVERDUE_FACTOR, LATENESS_MIN_DELIVERIES),
        )

    fun parse(json: String): LakeProfile {
        val root =
            try {
                mapper.readTree(json)
            } catch (failure: JacksonException) {
                throw LakeProfileErrors.invalid("$", "json", "the document is not JSON (${failure.originalMessage})")
            }
        if (root !is ObjectNode) throw LakeProfileErrors.invalid("$", "type", "the document must be a JSON object")
        checkKeys(root)
        val target = bytes(root, MAINTENANCE, TARGET_FILE_BYTES, MIN_TARGET_FILE_BYTES, MAX_TARGET_FILE_BYTES)
        val cap = bytes(root, MAINTENANCE, MAX_BYTES_REWRITTEN, 1, Long.MAX_VALUE)
        val rowGroup = bytes(root, WRITE, ROW_GROUP_BYTES, MIN_ROW_GROUP_BYTES, Long.MAX_VALUE)
        val sample = int(root, REPORTING, LATENESS_SAMPLE, MIN_LATENESS_SAMPLE, MAX_LATENESS_SAMPLE)
        val profile =
            LakeProfile(
                maintenanceWindow = window(root),
                maxBytesRewrittenPerRun = cap,
                targetFileBytes = target,
                compactMinSmallFiles = int(root, MAINTENANCE, COMPACT_MIN_SMALL_FILES, MIN_SMALL_FILES, Int.MAX_VALUE),
                compactSmallFileRatio = ratio(root),
                rewriteManifestsMinCount = int(root, MAINTENANCE, REWRITE_MANIFESTS_MIN_COUNT, MIN_MANIFESTS, Int.MAX_VALUE),
                cleanupIntervalHours = int(root, MAINTENANCE, CLEANUP_INTERVAL_HOURS, 1, Int.MAX_VALUE),
                rowGroupBytes = rowGroup,
                keep = KeepRulesReader.read(root.path(RETENTION).path(KEEP), path(RETENTION, KEEP)),
                keepTimeZone = zone(root),
                latenessSample = sample,
                latenessOverdueFactor = overdueFactor(root),
                latenessMinDeliveries = int(root, REPORTING, LATENESS_MIN_DELIVERIES, MIN_LATENESS_DELIVERIES, Int.MAX_VALUE),
            )
        checkAcrossKeys(profile)
        return profile
    }

    fun toJson(profile: LakeProfile): String =
        mapper.writeValueAsString(
            linkedMapOf(
                MAINTENANCE to
                    linkedMapOf(
                        WINDOW to profile.maintenanceWindow.text,
                        MAX_BYTES_REWRITTEN to profile.maxBytesRewrittenPerRun,
                        TARGET_FILE_BYTES to profile.targetFileBytes,
                        COMPACT_MIN_SMALL_FILES to profile.compactMinSmallFiles,
                        COMPACT_SMALL_FILE_RATIO to profile.compactSmallFileRatio,
                        REWRITE_MANIFESTS_MIN_COUNT to profile.rewriteManifestsMinCount,
                        CLEANUP_INTERVAL_HOURS to profile.cleanupIntervalHours,
                    ),
                WRITE to linkedMapOf(ROW_GROUP_BYTES to profile.rowGroupBytes),
                RETENTION to
                    linkedMapOf(
                        KEEP to
                            profile.keep.map {
                                linkedMapOf(
                                    "period" to it.period.wire,
                                    "keep_for" to linkedMapOf("n" to it.keepFor.n, "unit" to it.keepFor.unit.wire),
                                )
                            },
                        KEEP_TIME_ZONE to profile.keepTimeZone.id,
                    ),
                REPORTING to
                    linkedMapOf(
                        LATENESS_SAMPLE to profile.latenessSample,
                        LATENESS_OVERDUE_FACTOR to profile.latenessOverdueFactor,
                        LATENESS_MIN_DELIVERIES to profile.latenessMinDeliveries,
                    ),
            ),
        )

    /** Dotted key → the canonical JSON text of its value, in [KEYS] order. */
    fun flatten(profile: LakeProfile): Map<String, String> {
        val tree = mapper.readTree(toJson(profile))
        return KEYS
            .flatMap { (group, keys) -> keys.map { key -> path(group, key) to tree.path(group).path(key).toString() } }
            .toMap(LinkedHashMap())
    }

    /** The dotted key of [group] and [key], as `details.path` names it. */
    fun path(
        group: String,
        key: String,
    ): String = "$group.$key"

    private fun checkKeys(root: ObjectNode) {
        root.fieldNames().forEach { group ->
            if (group !in KEYS) throw LakeProfileErrors.invalid(group, UNKNOWN_KEY, "$group is not a profile key")
        }
        KEYS.forEach { (group, keys) ->
            val node = root.get(group) ?: throw LakeProfileErrors.invalid(group, REQUIRED, "$group is required")
            if (node !is ObjectNode) throw LakeProfileErrors.invalid(group, TYPE, "$group must be an object")
            node.fieldNames().forEach { key ->
                if (key !in keys) throw LakeProfileErrors.invalid(path(group, key), UNKNOWN_KEY, "${path(group, key)} is not a profile key")
            }
            keys.forEach { key ->
                if (!node.has(key)) throw LakeProfileErrors.invalid(path(group, key), REQUIRED, "${path(group, key)} is required")
            }
        }
    }

    private fun checkAcrossKeys(profile: LakeProfile) {
        if (profile.maxBytesRewrittenPerRun / 2 < profile.targetFileBytes) {
            throw LakeProfileErrors.invalid(
                path(MAINTENANCE, MAX_BYTES_REWRITTEN),
                "below_twice_target",
                "must be at least twice ${path(MAINTENANCE, TARGET_FILE_BYTES)} (${profile.targetFileBytes})",
            )
        }
        if (profile.rowGroupBytes > profile.targetFileBytes) {
            throw LakeProfileErrors.invalid(
                path(WRITE, ROW_GROUP_BYTES),
                "above_target",
                "must not exceed ${path(MAINTENANCE, TARGET_FILE_BYTES)} (${profile.targetFileBytes})",
            )
        }
        if (profile.latenessMinDeliveries > profile.latenessSample) {
            throw LakeProfileErrors.invalid(
                path(REPORTING, LATENESS_MIN_DELIVERIES),
                "above_sample",
                "must not exceed ${path(REPORTING, LATENESS_SAMPLE)} (${profile.latenessSample})",
            )
        }
    }

    private fun window(root: ObjectNode): MaintenanceWindow {
        val key = path(MAINTENANCE, WINDOW)
        val node = root.path(MAINTENANCE).path(WINDOW)
        if (!node.isTextual) throw LakeProfileErrors.invalid(key, TYPE, "must be a string HH:MM-HH:MM")
        val match =
            WINDOW_GRAMMAR.matchEntire(node.textValue())
                ?: throw LakeProfileErrors.invalid(key, "grammar", "must be HH:MM-HH:MM in UTC")
        return MaintenanceWindow(LocalTime.parse(match.groupValues[1]), LocalTime.parse(match.groupValues[2]))
    }

    private fun zone(root: ObjectNode): ZoneId {
        val key = path(RETENTION, KEEP_TIME_ZONE)
        val node = root.path(RETENTION).path(KEEP_TIME_ZONE)
        if (!node.isTextual) throw LakeProfileErrors.invalid(key, TYPE, "must be a string naming an IANA time zone")
        val name = node.textValue()
        if (name !in ZoneId.getAvailableZoneIds()) throw LakeProfileErrors.invalid(key, "time_zone", "$name is not an IANA time zone")
        return ZoneId.of(name)
    }

    private fun ratio(root: ObjectNode): Double {
        val key = path(MAINTENANCE, COMPACT_SMALL_FILE_RATIO)
        val value = decimal(root.path(MAINTENANCE).path(COMPACT_SMALL_FILE_RATIO), key)
        if (value.signum() <= 0 || value >= BigDecimal.ONE) {
            throw LakeProfileErrors.invalid(key, OUT_OF_RANGE, "must be above 0 and below 1")
        }
        return value.toDouble()
    }

    private fun overdueFactor(root: ObjectNode): Double {
        val key = path(REPORTING, LATENESS_OVERDUE_FACTOR)
        val value = decimal(root.path(REPORTING).path(LATENESS_OVERDUE_FACTOR), key)
        if (value <= BigDecimal.ONE || value > MAX_OVERDUE_FACTOR) {
            throw LakeProfileErrors.invalid(key, OUT_OF_RANGE, "must be above 1 and at most $MAX_OVERDUE_FACTOR")
        }
        return value.toDouble()
    }

    private fun decimal(
        node: JsonNode,
        key: String,
    ): BigDecimal {
        if (!node.isNumber) throw LakeProfileErrors.invalid(key, TYPE, "must be a number")
        return node.decimalValue()
    }

    private fun bytes(
        root: ObjectNode,
        group: String,
        key: String,
        min: Long,
        max: Long,
    ): Long = whole(root.path(group).path(key), path(group, key), min, max)

    private fun int(
        root: ObjectNode,
        group: String,
        key: String,
        min: Int,
        max: Int,
    ): Int = whole(root.path(group).path(key), path(group, key), min.toLong(), max.toLong()).toInt()

    /** A whole number in `min..max`; a fraction, a string or a number beyond a long is refused. */
    internal fun whole(
        node: JsonNode,
        key: String,
        min: Long,
        max: Long,
        index: Int? = null,
    ): Long {
        if (!node.isIntegralNumber) {
            val reason = if (node.isNumber) WHOLE_NUMBER else TYPE
            throw LakeProfileErrors.invalid(key, reason, "must be a whole number", index)
        }
        if (!node.canConvertToLong()) throw LakeProfileErrors.invalid(key, OUT_OF_RANGE, "must be between $min and $max", index)
        val value = node.longValue()
        if (value < min || value > max) throw LakeProfileErrors.invalid(key, OUT_OF_RANGE, "must be between $min and $max", index)
        return value
    }

    const val MAINTENANCE = "maintenance"
    const val WRITE = "write"
    const val RETENTION = "retention"
    const val REPORTING = "reporting"
    const val WINDOW = "window"
    const val MAX_BYTES_REWRITTEN = "max_bytes_rewritten_per_run"
    const val TARGET_FILE_BYTES = "target_file_bytes"
    const val COMPACT_MIN_SMALL_FILES = "compact_min_small_files"
    const val COMPACT_SMALL_FILE_RATIO = "compact_small_file_ratio"
    const val REWRITE_MANIFESTS_MIN_COUNT = "rewrite_manifests_min_count"
    const val CLEANUP_INTERVAL_HOURS = "cleanup_interval_hours"
    const val ROW_GROUP_BYTES = "row_group_bytes"
    const val KEEP = "keep"
    const val KEEP_TIME_ZONE = "keep_time_zone"
    const val LATENESS_SAMPLE = "lateness_sample"
    const val LATENESS_OVERDUE_FACTOR = "lateness_overdue_factor"
    const val LATENESS_MIN_DELIVERIES = "lateness_min_deliveries"

    internal const val UNKNOWN_KEY = "unknown_key"
    internal const val REQUIRED = "required"
    internal const val TYPE = "type"
    internal const val OUT_OF_RANGE = "out_of_range"
    internal const val WHOLE_NUMBER = "whole_number"

    private const val MIB = 1024L * 1024
    private const val MIN_TARGET_FILE_BYTES = 16 * MIB
    private const val MAX_TARGET_FILE_BYTES = 512 * MIB
    private const val MIN_ROW_GROUP_BYTES = 64 * 1024L
    private const val MIN_SMALL_FILES = 2
    private const val MIN_MANIFESTS = 2
    private const val MIN_LATENESS_SAMPLE = 2
    private const val MAX_LATENESS_SAMPLE = 200
    private const val MIN_LATENESS_DELIVERIES = 2
    private val MAX_OVERDUE_FACTOR = BigDecimal(100)
    private val WINDOW_GRAMMAR = Regex("^((?:[01]\\d|2[0-3]):[0-5]\\d)-((?:[01]\\d|2[0-3]):[0-5]\\d)$")
}
