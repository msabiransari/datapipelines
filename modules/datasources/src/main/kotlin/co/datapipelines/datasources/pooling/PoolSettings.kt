package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DialectAdapter
import com.zaxxer.hikari.HikariConfig

/** The unit a [PoolSetting]'s value is expressed in — what the form renders beside the input. */
enum class PoolSettingUnit(
    val wire: String,
) {
    /** A plain count of connections. */
    COUNT("count"),

    /** Milliseconds — every HikariCP duration is one. */
    MILLISECONDS("ms"),
}

/**
 * WHICH layer supplied a setting's effective value (datasources.md §5).
 *
 * The order is fixed and total: a dialect adapter's declaration wins over the application's,
 * which wins over HikariCP's own. The form renders this beside each prefilled field, because
 * "10, because that is HikariCP's default" and "2, because this product chose it" are different
 * facts to an operator deciding whether to change one.
 */
enum class PoolSettingSource(
    val wire: String,
) {
    /** The value this datasource stores under `properties.hikari.*`. */
    CONFIGURED("configured"),

    /** The dialect adapter's own declaration ([DialectAdapter.defaultHikariProperties]). */
    DIALECT("dialect_default"),

    /** This product's documented default (§5) — today, `minimumIdle` alone. */
    APPLICATION("application_default"),

    /** HikariCP's own default, read from a fresh `HikariConfig` rather than transcribed. */
    HIKARI("hikari_default"),
}

/**
 * One tunable connection-pool key (datasources.md §5).
 *
 * The catalog is what the create/edit dialog renders and what the range rules in
 * [PoolSettings.validate] police — one list, so a key the form offers cannot be a key the
 * validator has never heard of.
 *
 * It is deliberately NOT an allowlist for the API. `properties.hikari` stays passthrough (§5,
 * §12.1): a caller may still send any key HikariCP accepts, and the save-time test pool build
 * is what judges it. This catalog is the subset a PERSON is offered, plus the range rules that
 * subset needs.
 */
data class PoolSetting(
    /** HikariCP's own property name — also the key under `properties.hikari.*`. */
    val key: String,
    /** The form's label. */
    val label: String,
    val unit: PoolSettingUnit,
    /** One line: what this actually does. */
    val meaning: String,
    /**
     * The smallest NON-ZERO value HikariCP will honour, or null when it has no floor.
     *
     * These are the numbers `HikariConfig.validateNumerics()` enforces — and it enforces most of
     * them by WARN-and-overwrite, which is why they are restated here as refusals (see
     * [PoolSettings.validate]).
     */
    val minimum: Long?,
    /** What `0` means, or null when `0` is not a legal value. */
    val zeroMeaning: String?,
)

/** One setting's effective value for a datasource, and which layer supplied it. */
data class EffectivePoolSetting(
    val setting: PoolSetting,
    val value: Long,
    val source: PoolSettingSource,
) {
    /** True when this datasource's own `properties.hikari` supplied the value. */
    val configured: Boolean get() = source == PoolSettingSource.CONFIGURED
}

/** One broken range rule — rendered by the caller as `datasource.validation.properties_invalid`. */
data class PoolSettingViolation(
    /** The `properties.hikari.<key>` path the error points at. */
    val key: String,
    val message: String,
)

/**
 * The connection-pool settings a person may tune, their effective defaults, and the range rules
 * (datasources.md §5, round 094).
 *
 * ## Why the ranges are validated here at all
 *
 * HikariCP has floors for most of these, and `HikariConfig.validateNumerics()` enforces almost
 * all of them by **logging a WARN and overwriting the value** — `maxLifetime = 5000` silently
 * becomes 1 800 000, `keepaliveTime = 10000` silently becomes 0 (disabled),
 * `leakDetectionThreshold = 500` silently becomes 0, `minimumIdle = 50` against
 * `maximumPoolSize = 10` silently becomes 10. The value the row stores and the screen shows is
 * then not the value the pool runs with, and nothing anywhere says so. (Only
 * `connectionTimeout`, `validationTimeout`, `maximumPoolSize < 1` and a negative `minimumIdle`
 * are hard refusals in the setters — verified against the pinned HikariCP 6.3.3.)
 *
 * A form that offers these fields has to refuse what the pool would silently rewrite, or it is
 * a form that lies. Every rule below is HikariCP's own; none is invented.
 *
 * ## Why the defaults are read, not transcribed
 *
 * [HIKARI_DEFAULTS] comes from a fresh `HikariConfig` at class initialization. A hard-coded
 * table would be a second authority for the pinned library's own numbers, and would drift
 * silently on the next HikariCP bump — exactly the failure this file exists to prevent one
 * level up.
 */
object PoolSettings {
    /** §5: the server's documented `minimumIdle` default — HikariCP's own is `maximumPoolSize`. */
    const val DEFAULT_MINIMUM_IDLE = 2L

