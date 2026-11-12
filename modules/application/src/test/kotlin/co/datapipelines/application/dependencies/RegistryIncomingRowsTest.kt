package co.datapipelines.application.dependencies

import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.pipeline.inverse.InverseScope
import co.datapipelines.templates.TemplateUsageService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #877 §C.3 — the adapters fail CLOSED: a target family that does not answer an arm (`Unsupported`) or is not
 * registered at all is an [IllegalStateException] the verb rolls back on, never an empty list a guard would read as
 * "nothing pins this". Both shapes, through the production [RegistryIncomingRows] and through a guard port above it.
 */
class RegistryIncomingRowsTest {
    private val workspaceId = UUID.randomUUID()

    /** The pipeline family registered with NO inverse arms: its provider answers every incoming query `Unsupported`. */
    private val armless =
        RegistryIncomingRows(FamilyRegistry(listOf(PipelineReleaseFamily.descriptor(mockk(), StatementCeiling.NONE))))

    @Test
    fun `an Unsupported arm is an IllegalStateException - never an empty answer`() {
        shouldThrow<IllegalStateException> {
            armless.rows(workspaceId, InverseArm.PIPELINE_FROM_DASHBOARD, "acme/p", 1, InverseScope.LIVE)
        }.message shouldContain "does not answer incoming references"
    }

    @Test
    fun `a guard port over an Unsupported arm refuses to answer - the discard it guards cannot read 'no pins'`() {
        shouldThrow<IllegalStateException> { DashboardPipelineConsumers(armless).liveVersionPins(workspaceId, "acme/p", 1) }
        shouldThrow<IllegalStateException> { EndpointVersionPipelineConsumers(armless).anyVersionPins(workspaceId, "acme/p") }
    }

    @Test
    fun `an unregistered target family is an IllegalStateException at the first question`() {
        val usage = TemplateUsage(RegistryIncomingRows(FamilyRegistry(emptyList())), mockk<TemplateUsageService>(relaxed = true))

        shouldThrow<IllegalStateException> { usage.everPins(workspaceId, "acme/t.sql") }.message shouldContain "family is registered"
    }
}
