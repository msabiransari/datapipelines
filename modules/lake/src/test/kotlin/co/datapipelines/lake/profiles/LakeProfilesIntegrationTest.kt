package co.datapipelines.lake.profiles

import co.datapipelines.lake.definitions.AuditSinkFailure
import co.datapipelines.lake.definitions.LakeApplyOutcome
import co.datapipelines.lake.definitions.LakeDefinitionFixtures
import co.datapipelines.lake.profiles.LakeProfileFixtures.document
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The profile service over the real Postgres (brief 956): P4 (a new table takes the default, also after the mark
 * moves), P6 (a change takes effect at the next read and rewrites nothing), P7 (a new definition version keeps the
 * table's profile) and P9 (each change audited once, in its transaction; a failed change leaves no row; a service
 * write sets `updated_by` and `updated_at`).
 */
class LakeProfilesIntegrationTest {
    private val h = LakeProfilesHarness()

    @BeforeEach
    fun reset() = h.reset()

    @Test
    fun `P4 - a new table takes the default profile and an empty policy, and after the mark moves the new default`() {
        val first = h.table("orders")
        h.profiles.profileNameOf(first) shouldBe "production"
        h.policyJson(first) shouldBe "{}"
        h.profiles.makeDefault("development", h.actor)
        val second = h.table("returns")
        h.profiles.profileNameOf(second) shouldBe "development"
        h.profiles.profileNameOf(first) shouldBe "production"
        h.profiles.list().filter { it.isDefault }.map { it.name } shouldBe listOf("development")
    }

    @Test
    fun `P6 - an edit and a reassignment take effect at the next read and rewrite nothing`() {
        val table = h.table("orders")
        h.definitions.service
            .release(table, 1, h.actor, WriteSurface.SESSION)
            .apply
            .shouldBeInstanceOf<LakeApplyOutcome.Applied>()
        val metadata = icebergMetadata()
        val revisions = h.definitions.revisions("sales", "orders")
        val before = requireNotNull(h.profiles.find("production"))
        val assignedBefore = h.assignedAt(table)

        h.profiles.edit("production", document("maintenance.target_file_bytes" to "${32 * MIB}"), h.actor)
        h.profiles.forTable(table).targetFileBytes shouldBe 32 * MIB
        val after = requireNotNull(h.profiles.find("production"))
        after.updatedAt.isAfter(before.updatedAt) shouldBe true
        after.updatedBy shouldBe h.actor

        h.profiles.create("bulk", document("write.row_group_bytes" to "${4 * MIB}"), h.actor)
        h.profiles.assign(table, "bulk", h.actor)
        h.profiles.forTable(table).rowGroupBytes shouldBe 4 * MIB
        h.assignedAt(table).isAfter(assignedBefore) shouldBe true

        icebergMetadata() shouldBe metadata
        h.definitions.revisions("sales", "orders") shouldBe revisions
    }

