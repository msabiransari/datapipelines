package co.datapipelines.application.semantics

import co.datapipelines.auth.AuthErrorCodes
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFactRecorder
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.datasources.semantics.LearnedFactTrust
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * [SemanticsService] — the rules ABOVE the recorder: provenance stamping, the D-S9 predicate on
 * outbound links, the §7.1 retire rule, and the audit rows the §9 acceptance counts. The
 * recorder and repository are mocked (their own suites prove them against real databases); the
 * audit sink is a REAL in-memory one, because "an event was emitted" is the contract and a
 * strict mock would pass exactly when the emission is missing.
 */
class SemanticsServiceTest {
    private val repository = mockk<LearnedFactRepository>()
    private val recorder = mockk<LearnedFactRecorder>()
    private val pipelines = mockk<PipelineRepository>()
    private val audit = RecordingAuditSink()
    private val service = SemanticsService(repository, recorder, pipelines, audit)

    @Test
    fun `record stamps who, through what door and in which workspace, then audits the fact - kind, scope, refs, never the text`() {
        val request = slot<LearnedFactRecorder.Request>()
        val stored = SemanticsFixtures.fact()
        every { recorder.record(SemanticsFixtures.warehouse, capture(request)) } returns stored

        val result = service.record(SemanticsFixtures.principal(), SemanticsFixtures.warehouse, command(), WriteSurface.MCP)

        val row = audit.rows.single()
        assertAll(
            { request.captured.recordedBy shouldBe SemanticsFixtures.USER },
            { request.captured.recordedVia shouldBe "mcp" },
            { request.captured.recordedIn shouldBe SemanticsFixtures.ACME },
            { row.event shouldBe SemanticsAuditEvents.RECORDED },
            { row.keyId shouldBe "dpk_ABCDEFGHIJKL" },
            { row.details["kind"] shouldBe "unit" },
            { row.details["scope"] shouldBe "DATASOURCE" },
            { row.details["datasource"] shouldBe "warehouse" },
            { row.details["refs"] shouldBe listOf("orders.amount") },
            { row.details["trust"] shouldBe "observed" },
            { row.details["evidence"] shouldBe true },
            // Redaction: the fact text and the SQL are for the row, not the audit trail.
            { row.details.values.none { it.toString().contains("cents") || it.toString().contains("SELECT") } shouldBe true },
            { result["id"] shouldBe stored.id.toString() },
            { result["from_this_workspace"] shouldBe true },
            { result["evidence_sql"] shouldBe "SELECT amount FROM orders LIMIT 5" },
        )
    }

    @Test
    fun `supersedes and source_pipeline_id must be visible to the caller's workspace - else the not-found answer`() {
        val hidden = UUID.randomUUID()
        every { repository.findVisible(hidden, SemanticsFixtures.ACME) } returns null
        every { pipelines.findById(SemanticsFixtures.ACME, SemanticsFixtures.PIPELINE) } returns null

        val badPredecessor =
            shouldThrow<DatapipelinesException> {
                service.record(SemanticsFixtures.principal(), SemanticsFixtures.warehouse, command(supersedes = hidden), WriteSurface.MCP)
            }
        val badPipeline =
            shouldThrow<DatapipelinesException> {
                service.record(
                    SemanticsFixtures.principal(),
                    SemanticsFixtures.warehouse,
                    command(sourcePipelineId = SemanticsFixtures.PIPELINE),
                    WriteSurface.MCP,
                )
            }
        assertAll(
            { badPredecessor.code shouldBe PipelineErrorCodes.Semantics.NOT_FOUND },
            { badPredecessor.details["field"] shouldBe "supersedes" },
            { badPipeline.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND },
            { audit.rows shouldBe emptyList() },
        )
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    @Test
    fun `a visible predecessor on another datasource is not-found too - it cannot be superseded from here`() {
        val other = SemanticsFixtures.fact().copy(datasourceName = "other-db")
        every { repository.findVisible(other.id, SemanticsFixtures.ACME) } returns other

        shouldThrow<DatapipelinesException> {
            service.record(SemanticsFixtures.principal(), SemanticsFixtures.warehouse, command(supersedes = other.id), WriteSurface.MCP)
        }.code shouldBe PipelineErrorCodes.Semantics.NOT_FOUND
    }

    @Test
    fun `list applies the scope and table filters over the visible set and renders the full row`() {
        val onOrders = SemanticsFixtures.fact()
        val onEvents =
            SemanticsFixtures.fact(
                refs = listOf(FactRef(null, "events", null)),
                scope = LearnedFactScope.WORKSPACE,
                kind = co.datapipelines.datasources.semantics.LearnedFactKind.DEFINITION,
            )
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME, includeRetired = true, since = null) } returns
            listOf(onOrders, onEvents)

        val all =
            service.list(
                SemanticsFixtures.principal(),
                SemanticsFixtures.warehouse,
                SemanticsService.ListQuery(includeRetired = true),
            )
        val orders =
            service.list(
                SemanticsFixtures.principal(),
                SemanticsFixtures.warehouse,
                SemanticsService.ListQuery(table = "orders", includeRetired = true),
            )
        val workspace =
            service.list(
                SemanticsFixtures.principal(),
                SemanticsFixtures.warehouse,
                SemanticsService.ListQuery(scope = LearnedFactScope.WORKSPACE, includeRetired = true),
            )

