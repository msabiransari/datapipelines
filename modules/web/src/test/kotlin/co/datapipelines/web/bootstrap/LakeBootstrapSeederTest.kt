package co.datapipelines.web.bootstrap

import co.datapipelines.application.datasources.LakeImportResult
import co.datapipelines.application.datasources.LakeManifestFetcher
import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import co.datapipelines.datasources.BootstrapDatasourceFileException
import co.datapipelines.datasources.BootstrapLakeImport
import co.datapipelines.datasources.BootstrapLakeTable
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [LakeBootstrapSeeder] — the registrar's lake port answered with the registry service's ONE
 * import path (089 §E). The service is mocked: validation and idempotency are ITS suite's
 * subject; what this suite pins is the body the seeder hands it — the SSRF-vetted fetch, the
 * mirror rule for relative `path`s, the namespace, the allowlist — and the principal.
 */
class LakeBootstrapSeederTest {
    private val mapper = ObjectMapper()
    private val lakeTables = mockk<LakeTableRegistryService>()
    private val users = mockk<UserRepository>()
    private val actor = UUID.randomUUID()

    private var fetchedUrl: String? = null
    private var manifest: JsonNode? = null
    private val fetcher =
        LakeManifestFetcher { url ->
            fetchedUrl = url
            checkNotNull(manifest) { "the test set no manifest to serve" }
        }

    private val seeder = LakeBootstrapSeeder(lakeTables, users, fetcher)

    private val importedBodies = mutableListOf<JsonNode>()
    private val importedPrincipals = mutableListOf<AuthenticatedPrincipal>()

    private fun stubImport() {
        every { lakeTables.importTables(any(), capture(importedBodies), capture(importedPrincipals)) } returns
            LakeImportResult(registered = emptyList(), alreadyRegistered = emptyList())
        every { users.findById(actor) } returns
            User(
                id = actor,
                email = "admin@example.com",
                displayName = "admin",
                provider = "bootstrap",
                providerSubject = "admin@example.com",
                isActive = true,
                isAdmin = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
    }

    private fun lake(dialect: Map<String, Any?> = mapOf("catalog.kind" to "s3", "region" to "us-east-1")) =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            properties = DatasourceProperties(dialect = dialect),
        )

    /** The tables[] of the one body the import service received (every test imports exactly one). */
    private fun importedTables(): JsonNode = importedBodies.single().get("tables")

    private fun manifest(text: String) {
        manifest = mapper.readTree(text)
    }

    @Test
    fun `an https manifest's relative paths resolve against the manifest's publish_prefix`() {
        stubImport()
        manifest(
            """
            {"publish_prefix": "s3://datapipelines-co/sample-data/lake/v1",
             "tables": [
               {"name": "hvfhv_zone_day", "format": "parquet", "path": "hvfhv_zone_day/part-0.parquet",
                "location": "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet"}
             ]}
            """.trimIndent(),
        )

        seeder.seed(
            lake(),
            BootstrapLakeImport(
                tables = null,
                manifestUrl = "https://datapipelines-co.s3.amazonaws.com/sample-data/lake/v1/manifest.json",
                namespace = listOf("nyc", "mobility"),
                onlyTables = null,
            ),
            actor,
        )

        // The SSRF boundary vetted the URL BEFORE the fetch — and the fetch got it back unchanged.
        fetchedUrl shouldBe "https://datapipelines-co.s3.amazonaws.com/sample-data/lake/v1/manifest.json"
        val body = importedBodies.single()
        body.get("namespace").map { it.asText() } shouldContainExactly listOf("nyc", "mobility")
        val table = body.get("tables").single()
        // An https parent is not a servable location, so the manifest's own publication prefix is
        // the base — the value the embedded absolute `location` already carried.
        table.get("location").asText() shouldBe "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet"
        table.get("path") shouldBe null
    }

    @Test
    fun `an s3 manifest URL resolves paths against its own parent`() {
        stubImport()
        manifest("""{"tables": [{"name": "t", "format": "parquet", "path": "t/part-0.parquet"}]}""")

        seeder.seed(
            lake(),
            BootstrapLakeImport(
                tables = null,
                manifestUrl = "s3://datapipelines-co/sample-data/lake/v1/manifest.json",
                namespace = listOf("nyc"),
                onlyTables = null,
            ),
            actor,
        )

        // s3:// is TRANSLATED for the fetch (never fetched as-is — §A), but the location base is
        // the URL as given: the grammar's world, where the engine can read it.
        fetchedUrl shouldBe "https://datapipelines-co.s3.us-east-1.amazonaws.com/sample-data/lake/v1/manifest.json"
        importedTables()
            .single()
            .get("location")
            .asText() shouldBe "s3://datapipelines-co/sample-data/lake/v1/t/part-0.parquet"
    }