    /** The keys the create/edit dialog renders, in the order it renders them. */
    val CATALOG: List<PoolSetting> =
        listOf(
            PoolSetting(
                key = "maximumPoolSize",
                label = "Maximum pool size",
                unit = PoolSettingUnit.COUNT,
                meaning = "The most connections this datasource may hold open at once.",
                minimum = 1,
                zeroMeaning = null,
            ),
            PoolSetting(
                key = "minimumIdle",
                label = "Minimum idle",
                unit = PoolSettingUnit.COUNT,
                meaning = "Connections kept warm when nothing is running. Never more than the maximum pool size.",
                minimum = null,
                zeroMeaning = "keep nothing warm",
            ),
            PoolSetting(
                key = "connectionTimeout",
                label = "Connection timeout",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "How long a node waits for a free connection before its execution fails.",
                minimum = 250,
                zeroMeaning = "wait forever",
            ),
            PoolSetting(
                key = "idleTimeout",
                label = "Idle timeout",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "How long an unused connection above the minimum idle count survives before it is closed.",
                minimum = 10_000,
                zeroMeaning = "never close an idle connection",
            ),
            PoolSetting(
                key = "maxLifetime",
                label = "Max lifetime",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "How long any connection lives before it is retired and replaced. Keep it under the database's own timeout.",
                minimum = 30_000,
                zeroMeaning = "no maximum lifetime",
            ),
            PoolSetting(
                key = "keepaliveTime",
                label = "Keepalive",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "How often an idle connection is pinged so a firewall or proxy does not drop it. Must be under the max lifetime.",
                minimum = 30_000,
                zeroMeaning = "off",
            ),
            PoolSetting(
                key = "validationTimeout",
                label = "Validation timeout",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "How long the liveness check on a connection may take before it is considered dead.",
                minimum = 250,
                zeroMeaning = null,
            ),
            PoolSetting(
                key = "leakDetectionThreshold",
                label = "Leak detection threshold",
                unit = PoolSettingUnit.MILLISECONDS,
                meaning = "Log a possible connection leak when one is held longer than this. Diagnostics only.",
                minimum = 2_000,
                zeroMeaning = "off",
            ),
        )

    /** The catalogued keys, for membership tests. */
    val KEYS: Set<String> = CATALOG.map { it.key }.toSet()

    /** This product's own defaults (§5) — everything else falls through to HikariCP's. */
    val APPLICATION_DEFAULTS: Map<String, Long> = mapOf("minimumIdle" to DEFAULT_MINIMUM_IDLE)

    /**
     * HikariCP's defaults, READ from a fresh `HikariConfig` so they cannot drift from the pinned
     * library. `minimumIdle` is absent on purpose: HikariCP leaves it at `-1`, meaning "the same
     * as maximumPoolSize", which is not a number this table can carry — and [APPLICATION_DEFAULTS]
     * covers that key anyway.
     */
    val HIKARI_DEFAULTS: Map<String, Long> =
        HikariConfig().let { defaults ->
            mapOf(
                "maximumPoolSize" to defaults.maximumPoolSize.toLong(),
                "connectionTimeout" to defaults.connectionTimeout,
                "idleTimeout" to defaults.idleTimeout,
                "maxLifetime" to defaults.maxLifetime,
                "keepaliveTime" to defaults.keepaliveTime,
                "validationTimeout" to defaults.validationTimeout,
                "leakDetectionThreshold" to defaults.leakDetectionThreshold,
            )
        }

    /**
     * The effective value of every catalogued setting for [datasource] under [adapter] — what
     * the dialog prefills and what `GET /api/v1/datasources/{name}` reports.
     *
     * Resolution, in order: this row's `properties.hikari.*`, the dialect adapter's declaration,
     * this application's default, HikariCP's own. A key that resolves nowhere is omitted rather
     * than guessed — with today's adapters that never happens, and [poolSettingsAreTotalTest]
     * would be the place to notice if it started to.
     */
    fun effective(
        datasource: Datasource,
        adapter: DialectAdapter,
    ): List<EffectivePoolSetting> {
        val configured = datasource.properties.hikari
        return defaults(adapter).map { fallback ->
            val raw = configured.entries.firstOrNull { it.key == fallback.setting.key }?.value
            val parsed = raw?.let { asLong(it) }
            if (parsed == null) fallback else EffectivePoolSetting(fallback.setting, parsed, PoolSettingSource.CONFIGURED)
        }
    }

