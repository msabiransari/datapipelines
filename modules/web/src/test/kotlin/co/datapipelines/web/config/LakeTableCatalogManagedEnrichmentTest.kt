package co.datapipelines.web.config

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.application.datasources.LakeTableFormat
import co.datapipelines.application.datasources.LakeTableOrigin
import co.datapipelines.application.datasources.LakeTableRepository
import co.datapipelines.datasources.ManagedLakeCatalog
import co.datapipelines.datasources.ManagedLakeTable
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * #818: [LakeConfiguration.enrichedCatalog] enriches a MANAGED registry row from the managed read, so every reader
 * of the port (the executor's unavailable-table gate, introspection, stats) sees what the generation serves. The
 * booted `ManagedLakeReadsE2eTest` found the first version reporting a SERVED table as "no lifecycle row" (an elvis
 * over a null reason); this class pins each branch at the mapping itself.
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
     * #833 merge review F2: the BEAN's managed read runs on the shared template with no statement and no socket bound,
     * never through the generation build's bounded repository. This port is also read inside a release's, an import's
     * and a promotion's metadata transaction, where a socket bound set by one bounded read stays on the transaction's
     * connection and would cut the transaction's later statements. The read count keeps the case non-vacuous.
     */
    @Test
    fun `the bean's managed read carries no statement bound and no socket bound (#833 merge review F2)`() {
        val recording = RecordingTimeoutDataSource(LAKE)
        LakeConfiguration()
            .lakeTableCatalog(
                mockk { every { findByDatasource(LAKE) } returns listOf(row("orders", LakeTableOrigin.MANAGED, projection)) },
                NamedParameterJdbcTemplate(recording),
                beanProviderOf(SCHEDULED_JOBS_TEST_LAKE_PROPERTIES),
            ).registeredTables(LAKE)

        assertAll(
            { recording.statementCount() shouldBe 1 },
            { recording.timeoutOf(MANAGED_READ) shouldBe RecordingTimeoutDataSource.UNBOUNDED },
            { recording.socketTimeoutOf(MANAGED_READ) shouldBe RecordingTimeoutDataSource.NO_SOCKET_BOUND },
            { recording.networkTimeoutCalls() shouldBe emptyList() },
        )
    }

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
    }
}
