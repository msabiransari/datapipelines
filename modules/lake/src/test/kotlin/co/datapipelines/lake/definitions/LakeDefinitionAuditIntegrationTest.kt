package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Discard and purge-draft are audited inside their own write's transaction (dp-lake record §12, erratum 2026-11-12,
 * #814): ONE row with the acting user, the surface and the details enums.md §15 names; no row for a refusal; and an
 * audit failure rolls the write back. Released versions land at the repository level
 * ([LakeDefinitionsHarness.landWithoutApply]) unless a case needs an applied version, so no catalog apply runs between
 * the steps under test.
 */
class LakeDefinitionAuditIntegrationTest {
    private val h = LakeDefinitionsHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `discard writes one discarded row in its transaction - the pointer it left included`() {
        val id = twoReleased()
        h.service.discard(id, 2, h.actor, WriteSurface.API_KEY)
        val rows = h.audit.events.filter { it.event == LakeAuditEvents.DISCARDED }
        rows shouldHaveSize 1
        val row = rows.single()
        row.userId shouldBe h.actor
        row.inTransaction shouldBe true
        row.details shouldBe
            mapOf(
                "table_id" to id.toString(),
                "namespace" to "sales",
                "table" to "orders",
                "version" to 2,
                "via" to "api_key",
                "current_version" to 1,
            )
    }

    @Test
    fun `a discard that leaves no released version audits a null current version`() {
        val id = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION).tableId
        h.landWithoutApply(id, 1)
        h.service.discard(id, 1, h.actor, WriteSurface.MCP)
        val row = h.audit.events.single { it.event == LakeAuditEvents.DISCARDED }
        (row.details["current_version"] to row.details["via"]) shouldBe (null to "mcp")
        row.details.containsKey("current_version") shouldBe true
        h.table(id).currentDefinitionVersion shouldBe null
    }

    @Test
    fun `a refused discard writes no row`() {
        val applied = h.service.createDraft("sales", "applied", ORDERS_V1, h.actor, WriteSurface.SESSION).tableId
        h.service.release(applied, 1, h.actor, WriteSurface.SESSION)
        val id = twoReleased()
        h.service.discard(id, 2, h.actor, WriteSurface.SESSION)
        h.audit.events.clear()
        val refusals =
            listOf(
                PipelineErrorCodes.Lake.DEFINITION_APPLIED_VERSION to { h.service.discard(applied, 1, h.actor, WriteSurface.SESSION) },
                PipelineErrorCodes.Lake.DEFINITION_NOT_RELEASED to { h.service.discard(id, 2, h.actor, WriteSurface.SESSION) },
                PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND to { h.service.discard(id, 9, h.actor, WriteSurface.SESSION) },
                PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND to { h.service.discard(UUID.randomUUID(), 1, h.actor, WriteSurface.SESSION) },
            )
        refusals.forEach { (code, call) -> shouldThrow<DatapipelinesException> { call() }.code shouldBe code }
        h.audit.events.shouldBeEmpty()
    }

    @Test
    fun `purge-draft of a never-released table writes one row - the table went with it`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.purgeDraft(draft.tableId, h.actor, WriteSurface.SESSION, draft.bodyHash)
        val rows = h.audit.events.filter { it.event == LakeAuditEvents.DRAFT_PURGED }
        rows shouldHaveSize 1
        val row = rows.single()
        row.userId shouldBe h.actor
        row.inTransaction shouldBe true
        row.details shouldBe
            mapOf(
                "table_id" to draft.tableId.toString(),
                "namespace" to "sales",
                "table" to "orders",
                "version" to 1,
                "via" to "session",
                "table_deleted" to true,
            )
    }

    @Test
    fun `purge-draft of a released table audits the draft's version and keeps the table`() {
        val id = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION).tableId
        h.landWithoutApply(id, 1)
        val v1Hash = requireNotNull(h.definitions.findVersion(id, 1)).bodyHash
        h.service.writeDraft(id, ORDERS_V2, v1Hash, h.actor, WriteSurface.SESSION).version shouldBe 2
        h.service.purgeDraft(id, h.actor, WriteSurface.MCP)
        val row = h.audit.events.single { it.event == LakeAuditEvents.DRAFT_PURGED }
        (row.details["version"] to row.details["table_deleted"]) shouldBe (2 to false)
        row.details["via"] shouldBe "mcp"
        h.table(id).currentDefinitionVersion shouldBe 1
    }

    @Test
    fun `a refused purge-draft writes no row`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val released = h.service.createDraft("sales", "released", ORDERS_V1, h.actor, WriteSurface.SESSION).tableId
        h.landWithoutApply(released, 1)
        val refusals =
            listOf(
                PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT to {
                    h.service.purgeDraft(draft.tableId, h.actor, WriteSurface.SESSION, "0".repeat(64))
                },
                PipelineErrorCodes.Lake.DEFINITION_NOT_DRAFT to { h.service.purgeDraft(released, h.actor, WriteSurface.SESSION) },
                PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND to { h.service.purgeDraft(UUID.randomUUID(), h.actor, WriteSurface.SESSION) },
            )
        refusals.forEach { (code, call) -> shouldThrow<DatapipelinesException> { call() }.code shouldBe code }
        h.audit.events.shouldBeEmpty()
        h.definitions.findDraft(draft.tableId)?.bodyHash shouldBe draft.bodyHash
    }

    @Test
    fun `an audit failure rolls the discard back - still RELEASED, no discarder, the pointer unchanged`() {
        val id = twoReleased()
        val before = h.table(id)
        h.audit.failOn = LakeAuditEvents.DISCARDED
        shouldThrow<AuditSinkFailure> { h.service.discard(id, 2, h.actor, WriteSurface.SESSION) }
        h.jdbc.queryForMap(
            "SELECT status, discarded_by, discarded_at FROM lake_table_definition_versions WHERE table_id = ? AND version = 2",
            id,
        ) shouldBe mapOf("status" to "RELEASED", "discarded_by" to null, "discarded_at" to null)
        h.table(id) shouldBe before
        before.currentDefinitionVersion shouldBe 2
        h.audit.events.filter { it.event == LakeAuditEvents.DISCARDED }.shouldBeEmpty()
    }

    @Test
    fun `an audit failure rolls the purge back - the draft, the table and its namespace all stay`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.audit.failOn = LakeAuditEvents.DRAFT_PURGED
        shouldThrow<AuditSinkFailure> { h.service.purgeDraft(draft.tableId, h.actor, WriteSurface.SESSION, draft.bodyHash) }
        h.definitions.findDraft(draft.tableId)?.bodyHash shouldBe draft.bodyHash
        h.definitions.findTable(draft.tableId)?.namespace shouldBe "sales"
        h.jdbc.queryForObject("SELECT count(*) FROM lake_namespaces WHERE name = 'sales'", Int::class.java) shouldBe 1
        h.audit.events.filter { it.event == LakeAuditEvents.DRAFT_PURGED }.shouldBeEmpty()
    }

    /** `sales.orders` with v1 and v2 RELEASED at the repository level (current = 2, `pending`, nothing applied). */
    private fun twoReleased(): UUID {
        val v1 = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.landWithoutApply(v1.tableId, 1)
        val v2 = h.service.writeDraft(v1.tableId, ORDERS_V2, v1.bodyHash, h.actor, WriteSurface.SESSION)
        h.landWithoutApply(v1.tableId, v2.version)
        h.table(v1.tableId).let { (it.currentDefinitionVersion to it.applyState) shouldBe (2 to LakeApplyState.PENDING) }
        return v1.tableId
    }
}
