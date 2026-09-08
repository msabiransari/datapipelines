package co.datapipelines.application.datasources

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.LakeIntrospectionCache
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * [LakeTableRegistryService] — the ONE validated lake-table path REST and MCP share (089 §A).
 *
 * What this suite pins is what the SERVICE owns: the LAKE-only refusal, the D8 mutation gate
 * call, the duplicate → 409 mapping, unregister-of-absent → 404, the import's two body forms
 * and its idempotency, and the invalidation seam ([LakeTableRegistryService.refreshConnections]
 * — pool eviction + §5.7 publish on every successful mutation, on none of the refused ones).
 * The grammar/location/SSRF rules have their own suites ([LakeTableValidatorTest],
 * [LakeManifestUrlTest]); here they matter only as refusal propagation.
 *
 * The repository is a mock whose answers are RECORDED, never fixed returns where "did the row
 * reach the store, and with what" is the question (the module's mockk discipline).
 */
class LakeTableRegistryServiceTest {
    private val mapper = JsonMapper.builder().build()
    private val registry = mockk<DatasourceRegistry>()
    private val tables = mockk<LakeTableRepository>()
    private val published = mutableListOf<String>()
    private val evicted = mutableListOf<String>()
    private val gated = mutableListOf<Datasource>()

    private val invalidation = PoolInvalidationPublisher { name -> published += name }
    private val gate =
        LakeTableMutationGate { _, datasource ->
            gated += datasource
        }

    /** The real cache, so the invalidation seam is proven behaviorally, not by a recorded call. */
    private val introspectionCache = LakeIntrospectionCache()

    private val service =
        LakeTableRegistryService(
            datasources = registry,
            tables = tables,
            invalidation = invalidation,
            manifestFetcher = LakeManifestFetcher { url -> fetched[url] ?: error("unexpected fetch $url") },
            mutationGate = gate,
            introspectionCache = introspectionCache,
        )