    /**
     * The `pool` object of the §3.2 REST response and of `datasources_get` — every catalogued
     * setting's EFFECTIVE value and which layer supplied it.
     *
     * Rendered here, once, so the two surfaces cannot drift. Before 094 the MCP surface reported
     * the raw `properties.hikari` map, which is empty for almost every datasource in existence:
     * an agent asking "what is this pool running with" got `{}` and had to know HikariCP's
     * defaults, this product's `minimumIdle` override, and which of the two applied.
     *
     * The keys keep HikariCP's camelCase spelling rather than the envelope's snake_case, and
     * deliberately: they are the exact identifiers a caller writes back under
     * `properties.hikari.*`, and renaming them on the way out would make the read surface
     * un-actionable.
     */
    fun wire(
        datasource: Datasource,
        adapter: DialectAdapter,
    ): Map<String, Map<String, Any?>> =
        effective(datasource, adapter).associate { entry ->
            entry.setting.key to
                mapOf(
                    "value" to entry.value,
                    "unit" to entry.setting.unit.wire,
                    "source" to entry.source.wire,
                )
        }

    /** The effective DEFAULTS for [adapter] — the create dialog's prefill, before any row exists. */
    fun defaults(adapter: DialectAdapter): List<EffectivePoolSetting> =
        CATALOG.mapNotNull { setting ->
            val dialect = adapter.defaultHikariProperties[setting.key]?.let { asLong(it) }
            val application = APPLICATION_DEFAULTS[setting.key]
            val hikari = HIKARI_DEFAULTS[setting.key]
            when {
                dialect != null -> EffectivePoolSetting(setting, dialect, PoolSettingSource.DIALECT)
                application != null -> EffectivePoolSetting(setting, application, PoolSettingSource.APPLICATION)
                hikari != null -> EffectivePoolSetting(setting, hikari, PoolSettingSource.HIKARI)
                else -> null
            }
        }

    /**
     * The §5 range rules over a datasource's `properties.hikari` map, evaluated against the
     * EFFECTIVE values (a cross-field rule has to see the default the other half falls back to).
     *
     * Only catalogued keys are policed. An uncatalogued `hikari` key is still passthrough and
     * still judged by the save-time test pool build — this function narrows nothing.
     *
     * Non-numeric values are NOT reported here: `DatasourceValidator.probeHikariKey` already
     * names them with HikariCP's own message, and a second error on the same field would make
     * one typo read as two problems.
     */
    fun validate(
        datasource: Datasource,
        adapter: DialectAdapter,
    ): List<PoolSettingViolation> {
        val effective = effective(datasource, adapter).associateBy { it.setting.key }
        val configured =
            datasource.properties.hikari.keys
                .filter { it in KEYS }
                .toSet()
        val floors = floorViolations(effective, configured)
        // A key that already failed its own floor is EXCLUDED from the cross-field rules.
        // `maximumPoolSize: 0` is one mistake, and reporting it again as "minimumIdle exceeds
        // maximumPoolSize" would make one typo read as two problems and point the operator at
        // the field they got right.
        val values = PoolValues(effective, failed = floors.mapTo(mutableSetOf()) { it.key })
        return floors + crossFieldViolations(values, configured)
    }

    /**
     * The per-key floors, reported only for keys the CALLER set: a default that a future
     * HikariCP bump put below its own floor is our bug to fix, not this caller's to see.
     */
    private fun floorViolations(
        effective: Map<String, EffectivePoolSetting>,
        configured: Set<String>,
    ): List<PoolSettingViolation> =
        configured.mapNotNull { key ->
            val entry = effective[key] ?: return@mapNotNull null
            val setting = entry.setting
            when {
                entry.value < 0 -> {
                    PoolSettingViolation(key, "'$key' cannot be negative.")
                }

                entry.value == 0L && setting.zeroMeaning == null -> {
                    PoolSettingViolation(key, "'$key' must be at least ${setting.minimum}; 0 is not a valid value for it.")
                }

                setting.minimum != null && entry.value != 0L && entry.value < setting.minimum -> {
                    PoolSettingViolation(
                        key,
                        "'$key' must be at least ${setting.minimum}${setting.unit.suffix()}" +
                            (setting.zeroMeaning?.let { " (or 0 for $it)" } ?: "") +
                            " — HikariCP would otherwise silently replace it.",
                    )
                }

                else -> {
                    null
                }
            }
        }

    /** The four rules HikariCP enforces by SILENTLY overwriting one of a pair (§5). */
    private fun crossFieldViolations(
        values: PoolValues,
        configured: Set<String>,
    ): List<PoolSettingViolation> =
        listOfNotNull(
            minIdleWithinMaxPool(values, configured),
            keepaliveUnderMaxLifetime(values, configured),
            leakUnderMaxLifetime(values, configured),
            idleTimeoutUnderMaxLifetime(values, configured),
        )

