package co.datapipelines.templates

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Normalizes a render context to the canonical wire forms of templates.md §4.4.
 *
 * `${var}` must render per the value's canonical Type System type. Two families are handled
 * differently, on purpose:
 *  - **Numbers and booleans stay native**, so template arithmetic and `?c` keep working
 *    (`r.total >= ${min_total?c}`). Their plain rendering is [FreemarkerConfigFactory]'s
 *    `numberFormat = "@plain"` ([PlainNumberFormatFactory], which preserves a declared decimal
 *    scale where every built-in format drops it) and `booleanFormat = "c"`.
 *  - **Dates, times, timestamps and binary are pre-formatted to strings here**, which both
 *    gives §4.4's exact spelling (a `Z`-suffixed UTC timestamp, base64 binary) under this
 *    module's control and sidesteps Freemarker 2.3.x's uneven `java.time` wrapping. These are
 *    used in SQL as quoted literals (`'${start_date}'`), never in arithmetic, so a string is
 *    the right shape.
 *
 * Anything not recognised falls through to `toString()` — the context is contractually only
 * canonical scalars and collections (§4.4), and a `toString()` fallback cannot expose Java
 * members the way handing an arbitrary object to a Beans wrapper would.
 */
internal object RenderContextNormalizer {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun normalize(context: Map<String, Any?>): Map<String, Any?> = context.mapValues { normalizeValue(it.value) }

    private fun normalizeValue(value: Any?): Any? =
        when (value) {
            null -> null
            is Boolean, is Number -> value
            is CharSequence -> value.toString()
            is ByteArray -> Base64.getEncoder().encodeToString(value)
            is LocalDate -> value.format(DateTimeFormatter.ISO_LOCAL_DATE)
            is LocalTime -> value.format(TIME)
            is Instant -> DateTimeFormatter.ISO_INSTANT.format(value)
            is OffsetDateTime -> DateTimeFormatter.ISO_INSTANT.format(value.toInstant())
            is ZonedDateTime -> DateTimeFormatter.ISO_INSTANT.format(value.toInstant())
            is LocalDateTime -> DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.UTC))
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalizeValue(v) }
            is Collection<*> -> value.map { normalizeValue(it) }
            else -> value.toString()
        }
}