    private val fetched = mutableMapOf<String, com.fasterxml.jackson.databind.JsonNode>()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val principal =
        AuthenticatedPrincipal(
            userId,
            "agent@example.test",
            "Agent",
            setOf(Scope.AUTHOR),
            AuthMethod.API_KEY,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    private fun lake() =
        Datasource(
            name = "sample-lake",
            displayName = "Sample lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb:",
            properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3", "region" to "us-east-1")),
        )

    private fun postgres() =
        Datasource(name = "pg-prod", displayName = "PG", dialect = Dialect.POSTGRES, jdbcUrl = "jdbc:postgresql://h/db")

    private fun row(
        registration: LakeTableRegistration,
        datasourceId: String = "sample-lake",
    ) = LakeTable(
        id = UUID.randomUUID(),
        datasourceId = datasourceId,
        namespace = registration.namespace,
        name = registration.name,
        format = registration.format,
        location = registration.location,
        partitionColumn = registration.partitionColumn,
        registeredBy = userId,
        registeredAt = Instant.now(),
    )

    private val registration =
        LakeTableRegistration(
            namespace = listOf("nyc", "mobility"),
            name = "hvfhv_zone_day",
            format = LakeTableFormat.PARQUET,
            location = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
            partitionColumn = null,
        )

    private fun body(json: String) = mapper.readTree(json)

    // ---------------------------------------------------------- LAKE-only

    @Test
    fun `every operation refuses a non-LAKE datasource with the catalogued code`() {
        val ds = postgres()
        assertAll(
            {
                shouldThrow<DatapipelinesException> { service.list(ds) }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_DIALECT_REQUIRED
            },
            {
                shouldThrow<DatapipelinesException> {
                    service.register(ds, body("""{"namespace":"nyc","name":"t","format":"parquet","location":"s3://b/x"}"""), principal)
                }.code shouldBe PipelineErrorCodes.Datasource.LAKE_DIALECT_REQUIRED
            },
            {
                shouldThrow<DatapipelinesException> { service.unregister(ds, listOf("nyc"), "t", principal) }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_DIALECT_REQUIRED
            },
            {
                shouldThrow<DatapipelinesException> { service.importTables(ds, body("""{"tables": []}"""), principal) }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_DIALECT_REQUIRED
            },
            // A refused operation evicts nothing and publishes nothing.
            { evicted shouldBe emptyList() },
            { published shouldBe emptyList() },
        )
    }

    // ---------------------------------------------------------- register

    @Test
    fun `register stores the validated row, then evicts and publishes`() {
        val stored = mutableListOf<LakeTableRegistration>()
        every { tables.insert(any(), any(), any()) } answers { row(secondArg<LakeTableRegistration>().also(stored::add)) }
        every { registry.retirePool(any()) } answers {
            evicted += firstArg<String>()
            true
        }

        val result =
            service.register(
                lake(),
                body(
                    """
                    {"namespace": ["nyc", "mobility"], "name": "hvfhv_zone_day", "format": "parquet",
                     "location": "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet"}
                    """.trimIndent(),
                ),
                principal,
            )

        assertAll(
            { stored shouldContainExactly listOf(registration) },
            { result.qualifiedName shouldBe "nyc.mobility.hvfhv_zone_day" },
            { evicted shouldContainExactly listOf("sample-lake") },
            { published shouldContainExactly listOf("sample-lake") },
            { gated shouldHaveSize 1 },
        )
    }

    @Test
    fun `register maps the unique violation to the catalogued 409 and evicts nothing`() {
        every { tables.insert(any(), any(), any()) } throws DuplicateKeyException("uq_lake_tables_datasource_namespace_name")
        seedCacheEntry()

        val e =
            shouldThrow<DatapipelinesException> {
                service.register(
                    lake(),
                    body("""{"namespace": "nyc.mobility", "name": "hvfhv_zone_day", "format": "parquet", "location": "s3://b/x"}"""),
                    principal,
                )
            }
        assertAll(
            { e.code shouldBe PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE },
            { evicted shouldBe emptyList() },
            { published shouldBe emptyList() },
            // A refused write invalidates nothing: the cached introspection entry survives.
            { cacheEntrySurvives() shouldBe true },
        )
    }

    @Test
    fun `a successful mutation drops phase C's introspection cache beside the pool eviction`() {
        every { tables.insert(any(), any(), any()) } answers { row(secondArg<LakeTableRegistration>()) }
        every { registry.retirePool(any()) } returns true
        seedCacheEntry()

        service.register(
            lake(),
            body("""{"namespace": "nyc.mobility", "name": "hvfhv_zone_day", "format": "parquet", "location": "s3://b/x"}"""),
            principal,
        )

        // The seam ran: the next read re-derives instead of serving the pre-mutation entry.
        cacheEntrySurvives() shouldBe false
    }

    /** A pre-mutation introspection entry for the datasource, as the introspector would leave one. */
    private fun seedCacheEntry() {
        introspectionCache.get("sample-lake", "tables", "") { "cached" } shouldBe "cached"
    }

    /** True when the seeded entry is still served — i.e. refreshConnections did NOT run. */
    private fun cacheEntrySurvives(): Boolean = introspectionCache.get("sample-lake", "tables", "") { "re-derived" } == "cached"

    @Test
    fun `register refuses an injection-bearing location before any store call`() {
        val e =
            shouldThrow<DatapipelinesException> {
                service.register(
                    lake(),
                    body(
                        """{"namespace": "nyc", "name": "t", "format": "parquet",
                           "location": "s3://b/x'); DROP TABLE lake_tables; --"}""",
                    ),
                    principal,
                )
            }
        e.code shouldBe PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID
    }

    // ---------------------------------------------------------- unregister

    @Test
    fun `unregister deletes and invalidates - an absent triple is the catalogued 404`() {
        every { tables.delete(any(), any(), any()) } returns true andThen false
        every { registry.retirePool(any()) } answers {
            evicted += firstArg<String>()
            true
        }

        service.unregister(lake(), listOf("nyc", "mobility"), "hvfhv_zone_day", principal)
        val e = shouldThrow<DatapipelinesException> { service.unregister(lake(), listOf("nyc"), "ghost", principal) }

        assertAll(
            { e.code shouldBe PipelineErrorCodes.Datasource.LAKE_TABLE_NOT_FOUND },
            // Exactly ONE invalidation — the successful delete's.
            { evicted shouldContainExactly listOf("sample-lake") },
            { published shouldContainExactly listOf("sample-lake") },
        )
    }

    // ---------------------------------------------------------- import

    @Test
    fun `import registers the inline tables block and reports duplicates idempotently`() {
        every { tables.insertIfAbsent(any(), any(), any()) } answers { row(secondArg<LakeTableRegistration>()) } andThenAnswer { null }
        every { registry.retirePool(any()) } answers {
            evicted += firstArg<String>()
            true
        }

        val result =
            service.importTables(
                lake(),
                body(
                    """
                    {"namespace": "nyc.mobility",
                     "tables": [
                       {"name": "hvfhv_zone_day", "format": "parquet", "location": "s3://b/z/part-0.parquet"},
                       {"name": "hvfhv_trips", "format": "parquet",
                        "location": "s3://b/t/pickup_date=*/part-*.parquet", "partition_column": "pickup_date"}
                     ]}
                    """.trimIndent(),
                ),
                principal,
            )

        assertAll(
            { result.registered shouldHaveSize 1 },
            { result.registered[0].namespace shouldBe listOf("nyc", "mobility") },
            { result.alreadyRegistered shouldContainExactly listOf("nyc.mobility.hvfhv_trips") },
            // One insertion → one invalidation round, not one per row and not zero.
            { evicted shouldContainExactly listOf("sample-lake") },
            { published shouldContainExactly listOf("sample-lake") },
        )
    }

    @Test
    fun `import by manifest_url resolves, fetches and imports - the SSRF guard vets the URL first`() {
        val ds = lake()
        val manifest =
            body(
                """
                {"publish_prefix": "s3://datapipelines-co/sample-data/lake/v1",
                 "tables": [
                   {"name": "hvfhv_zone_day", "format": "parquet", "path": "hvfhv_zone_day/part-0.parquet"}
                 ]}
                """.trimIndent(),
            )
        fetched["https://datapipelines-co.s3.us-east-1.amazonaws.com/sample-data/lake/v1/manifest.json"] = manifest
        val inserted = mutableListOf<LakeTableRegistration>()
        every { tables.insertIfAbsent(any(), any(), any()) } answers { row(secondArg<LakeTableRegistration>().also(inserted::add)) }
        every { registry.retirePool(any()) } answers {
            evicted += firstArg<String>()
            true
        }

        val result =
            service.importTables(
                ds,
                body(
                    """{"namespace": ["nyc", "mobility"],
                       "manifest_url": "s3://datapipelines-co/sample-data/lake/v1/manifest.json"}""",
                ),
                principal,
            )

        assertAll(
            { result.registered shouldHaveSize 1 },
            {
                inserted shouldContainExactly
                    listOf(
                        LakeTableRegistration(
                            namespace = listOf("nyc", "mobility"),
                            name = "hvfhv_zone_day",
                            format = LakeTableFormat.PARQUET,
                            // The relative `path` was resolved against the manifest's publish_prefix.
                            location = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
                            partitionColumn = null,
                        ),
                    )
            },
        )
    }

    @Test
    fun `import refuses a URL outside the datasource's own roots without fetching`() {
        val e =
            shouldThrow<DatapipelinesException> {
                service.importTables(lake(), body("""{"manifest_url": "https://evil.example.com/manifest.json"}"""), principal)
            }
        assertAll(
            { e.code shouldBe PipelineErrorCodes.Datasource.LAKE_MANIFEST_URL_FORBIDDEN },
            { fetched shouldBe emptyMap<String, com.fasterxml.jackson.databind.JsonNode>() },
            { evicted shouldBe emptyList() },
        )
    }

    @Test
    fun `import validates every entry before the first insert - all-or-nothing`() {
        var insertCalls = 0
        every { tables.insertIfAbsent(any(), any(), any()) } answers {
            insertCalls++
            row(secondArg<LakeTableRegistration>())
        }

        val e =
            shouldThrow<DatapipelinesException> {
                service.importTables(
                    lake(),
                    body(
                        """
                        {"namespace": "nyc",
                         "tables": [
                           {"name": "fine", "format": "parquet", "location": "s3://b/fine.parquet"},
                           {"name": "bad name", "format": "parquet", "location": "s3://b/x.parquet"}
                         ]}
                        """.trimIndent(),
                    ),
                    principal,
                )
            }
        assertAll(
            { e.code shouldBe PipelineErrorCodes.Datasource.LAKE_NAME_INVALID },
            { insertCalls shouldBe 0 },
        )
    }

    @Test
    fun `a body with neither tables nor manifest_url is a payload-shape 400`() {
        shouldThrow<DatapipelinesException> { service.importTables(lake(), body("""{"namespace": "nyc"}"""), principal) }
            .code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID
    }

    @Test
    fun `a body with BOTH tables and manifest_url is refused - no silent winner`() {
        shouldThrow<DatapipelinesException> {
            service.importTables(
                lake(),
                body("""{"tables": [], "manifest_url": "s3://b/m.json"}"""),
                principal,
            )
        }.code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID
    }
}
