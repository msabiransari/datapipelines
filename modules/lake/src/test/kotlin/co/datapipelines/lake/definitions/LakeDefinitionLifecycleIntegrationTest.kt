package co.datapipelines.lake.definitions

import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V1
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V2
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.ORDERS_V3
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException

/**
 * The definition lifecycle (lake.md §2) against V54 on a real Postgres: the one-draft index at the database, the
 * `If-Match` hash, release / discard / purge-draft refusals, and the receiver posture. A release applies in the same
 * request, so these cases run the catalog and MinIO too.
 */
class LakeDefinitionLifecycleIntegrationTest {
    private val h = LakeDefinitionsHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `create opens draft v1 and the namespace and table rows - no apply state before a release`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.MCP)
        draft.version shouldBe 1
        draft.status shouldBe PipelineVersionStatus.DRAFT
        draft.bodyJson shouldBe LakeDefinitionBodies.toJson(LakeDefinitionBodies.parse(ORDERS_V1, null)).let(::jsonbText)
        val table = h.table(draft.tableId)
        (table.status to table.applyState) shouldBe (LakeTableStatus.ACTIVE to null)
        table.currentDefinitionVersion shouldBe null
        h.jdbc.queryForObject("SELECT created_via FROM lake_table_definition_versions", String::class.java) shouldBe "mcp"
        h.jdbc.queryForObject("SELECT policy_json->'retention'->>'protection_days' FROM lake_tables_managed", String::class.java) shouldBe
            "7"
        h.service.findTable("sales", "orders")?.id shouldBe draft.tableId
    }

    @Test
    fun `the one-draft index refuses a second DRAFT at the database`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val refusal =
            shouldThrow<DuplicateKeyException> {
                h.jdbc.update(
                    "INSERT INTO lake_table_definition_versions (table_id, version, body_json, body_hash, created_by) " +
                        "VALUES (?, 2, '{\"fields\":[]}'::jsonb, 'x', ?)",
                    draft.tableId,
                    h.actor,
                )
            }
        refusal.message shouldContain "uq_lake_table_definition_versions_one_draft"
        h.definitions.insertDraft(draft.tableId, 2, ORDERS_V1, h.actor, WriteSurface.SESSION) shouldBe null
    }

    @Test
    fun `a stale hash is refused and the draft is unchanged - the current hash writes`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val stale =
            shouldThrow<DatapipelinesException> {
                h.service.writeDraft(
                    draft.tableId,
                    ORDERS_V2,
                    "0".repeat(64),
                    h.actor,
                    WriteSurface.API_KEY,
                )
            }
        stale.code shouldBe PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT
        h.definitions.findDraft(draft.tableId)?.bodyHash shouldBe draft.bodyHash
        val written = h.service.writeDraft(draft.tableId, ORDERS_V2, draft.bodyHash, h.actor, WriteSurface.API_KEY)
        (written.version to (written.bodyHash == draft.bodyHash)) shouldBe (1 to false)
        h.jdbc.queryForObject("SELECT updated_via FROM lake_table_definition_versions", String::class.java) shouldBe "api_key"
    }

    @Test
    fun `an invalid body never reaches a row`() {
        val refusal =
            shouldThrow<DatapipelinesException> {
                h.service.createDraft(
                    "sales",
                    "orders",
                    "{\"fields\":[]}",
                    h.actor,
                    WriteSurface.SESSION,
                )
            }
        refusal.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
        val name =
            shouldThrow<DatapipelinesException> { h.service.createDraft("Sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION) }
        (name.code to name.details["path"]) shouldBe (PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY to "namespace")
        h.jdbc.queryForObject("SELECT count(*) FROM lake_table_definition_versions", Int::class.java) shouldBe 0
        h.jdbc.queryForObject("SELECT count(*) FROM lake_tables_managed", Int::class.java) shouldBe 0
    }

    @Test
    fun `a second create of the same table is name_taken`() {
        h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val refusal =
            shouldThrow<DatapipelinesException> { h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION) }
        refusal.code shouldBe PipelineErrorCodes.Lake.DEFINITION_NAME_TAKEN
    }

    @Test
    fun `release of a non-draft is refused - and of an unknown version`() {
        val draft = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service
            .release(draft.tableId, 1, h.actor, WriteSurface.SESSION)
            .apply
            .shouldBeInstanceOf<LakeApplyOutcome.Applied>()
        val again = shouldThrow<DatapipelinesException> { h.service.release(draft.tableId, 1, h.actor, WriteSurface.SESSION) }
        (again.code to again.details["status"]) shouldBe (PipelineErrorCodes.Lake.DEFINITION_NOT_DRAFT to "RELEASED")
        val unknown = shouldThrow<DatapipelinesException> { h.service.release(draft.tableId, 9, h.actor, WriteSurface.SESSION) }
        unknown.code shouldBe PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND
        val stale = h.service.writeDraft(draft.tableId, ORDERS_V2, draft.bodyHash, h.actor, WriteSurface.SESSION)
        val wrongHash =
            shouldThrow<DatapipelinesException> {
                h.service.release(
                    draft.tableId,
                    stale.version,
                    h.actor,
                    WriteSurface.SESSION,
                    "f".repeat(64),
                )
            }
        wrongHash.code shouldBe PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT
        h.audit.events.count { it.event == LakeAuditEvents.RELEASED } shouldBe 1
    }

    @Test
    fun `after a release a write opens the next draft from the released hash - copy on write`() {
        val v1 = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION)
        val wrong =
            shouldThrow<DatapipelinesException> {
                h.service.writeDraft(
                    v1.tableId,
                    ORDERS_V2,
                    "0".repeat(64),
                    h.actor,
                    WriteSurface.SESSION,
                )
            }
        wrong.code shouldBe PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT
        val v2 = h.service.writeDraft(v1.tableId, ORDERS_V2, v1.bodyHash, h.actor, WriteSurface.SESSION)
        (v2.version to v2.status) shouldBe (2 to PipelineVersionStatus.DRAFT)
        val skipped = ORDERS_V2.replace("\"id\":5", "\"id\":7")
        val refusal =
            shouldThrow<DatapipelinesException> { h.service.writeDraft(v1.tableId, skipped, v2.bodyHash, h.actor, WriteSurface.SESSION) }
        (refusal.code to refusal.details["reason"]) shouldBe (PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY to "id_sequence")
    }

    @Test
    fun `discard refuses the applied version, discards another and recomputes the current pointer`() {
        val v1 = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION)
        val applied = shouldThrow<DatapipelinesException> { h.service.discard(v1.tableId, 1, h.actor, WriteSurface.SESSION) }
        applied.code shouldBe PipelineErrorCodes.Lake.DEFINITION_APPLIED_VERSION
        val v2 = h.service.writeDraft(v1.tableId, ORDERS_V2, v1.bodyHash, h.actor, WriteSurface.SESSION)
        h.landWithoutApply(v1.tableId, v2.version)
        h.table(v1.tableId).let { (it.currentDefinitionVersion to it.applyState) shouldBe (2 to LakeApplyState.PENDING) }
        h.service.discard(v1.tableId, 2, h.actor, WriteSurface.SESSION)
        h.table(v1.tableId).let { (it.currentDefinitionVersion to it.applyState) shouldBe (1 to LakeApplyState.APPLIED) }
        val notReleased = shouldThrow<DatapipelinesException> { h.service.discard(v1.tableId, 2, h.actor, WriteSurface.SESSION) }
        (notReleased.code to notReleased.details["status"]) shouldBe (PipelineErrorCodes.Lake.DEFINITION_NOT_RELEASED to "DISCARDED")
    }

    @Test
    fun `purge-draft of a never-released table removes the table and its empty namespace`() {
        val v1 = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val stale = shouldThrow<DatapipelinesException> { h.service.purgeDraft(v1.tableId, h.actor, WriteSurface.SESSION, "0".repeat(64)) }
        stale.code shouldBe PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT
        h.service.purgeDraft(v1.tableId, h.actor, WriteSurface.SESSION, v1.bodyHash)
        h.definitions.findTable(v1.tableId) shouldBe null
        h.jdbc.queryForObject("SELECT count(*) FROM lake_namespaces", Int::class.java) shouldBe 0
        val none = shouldThrow<DatapipelinesException> { h.service.purgeDraft(v1.tableId, h.actor, WriteSurface.SESSION) }
        none.code shouldBe PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND
    }

    @Test
    fun `purge-draft of a released table keeps the table - and refuses when there is no draft`() {
        val v1 = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service.release(v1.tableId, 1, h.actor, WriteSurface.SESSION)
        val noDraft = shouldThrow<DatapipelinesException> { h.service.purgeDraft(v1.tableId, h.actor, WriteSurface.SESSION) }
        noDraft.code shouldBe PipelineErrorCodes.Lake.DEFINITION_NOT_DRAFT
        h.service.writeDraft(v1.tableId, ORDERS_V3, v1.bodyHash, h.actor, WriteSurface.SESSION).version shouldBe 2
        h.service.purgeDraft(v1.tableId, h.actor, WriteSurface.SESSION)
        h.table(v1.tableId).currentDefinitionVersion shouldBe 1
    }

    @Test
    fun `a receiver refuses every authoring write and changes nothing`() {
        val authoring = h.service.createDraft("sales", "orders", ORDERS_V1, h.actor, WriteSurface.SESSION)
        val receiver = LakeDefinitionsHarness(authoringEnabled = false).service
        val id = authoring.tableId
        val writes: List<() -> Unit> =
            listOf(
                { receiver.createDraft("sales", "other", ORDERS_V1, h.actor, WriteSurface.SESSION) },
                { receiver.writeDraft(id, ORDERS_V2, authoring.bodyHash, h.actor, WriteSurface.SESSION) },
                { receiver.release(id, 1, h.actor, WriteSurface.SESSION) },
                { receiver.discard(id, 1, h.actor, WriteSurface.SESSION) },
                { receiver.purgeDraft(id, h.actor, WriteSurface.SESSION) },
            )
        writes.forEach { write ->
            val refusal = shouldThrow<DatapipelinesException> { write() }
            (refusal.code to refusal.details["capability"]) shouldBe
                (PipelineErrorCodes.Lake.DEFINITION_AUTHORING_DISABLED to "lake-definitions-authoring")
        }
        h.definitions.findDraft(id)?.bodyHash shouldBe authoring.bodyHash
        h.jdbc.queryForObject("SELECT count(*) FROM lake_tables_managed", Int::class.java) shouldBe 1
    }

    private fun jsonbText(canonical: String): String =
        requireNotNull(h.jdbc.queryForObject("SELECT CAST(? AS jsonb)::text", String::class.java, canonical))
}
