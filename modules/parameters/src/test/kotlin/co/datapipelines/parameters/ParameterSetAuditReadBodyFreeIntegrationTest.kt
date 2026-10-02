package co.datapipelines.parameters

import co.datapipelines.parameters.ParametersTestDb.AUTHOR
import co.datapipelines.parameters.ParametersTestDb.WORKSPACE
import co.datapipelines.pipeline.DashboardPin
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The 332 gate's finding, guarded (#372's B2): the lifecycle audit rows' pre-read and the draft purge's
 * own guard, on a stored row the CURRENT model cannot read — a parameter definition without `label`
 * ([ParameterSetModel] requires it), seeded straight into the tables. The audit read is BODY-FREE
 * (record + version detail alone), so the purge still answers the verb's own guard — `parameter.in_use`
 * when a live dashboard pins the draft, the purge itself when free — never the exception mapper's
 * 400 "Request body could not be read" that the pre-fix `findWorking` body read produced.
 */
class ParameterSetAuditReadBodyFreeIntegrationTest {
    private lateinit var h: ParametersHarness
    private val name = "acme/sales/unreadable"

    @BeforeEach
    fun reset() {
        ParametersTestDb.reset()
        h = ParametersHarness()
    }

    /** A set whose sole DRAFT's body_json the model refuses: the definition carries no `label`. */
    private fun seedUnreadableDraft(): UUID {
        val id = UUID.randomUUID()
        val body =
            ParameterSetFixtures
                .setJson(ParameterSetFixtures.countryJson().replace("\"label\": \"Country\",", ""), name = name)
                // V39's CHECK forbids a `name` key in the stored body (it is the set column's); the model's
                // trap is the definition's MISSING `label`, which only the binding refuses.
                .replace("\"name\": \"$name\", ", "")
        h.jdbc.update(
            """
            INSERT INTO parameter_sets (id, workspace_id, name, display_name, description, current_version, created_by)
            VALUES (:id, :workspaceId, :name, 'Unreadable', '', NULL, :author)
            """.trimIndent(),
            mapOf("id" to id, "workspaceId" to WORKSPACE, "name" to name, "author" to AUTHOR),
        )
        h.jdbc.update(
            """
            INSERT INTO parameter_set_versions
                (parameter_set_id, version, body_json, status, body_hash, created_by, updated_by, updated_at, created_via, updated_via)
            SELECT :id, 1, CAST(:body AS jsonb), 'DRAFT', 'seed-hash-unreadable', :author, :author, NOW(), 'mcp', 'mcp'
            """.trimIndent(),
            mapOf("id" to id, "body" to body, "author" to AUTHOR),
        )
        return id
    }

    @Test
    fun `the audit pre-read is body-free and the draft purge answers its own guard - never the 400 (#372 B2)`() {
        val id = seedUnreadableDraft()

        // The trap this guard closes: the pre-fix pre-read (findWorking) bound the BODY and exploded —
        // at the controller that surfaced as the mapper's 400, before the verb's guard ever ran.
        shouldThrow<com.fasterxml.jackson.databind.exc.MismatchedInputException> {
            h.service.findWorking(WORKSPACE, ReadLens.Everything, id)
        }

        // The BODY-FREE audit reads answer the identity from record + version detail alone.
        h.service.auditIdentity(WORKSPACE, ReadLens.Everything, id) shouldBe (name to 1)
        h.service.auditVersionIdentity(WORKSPACE, ReadLens.Everything, id, 1) shouldBe (name to 1)

        // Pinned: the verb's own guard answers 409 parameter.in_use — not a 400.
        h.pinnedByDashboards += DashboardPin("acme/boards/revenue", 4, PipelineVersionStatus.RELEASED)
        val refused = shouldThrow<DatapipelinesException> { h.service.purgeDraft(WORKSPACE, id, "seed-hash-unreadable") }
        refused.code shouldBe ParameterErrorCodes.IN_USE

        // Free: the purge runs — the sole draft takes the set (scope = entity), still without a body read.
        h.pinnedByDashboards.clear()
        h.service.purgeDraft(WORKSPACE, id, "seed-hash-unreadable") shouldBe Purged.Entity
    }
}
