package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.body
import co.datapipelines.lake.definitions.LakeDefinitionFixtures.field
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The sort columns' lock through the lifecycle (lake.md §1 and §2; dp-lake design record §11a call 36 item 3 and the
 * owner's ruling of 2026-11-13 on it, row M10): I4 a release re-checks the sort rule, I5 the first release EVER fixes the
 * sort columns, also after it is discarded, for a keyed and for a legacy (keyless) first release.
 */
class LakeSortColumnsLifecycleIntegrationTest {
    private val h = LakeDefinitionsHarness()

    @BeforeEach
    fun clean() {
        h.reset()
    }

    @Test
    fun `I4 - a keyless draft of a never-released table is refused by release - nothing released, no audit row`() {
        val id = h.insertUnvalidatedDraft(NS, T, body(FIELDS, sort = null))
        val refusal = shouldThrow<DatapipelinesException> { h.service.release(id, 1, h.actor, WriteSurface.SESSION) }
        println("(I4) ${refusal.code} ${refusal.details}")
        refusal.invalid() shouldBe Triple(PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY, "sort_field_ids", "missing")
        requireNotNull(h.definitions.findVersion(id, 1)).status shouldBe PipelineVersionStatus.DRAFT
        h.table(id).currentDefinitionVersion shouldBe null
        h.audit.events.none { it.event == LakeAuditEvents.RELEASED } shouldBe true
    }

    @Test
    fun `I5 - the first release fixes the sort columns, and its discard frees nothing`() {
        val v1 = h.service.createDraft(NS, T, body(FIELDS, sort = listOf(2)), h.actor, WriteSurface.SESSION)
        val id = v1.tableId
        val resaved = h.service.writeDraft(id, body(FIELDS, sort = listOf(2, 1)), v1.bodyHash, h.actor, WriteSurface.SESSION)
        val draft = h.service.writeDraft(id, body(FIELDS, sort = listOf(1)), resaved.bodyHash, h.actor, WriteSurface.SESSION)
        releaseUnapplied(id, 1)

        val released = requireNotNull(h.definitions.findVersion(id, 1))
        locked { h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = listOf(2)), released.bodyHash, h.actor, VIA) }
        val v2 = h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = listOf(1)), released.bodyHash, h.actor, VIA)
        h.service.discard(id, 1, h.actor, WriteSurface.SESSION)
        println("(I5) v1 ${draft.bodyJson} discarded; table ${h.table(id)}")
        h.table(id).currentDefinitionVersion shouldBe null
        requireNotNull(h.definitions.findVersion(id, 1)).status shouldBe PipelineVersionStatus.DISCARDED

        locked { h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = listOf(2)), v2.bodyHash, h.actor, VIA) }
        val kept = h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = listOf(1)), v2.bodyHash, h.actor, VIA)
        kept.version shouldBe 2
        h.service.release(id, 2, h.actor, VIA).apply shouldBe LakeApplyOutcome.Applied(2, committed = true)
    }

    @Test
    fun `I5 - a legacy first release keeps the table keyless after its discard, at save and at release`() {
        val id = h.insertUnvalidatedDraft(NS, T, body(FIELDS, sort = null))
        h.landWithoutApply(id, 1)
        val released = requireNotNull(h.definitions.findVersion(id, 1))
        val v2 = h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = null), released.bodyHash, h.actor, VIA)
        h.service.discard(id, 1, h.actor, WriteSurface.SESSION)
        h.table(id).currentDefinitionVersion shouldBe null

        locked { h.service.writeDraft(id, body(FIELDS + field(3, "note"), sort = listOf(1)), v2.bodyHash, h.actor, VIA) }
        h.overwriteUnvalidatedDraft(id, body(FIELDS + field(3, "note"), sort = listOf(1)))
        val events = h.audit.events.size
        locked { h.service.release(id, 2, h.actor, VIA) }
        requireNotNull(h.definitions.findVersion(id, 2)).status shouldBe PipelineVersionStatus.DRAFT
        h.table(id).currentDefinitionVersion shouldBe null
        h.audit.events.size shouldBe events
    }

    /** Releases [version] through the service while a foreign table lease keeps its apply from running (`Busy`). */
    private fun releaseUnapplied(
        id: UUID,
        version: Int,
    ) {
        val foreign = LakeTestDb.lease(LeaseKind.TABLE, "$NS.$T")
        try {
            h.service.release(id, version, h.actor, VIA).apply.shouldBeInstanceOf<LakeApplyOutcome.Busy>()
        } finally {
            LakeTestDb.leases.release(foreign.operationId)
        }
        h.table(id).let { (it.applyState to it.appliedVersionHere) shouldBe (LakeApplyState.PENDING to null) }
    }

    private fun locked(write: () -> Any) {
        val refusal = shouldThrow<DatapipelinesException> { write() }
        println("(I5) refused: ${refusal.details}")
        refusal.invalid() shouldBe Triple(PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY, "sort_field_ids", "locked")
    }

    private fun DatapipelinesException.invalid() = Triple(code, details["path"], details["reason"])

    private companion object {
        const val NS = "sorting"
        const val T = "events"
        val VIA = WriteSurface.SESSION
        val FIELDS = listOf(field(1, "id", "BIGINTEGER", "required" to true), field(2, "customer"))
    }
}
