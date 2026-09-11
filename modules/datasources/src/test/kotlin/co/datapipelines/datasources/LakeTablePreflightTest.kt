package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/**
 * 109 §A/§B — the registration pre-flight and the shown/hidden dialect projection, at the
 * module boundary (the E2E four-engine test exercises the same seam end to end, but kover
 * counts THIS module's own runs, and the seams have unit-shaped behavior worth pinning
 * anyway: the refusal is failure-as-data, never a throw).
 *
 * Real DuckDB over `file://` parquet in a [@TempDir] — the [LakeViewIsolationIntegrationTest]
 * harness shape — so the engine text asserted is the engine's own, not a mock's.
 */
class LakeTablePreflightTest {
    @TempDir
    lateinit var tempDir: File

    private fun lakeDatasource(name: String) =
        Datasource(
            name = name,
            displayName = name,
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = CredentialKind.NONE,
            // `unsigned: true` — these tables are LOCAL files. `kind: none` alone would emit
            // `CREATE SECRET … PROVIDER credential_chain`, which DuckDB VALIDATES at create
            // against the box's AWS config: green on a laptop with `~/.aws`, red on a CI
            // runner with none ("Secret Validation Failure … Credential Chain: 'config'",
            // 2026-09-11, every GitHub run). The fixture must not depend on the developer's
            // credentials; a local-file lake declares the public/unsigned posture and no
            // secret is created (DialectAdapters.lakeSecretStatement).
            properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3", "unsigned" to "true")),
        )

    private fun parquet(file: String): File =
        tempDir.resolve(file).apply {
            DriverManager.getConnection("jdbc:duckdb:").use { writer ->
                writer.createStatement().use { it.execute("COPY (SELECT 1 AS id) TO '$absolutePath' (FORMAT PARQUET)") }
            }
        }

    @Test
    fun `a readable parquet table pre-flights clean - null, the healthy spelling`() {
        val file = parquet("trips.parquet")
        val error =
            LakeTablePreflight.check(
                lakeDatasource("preflight_ok"),
                LakeRegisteredTable(listOf("nyc", "mobility"), "t", "parquet", "file://${file.absolutePath}"),
            )
        error shouldBe null
    }

    @Test
    fun `a file that is not parquet is refused with the engine's own text - bounded, never thrown`() {
        val broken = tempDir.resolve("broken.parquet").apply { writeText("this is not parquet") }
        val error =
            LakeTablePreflight.check(
                lakeDatasource("preflight_bad"),
                LakeRegisteredTable(listOf("nyc"), "t", "parquet", "file://${broken.absolutePath}"),
            )
        assertAll(
            { error.shouldNotBeNull() },
            // The engine's diagnosis, not a wrapper's — the sentence that ends the investigation.
            { error shouldContain "parquet" },
            { (error!!.length <= LakeTablePreflight.MAX_ERROR_CHARS) shouldBe true },
        )
    }

    @Test
    fun `an emission-refused table - a 3-segment namespace - refuses without an engine round-trip`() {
        val file = parquet("fine.parquet")
        val error =
            LakeTablePreflight.check(
                lakeDatasource("preflight_ns"),
                LakeRegisteredTable(listOf("a", "b", "c"), "t", "parquet", "file://${file.absolutePath}"),
            )
        error.shouldNotBeNull() shouldContain "3-segment namespace"
    }

    /**
     * A Postgres-backed registry (the module's SharedPostgres) with no lake tables — the
     * §8.1B outcome write runs, and [DefaultDatasourceRegistry.save]'s validation is the real
     * one. Truncates and seeds the `created_by` user, the registry suite's discipline.
     */
    private fun registryWithNoTables(): Pair<DefaultDatasourceRegistry, UUID> {
        val jdbc = JdbcTemplate(SharedPostgres.pooledDataSource())
        jdbc.execute("TRUNCATE datasources, users CASCADE")
        val owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES ('preflight@example.com', 'P', 'google', 'sub-pf') RETURNING id",
                    UUID::class.java,
                ),
            )
        val registry =
            DefaultDatasourceRegistry(
                DatasourceRepository(NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())),
                testEncryptor(),
                lakeTables = LakeTableCatalog { emptyList() },
            )
        return registry to owner
    }

    @Test
    fun `the probe LISTS a declared file-root catalog ref through testConnection`() {
        // 109 §B's file-root branch of the listing probe: catalog.ref file:// wins as the
        // listed root, glob runs on the probe's own connection, and a real parquet under it
        // makes the whole probe connected.
        val root = tempDir.resolve("mirror").apply { mkdirs() }
        tempDir.resolve("mirror/hvfhv_zone_day").apply { mkdirs() }
        parquet("mirror/hvfhv_zone_day/part-0.parquet")
        val (registry, owner) = registryWithNoTables()
        registry.save(
            lakeDatasource("preflight_file")
                .copy(properties = DatasourceProperties(dialect = mapOf("catalog.ref" to "file://${root.absolutePath}"))),
            owner,
        )

        registry.testConnection("preflight_file").shouldNotBeNull().connected shouldBe true
    }

    @Test
    fun `a quoted catalog ref is not globbable - the probe falls back to the metadata read`() {
        // The no-escaping rule: a root carrying a quote cannot enter the glob literal, so no
        // listing is attempted and the metadata version read remains the whole proof — a
        // CONNECTED answer, not a manufactured refusal.
        val (registry, owner) = registryWithNoTables()
        registry.save(
            lakeDatasource("preflight_quote")
                .copy(properties = DatasourceProperties(dialect = mapOf("catalog.ref" to "file:///o'brien"))),
            owner,
        )

        registry.testConnection("preflight_quote").shouldNotBeNull().connected shouldBe true
    }

    // ------------------------------- the shown/hidden projection (109 §B)

    @Test
    fun `visibleDialectProperties keeps the lake keys and drops secret-classified ones`() {
        val visible =
            visibleDialectProperties(
                Dialect.LAKE,
                mapOf(
                    "region" to "us-east-1",
                    "unsigned" to "true",
                    "auth_clientKey" to "sk-do-not-show",
                    "password" to "never",
                ),
            )
        assertAll(
            { visible["region"] shouldBe "us-east-1" },
            { visible["unsigned"] shouldBe "true" },
            { visible.containsKey("auth_clientKey") shouldBe false },
            { visible.containsKey("password") shouldBe false },
        )
    }

    @Test
    fun `the projection is over KEYS - a value that is itself a secret still travels under a clean key`() {
        // The §5.6 carrier rule: a secret VALUE belongs under credential, which never reaches
        // properties; the projection neither inspects nor mangles values.
        visibleDialectProperties(Dialect.LAKE, mapOf("region" to "whatever")) shouldBe mapOf("region" to "whatever")
    }
}