    @Test
    fun `a file manifest URL resolves paths against its own parent - the mirror works unchanged`() {
        stubImport()
        manifest(
            """
            {"publish_prefix": "s3://datapipelines-co/sample-data/lake/v1",
             "tables": [{"name": "t", "format": "parquet", "path": "t/part-0.parquet"}]}
            """.trimIndent(),
        )

        seeder.seed(
            lake(mapOf("catalog.ref" to "file:///srv/lake-mirror")),
            BootstrapLakeImport(
                tables = null,
                manifestUrl = "file:///srv/lake-mirror/v1/manifest.json",
                namespace = listOf("nyc"),
                onlyTables = null,
            ),
            actor,
        )

        // The canonical publication (publish_prefix) is NOT where this deployment reads.
        fetchedUrl shouldBe "file:///srv/lake-mirror/v1/manifest.json"
        importedTables()
            .single()
            .get("location")
            .asText() shouldBe "file:///srv/lake-mirror/v1/t/part-0.parquet"
    }

    @Test
    fun `only_tables filters the manifest and a name it does not offer fails the boot`() {
        stubImport()
        manifest(
            """
            {"tables": [
              {"name": "hvfhv_zone_day", "format": "parquet", "path": "hvfhv_zone_day/part-0.parquet"},
              {"name": "hvfhv_trips_iceberg", "format": "iceberg", "path": "hvfhv_trips_iceberg"}
            ]}
            """.trimIndent(),
        )
        val import =
            BootstrapLakeImport(
                tables = null,
                manifestUrl = "s3://b/v1/manifest.json",
                namespace = listOf("nyc"),
                onlyTables = listOf("hvfhv_zone_day"),
            )

        seeder.seed(lake(), import, actor)

        importedBodies.single().get("tables").map { it.get("name").asText() } shouldContainExactly listOf("hvfhv_zone_day")

        shouldThrow<BootstrapDatasourceFileException> {
            seeder.seed(lake(), import.copy(onlyTables = listOf("hvfhv_zone_day", "no_such_table")), actor)
        }.message shouldBe
            "Bootstrap datasource 'sample-lake' has an 'only_tables' allowlist naming no_such_table, " +
            "which the seed source does not offer — fix the allowlist or the source."
    }

    @Test
    fun `a URL outside the datasource's roots never reaches the fetcher - the SSRF refusal travels`() {
        stubImport()
        manifest("""{"tables": []}""")

        shouldThrow<DatapipelinesException> {
            seeder.seed(
                lake(),
                BootstrapLakeImport(
                    tables = null,
                    manifestUrl = "https://evil.example.com/manifest.json",
                    namespace = null,
                    onlyTables = null,
                ),
                actor,
            )
        }.code shouldBe PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN

        fetchedUrl shouldBe null
        importedBodies shouldContainExactly emptyList()
    }

    @Test
    fun `the inline tables block maps onto the import body verbatim, with the shared namespace`() {
        stubImport()

        seeder.seed(
            lake(),
            BootstrapLakeImport(
                tables =
                    listOf(
                        BootstrapLakeTable(
                            namespace = null,
                            name = "hvfhv_trips",
                            format = "parquet",
                            location = "s3://b/v1/hvfhv_trips/pickup_date=*/part-*.parquet",
                            partitionColumn = "pickup_date",
                        ),
                    ),
                manifestUrl = null,
                namespace = listOf("nyc", "mobility"),
                onlyTables = null,
            ),
            actor,
        )

        val body = importedBodies.single()
        body.get("namespace").map { it.asText() } shouldContainExactly listOf("nyc", "mobility")
        val table = body.get("tables").single()
        table.get("name").asText() shouldBe "hvfhv_trips"
        table.get("partition_column").asText() shouldBe "pickup_date"
        // No fetch on the inline path.
        fetchedUrl shouldBe null
    }

    @Test
    fun `the import runs as the bootstrap actor - admin-scoped, recorded as registered_by`() {
        stubImport()

        seeder.seed(
            lake(),
            BootstrapLakeImport(
                tables = listOf(BootstrapLakeTable(null, "t", "parquet", "s3://b/t", null)),
                manifestUrl = null,
                namespace = listOf("nyc"),
                onlyTables = null,
            ),
            actor,
        )

        val principal = importedPrincipals.single()
        principal.userId shouldBe actor
        principal.isSuperAdmin shouldBe true // the D8 global-mutation gate's whole question
    }
}
