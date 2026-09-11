package co.datapipelines.web.ui.site

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import java.util.Locale

/**
 * One table row of one demo family, read off the family's published manifest. The three
 * families publish three manifest shapes (they were written by three build pipelines), so
 * every field is read from whichever key that family carries and normalised here:
 * nyc/trade name tables under `table`, the lake family under `name`; nyc/lake count rows
 * in `row_count`, trade in `rows`; the lake rows alone carry `format` and `bytes`.
 */
data class DemoTable(
    val engine: String,
    val table: String,
    val rowCount: Long,
    val rowCountFormatted: String,
    val format: String?,
    val bytes: Long?,
    val objectCount: Long?,
    val location: String?,
    val metadataLocation: String?,
    val description: String?,
    val grain: String,
    val keyColumns: String,
)

/** The DDL-derived facts of one table: its grain in one line, and the columns that matter. */
data class DemoTableFact(
    val grain: String,
    val keyColumns: String,
)

/**
 * One provenance row of one family's manifest: where the data came from and what the
 * publisher's terms are. The manifest is the attribution the data SHIPS with — the page
 * renders these rows rather than retyping them, so the page cannot claim a licence the
 * artifact does not carry.
 */
data class DemoProvenance(
    val source: String,
    val sourceUrl: String?,
    val license: String?,
    val licenseVerified: String?,
    val publisher: String?,
    val publisherNotice: String?,
    val notice: String?,
    val citation: String?,
    val sourcePage: String?,
    val openDataCollection: String?,
)

/** One published file of one family, with its size — the disk-footage facts. */
data class DemoArtifact(
    val file: String,
    val bytes: Long,
)

/**
 * One demo family (nyc, trade, lake) as its published manifest describes it. [version] is
 * the published directory name the deploy pin names. [licenseVerified] is the family's
 * licence-stamp date read FROM the manifest's provenance rows — null when ANY row carries
 * no stamp, so a family whose licence verification is incomplete renders as unverified and
 * the page makes no licence claim for it.
 */
data class DemoFamily(
    val key: String,
    val dataset: String,
    val version: String,
    val window: String?,
    val tables: List<DemoTable>,
    val provenance: List<DemoProvenance>,
    val artifacts: List<DemoArtifact>,
) {
    val licenseVerified: String? =
        when {
            provenance.isEmpty() -> null
            provenance.any { it.licenseVerified.isNullOrBlank() } -> null
            else -> provenance.mapNotNull { it.licenseVerified }.distinct().singleOrNull()
        }

    /** The combined byte size of the family's published files — the artifacts[] families only. */
    val artifactsBytes: Long = artifacts.sumOf { it.bytes }
}

/**
 * The `/demo-data` page's data (116): the three published sample-data families, read from
 * the manifests vendored at `resources/site/demo/manifest-{nyc,trade,lake}.json` — the
 * `manifest.json` of exactly the version `deploy/env/defaults.env` pins for each family.
 *
 * The rule the page exists for is the tools page's rule: **the tables on the page are
 * generated, not typed.** A hand-written table is wrong the next artifact is repacked; a
 * rendered one is wrong only if its manifest is, and `SiteDemoDataGuardsTest` pins the
 * manifests to the deploy pins so a repack that forgets the page fails the build.
 *
 * The posture is [DocsCatalog]'s: everything is read and parsed ONCE here, at startup
 * (the bean in `UiConfig`), and a missing or unparsable manifest fails fast — a page that
 * rendered "0 tables" would be a false claim about public data, not a degraded page.
 */