        assertAll(
            { all.map { it["id"] } shouldContainExactly listOf(onOrders.id.toString(), onEvents.id.toString()) },
            { orders.map { it["id"] } shouldContainExactly listOf(onOrders.id.toString()) },
            { workspace.map { it["id"] } shouldContainExactly listOf(onEvents.id.toString()) },
            { all.first().containsKey("refs") shouldBe true },
            { all.first().containsKey("source_pipeline") shouldBe false },
        )
    }

    @Test
    fun `list passes since through to the store - the what-was-recorded-since question`() {
        val since = Instant.parse("2026-09-11T00:00:00Z")
        every { repository.findVisibleByDatasource("warehouse", SemanticsFixtures.ACME, includeRetired = false, since = since) } returns
            emptyList()

        service.list(SemanticsFixtures.principal(), SemanticsFixtures.warehouse, SemanticsService.ListQuery(since = since)) shouldBe
            emptyList()
    }

    @Test
    fun `retire audits the retirement, and a DATASOURCE fact recorded from another workspace needs ws_admin`() {
        val theirs = SemanticsFixtures.fact(recordedIn = SemanticsFixtures.GLOBEX)
        every { repository.findVisible(theirs.id, SemanticsFixtures.ACME) } returns theirs andThen
            theirs.copy(trust = LearnedFactTrust.RETIRED, retiredAt = Instant.now(), retiredReason = "wrong")
        every { repository.retire(theirs.id, "wrong") } returns true

        val refused =
            shouldThrow<DatapipelinesException> { service.retire(SemanticsFixtures.principal(), theirs.id, "wrong") }
        val result = service.retire(SemanticsFixtures.principal(flags = MembershipFlags(author = true, admin = true)), theirs.id, "wrong")

        assertAll(
            { refused.code shouldBe AuthErrorCodes.ROLE_REQUIRED },
            { refused.details["required"] shouldBe "ws_admin" },
            { result["trust"] shouldBe "retired" },
            { audit.rows.map { it.event } shouldContainExactly listOf(SemanticsAuditEvents.RETIRED) },
            { audit.rows.single().details["recorded_in_this_workspace"] shouldBe false },
            { audit.rows.single().details["reason"] shouldBe "wrong" },
        )
    }

    @Test
    fun `retire of an invisible fact is not-found, and a WORKSPACE fact of one's own workspace needs only author`() {
        val gone = UUID.randomUUID()
        every { repository.findVisible(gone, SemanticsFixtures.ACME) } returns null
        val mine =
            SemanticsFixtures.fact(
                scope = LearnedFactScope.WORKSPACE,
                kind = co.datapipelines.datasources.semantics.LearnedFactKind.DEFINITION,
            )
        every { repository.findVisible(mine.id, SemanticsFixtures.ACME) } returns mine
        every { repository.retire(mine.id, "obsolete") } returns true

        shouldThrow<DatapipelinesException> { service.retire(SemanticsFixtures.principal(), gone, "x") }.code shouldBe
            PipelineErrorCodes.Semantics.NOT_FOUND
        service.retire(SemanticsFixtures.principal(), mine.id, "obsolete")["id"] shouldBe mine.id.toString()
    }

    @Test
    fun `the pipeline link renders only where the reader can read the pipeline - D-S9`() {
        val linked = SemanticsFixtures.fact(sourcePipelineId = SemanticsFixtures.PIPELINE)
        every { repository.findVisibleByDatasource("warehouse", any(), includeRetired = false, since = null) } returns listOf(linked)
        every { pipelines.findById(SemanticsFixtures.ACME, SemanticsFixtures.PIPELINE) } returns pipelineRecord()
        every { pipelines.findById(SemanticsFixtures.GLOBEX, SemanticsFixtures.PIPELINE) } returns null

        val acme = service.list(SemanticsFixtures.principal(), SemanticsFixtures.warehouse, SemanticsService.ListQuery()).single()
        val globex =
            service
                .list(
                    SemanticsFixtures.principal(workspaceId = SemanticsFixtures.GLOBEX),
                    SemanticsFixtures.warehouse,
                    SemanticsService.ListQuery(),
                ).single()

        assertAll(
            { acme["source_pipeline"] shouldBe mapOf("id" to SemanticsFixtures.PIPELINE.toString(), "name" to "finance/revenue") },
            { acme["from_this_workspace"] shouldBe true },
            { globex.containsKey("source_pipeline") shouldBe false },
            { globex["from_this_workspace"] shouldBe false },
            // The evidence and trust cross the boundary; only the link stops.
            { globex["evidence_summary"] shouldBe "amount=1250 | amount=300" },
        )
    }

    private fun command(
        supersedes: UUID? = null,
        sourcePipelineId: UUID? = null,
    ) = SemanticsService.RecordCommand(
        scope = LearnedFactScope.DATASOURCE,
        kind = "unit",
        fact = "amount is in cents",
        refs = listOf(FactRef(null, "orders", "amount")),
        evidenceSql = "SELECT amount FROM orders LIMIT 5",
        evidenceSummary = null,
        sourcePipelineId = sourcePipelineId,
        sourceVersion = null,
        supersedes = supersedes,
    )

    private fun pipelineRecord() =
        PipelineRecord(
            id = SemanticsFixtures.PIPELINE,
            name = "finance/revenue",
            displayName = "Revenue",
            description = "",
            ownerId = SemanticsFixtures.USER,
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
