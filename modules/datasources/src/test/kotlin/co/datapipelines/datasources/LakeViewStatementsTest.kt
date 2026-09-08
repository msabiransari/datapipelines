package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * [LakeViewStatements] — phase B's registry-rows → connect-time SQL (089 §B).
 *
 * Pure generation: quoting, namespace grouping, the parquet/iceberg split, the search-path
 * rule, and the two emission-boundary refusals (an unmappable namespace depth, a location that
 * fails the registry's grammar). The engine-side proof that these statements RUN — views
 * readable through a real pool, a second registration visible after evict — is
 * [LakePoolViewsIntegrationTest].
 */
class LakeViewStatementsTest {
    private val lake = DialectAdapters.forDialect(Dialect.LAKE)

    private fun table(
        namespace: List<String>,
        name: String,
        format: String = "parquet",
        location: String = "s3://bucket/prefix/$name.parquet",
    ) = LakeRegisteredTable(namespace, name, format, location)

    @Test
    fun `zero tables means no statements and no ATTACH`() {
        LakeViewStatements.forTables(emptyList(), lake) shouldBe emptyList()
    }

    @Test
    fun `a single two-segment namespace gets one ATTACH, one schema, the views, and the search path`() {
        val statements =
            LakeViewStatements.forTables(
                listOf(
                    table(listOf("nyc", "mobility"), "hvfhv_trips", location = "s3://b/trips/pickup_date=*/part-*.parquet"),
                    table(listOf("nyc", "mobility"), "hvfhv_zone_day"),
                ),
                lake,
            )

        statements shouldContainExactly
            listOf(
                "ATTACH IF NOT EXISTS ':memory:' AS \"nyc\"",
                "CREATE SCHEMA IF NOT EXISTS \"nyc\".\"mobility\"",
                "CREATE OR REPLACE VIEW \"nyc\".\"mobility\".\"hvfhv_trips\" AS SELECT * FROM " +
                    "read_parquet('s3://b/trips/pickup_date=*/part-*.parquet', hive_partitioning = true)",
                "CREATE OR REPLACE VIEW \"nyc\".\"mobility\".\"hvfhv_zone_day\" AS SELECT * FROM " +
                    "read_parquet('s3://bucket/prefix/hvfhv_zone_day.parquet', hive_partitioning = true)",
                "SET search_path = 'nyc.mobility'",
            )
    }

    @Test
    fun `multiple namespaces mean no search path - fully-qualified names are required`() {
        val statements =
            LakeViewStatements.forTables(
                listOf(
                    table(listOf("nyc", "mobility"), "trips"),
                    table(listOf("trade", "root"), "orders"),
                ),
                lake,
            )

        assertAll(
            { statements.none { it.startsWith("SET search_path") } shouldBe true },
            // Both catalog heads get their ATTACH, each namespace its schema.
            {
                statements.filter { it.startsWith("ATTACH") } shouldContainExactly
                    listOf(
                        "ATTACH IF NOT EXISTS ':memory:' AS \"nyc\"",
                        "ATTACH IF NOT EXISTS ':memory:' AS \"trade\"",
                    )
            },
        )
    }

    @Test
    fun `a one-segment namespace is a schema in the default catalog - no ATTACH`() {
        val statements =
            LakeViewStatements.forTables(listOf(table(listOf("solo"), "u")), lake)

        statements shouldContainExactly
            listOf(
                "CREATE SCHEMA IF NOT EXISTS \"solo\"",
                "CREATE OR REPLACE VIEW \"solo\".\"u\" AS SELECT * FROM " +
                    "read_parquet('s3://bucket/prefix/u.parquet', hive_partitioning = true)",
                "SET search_path = 'solo'",
            )
    }

    @Test
    fun `iceberg tables scan through iceberg_scan, parquet through read_parquet`() {
        val statements =
            LakeViewStatements.forTables(
                listOf(
                    table(listOf("lake"), "events", format = "iceberg", location = "s3://b/iceberg/events"),
                    table(listOf("lake"), "clicks", format = "parquet", location = "file://data/clicks/"),
                ),
                lake,
            )

        assertAll(
            { statements.any { it.contains("iceberg_scan('s3://b/iceberg/events')") } shouldBe true },
            { statements.any { it.contains("read_parquet('file://data/clicks/', hive_partitioning = true)") } shouldBe true },
        )
    }

    @Test
    fun `identifiers are quoted through the adapter - an embedded quote is doubled`() {
        // Registry names cannot contain a quote (the 077 segment grammar), but the generator's
        // quoting is the boundary and is pinned as one.
        val weird = LakeRegisteredTable(listOf("odd\"head", "s"), "t", "parquet", "s3://b/t.parquet")
        LakeViewStatements.forTables(listOf(weird), lake).first() shouldBe
            "ATTACH IF NOT EXISTS ':memory:' AS \"odd\"\"head\""
    }

    @Test
    fun `a namespace deeper than two segments is refused - the engine cannot name the place`() {
        val e =
            shouldThrow<DatapipelinesException> {
                LakeViewStatements.forTables(listOf(table(listOf("a", "b", "c"), "t")), lake)
            }
        assertAll(
            { e.code shouldBe DatasourceErrorCodes.LAKE_NAMESPACE_INVALID },
            { e.message shouldContain "a.b.c.t" },
        )
    }

    @Test
    fun `a location that fails the registry grammar is refused rather than interpolated`() {
        assertAll(
            // An injection string: quote, whitespace, a second statement.
            {
                shouldThrow<DatapipelinesException> {
                    LakeViewStatements.forTables(
                        listOf(table(listOf("nyc"), "t", location = "s3://b/x'); DROP TABLE lake_tables; --")),
                        lake,
                    )
                }.code shouldBe DatasourceErrorCodes.LAKE_LOCATION_INVALID
            },
            // A scheme outside the allowlist.
            {
                shouldThrow<DatapipelinesException> {
                    LakeViewStatements.forTables(listOf(table(listOf("nyc"), "t", location = "https://evil.example/x")), lake)
                }.code shouldBe DatasourceErrorCodes.LAKE_LOCATION_INVALID
            },
            // A backslash.
            {
                shouldThrow<DatapipelinesException> {
                    LakeViewStatements.forTables(listOf(table(listOf("nyc"), "t", location = "file://data\\x")), lake)
                }.code shouldBe DatasourceErrorCodes.LAKE_LOCATION_INVALID
            },
        )
    }

    @Test
    fun `an unknown format is refused rather than guessed`() {
        shouldThrow<DatapipelinesException> {
            LakeViewStatements.forTables(listOf(table(listOf("nyc"), "t", format = "csv")), lake)
        }.code shouldBe DatasourceErrorCodes.LAKE_FORMAT_INVALID
    }

    @Test
    fun `hive_partitioning is unconditional - a registered-but-absent partition column cannot disable pruning`() {
        // Both tables carry no partition_column distinction at this layer: the clause is always
        // emitted (verified harmless on a plain file against duckdb_jdbc 1.5.5.1).
        val statements = LakeViewStatements.forTables(listOf(table(listOf("nyc"), "plain")), lake)
        statements.single { it.startsWith("CREATE OR REPLACE VIEW") } shouldContain "hive_partitioning = true"
    }

    // ----------------------------------------------------------------- 089 §F: the iceberg loads

    @Test
    fun `an iceberg table prepends INSTALL and LOAD iceberg when no extension directory is set`() {
        // catalog.kind s3 makes the ADAPTER load httpfs+aws only — the registry-driven need
        // lives here, under every catalog kind (089 §F, the phase-4 live finding).
        val statements =
            LakeViewStatements.forTables(
                listOf(table(listOf("lake"), "events", format = "iceberg", location = "s3://b/iceberg/events")),
                lake,
            )

        statements shouldContainExactly
            listOf(
                "INSTALL iceberg",
                "LOAD iceberg",
                "CREATE SCHEMA IF NOT EXISTS \"lake\"",
                "CREATE OR REPLACE VIEW \"lake\".\"events\" AS SELECT * FROM iceberg_scan('s3://b/iceberg/events')",
                "SET search_path = 'lake'",
            )
    }

    @Test
    fun `an iceberg table under a bundled extension directory gets SET plus bare LOADs and never an INSTALL`() {
        val statements =
            LakeViewStatements.forTables(
                listOf(table(listOf("lake"), "events", format = "iceberg", location = "s3://b/iceberg/events")),
                lake,
                duckdbExtensionDirectory = "/opt/datapipelines/duckdb-extensions",
            )

        assertAll(
            {
                statements.take(3) shouldContainExactly
                    listOf(
                        "SET extension_directory = '/opt/datapipelines/duckdb-extensions'",
                        "LOAD avro",
                        "LOAD iceberg",
                    )
            },
            { statements.none { it.startsWith("INSTALL") } shouldBe true },
        )
    }

    @Test
    fun `a parquet-only registry loads no iceberg extension - with or without a directory`() {
        assertAll(
            {
                LakeViewStatements
                    .forTables(listOf(table(listOf("nyc"), "trips")), lake)
                    .none { it.startsWith("INSTALL") || it.startsWith("LOAD") } shouldBe true
            },
            {
                // The bundled directory is not even SET for a registry that never needs it.
                LakeViewStatements
                    .forTables(listOf(table(listOf("nyc"), "trips")), lake, duckdbExtensionDirectory = "/opt/x")
                    .none { it.contains("extension") || it.startsWith("LOAD") || it.startsWith("INSTALL") } shouldBe true
            },
        )
    }

    @Test
    fun `a mixed registry emits the iceberg loads exactly once`() {
        val statements =
            LakeViewStatements.forTables(
                listOf(
                    table(listOf("lake"), "events", format = "iceberg", location = "s3://b/iceberg/events"),
                    table(listOf("lake"), "snapshots", format = "iceberg", location = "s3://b/iceberg/snapshots"),
                    table(listOf("lake"), "clicks", location = "s3://b/clicks/"),
                ),
                lake,
            )
        statements.filter { it == "LOAD iceberg" }.size shouldBe 1
    }

    @Test
    fun `an unsafe extension directory is refused at this boundary when iceberg tables need it`() {
        val iceberg = table(listOf("lake"), "events", format = "iceberg", location = "s3://b/iceberg/events")
        assertAll(
            // Interpolated into a SQL string literal, so the attack string is refused, not escaped.
            {
                shouldThrow<DatapipelinesException> {
                    LakeViewStatements.forTables(listOf(iceberg), lake, duckdbExtensionDirectory = "/opt/x'; DROP--")
                }.code shouldBe DatasourceErrorCodes.PROPERTIES_INVALID
            },
            {
                shouldThrow<DatapipelinesException> {
                    LakeViewStatements.forTables(listOf(iceberg), lake, duckdbExtensionDirectory = "relative/dir")
                }.code shouldBe DatasourceErrorCodes.PROPERTIES_INVALID
            },
            // A parquet-only registry never interpolates the value, so it is never validated
            // here — the pool-build adapter's own require() stays the refusal for that case.
            {
                LakeViewStatements
                    .forTables(listOf(table(listOf("nyc"), "trips")), lake, duckdbExtensionDirectory = "relative/dir")
                    .isNotEmpty() shouldBe true
            },
        )
    }
}
