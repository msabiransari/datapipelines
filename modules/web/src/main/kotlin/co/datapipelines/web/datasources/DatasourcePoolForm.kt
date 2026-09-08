package co.datapipelines.web.datasources

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DialectAdapters
import co.datapipelines.datasources.pooling.EffectivePoolSetting
import co.datapipelines.datasources.pooling.PoolSettingSource
import co.datapipelines.datasources.pooling.PoolSettings
import co.datapipelines.typesystem.Dialect

/**
 * One rendered field of the create/edit dialog's "Connection pool" section
 * (ui-screens.md §4.5, datasources.md §5).
 *
 * A flat view model rather than the domain type: the template renders strings and does no
 * branching, so "which layer supplied this default" is decided once, in Kotlin, where it can be
 * tested.
 */
data class PoolFieldView(
    /** The form field's name — `pool.<hikariKey>`; also the input's id suffix. */
    val name: String,
    /** HikariCP's key, which is what this value is stored under in `properties.hikari`. */
    val key: String,
    val label: String,
    /** `count` or `ms`. */
    val unit: String,
    /** One line: what this setting does. */
    val meaning: String,
    /** The prefilled value — the effective default, or this datasource's own. */
    val value: String,
    /** Where that value came from, in words. */
    val help: String,
    /** True when the datasource itself set this, so the form can mark it as changed-from-default. */
    val configured: Boolean,
)

/**
 * The bridge between the dialog's "Connection pool" section and `properties.hikari`
 * (round 094, owner ruling 1: a person can tune a datasource without REST).
 *
 * Two directions, one rule each:
 *
 * - **Out** ([fields]): every catalogued key, prefilled with the EFFECTIVE default for the chosen
 *   dialect and labelled with the layer that supplied it, so an operator can tell "10, because
 *   HikariCP says so" from "2, because this product chose it".
 * - **In** ([toHikari]): a field left AT its default is not persisted. That is the whole reason
 *   this class compares rather than copies — persisting the prefill would freeze today's
 *   defaults into every datasource ever created through the form, and a later change to a
 *   product default would then silently apply to nothing.
 *
 * Values that do not parse as a number are passed through UNCHANGED rather than dropped: the
 * validator's per-key HikariCP probe names them (`properties.hikari.<key> was rejected: …`), and
 * silently discarding a typo would save a datasource the operator believes they retuned.
 */
object DatasourcePoolForm {
    /** The form-field prefix. Dotted so the pool section cannot collide with a top-level field. */
    const val PREFIX = "pool."

    /** The fields the create dialog renders for [dialect] — defaults only, no datasource yet. */
    fun fields(dialect: Dialect): List<PoolFieldView> = render(PoolSettings.defaults(DialectAdapters.forDialect(dialect)))

    /** The fields the edit dialog renders for [datasource] — its own values where it has them. */
    fun fields(datasource: Datasource): List<PoolFieldView> =
        render(PoolSettings.effective(datasource, DialectAdapters.forDialect(datasource.dialect)))

    /**
     * The `properties.hikari` map to persist: every posted pool field whose value DIFFERS from
     * the effective default for [dialect].
     *
     * @param existing the datasource's current `properties.hikari`, on an edit. A key the form
     *   did not post at all (an older browser, a truncated body) keeps its stored value rather
     *   than being silently reset to the default — the form owns the keys it renders, not the
     *   whole map, and `properties.hikari` is passthrough, so it may legitimately hold keys this
     *   dialog knows nothing about.
     */
    fun toHikari(
        params: Map<String, String>,
        dialect: Dialect,
        existing: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val adapter = DialectAdapters.forDialect(dialect)
        val defaults = PoolSettings.defaults(adapter).associate { it.setting.key to it.value.toString() }
        // Start from what the row already holds, so uncatalogued passthrough keys survive an edit.
        val result = existing.toMutableMap()
        PoolSettings.CATALOG.forEach { setting ->
            val posted = params[PREFIX + setting.key]?.trim() ?: return@forEach
            when {
                // An emptied field means "use the default", which is the same as not storing it.
                posted.isEmpty() -> result.remove(setting.key)

                posted == defaults[setting.key] -> result.remove(setting.key)

                else -> result[setting.key] = posted.toLongOrNull() ?: posted
            }
        }
        return result
    }

    private fun render(settings: List<EffectivePoolSetting>): List<PoolFieldView> =
        settings.map { entry ->
            PoolFieldView(
                name = PREFIX + entry.setting.key,
                key = entry.setting.key,
                label = entry.setting.label,
                unit = entry.setting.unit.wire,
                meaning = entry.setting.meaning + (entry.setting.zeroMeaning?.let { " 0 = $it." } ?: ""),
                value = entry.value.toString(),
                help = help(entry),
                configured = entry.configured,
            )
        }

    /** WHICH layer supplied the prefilled value, in a sentence an operator can act on. */
    private fun help(entry: EffectivePoolSetting): String =
        when (entry.source) {
            PoolSettingSource.CONFIGURED -> "Set on this datasource."
            PoolSettingSource.DIALECT -> "Default ${entry.value} — this dialect's."
            PoolSettingSource.APPLICATION -> "Default ${entry.value} — this server's."
            PoolSettingSource.HIKARI -> "Default ${entry.value} — HikariCP's own."
        }
}