    /** HikariCP: `if (minIdle > maxPoolSize) minIdle = maxPoolSize`, with no log at all. */
    private fun minIdleWithinMaxPool(
        values: PoolValues,
        configured: Set<String>,
    ): PoolSettingViolation? {
        val maxPool = values.maximumPoolSize ?: return null
        val minIdle = values.minimumIdle?.takeIf { it > maxPool } ?: return null
        val field = crossFieldOwner(configured, "minimumIdle", "maximumPoolSize") ?: return null
        return PoolSettingViolation(
            field,
            "'minimumIdle' ($minIdle) cannot exceed 'maximumPoolSize' ($maxPool) — " +
                "HikariCP would silently lower it to $maxPool.",
        )
    }

    /** HikariCP disables the keepalive when it reaches `maxLifetime`. */
    private fun keepaliveUnderMaxLifetime(
        values: PoolValues,
        configured: Set<String>,
    ): PoolSettingViolation? {
        val maxLifetime = values.boundedMaxLifetime ?: return null
        val keepalive = values.keepaliveTime?.takeIf { it > 0 && it >= maxLifetime } ?: return null
        val field = crossFieldOwner(configured, "keepaliveTime", "maxLifetime") ?: return null
        return PoolSettingViolation(
            field,
            "'keepaliveTime' ($keepalive ms) must be less than 'maxLifetime' ($maxLifetime ms) — " +
                "HikariCP would silently disable the keepalive.",
        )
    }

    /** HikariCP disables leak detection when its threshold passes `maxLifetime`. */
    private fun leakUnderMaxLifetime(
        values: PoolValues,
        configured: Set<String>,
    ): PoolSettingViolation? {
        val maxLifetime = values.boundedMaxLifetime ?: return null
        val leak = values.leakDetectionThreshold?.takeIf { it > maxLifetime } ?: return null
        val field = crossFieldOwner(configured, "leakDetectionThreshold", "maxLifetime") ?: return null
        return PoolSettingViolation(
            field,
            "'leakDetectionThreshold' ($leak ms) cannot exceed 'maxLifetime' ($maxLifetime ms) — " +
                "HikariCP would silently disable leak detection.",
        )
    }

    /**
     * HikariCP disables the idle timeout when it comes within a second of `maxLifetime` — but
     * ONLY while the pool can actually shrink (`minIdle < maxPoolSize`). A fixed-size pool has
     * no idle timeout to disable, so neither does this rule.
     */
    private fun idleTimeoutUnderMaxLifetime(
        values: PoolValues,
        configured: Set<String>,
    ): PoolSettingViolation? {
        val maxLifetime = values.boundedMaxLifetime?.takeIf { values.poolCanShrink } ?: return null
        val idleTimeout =
            values.idleTimeout?.takeIf { it > 0 && it + MILLIS_PER_SECOND > maxLifetime } ?: return null
        val field = crossFieldOwner(configured, "idleTimeout", "maxLifetime") ?: return null
        return PoolSettingViolation(
            field,
            "'idleTimeout' ($idleTimeout ms) must be at least 1000 ms below 'maxLifetime' ($maxLifetime ms) — " +
                "HikariCP would silently disable the idle timeout.",
        )
    }

    /**
     * The effective values the cross-field rules read, with every key that already failed its
     * own floor read back as null — so a rule can be written as a chain of early returns
     * instead of a condition nobody can check by eye.
     */
    private class PoolValues(
        effective: Map<String, EffectivePoolSetting>,
        failed: Set<String>,
    ) {
        private val values = effective.filterKeys { it !in failed }.mapValues { it.value.value }

        val maximumPoolSize = values["maximumPoolSize"]
        val minimumIdle = values["minimumIdle"]
        val idleTimeout = values["idleTimeout"]
        val keepaliveTime = values["keepaliveTime"]
        val leakDetectionThreshold = values["leakDetectionThreshold"]

        /** `maxLifetime` when it actually bounds a connection's life; null for 0 (unbounded). */
        val boundedMaxLifetime = values["maxLifetime"]?.takeIf { it > 0 }

        /** Whether the pool has idle connections to reclaim at all. */
        val poolCanShrink: Boolean
            get() {
                val max = maximumPoolSize ?: return false
                val min = minimumIdle ?: return false
                return min < max
            }
    }

    /**
     * Which field a cross-field violation is reported against: the one the CALLER set, preferring
     * the first named. Null when the caller set neither — the pair is then two defaults that
     * disagree, which is this product's defect to fix, not an operator's error to be shown.
     */
    private fun crossFieldOwner(
        configured: Set<String>,
        primary: String,
        secondary: String,
    ): String? =
        when {
            primary in configured -> primary
            secondary in configured -> secondary
            else -> null
        }

    /** `"600000"`, `600000`, `600000L` — the three shapes a JSONB round-trip produces. */
    private fun asLong(value: Any?): Long? =
        when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }

    private fun PoolSettingUnit.suffix(): String = if (this == PoolSettingUnit.MILLISECONDS) " ms" else ""

    private const val MILLIS_PER_SECOND = 1_000L
}
