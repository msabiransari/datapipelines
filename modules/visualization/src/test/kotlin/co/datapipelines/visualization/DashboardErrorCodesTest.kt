package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [DashboardErrorCodes] and `PipelineErrorCodes.Dashboard` are ONE named set (the `VisualizationErrorCodesTest` twin). */
class DashboardErrorCodesTest {
    @Test
    fun `DashboardErrorCodes and PipelineErrorCodes-Dashboard are the same named set`() {
        CatalogSections.namedConstantsOf(PipelineErrorCodes.Dashboard::class.java) shouldBe
            CatalogSections.namedConstantsOf(DashboardErrorCodes::class.java)
        DashboardErrorCodes.ALL.size shouldBe EXPECTED_DASHBOARD_CODES
    }

    private companion object {
        /** §13.23's rows — the spec's 24, the ten L1a added (pipeline-contract v1.44) and L2's `too_many_invocations` (v1.45). */
        const val EXPECTED_DASHBOARD_CODES = 35
    }
}
