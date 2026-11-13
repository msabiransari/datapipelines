package co.datapipelines.web.config

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.application.datasources.LakeTableOrigin
import co.datapipelines.application.datasources.LakeTableRepository
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.datasources.ManagedLakeTable
import co.datapipelines.lake.LakeProperties
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #818: [LakeConfiguration.enrichedCatalog] enriches a MANAGED registry row from the managed read, so every reader
 * of the port (the executor's unavailable-table gate, introspection, stats) sees what the generation serves. The
 * booted `ManagedLakeReadsE2eTest` found the first version reporting a SERVED table as "no lifecycle row" (an elvis
 * over a null reason); this class pins each branch at the mapping itself, and (#867) which bound the wired bean's managed
 * read carries inside and outside a metadata transaction.
 */
class LakeTableCatalogManagedEnrichmentTest {
    private val served = "s3://dp-lake/lake/sales/orders/metadata/00002-b.metadata.json"
    private val projection = "s3://dp-lake/lake/sales/orders/metadata/00001-a.metadata.json"

    @Test
    fun `a served managed table carries the catalog location, no unavailable reason and its notice`() {
        val table =
            catalog(
                listOf(row("orders", LakeTableOrigin.MANAGED, projection)),
                listOf(managed("orders", served, "deprecated", notice = "This table is deprecated since 2026-11-03.")),
            ).single()

        assertAll(
            { table.managed shouldBe true },
            { table.location shouldBe served },
            { table.unavailableReason.shouldBeNull() },
            { table.notice shouldBe "This table is deprecated since 2026-11-03." },
        )
    }

    @Test
    fun `an unavailable managed table carries the managed read's reason`() {
        val table =
            catalog(
                listOf(row("orders", LakeTableOrigin.MANAGED, projection)),
                listOf(managed("orders", served, "archived")),
            ).single()

        table.unavailableReason shouldBe "status archived"
    }

    @Test
    fun `a managed row the managed read did not return keeps its projection and is unavailable`() {
        val table = catalog(listOf(row("orders", LakeTableOrigin.MANAGED, projection)), emptyList()).single()

        assertAll(
            { table.location shouldBe projection },
            { table.unavailableReason shouldBe ManagedLakeTable.REASON_NO_LIFECYCLE_ROW },
        )
    }

    @Test
    fun `an external row is untouched and a datasource without managed rows never runs the managed read`() {
        var managedReads = 0
        val tables =
            mockk<LakeTableRepository> {
                every { findByDatasource(LAKE) } returns
                    listOf(row("zones", LakeTableOrigin.EXTERNAL, "s3://ref/z.parquet"))
            }
        val table =
            LakeConfiguration
                .enrichedCatalog(
                    tables,
                    ManagedLakeCatalog {
                        managedReads++
                        emptyList()
                    },
                ).registeredTables(LAKE)
                .single()

        assertAll(
            { table.managed shouldBe false },
            { table.location shouldBe "s3://ref/z.parquet" },
            { table.unavailableReason.shouldBeNull() },
            { table.notice.shouldBeNull() },
            { managedReads shouldBe 0 },
        )
    }

    /**
     * #833 merge review F2, kept by #867: INSIDE a genuine metadata transaction (a transaction manager over the bean's
     * own DataSource, as `metadataTransactionManager` is) the BEAN's managed read carries no statement bound and sets no
     * socket bound. This port is read inside a release's, an import's and a promotion's metadata transaction, where a
     * socket bound set by one bounded read stays on the transaction's connection and would cut its later statements. The
     * read count keeps the case non-vacuous.
     */
    @Test
    fun `inside a metadata transaction the bean's managed read carries no statement bound and no socket bound`() {
        val recording = RecordingTimeoutDataSource(LAKE)
        val catalog = bean(recording)

        TransactionTemplate(DataSourceTransactionManager(recording)).executeWithoutResult { catalog.registeredTables(LAKE) }

        assertAll(
            { recording.statementCount() shouldBe 1 },
            { recording.timeoutOf(MANAGED_READ) shouldBe RecordingTimeoutDataSource.UNBOUNDED },
            { recording.socketTimeoutOf(MANAGED_READ) shouldBe RecordingTimeoutDataSource.NO_SOCKET_BOUND },
            { recording.networkTimeoutCalls() shouldBe emptyList() },
        )
    }

    /** #867: a participant in an outer metadata transaction (REQUIRED inside REQUIRED) is still on the transaction's path. */
    @Test
    fun `a participating inner transaction keeps the managed read unbounded`() {
        val recording = RecordingTimeoutDataSource(LAKE)
        val catalog = bean(recording)
        val transactions = TransactionTemplate(DataSourceTransactionManager(recording))

        transactions.executeWithoutResult { transactions.executeWithoutResult { catalog.registeredTables(LAKE) } }

        assertAll(
            { recording.statementCount() shouldBe 1 },
            { recording.timeoutOf(MANAGED_READ) shouldBe RecordingTimeoutDataSource.UNBOUNDED },
            { recording.networkTimeoutCalls() shouldBe emptyList() },
        )
    }

    /**
     * #867: OUTSIDE any transaction (the introspection and stats listings) the BEAN's managed read is bounded: exactly one
     * managed statement, the 5 s statement bound and the 10 s socket bound (`MANAGED_BUILD_READ_TIMEOUT_SECONDS` + 5 s,
     * written as literals, never computed from the class under test), one socket setter call.
     */
    @Test
    fun `outside a transaction the bean's managed read carries the 5 s statement bound and the 10 s socket bound`() {
        val recording = RecordingTimeoutDataSource(LAKE)

        bean(recording).registeredTables(LAKE)

        assertAll(
            { recording.statementCount() shouldBe 1 },
            { recording.timeoutOf(MANAGED_READ) shouldBe LISTING_STATEMENT_SECONDS },
            { recording.socketTimeoutOf(MANAGED_READ) shouldBe LISTING_SOCKET_MILLIS },
            { recording.networkTimeoutCalls() shouldBe listOf(LISTING_SOCKET_MILLIS) },
        )
    }

    /**
     * #867: ONE bean, alternating calls — the port is chosen at every call, not when the bean is built. A choice made at
     * bean creation (outside any transaction) would bound every call, or none.
     */
    @Test
    fun `one bean chooses per call - inside, outside, inside, outside`() {
        val recording = RecordingTimeoutDataSource(LAKE)
        val catalog = bean(recording)
        val transactions = TransactionTemplate(DataSourceTransactionManager(recording))

        transactions.executeWithoutResult { catalog.registeredTables(LAKE) }
        catalog.registeredTables(LAKE)
        transactions.executeWithoutResult { catalog.registeredTables(LAKE) }
        catalog.registeredTables(LAKE)

        assertAll(
            { recording.firstLines().all { it.startsWith(MANAGED_READ) } shouldBe true },
            { recording.timeouts() shouldBe listOf(0, LISTING_STATEMENT_SECONDS, 0, LISTING_STATEMENT_SECONDS) },
            { recording.socketTimeouts() shouldBe listOf(0, LISTING_SOCKET_MILLIS, 0, LISTING_SOCKET_MILLIS) },
        )
    }

    /** #867: the wired bean keeps the laziness — a datasource with external rows only sends no managed statement at all. */
    @Test
    fun `the bean sends no managed statement for a datasource without managed rows, on either path`() {
        val recording = RecordingTimeoutDataSource(LAKE)
        val catalog = bean(recording, rows = listOf(row("zones", LakeTableOrigin.EXTERNAL, "s3://ref/z.parquet")))

        catalog.registeredTables(LAKE).single().managed shouldBe false
        TransactionTemplate(DataSourceTransactionManager(recording)).executeWithoutResult { catalog.registeredTables(LAKE) }

        recording.statementCount() shouldBe 0
    }

    /** #867: with the lake disabled both ports report every managed row `lake.disabled` — one mapping, two templates. */
    @Test
    fun `a disabled lake reports lake disabled on both paths`() {
        val recording = RecordingTimeoutDataSource(LAKE, tableName = "orders")
        val catalog = bean(recording, lake = LakeProperties(enabled = false))

        val outside = catalog.registeredTables(LAKE).single()
        val inside =
            TransactionTemplate(DataSourceTransactionManager(recording)).execute { catalog.registeredTables(LAKE).single() }

        assertAll(
            { recording.statementCount() shouldBe 2 },
            { outside.unavailableReason shouldBe ManagedLakeTable.REASON_LAKE_DISABLED },
            { inside?.unavailableReason shouldBe ManagedLakeTable.REASON_LAKE_DISABLED },
        )
    }

    /** The production bean over [recording], its projection answering [rows]. */
    private fun bean(
        recording: RecordingTimeoutDataSource,
        rows: List<LakeTable> = listOf(row("orders", LakeTableOrigin.MANAGED, projection)),
        lake: LakeProperties = SCHEDULED_JOBS_TEST_LAKE_PROPERTIES,
    ) = LakeConfiguration().lakeTableCatalog(
        mockk { every { findByDatasource(LAKE) } returns rows },
        NamedParameterJdbcTemplate(recording),
        beanProviderOf(lake),
    )

    private fun catalog(
        rows: List<LakeTable>,
        managed: List<ManagedLakeTable>,
    ) = LakeConfiguration
        .enrichedCatalog(mockk { every { findByDatasource(LAKE) } returns rows }, ManagedLakeCatalog { managed })
        .registeredTables(LAKE)

    private fun row(
        name: String,
        origin: LakeTableOrigin,
        location: String,
    ) = LakeTable(
        id = UUID.randomUUID(),
        datasourceId = LAKE,
        namespace = listOf(LAKE, "sales"),
        name = name,
        format = if (origin == LakeTableOrigin.MANAGED) LakeTableFormat.ICEBERG else LakeTableFormat.PARQUET,
        location = location,
        partitionColumn = null,
        registeredBy = UUID.randomUUID(),
        registeredAt = Instant.parse("2026-11-04T00:00:00Z"),
        origin = origin,
        revisionSeq = if (origin == LakeTableOrigin.MANAGED) 1L else null,
    )

    private fun managed(
        name: String,
        location: String,
        status: String,
        notice: String? = null,
    ) = ManagedLakeTable(
        namespace = listOf(LAKE, "sales"),
        name = name,
        format = "iceberg",
        metadataLocation = location,
        status = status,
        deprecationMessage = notice,
    )

    private companion object {
        const val LAKE = "lake"

        /** The managed read's SQL, as `ScheduledJobsStatementBoundsTest` matches it. */
        const val MANAGED_READ = "SELECT t.namespace, t.name"

        /** #867: the listing read's statement bound, seconds (MANAGED_BUILD_READ_TIMEOUT_SECONDS) — a literal. */
        const val LISTING_STATEMENT_SECONDS = 5

        /** #867: its socket bound, the statement bound plus BoundedJdbc's 5 s slack, in milliseconds — a literal. */
        const val LISTING_SOCKET_MILLIS = 10_000
    }
}
