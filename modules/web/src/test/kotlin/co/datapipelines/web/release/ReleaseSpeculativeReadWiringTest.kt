package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.dependencies.SavepointReadIsolation
import co.datapipelines.pipeline.release.PipelineReleaseCoordination
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The production wiring of the release walk's speculative-read fence (#868 R1, round 3): the coordinator's planning walk
 * fences every speculative read with a [SavepointReadIsolation] — a NESTED (savepoint) template on the SAME
 * `metadataTransactionManager` both release transactions run on — never the unfenced `ReadIsolation.NONE`, under which a
 * failed speculative statement aborts the transaction (`ReleaseSpeculativeReadPostgresTest`). The fields are private
 * wiring, so they are read reflectively: a renamed field fails this test loudly, never silently.
 */
class ReleaseSpeculativeReadWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
        )

    @Test
    fun `the coordinator's planning walk fences speculative reads with a savepoint on the metadata manager`() {
        context.run { context ->
            val coordinator = context.getBean(PipelineReleaseCoordination::class.java).shouldBeInstanceOf<ReleaseCoordinator>()
            val planning = coordinator.field("planning").shouldBeInstanceOf<ReleasePlanningWalk>()
            val fence = planning.field("speculativeReads").shouldBeInstanceOf<SavepointReadIsolation>()
            val savepoint = fence.field("savepoint").shouldBeInstanceOf<TransactionTemplate>()
            savepoint.propagationBehavior shouldBe TransactionDefinition.PROPAGATION_NESTED
            savepoint.transactionManager shouldBeSameInstanceAs context.getBean(PlatformTransactionManager::class.java)
        }
    }

    private fun Any.field(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
