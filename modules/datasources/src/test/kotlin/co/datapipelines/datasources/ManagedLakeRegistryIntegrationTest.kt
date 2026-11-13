package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ManagedLakeGeneration
import co.datapipelines.logcapture.capturingLogs
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #818 (dp-lake P5, design record §6, §10) — the managed lake datasource at the registry level: metadata in a
 * real Postgres, the catalog read behind a scripted [ManagedLakeCatalog].
 *
 * - The registrar's privileged write ([DefaultDatasourceRegistry.syncManagedLake]): CREATED, UNCHANGED (no
 *   write), RESYNCED (row rewritten, live pool retired, peers told), NAME_TAKEN (a live or a soft-deleted row
 *   that is not the managed one).
 * - The chokepoint: every save and delete of the managed row is refused `datasource.managed`, and so is a new row
 *   that claims to be it.
 * - The generation's catalog read: planned from the catalog's metadata location, never the registry projection;
 *   a failed read builds nothing (cold → `lake.unavailable`), and a failed tick read retires nothing.
 * - The freshness tick: identity over repeated ticks, retirement when the catalog moved.
 * - A catalog location that fails the location grammar gets no view, and the instance still serves.
 *
 * No table here gets a view, so nothing reaches S3; the S3 extensions still load (`catalog.kind = s3`), exactly
 * as [LakeConnectivityIntegrationTest]'s pools load them. The MinIO-backed reads are [ManagedLakeMinioIntegrationTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedLakeRegistryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var owner: UUID

    private val encryptor = testEncryptor()

    /** What the scripted catalog answers next; [catalogFailure] wins when set. */
    @Volatile private var catalogTables: List<ManagedLakeTable> = emptyList()

    @Volatile private var catalogFailure: RuntimeException? = null

    private val catalogReads = AtomicInteger()

    private val catalog =
        ManagedLakeCatalog { _ ->
            catalogReads.incrementAndGet()
            catalogFailure?.let { throw it }
            catalogTables
        }

    private val recorded = CopyOnWriteArrayList<Pair<String, String?>>()
    private val published = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner = insertUser()
        catalogTables = emptyList()
        catalogFailure = null
        catalogReads.set(0)
        recorded.clear()
        published.clear()
    }

    private fun registry(
        auditSink: DatasourceAuditSink = DatasourceAuditSink.NONE,
        registered: List<LakeRegisteredTable> = emptyList(),
    ): DefaultDatasourceRegistry =
        DefaultDatasourceRegistry(
            DatasourceRepository(jdbc),
            encryptor,
            auditSink = auditSink,
            invalidation = { published += it },
            lakeTables = { registered },
            lakeViewRecorder = { _, namespace, table, error -> recorded += (namespace + table).joinToString(".") to error },
            managedLake = catalog,
            maxGenerationAge = java.time.Duration.ofMinutes(MAX_AGE_MINUTES),
        )

    // ------------------ the registrar's write: CREATED / UNCHANGED / RESYNCED / NAME_TAKEN

    @Test
    fun `sync creates the managed row once, then writes nothing while configuration is unchanged`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val firstVersion = updatedAt()
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.UNCHANGED
            val stored = registry.get(LAKE).shouldNotBeNull()
            assertAll(
                { updatedAt() shouldBe firstVersion },
                // #874: CREATED minted generation 1; an UNCHANGED sync writes nothing, so it stays there.
                { generation() shouldBe 1L },
                { stored.isManagedLake() shouldBe true },
                { stored.isReadonly shouldBe true },
                // The credential took the encrypted path: never in properties, never returned.
                { stored.secret.shouldBeNull() },
                {
                    stored.properties.dialect.values
                        .map { it.toString() }
                        .none { it == READER_SECRET } shouldBe true
                },
                { credentialHex().contains(READER_SECRET.toByteArray().joinToString("") { "%02x".format(it) }) shouldBe false },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a configuration change re-syncs the row, retires the live pool and tells the peers`() {
        val audit = RecordingAuditSink()
        val registry = registry(auditSink = audit)
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val firstPool = registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            val firstVersion = updatedAt()

            // Credential rotation: the reader pair changed in configuration, then a restart.
            val firstGeneration = generation()
            registry.syncManagedLake(managed(secret = "$READER_SECRET-rotated"), owner) shouldBe ManagedLakeSync.RESYNCED

            assertAll(
                { (updatedAt().after(firstVersion)) shouldBe true },
                // #874: a RESYNC is a definition update — exactly one advance.
                { generation() shouldBe firstGeneration + 1 },
                { audit.countOf(DatasourceAuditEvents.POOL_REBUILD) shouldBe 1 },
                { published shouldContainExactly listOf(LAKE) },
                { registry.poolFor(registry.get(LAKE).shouldNotBeNull()) shouldNotBe firstPool },
            )
            // And the rotated secret is what the row now decrypts to: the next sync is UNCHANGED.
            registry.syncManagedLake(managed(secret = "$READER_SECRET-rotated"), owner) shouldBe ManagedLakeSync.UNCHANGED
            generation() shouldBe firstGeneration + 1
        } finally {
            registry.shutdown()
        }
    }

    /**
     * #845 (B.3, the upgrade note): a release that adds `egress_proxy` to the configured row RESYNCs the stored row on
     * its first boot (sameManagedShape compares the dialect map whole), retiring the pool built without the proxy;
     * the next boot is UNCHANGED, and dropping the proxy from configuration RESYNCs again.
     */
    @Test
    fun `845 - an egress proxy added by an upgrade re-syncs the row on the first boot, then is unchanged`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val withoutProxy = registry.poolFor(registry.get(LAKE).shouldNotBeNull())

            registry.syncManagedLake(managed(egressProxy = "egress-proxy:3128"), owner) shouldBe ManagedLakeSync.RESYNCED

            assertAll(
                {
                    registry
                        .get(LAKE)
                        .shouldNotBeNull()
                        .properties.dialect[LakeHttpBounds.EGRESS_PROXY_KEY] shouldBe "egress-proxy:3128"
                },
                { registry.poolFor(registry.get(LAKE).shouldNotBeNull()) shouldNotBe withoutProxy },
            )
            registry.syncManagedLake(managed(egressProxy = "egress-proxy:3128"), owner) shouldBe ManagedLakeSync.UNCHANGED
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.RESYNCED
            registry
                .get(LAKE)
                .shouldNotBeNull()
                .properties.dialect
                .containsKey(LakeHttpBounds.EGRESS_PROXY_KEY) shouldBe false
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a live row that is not the managed one holds the name - NAME_TAKEN, and the row is untouched`() {
        val registry = registry()
        try {
            registry.save(Fixtures.h2(name = LAKE, secret = "pw"), owner)
            val before = updatedAt()
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.NAME_TAKEN
            assertAll(
                { updatedAt() shouldBe before },
                { registry.get(LAKE).shouldNotBeNull().dialect shouldBe Dialect.H2 },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a soft-deleted row holding the name is NAME_TAKEN too - the name is never reused`() {
        val registry = registry()
        try {
            registry.save(Fixtures.h2(name = LAKE, secret = "pw"), owner)
            registry.delete(LAKE).deleted shouldBe true
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.NAME_TAKEN
            registry.get(LAKE).shouldBeNull()
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ the chokepoint: datasource.managed

    @Test
    fun `every save and delete of the managed row is refused datasource managed, and so is a row claiming to be it`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val before = updatedAt()
            val edit = shouldThrow<DatapipelinesException> { registry.save(managed().copy(description = "edited"), owner) }
            // An edit that drops the marker is still an edit of the managed row: the STORED row decides.
            val unmark =
                shouldThrow<DatapipelinesException> {
                    registry.save(managed().copy(properties = DatasourceProperties(dialect = mapOf("catalog.kind" to "s3"))), owner)
                }
            val delete = shouldThrow<DatapipelinesException> { registry.delete(LAKE) }
            // A NEW row that carries the marker under another name would be a second managed lake.
            val impostor = shouldThrow<DatapipelinesException> { registry.save(managed().copy(name = "lake_two"), owner) }
            assertAll(
                {
                    listOf(edit, unmark, delete, impostor).map { it.code }.distinct() shouldContainExactly
                        listOf(DatasourceErrorCodes.MANAGED)
                },
                { edit.details["datasource"] shouldBe LAKE },
                { impostor.details["datasource"] shouldBe "lake_two" },
                { updatedAt() shouldBe before },
                { registry.get(LAKE).shouldNotBeNull().description shouldBe managed().description },
                { registry.get("lake_two").shouldBeNull() },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a bootstrap credential resync never probes or writes the managed row`() {
        val audit = RecordingAuditSink()
        val registry = registry(auditSink = audit)
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            val before = updatedAt()
            val ciphertext = credentialHex()

            val outcome = registry.resyncBootstrapCredential(LAKE, "a-bootstrap-file-secret")

            assertAll(
                { outcome shouldBe CredentialResync.MANAGED_ROW },
                // Nothing probed: the resync's probe audits a connection test before it connects.
                { audit.countOf(DatasourceAuditEvents.CONNECTION_TEST) shouldBe 0 },
                { credentialHex() shouldBe ciphertext },
                { updatedAt() shouldBe before },
                // Configuration still owns the row: the next boot's sync finds it unchanged.
                { registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.UNCHANGED },
            )
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ the generation's catalog read

    @Test
    fun `views are planned from the catalog's metadata location, never the registry row's projection`() {
        // The projection lags a commit: the registry row still says v1, the catalog's row is v2.
        val projection = row("orders", location = "s3://dp-lake/sales/orders/metadata/00001-a.metadata.json")
        val current = table("orders", metadataLocation = "s3://dp-lake/sales/orders/metadata/00002-b.metadata.json")
        val tables = ManagedLakeReads(catalog).plannerInput(LAKE, listOf(projection, external()), listOf(current))
        val plan = LakeViewStatements.planForTables(tables, DialectAdapters.forDialect(Dialect.LAKE))
        val orders =
            plan.views
                .single { it.table.name == "orders" }
                .sql
                .shouldNotBeNull()
        assertAll(
            { orders shouldContain "00002-b.metadata.json" },
            { orders shouldNotContain "00001-a.metadata.json" },
            // The external row on the same datasource keeps today's path.
            {
                plan.views
                    .single { it.table.name == "zones" }
                    .sql
                    .shouldNotBeNull() shouldContain "zones.parquet"
            },
        )
    }

    @Test
    fun `a failed catalog read builds no generation - a cold datasource answers lake unavailable`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            catalogFailure = IllegalStateException("catalog connection refused")
            val refused = shouldThrow<DatapipelinesException> { registry.poolFor(registry.get(LAKE).shouldNotBeNull()) }
            assertAll(
                { refused.code shouldBe ManagedLakeErrorCodes.UNAVAILABLE },
                { refused.details["datasource"] shouldBe LAKE },
                { refused.details["cause"].toString() shouldContain "catalog connection refused" },
                { registry.liveManagedGeneration(LAKE).shouldBeNull() },
                { registry.generationBuiltAt(LAKE).shouldBeNull() },
            )
            // The catalog recovers: the next lease builds.
            catalogFailure = null
            registry.poolFor(registry.get(LAKE).shouldNotBeNull()).leaseConnection().use { selectOne(it) shouldBe 1 }
            registry.liveManagedGeneration(LAKE).shouldNotBeNull()
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a build reads the catalog exactly once, and the generation it serves is that read - #818b B`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner) shouldBe ManagedLakeSync.CREATED
            catalogTables = listOf(table("orders", null))
            val before = catalogReads.get()
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            pool.leaseConnection().use { selectOne(it) shouldBe 1 }
            assertAll(
                { catalogReads.get() - before shouldBe 1 },
                { pool.managedGeneration.shouldNotBeNull().tables shouldBe catalogTables },
                {
                    val reasons =
                        registry
                            .liveManagedGeneration(LAKE)
                            .shouldNotBeNull()
                            .unavailableTables
                            .map { it.reason }
                    reasons shouldBe listOf(ManagedLakeTable.REASON_NO_CATALOG_ROW)
                },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `the tick reads the catalog it is handed, never the build's port - #818b A`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            val buildReads = catalogReads.get()
            val tickReads = AtomicInteger()
            val tickCatalog =
                ManagedLakeCatalog { _ ->
                    tickReads.incrementAndGet()
                    throw org.springframework.dao.QueryTimeoutException("cancelled", java.sql.SQLException("canceling statement", "57014"))
                }

            val logs = capturingLogs { registry.refreshManagedGenerations(tickCatalog) shouldBe 0 }

            assertAll(
                { tickReads.get() shouldBe 1 },
                { catalogReads.get() shouldBe buildReads },
                {
                    logs.any {
                        it.contains("event=lake.freshness_read_failed") && it.contains("datasource=$LAKE") && it.contains("sql_state=57014")
                    } shouldBe true
                },
                { registry.liveManagedGeneration(LAKE).shouldNotBeNull() },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `a failed tick read retires nothing - the live generation serves on`() {
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            catalogFailure = IllegalStateException("catalog connection refused")
            val logs = capturingLogs { registry.refreshManagedGenerations(catalog) shouldBe 0 }
            assertAll(
                { logs.any { it.contains("event=lake.freshness_read_failed") && it.contains("datasource=$LAKE") } shouldBe true },
                { registry.poolFor(registry.get(LAKE).shouldNotBeNull()) shouldBe pool },
            )
            pool.leaseConnection().use { selectOne(it) shouldBe 1 }
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ the freshness tick

    @Test
    fun `an unchanged catalog keeps the generation across ticks, a moved catalog retires it`() {
        catalogTables = listOf(table("orders", metadataLocation = null))
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            val pool = registry.poolFor(registry.get(LAKE).shouldNotBeNull())
            val generation = registry.liveManagedGeneration(LAKE).shouldNotBeNull()
            generation.tables.map { it.qualifiedName } shouldContainExactly listOf("lake.sales.orders")

            repeat(THREE_TICKS) {
                withClue("tick ${it + 1} on an unchanged catalog") { registry.refreshManagedGenerations(catalog) shouldBe 0 }
            }
            registry.poolFor(registry.get(LAKE).shouldNotBeNull()) shouldBe pool

            // The table's first commit lands in the catalog: the generation that omitted it is stale.
            catalogTables = listOf(table("orders", metadataLocation = "s3://dp-lake/sales/orders/metadata/00001-a.metadata.json"))
            val logs = capturingLogs { registry.refreshManagedGenerations(catalog) shouldBe 1 }
            assertAll(
                { logs.any { it.contains("event=lake.generation_stale") && it.contains("tables_stale=1") } shouldBe true },
                { registry.liveManagedGeneration(LAKE).shouldBeNull() },
            )
        } finally {
            registry.shutdown()
        }
    }

    @Test
    fun `the tick never reads the catalog for a datasource that is not the managed lake`() {
        val registry = registry()
        try {
            registry.save(Fixtures.h2(name = "warehouse", secret = "pw"), owner)
            registry.poolFor(registry.get("warehouse").shouldNotBeNull()).leaseConnection().use { selectOne(it) shouldBe 1 }
            registry.refreshManagedGenerations(catalog) shouldBe 0
            catalogReads.get() shouldBe 0
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ the location grammar holds on the CATALOG value

    @Test
    fun `a catalog location carrying a quote gets no view, and the instance still serves`() {
        catalogTables =
            listOf(
                table("orders", metadataLocation = "s3://dp-lake/sales/orders/metadata/x'; ATTACH 'y"),
                table("returns", metadataLocation = null),
            )
        val registry = registry()
        try {
            registry.syncManagedLake(managed(), owner)
            registry.poolFor(registry.get(LAKE).shouldNotBeNull()).leaseConnection().use { selectOne(it) shouldBe 1 }
            val orders = recorded.filter { it.first == "lake.sales.orders" }
            assertAll(
                // Refused at the SQL-emission boundary (the registry's grammar), never sent to the engine.
                { orders.map { it.second }.single().shouldNotBeNull() shouldContain "fails the registry's grammar" },
                // The omitted table records nothing: an omission is not a view failure.
                { recorded.filter { it.first == "lake.sales.returns" }.shouldBeEmpty() },
            )
        } finally {
            registry.shutdown()
        }
    }

    // ------------------ the executor's read

    @Test
    fun `the generation's unavailable tables are exactly its snapshot rows that carry a reason - #818b B`() {
        val generation =
            ManagedLakeGeneration(
                LAKE,
                listOf(
                    table("orders", "s3://dp-lake/sales/orders/metadata/00001-a.metadata.json").copy(status = "archived"),
                    table("customers", "s3://dp-lake/sales/customers/metadata/00001-a.metadata.json"),
                    table("returns", null),
                ),
            )

        generation.unavailableTables shouldContainExactly
            listOf(
                LakeUnavailableTable(listOf(LAKE, "sales"), "orders", "status archived"),
                LakeUnavailableTable(listOf(LAKE, "sales"), "returns", ManagedLakeTable.REASON_NO_CATALOG_ROW),
            )
    }

    @Test
    fun `the generation snapshot is carried on the pool and reports its locations`() {
        val location = "s3://dp-lake/sales/orders/metadata/00001-a.metadata.json"
        val generation = ManagedLakeGeneration(LAKE, listOf(table("orders", metadataLocation = location), table("returns", null)))
        assertAll(
            { generation.locations shouldBe mapOf("lake.sales.orders" to location, "lake.sales.returns" to null) },
            { generation.staleTableCount(generation.tables) shouldBe 0 },
            { generation.staleTableCount(generation.tables.take(1)) shouldBe 1 },
            { generation.staleTableCount(generation.tables + table("refunds", null)) shouldBe 1 },
            // A view failure on the row is not staleness: the generation wrote it itself.
            { generation.staleTableCount(generation.tables.map { it.copy(lastError = "boom") }) shouldBe 0 },
        )
    }

    @Test
    fun `the generation lists a location only for a table it planned a view for - #818b C`() {
        val at = { name: String -> "s3://dp-lake/sales/$name/metadata/00001-a.metadata.json" }
        val withStatus = { name: String, status: String? -> table(name, at(name)).copy(status = status) }
        val generation =
            ManagedLakeGeneration(
                LAKE,
                listOf(
                    withStatus("active", "active"),
                    withStatus("deprecated", "deprecated").copy(deprecationMessage = "use v2"),
                    withStatus("archived", "archived"),
                    withStatus("purged", "purged"),
                    withStatus("nolifecycle", null),
                    table("nocatalog", null),
                ),
            )
        val disabled = ManagedLakeGeneration(LAKE, listOf(withStatus("active", "active").copy(lakeDisabled = true)))
        assertAll(
            {
                generation.locations shouldBe
                    mapOf(
                        "lake.sales.active" to at("active"),
                        "lake.sales.deprecated" to at("deprecated"),
                        "lake.sales.archived" to null,
                        "lake.sales.purged" to null,
                        "lake.sales.nolifecycle" to null,
                        "lake.sales.nocatalog" to null,
                    )
            },
            { disabled.locations shouldBe mapOf("lake.sales.active" to null) },
            // The snapshot stays complete: restoring an archived table (same location) is still staleness.
            {
                val restored = generation.tables.map { if (it.name == "archived") it.copy(status = "active") else it }
                generation.staleTableCount(restored) shouldBe 1
            },
        )
    }

    // ------------------ fixtures

    private fun managed(
        secret: String = READER_SECRET,
        egressProxy: String? = null,
    ) = Datasource(
        name = LAKE,
        displayName = "Managed lake",
        description = "The server's managed lake.",
        dialect = Dialect.LAKE,
        jdbcUrl = "jdbc:duckdb::memory:",
        username = "reader",
        credentialKind = CredentialKind.PASSWORD,
        secret = secret,
        properties =
            DatasourceProperties(
                dialect =
                    mapOf(
                        "catalog.kind" to "s3",
                        MANAGED_DIALECT_KEY to "true",
                        "region" to "us-east-1",
                        "endpoint" to "localhost:9",
                        "url_style" to "path",
                        "memory_limit" to "512MB",
                    ) + listOfNotNull(egressProxy?.let { LakeHttpBounds.EGRESS_PROXY_KEY to it }),
            ),
        isReadonly = true,
    )

    private fun table(
        name: String,
        metadataLocation: String?,
    ) = ManagedLakeTable(
        namespace = listOf(LAKE, "sales"),
        name = name,
        format = "iceberg",
        metadataLocation = metadataLocation,
        status = "active",
    )

    private fun row(
        name: String,
        location: String = "s3://dp-lake/sales/$name/metadata/00001-a.metadata.json",
    ) = LakeRegisteredTable(listOf(LAKE, "sales"), name, "iceberg", location, managed = true)

    private fun external() = LakeRegisteredTable(listOf(LAKE, "ref"), "zones", "parquet", "s3://dp-ref/zones.parquet")

    /** #874 — the managed row's V59 generation. */
    private fun generation(): Long =
        checkNotNull(
            jdbc.queryForObject("SELECT generation FROM datasources WHERE name = :name", mapOf("name" to LAKE), Long::class.java),
        )

    private fun updatedAt(): Timestamp =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT updated_at FROM datasources WHERE name = :name",
                mapOf("name" to LAKE),
                Timestamp::class.java,
            ),
        )

    private fun credentialHex(): String =
        jdbc
            .queryForObject(
                "SELECT encode(credential_encrypted, 'hex') FROM datasources WHERE name = :name",
                mapOf("name" to LAKE),
                String::class.java,
            ).orEmpty()

    private fun selectOne(connection: java.sql.Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT 1").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES ('owner@example.com', 'Owner', 'google', 'sub-1')
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private companion object {
        const val LAKE = "lake"
        const val READER_SECRET = "reader-secret-value"
        const val MAX_AGE_MINUTES = 15L
        const val THREE_TICKS = 3
    }
}
