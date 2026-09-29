package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

/**
 * [ScopeConsumers] costs inputs + bindings, never inputs × bindings: one bitset per SOURCE is built once and each input
 * mapping ORs it in. The wide case below maps 40,000 inputs onto a source with 40,000 bindings — 1.6 × 10⁹ lookups on a
 * per-mapping walk of the source, ~80,000 on the per-source one — and every consumer is still found.
 */
class ScopeConsumersTest {
    @Test
    fun `a wide source mapped by a wide occurrence costs inputs plus bindings - every parameter still finds its group`() {
        val body =
            DashboardBody(
                displayName = "wide",
                sources =
                    listOf(
                        DashboardSource(
                            name = "s",
                            pipeline = ArtifactRef("p", 1),
                            parameters = (0 until WIDTH).associate { "p$it" to ParameterBinding(parameter = "p$it") },
                        ),
                    ),
                visualizations =
                    listOf(
                        VisualizationOccurrence(
                            name = "v",
                            type = DashboardObjectType.VISUALIZATION,
                            visualization = ArtifactRef("viz", 1),
                            inputs = (0 until WIDTH).associate { "i$it" to InputMapping("s") },
                        ),
                    ),
                groups =
                    listOf(
                        DashboardGroup("g", DashboardObjectType.GROUP, members = listOf("v")),
                        DashboardGroup("empty", DashboardObjectType.GROUP),
                    ),
                layout = DashboardLayout(),
            )
        val set = ParameterSetFact(PipelineVersionStatus.RELEASED, (0 until WIDTH).map { SetParameterFact("p$it", emptySet()) })
        val consumers =
            assertTimeoutPreemptively(
                Duration.ofSeconds(WIDE_SECONDS),
                ThrowingSupplier { ScopeConsumers(body, set, GroupGraph(body)) },
            )
        consumers.groupsConsuming("p0") shouldBe setOf("g")
        consumers.groupsConsuming("p${WIDTH - 1}") shouldBe setOf("g")
        consumers.groupsConsuming("not_declared") shouldBe emptySet()
    }

    private companion object {
        const val WIDTH = 40_000
        const val WIDE_SECONDS = 10L
    }
}
