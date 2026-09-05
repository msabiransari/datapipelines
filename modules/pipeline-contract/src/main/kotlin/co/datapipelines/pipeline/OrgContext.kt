package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The organisation-configuration tier of the execution Context (calculators design §0.1/§0.2,
 * pipeline-contract §7.1).
 *
 * Organisation facts — currency, fiscal year start, week start, timezone — are *configuration*,
 * not pipeline data: they change once a decade, they are identical for every pipeline in a
 * deployment, and a value that differs between deployments must be visible in the deployment's
 * yml rather than copied into every body. `ConfigValidator` refuses startup on a bad one.
 *
 * ## Why the values are carried as a map
 *
 * The context-key spelling — `datapipelines.org.currency.name` → `org_currency_name`, dots and
 * dashes to `_` (§0.2 tier 1) — is the contract, and it is what every consumer addresses: a
 * calculator input `"$org_fiscal_start_date"`, a template's `:org_currency_symbol` bind, the
 * import check that refuses a body binding a key the target does not define. A map keyed by the
 * context key is therefore the honest shape, and it is what lets a test (and, later, a
 * deployment with extra org keys) construct a context whose key SET differs — the one thing
 * `pipeline.import.context_key_missing` exists to detect.
 *
 * Every org value is typed `STRING` on purpose (§0.2): `org_fiscal_start_date` is an `MM-DD`
 * string, and the calculator kinds that need a date parse it. Org keys are never secrets.
 */
class OrgContext private constructor(
    /** The org tier's values, keyed by context key, in declaration order. */
    val values: Map<String, Any?>,
) {
    /** The context keys this deployment's org configuration defines. */
    val keys: Set<String> get() = values.keys

    override fun toString(): String = "OrgContext(keys=$keys)"

    companion object {
        const val CURRENCY_NAME = "org_currency_name"
        const val CURRENCY_SYMBOL = "org_currency_symbol"
        const val FISCAL_START_DATE = "org_fiscal_start_date"
        const val WEEK_START = "org_week_start"
        const val TIMEZONE = "org_timezone"

        /** The prefix every org-tier context key carries (§0.2) — the import check reads it. */
        const val PREFIX = "org_"

        /** The five keys §0.1's yml block defines, in yml order. */
        val KEYS: List<String> = listOf(CURRENCY_NAME, CURRENCY_SYMBOL, FISCAL_START_DATE, WEEK_START, TIMEZONE)

        /** The documented shipped defaults (configuration.md §3.21) — also the test default. */
        val DEFAULTS: OrgContext = of("Dollar", "$", "01-01", "monday", "UTC")

        /** The org tier as configured, in the shape §0.1's yml block declares. */
        fun of(
            currencyName: String,
            currencySymbol: String,
            fiscalStartDate: String,
            weekStart: String,
            timezone: String,
        ): OrgContext =
            OrgContext(
                linkedMapOf(
                    CURRENCY_NAME to currencyName,
                    CURRENCY_SYMBOL to currencySymbol,
                    FISCAL_START_DATE to fiscalStartDate,
                    WEEK_START to weekStart,
                    TIMEZONE to timezone,
                ),
            )

        /**
         * An org tier from an arbitrary key set — the seam a deployment with extra `org_*` keys,
         * and the promotion-refusal test, both need. Keys not matching §6.1's shape are refused
         * outright: an org key that cannot be bound as `:key` is a configuration defect.
         */
        fun ofValues(values: Map<String, Any?>): OrgContext {
            values.keys.forEach {
                require(it.startsWith(PREFIX) && ContextKeys.NAME.matches(it)) {
                    "org context key '$it' must start with '$PREFIX' and match ${ContextKeys.NAME.pattern}"
                }
            }
            return OrgContext(LinkedHashMap(values))
        }
    }
}

/**
 * The Context's non-pipeline tiers (calculators design §0.2) and the naming rule every key obeys.
 *
 * Tier order, lowest precedence first: **org config** < **platform keys** < declared
 * `parameters` < execute-time inputs < calculator outputs. The whole point of one namespace is
 * that a node binds `:anything` without knowing which tier supplied it; the tier only decides
 * who wins when two of them spell a key the same way.
 */
object ContextKeys {
    /** §6.1 — every Context key, whatever its tier. */
    val NAME = Regex("[a-z_][a-z0-9_]*")

    /** The run's calendar date, evaluated in `org_timezone` at execution start. */
    const val CURRENT_DATE = "current_date"

    /** The run's start instant. */
    const val CURRENT_TIMESTAMP = "current_timestamp"

    /** The execution's id, as a string — the join key every surface already shows. */
    const val EXECUTION_ID = "execution_id"

    /** The platform tier's keys and their canonical types (§0.2 tier 2). */
    val PLATFORM_TYPES: Map<String, LogicalType> =
        linkedMapOf(
            CURRENT_DATE to LogicalType.DATE,
            CURRENT_TIMESTAMP to LogicalType.TIMESTAMP,
            EXECUTION_ID to LogicalType.STRING,
        )

    /** The platform tier's keys, in declaration order. */
    val PLATFORM: List<String> get() = PLATFORM_TYPES.keys.toList()

    /**
     * The platform tier for one execution.
     *
     * `current_date` is the date **in `org_timezone`**, not the JVM's: a deployment in Sydney
     * running on a UTC box would otherwise see yesterday's date for the first ten hours of every
     * local day, which is precisely the class of quiet wrongness org config exists to remove.
     */
    fun platformValues(
        executionId: UUID,
        startedAt: Instant,
        timezone: ZoneId,
    ): Map<String, Any?> =
        linkedMapOf(
            CURRENT_DATE to LocalDate.ofInstant(startedAt, timezone),
            CURRENT_TIMESTAMP to startedAt,
            EXECUTION_ID to executionId.toString(),
        )

    /**
     * The org + platform tiers as the save-time dry render sees them (§0.2): deployment
     * constants, so a template binding `:org_currency_symbol` or `:current_date` validates
     * without the pipeline declaring anything. The instant is [Clock.systemUTC]'s by default —
     * a dry render only needs a value of the right TYPE, never a particular one.
     */
    fun deploymentValues(
        org: OrgContext,
        executionId: UUID = ZERO_EXECUTION_ID,
        startedAt: Instant = Instant.now(Clock.systemUTC()),
        timezone: ZoneId = zoneOf(org),
    ): Map<String, Any?> = org.values + platformValues(executionId, startedAt, timezone)

    /**
     * The deployment's zone, or UTC when the configured id is unparseable.
     *
     * Falling back rather than throwing is deliberate: `ConfigValidator` refuses startup on an
     * invalid `datapipelines.org.timezone`, so reaching the fallback means a Context was built
     * from a hand-made [OrgContext] in a test — and a validator that throws inside execution
     * setup would turn a configuration defect into an uncatalogued 500.
     */
    fun zoneOf(org: OrgContext): ZoneId =
        runCatching { ZoneId.of(org.values[OrgContext.TIMEZONE] as? String ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))

    /** The dry render's stand-in execution id — fixed, so a save-time render is reproducible. */
    private val ZERO_EXECUTION_ID: UUID = UUID(0L, 0L)
}
