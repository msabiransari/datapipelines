package co.datapipelines.application.semantics

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.TableInfo
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.datasources.semantics.LearnedFactTrust
import co.datapipelines.datasources.semantics.SchemaFingerprint
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * [LearnedFactsEnricher] — design §7.2 over mocked stores: which facts land on which object,
 * the §6 mark WRITTEN on the read path, D-S5 conflicts flagged on both sides, and the D-S9
 * pipeline link stopping at the reader's workspace. The detector itself is proven against a
 * real changing table in `LearnedFactDriftTest`; this suite proves the surface wiring around it.
 */
class LearnedFactsEnricherTest {
    private val repository = mockk<LearnedFactRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val enricher = LearnedFactsEnricher(repository, pipelines)

    private val amount = column("amount", LogicalType.BIGDECIMAL)
    private val placedAt = column("placed_at", LogicalType.TIMESTAMP)
    private val liveFingerprint = SchemaFingerprint.combine(mapOf("orders" to SchemaFingerprint.of(listOf(amount, placedAt))))

    @Test
    fun `columns carry their facts, a changed column set is marked needs_review on the row, and a conflict is flagged on both`() {
        val fresh = SemanticsFixtures.fact(fingerprint = liveFingerprint)
        val recordedAgainstOlderShape = SemanticsFixtures.fact(text = "amount is in dollars", fingerprint = "orders=stale-digest")
        val onPlacedAt =
            SemanticsFixtures.fact(
                kind = LearnedFactKind.TIME_ZONE,
                refs = listOf(FactRef(null, "orders", "placed_at")),
                text = "placed_at is UTC",
                fingerprint = liveFingerprint,
            )
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME) } returns
            listOf(fresh, recordedAgainstOlderShape, onPlacedAt)
        every { repository.markTrust(recordedAgainstOlderShape.id, LearnedFactTrust.NEEDS_REVIEW) } returns true

        val byColumn = enricher.forColumns(SemanticsFixtures.ACME, SemanticsFixtures.warehouse, "orders", null, listOf(amount, placedAt))

        val onAmount = byColumn.getValue("amount")
        assertAll(
            { onAmount.map { it["id"] } shouldContainExactly listOf(fresh.id.toString(), recordedAgainstOlderShape.id.toString()) },
            { onAmount[0]["trust"] shouldBe "observed" },
            { onAmount[0].containsKey("drift") shouldBe false },
            { onAmount[1]["trust"] shouldBe "needs_review" },
            { onAmount[1]["drift"] shouldBe "table columns changed since this was recorded" },
            // D-S5: same kind, same refs → both flagged, neither wins.
            { onAmount[0]["conflict"] shouldBe true },
            { onAmount[1]["conflict"] shouldBe true },
            { byColumn.getValue("placed_at").single().containsKey("conflict") shouldBe false },
        )
        // The §6 mark: the demotion was WRITTEN, exactly once, for the one fact that drifted.
        verify(exactly = 1) { repository.markTrust(recordedAgainstOlderShape.id, LearnedFactTrust.NEEDS_REVIEW) }
        verify(exactly = 0) { repository.markTrust(fresh.id, any()) }
    }

    @Test
    fun `a fact whose column is gone is served stale beside the current columns, on no column of its own`() {
        val onGone = SemanticsFixtures.fact(refs = listOf(FactRef(null, "orders", "total")), fingerprint = liveFingerprint)
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME) } returns listOf(onGone)
        every { repository.markTrust(onGone.id, LearnedFactTrust.STALE) } returns true

        val byColumn = enricher.forColumns(SemanticsFixtures.ACME, SemanticsFixtures.warehouse, "orders", null, listOf(amount, placedAt))

        // The column no longer exists, so there is no column entry to hang the fact on — but the
        // row was marked, which is what the next listing (and the UI) shows beside the columns.
        byColumn shouldBe emptyMap()
        verify(exactly = 1) { repository.markTrust(onGone.id, LearnedFactTrust.STALE) }
    }

    @Test
    fun `tables carry their table-grain facts and a listing that lost the table marks the fact stale`() {
        val grain =
            SemanticsFixtures.fact(
                kind = LearnedFactKind.GRAIN,
                refs = listOf(FactRef(null, "orders", null)),
                text = "one row per order line",
            )
        val onGoneTable =
            SemanticsFixtures.fact(
                kind = LearnedFactKind.GRAIN,
                refs = listOf(FactRef(null, "shipments", null)),
                text = "one row per parcel",
            )
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME) } returns listOf(grain, onGoneTable)
        every { repository.markTrust(onGoneTable.id, LearnedFactTrust.STALE) } returns true

        val listing = listOf(TableInfo(listOf("public"), "orders", "TABLE"))
        // A FILTERED listing first: it proves nothing about what it did not list, so no mark.
        enricher.forTables(SemanticsFixtures.ACME, SemanticsFixtures.warehouse, listing, complete = false)
        verify(exactly = 0) { repository.markTrust(any(), any()) }

        val byTable = enricher.forTables(SemanticsFixtures.ACME, SemanticsFixtures.warehouse, listing, complete = true)

        assertAll(
            { byTable.keys shouldBe setOf("orders") },
            { byTable.getValue("orders").single()["kind"] shouldBe "grain" },
        )
        verify(exactly = 1) { repository.markTrust(onGoneTable.id, LearnedFactTrust.STALE) }
    }

    @Test
    fun `a fact already stale on a column rides on its table's entry - shown beside the columns, not lost`() {
        val staleOnColumn = SemanticsFixtures.fact(refs = listOf(FactRef(null, "orders", "total")), trust = LearnedFactTrust.STALE)
        val liveOnColumn = SemanticsFixtures.fact()
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME) } returns listOf(staleOnColumn, liveOnColumn)

        val byTable =
            enricher.forTables(
                SemanticsFixtures.ACME,
                SemanticsFixtures.warehouse,
                listOf(TableInfo(listOf("public"), "orders", "TABLE")),
                complete = true,
            )

        val shown = byTable.getValue("orders")
        assertAll(
            { shown.map { it["id"] } shouldContainExactly listOf(staleOnColumn.id.toString()) },
            { shown.single()["trust"] shouldBe "stale" },
            { shown.single()["drift"] shouldBe "a referenced column or table no longer exists" },
        )
    }

    @Test
    fun `the datasource-wide block carries window and sampling only, served as stored`() {
        val window =
            SemanticsFixtures.fact(
                kind = LearnedFactKind.WINDOW,
                refs = listOf(FactRef(null, "orders", null)),
                text = "2024-01-01 → 2025-12-31",
            )
        val unit = SemanticsFixtures.fact()
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME) } returns listOf(window, unit)

        enricher.forDatasource(SemanticsFixtures.ACME, SemanticsFixtures.warehouse).map { it["kind"] } shouldContainExactly listOf("window")
        verify(exactly = 0) { repository.markTrust(any(), any()) }
    }

    @Test
    fun `the pipeline link crosses workspaces only as far as the reader may see - D-S9`() {
        val linked = SemanticsFixtures.fact(sourcePipelineId = SemanticsFixtures.PIPELINE, fingerprint = liveFingerprint)
        every { repository.findVisibleByDatasource("warehouse", any()) } returns listOf(linked)
        every { pipelines.findById(SemanticsFixtures.ACME, SemanticsFixtures.PIPELINE) } returns
            co.datapipelines.pipeline.PipelineRecord(
                id = SemanticsFixtures.PIPELINE,
                name = "finance/revenue",
                displayName = "Revenue",
                description = "",
                ownerId = SemanticsFixtures.USER,
                currentVersion = 1,
                createdAt = java.time.Instant.EPOCH,
                updatedAt = java.time.Instant.EPOCH,
            )
        every { pipelines.findById(SemanticsFixtures.GLOBEX, SemanticsFixtures.PIPELINE) } returns null

        val acme =
            enricher
                .forColumns(
                    SemanticsFixtures.ACME,
                    SemanticsFixtures.warehouse,
                    "orders",
                    null,
                    listOf(amount, placedAt),
                ).getValue("amount")
                .single()
        val globex =
            enricher
                .forColumns(
                    SemanticsFixtures.GLOBEX,
                    SemanticsFixtures.warehouse,
                    "orders",
                    null,
                    listOf(amount, placedAt),
                ).getValue("amount")
                .single()

        assertAll(
            { acme["source_pipeline"] shouldBe mapOf("id" to SemanticsFixtures.PIPELINE.toString(), "name" to "finance/revenue") },
            { acme["from_this_workspace"] shouldBe true },
            { globex.containsKey("source_pipeline") shouldBe false },
            { globex["from_this_workspace"] shouldBe false },
            { globex["trust"] shouldBe "observed" },
        )
    }

    private fun column(
        name: String,
        type: LogicalType,
    ) = ColumnInfo(ColumnSchema(name, type), type.name, emptyList())
}