    @Test
    fun `P7 - a new released definition version leaves the table's profile`() {
        val draft = h.definitions.service.createDraft("sales", "orders", LakeDefinitionFixtures.ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.definitions.service
            .release(draft.tableId, 1, h.actor, WriteSurface.SESSION)
            .apply
            .shouldBeInstanceOf<LakeApplyOutcome.Applied>()
        h.profiles.assign(draft.tableId, "development", h.actor)
        val profile = h.profileIdOf(draft.tableId)
        val v2 =
            h.definitions.service
                .writeDraft(draft.tableId, LakeDefinitionFixtures.ORDERS_V2, draft.bodyHash, h.actor, WriteSurface.SESSION)
        v2.version shouldBe 2
        h.definitions.service
            .release(draft.tableId, 2, h.actor, WriteSurface.SESSION)
            .apply
            .shouldBeInstanceOf<LakeApplyOutcome.Applied>()
        h.profileIdOf(draft.tableId) shouldBe profile
        h.profiles.profileNameOf(draft.tableId) shouldBe "development"
    }

    @Test
    fun `P9 - each change writes its event once inside its transaction and a no-op writes none`() {
        val table = h.table("orders")
        h.audit.events.clear()
        val created = h.profiles.create("bulk", LakeProfileFixtures.BUILT_IN, h.actor)
        (created.createdBy to created.updatedBy) shouldBe (h.actor to h.actor)
        h.profiles.edit("bulk", document("maintenance.cleanup_interval_hours" to "12"), h.actor)
        h.profiles.edit("bulk", document("maintenance.cleanup_interval_hours" to "12"), h.actor)
        h.profiles.assign(table, "bulk", h.actor)
        h.profiles.assign(table, "bulk", h.actor)
        h.profiles.makeDefault("bulk", h.actor)
        h.profiles.makeDefault("bulk", h.actor)
        val events = h.profileEvents()
        events.map { it.event } shouldBe
            listOf(
                LakeProfileAuditEvents.CREATED,
                LakeProfileAuditEvents.UPDATED,
                LakeProfileAuditEvents.ASSIGNED,
                LakeProfileAuditEvents.UPDATED,
            )
        events.all { it.inTransaction && it.userId == h.actor } shouldBe true
        events[1].details["changed"] shouldBe listOf("maintenance.cleanup_interval_hours")
        events[2].details["previous_profile"] shouldBe "production"
        events[3].details["previous_default"] shouldBe "production"
    }

    @Test
    fun `P9 - a refused change and a change whose audit row fails leave no row and no change`() {
        val table = h.table("orders")
        h.audit.events.clear()
        shouldThrow<DatapipelinesException> { h.profiles.edit("production", document("write.row_group_bytes" to "1"), h.actor) }
            .code shouldBe PipelineErrorCodes.Lake.PROFILE_INVALID
        shouldThrow<DatapipelinesException> { h.profiles.create("Bad Name", LakeProfileFixtures.BUILT_IN, h.actor) }
            .details["path"] shouldBe "name"
        shouldThrow<DatapipelinesException> { h.profiles.create("production", LakeProfileFixtures.BUILT_IN, h.actor) }
            .details["reason"] shouldBe "taken"
        shouldThrow<DatapipelinesException> { h.profiles.assign(table, "missing", h.actor) }
            .code shouldBe PipelineErrorCodes.Lake.PROFILE_NOT_FOUND
        h.profileEvents() shouldBe emptyList()

        h.audit.failOn = LakeProfileAuditEvents.UPDATED
        shouldThrow<AuditSinkFailure> { h.profiles.edit("production", document("maintenance.cleanup_interval_hours" to "12"), h.actor) }
        shouldThrow<AuditSinkFailure> { h.profiles.makeDefault("development", h.actor) }
        h.audit.failOn = LakeProfileAuditEvents.CREATED
        shouldThrow<AuditSinkFailure> { h.profiles.create("bulk", LakeProfileFixtures.BUILT_IN, h.actor) }
        h.audit.failOn = LakeProfileAuditEvents.ASSIGNED
        shouldThrow<AuditSinkFailure> { h.profiles.assign(table, "development", h.actor) }
        h.audit.failOn = null

        val production = requireNotNull(h.profiles.find("production"))
        production.values shouldBe LakeProfileFixtures.builtIn()
        (production.isDefault to production.seedPending) shouldBe (true to true)
        production.updatedBy shouldBe null
        h.profiles.find("bulk") shouldBe null
        h.profiles.profileNameOf(table) shouldBe "production"
        h.profileEvents() shouldBe emptyList()
    }

    @Test
    fun `forTable of an unknown table is the definition's not_found`() {
        shouldThrow<DatapipelinesException> { h.profiles.forTable(java.util.UUID.randomUUID()) }
            .code shouldBe PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND
    }

    private fun icebergMetadata(): List<String> =
        h.jdbc.queryForList("SELECT metadata_location FROM iceberg_tables ORDER BY table_name", String::class.java)

    private companion object {
        const val MIB = 1024L * 1024
    }
}