class SiteDemoData private constructor(
    manifests: Map<String, String>,
) {
    constructor(classLoader: ClassLoader) : this(
        FAMILY_KEYS.associateWith { key ->
            val path = "$RESOURCE_DIR/manifest-$key.json"
            classLoader.getResourceAsStream(path)?.readBytes()?.decodeToString()
                ?: error("demo-data: $path is not on the classpath — the vendored manifest is missing and the page would be a false claim")
        },
    )

    /**
     * The three families in page order — nyc, trade, lake — each parsed once, at startup.
     * The constructor pass then verifies the DDL-derived fact table below knows EXACTLY the
     * tables the manifests carry, in both directions: a repack that adds a table fails
     * startup until someone writes its grain, and a fact left behind by a dropped table
     * fails too — the same drift the page exists to prevent, caught one level earlier.
     */
    val families: List<DemoFamily> =
        FAMILY_KEYS.map { key -> parse(key, checkNotNull(manifests[key])) }.also { parsed ->
            val manifestTables = parsed.flatMap { it.tables }.map { it.table }.toSet()
            val factTables = TABLE_FACTS.keys.toSet()
            require(manifestTables == factTables) {
                "demo-data: the manifest tables and the DDL fact table disagree — " +
                    "manifest-only: ${(manifestTables - factTables).sorted()}, fact-only: ${(factTables - manifestTables).sorted()}"
            }
        }

    /** [key]'s family, for the guards. */
    fun family(key: String): DemoFamily = checkNotNull(families.firstOrNull { it.key == key }) { "no demo family '$key'" }

    /**
     * One manifest's JSON into a [DemoFamily]. Visible for the tests, which feed doctored
     * manifests through this same parser (on disk, a doctored manifest would be a lie in
     * the repo; in memory, it is exactly the repack scenario the null-licence branch
     * exists for).
     */
    fun parse(
        key: String,
        json: String,
    ): DemoFamily {
        val node = parseTree(key, json)
        val version = node.path("version").asText("")
        require(version.isNotEmpty()) { "demo-data: manifest-$key.json carries no version" }
        val dataset = node.path("dataset").asText(node.path("family").asText(""))
        require(dataset.isNotEmpty()) { "demo-data: manifest-$key.json carries no dataset/family" }
        val tables = parseTables(key, node)
        val provenance = parseProvenance(key, node)
        return DemoFamily(
            key = key,
            dataset = dataset,
            version = version,
            window = parseWindow(node),
            tables = tables,
            provenance = provenance,
            artifacts = parseArtifacts(node),
        )
    }

    private fun parseTree(
        key: String,
        json: String,
    ): JsonNode =
        try {
            mapper.readTree(json)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("demo-data: manifest-$key.json does not parse", e)
        }

    private fun parseTables(
        key: String,
        node: JsonNode,
    ): List<DemoTable> {
        val tablesNode = node.path("tables")
        require(tablesNode.isArray && !tablesNode.isEmpty) {
            "demo-data: manifest-$key.json declares no tables — the page would render a false '0 tables'"
        }
        return tablesNode.map { t ->
            val name = t.path("table").asText(t.path("name").asText(""))
            require(name.isNotEmpty()) { "demo-data: manifest-$key.json has a table row with no name" }
            val rowCount =
                when {
                    t.hasNonNull("row_count") -> t.get("row_count").asLong()
                    t.hasNonNull("rows") -> t.get("rows").asLong()
                    else -> error("demo-data: table '$name' in manifest-$key.json carries no row count")
                }
            val fact =
                TABLE_FACTS[name]
                    ?: error(
                        "demo-data: table '$name' in manifest-$key.json has no DDL fact row — write its grain and key columns",
                    )
            DemoTable(
                engine = t.path("engine").asText("LAKE"),
                table = name,
                rowCount = rowCount,
                rowCountFormatted = String.format(Locale.US, "%,d", rowCount),
                format = t.path("format").asText(null),
                bytes = if (t.hasNonNull("bytes")) t.get("bytes").asLong() else null,
                objectCount = if (t.hasNonNull("object_count")) t.get("object_count").asLong() else null,
                location = t.path("location").asText(null),
                metadataLocation = t.path("metadata_location").asText(null),
                description = t.path("description").asText(null),
                grain = fact.grain,
                keyColumns = fact.keyColumns,
            )
        }
    }

    private fun parseProvenance(
        key: String,
        node: JsonNode,
    ): List<DemoProvenance> {
        val provenance =
            node.path("provenance").map { pr ->
                DemoProvenance(
                    source = pr.path("dataset").asText(pr.path("source").asText("")),
                    sourceUrl = textOrNull(pr, "source_url"),
                    license = textOrNull(pr, "license"),
                    licenseVerified = textOrNull(pr, "license_verified"),
                    publisher = textOrNull(pr, "publisher"),
                    publisherNotice = textOrNull(pr, "publisher_notice"),
                    notice = textOrNull(pr, "notice"),
                    citation = textOrNull(pr, "citation"),
                    sourcePage = textOrNull(pr, "source_page"),
                    openDataCollection = textOrNull(pr, "open_data_collection"),
                )
            }
        require(provenance.isNotEmpty()) {
            "demo-data: manifest-$key.json declares no provenance — the page would publish data with no source named"
        }
        return provenance
    }

    /** The trade manifest carries the window as data; the other families' windows live in their locks. */
    private fun parseWindow(node: JsonNode): String? {
        val windowNode = node.path("window")
        return if (windowNode.has("start") && windowNode.has("end")) {
            "${windowNode.get("start").asText()} → ${windowNode.get("end").asText()}"
        } else {
            null
        }
    }

    private fun parseArtifacts(node: JsonNode): List<DemoArtifact> =
        node
            .path("artifacts")
            .map { a -> DemoArtifact(a.path("file").asText(a.path("name").asText("")), a.path("bytes").asLong(0L)) }

    private fun textOrNull(
        node: JsonNode,
        field: String,
    ): String? {
        val value = node.get(field)?.asText()
        return value?.takeIf { it.isNotBlank() && it != "null" }
    }

    private companion object {
        val FAMILY_KEYS = listOf("nyc", "trade", "lake")

        /** Classpath directory of the vendored manifests — not the static site asset route. */
        const val RESOURCE_DIR = "site/demo"

        /**
         * The per-table grain and key columns, written ONCE from the families' DDL files
         * (scripts/sample-data, scripts/sample-data-trade — the ddl directories; the lake
         * family has no DDL — its facts come from scripts/sample-data-lake's README and the
         * manifest's own descriptions).
         * Keyed by table name, which is unique across the three families. The init block
         * holds this map and the manifests to exact agreement.
         */
        val TABLE_FACTS: Map<String, DemoTableFact> =
            mapOf(
                // nyc — Postgres (postgres-trips.sql)
                "trips" to
                    DemoTableFact(
                        "one row per sampled yellow-taxi trip",
                        "trip_id, pickup_ts, pickup_date, pu_location_id, total_amount",
                    ),
                "trips_daily" to
                    DemoTableFact(
                        "one row per day of the window",
                        "pickup_date, trip_count, total_revenue",
                    ),
                "trips_monthly" to
                    DemoTableFact(
                        "one row per month × pickup zone",
                        "month_start, pu_location_id, trip_count, total_revenue",
                    ),
                // nyc — MySQL (mysql-weather.sql)
                "stations" to
                    DemoTableFact(
                        "one row per NOAA GHCN station (the five pinned NYC-area ids)",
                        "station_id, name, latitude, longitude",
                    ),
                "observations" to
                    DemoTableFact(
                        "one row per station × day × element, in standard units",
                        "station_id, obs_date, element, value, unit",
                    ),
                // nyc — SQLite (sqlite-reference.sql)
                "zones" to
                    DemoTableFact(
                        "one row per TLC taxi zone",
                        "location_id, borough, zone, service_zone",
                    ),
                "rate_codes" to
                    DemoTableFact(
                        "one row per rate code",
                        "rate_code_id, description",
                    ),
                "payment_types" to
                    DemoTableFact(
                        "one row per payment type",
                        "payment_type_id, description",
                    ),
                "calendar" to
                    DemoTableFact(
                        "one row per day of the window, with weekend and holiday flags",
                        "cal_date, day_of_week, is_weekend, is_holiday",
                    ),
                // trade — DuckDB (duckdb-us-trade.sql)
                "trade_monthly" to
                    DemoTableFact(
                        "one row per flow × month × partner × HS-6 commodity",
                        "flow, period, partner_code, hs_code, value_usd",
                    ),
                "partners" to
                    DemoTableFact(
                        "one row per Census partner country (the top 15 by 2024 goods trade)",
                        "partner_code, partner_name",
                    ),
                "hs_chapters" to
                    DemoTableFact(
                        "one row per HS-2 chapter",
                        "chapter, chapter_name",
                    ),
                "trade_flow_monthly" to
                    DemoTableFact(
                        "one row per flow × month, all partners and chapters totalled",
                        "flow, period, total_value_usd, hs6_cell_count",
                    ),
                "partner_iso_crosswalk" to
                    DemoTableFact(
                        "one row per Census code the Comtrade reconciliation needs",
                        "census_code, iso_numeric, partner_name",
                    ),
                // trade — MySQL (mysql-world-trade.sql)
                "comtrade_annual" to
                    DemoTableFact(
                        "one row per reporter × flow × year at the headline TOTAL level",
                        "reporter_code, flow, period, value_usd",
                    ),
                "reporters" to
                    DemoTableFact(
                        "one row per Comtrade reporter country",
                        "reporter_code, reporter_name",
                    ),
                // trade — SQLite (sqlite-fx.sql)
                "fx_daily" to
                    DemoTableFact(
                        "one row per business day × currency, in both quote directions",
                        "rate_date, currency, per_usd, usd_per",
                    ),
                "fx_monthly" to
                    DemoTableFact(
                        "one row per month × currency, the G.5 monthly average",
                        "month, currency, per_usd, usd_per",
                    ),
                "currencies" to
                    DemoTableFact(
                        "one row per currency, with the Board's own series ids",
                        "currency, name, series_daily, series_monthly",
                    ),
                "partner_currency" to
                    DemoTableFact(
                        "one row per reconciled Census partner, naming its currency",
                        "census_code, partner, currency",
                    ),
                // themselves (read_parquet DESCRIBE, 2026-09-11).
                "hvfhv_trips" to
                    DemoTableFact(
                        "one row per high-volume for-hire trip, one directory per day",
                        "pickup_date (partition), pickup_at, pu_location_id, base_fare, driver_pay",
                    ),
                "hvfhv_trips_sample" to
                    DemoTableFact(
                        "the same trips, hash-sampled 1 in 16, one file per month",
                        "pickup_at, pu_location_id, base_fare, tips, hvfhs_license_num",
                    ),
                "hvfhv_zone_day" to
                    DemoTableFact(
                        "one row per pickup zone × day × company",
                        "pickup_date, pu_location_id, company, trip_count, shared_request_count",
                    ),
                "hvfhs_companies" to
                    DemoTableFact(
                        "one row per HVFHS licensee code",
                        "hvfhs_license_num, company, display_name",
                    ),
                "hvfhv_trips_iceberg" to
                    DemoTableFact(
                        "the 1-in-16 sample again, as an Iceberg table read by its metadata file",
                        "pickup_at, pu_location_id, base_fare, shared_request",
                    ),
            )

        val mapper: ObjectMapper = JsonMapper.builder().build()
    }
}
