package co.datapipelines.lake.append

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.delivery.LakeDeliveryState
import co.datapipelines.pipeline.LakeAppendInput
import co.datapipelines.pipeline.LakeOrderKey
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.sql.SQLException

/**
 * #968 D7 (O9's units): a failure of the WRITE scan reaches the caller as `pipeline.staging.scratch_limit_exceeded`
 * when that is its code (a sort that outgrew the scratch reservation) and as `lake.append.failed` otherwise, a staging
 * memory-limit failure and a raw [SQLException] included. The ledger row is FAILED, phase `publish`, with the typed
 * code as it is (none for a raw exception); no snapshot.
 */
class LakeWritebackRunnerScanFailureIntegrationTest {
    private val h = AppendPublicationHarness()

    @BeforeEach
    fun reset() = h.reset()

    @Test
    fun `a scratch-limit failure of the write scan reaches the caller and the ledger with its own code`() {
        val failure = publishFailing(DatapipelinesException(PipelineErrorCodes.Staging.SCRATCH_LIMIT_EXCEEDED, "spill limit"))
        failure.caller shouldBe PipelineErrorCodes.Staging.SCRATCH_LIMIT_EXCEEDED
        failure.ledger shouldBe Triple(LakeDeliveryState.FAILED.name, "publish", PipelineErrorCodes.Staging.SCRATCH_LIMIT_EXCEEDED)
    }

    @Test
    fun `another staging code is still replaced for the caller and recorded in the ledger`() {
        val failure = publishFailing(DatapipelinesException(PipelineErrorCodes.Staging.MEMORY_LIMIT_EXCEEDED, "memory limit"))
        failure.caller shouldBe PipelineErrorCodes.Lake.APPEND_FAILED
        failure.ledger shouldBe Triple(LakeDeliveryState.FAILED.name, "publish", PipelineErrorCodes.Staging.MEMORY_LIMIT_EXCEEDED)
    }

    @Test
    fun `a raw SQLException is still replaced for the caller and leaves no ledger code`() {
        val failure = publishFailing(SQLException("Out of Memory Error: unclassified"))
        failure.caller shouldBe PipelineErrorCodes.Lake.APPEND_FAILED
        failure.ledger shouldBe Triple(LakeDeliveryState.FAILED.name, "publish", null)
    }

    private data class Outcome(
        val caller: String,
        val ledger: Triple<String, String?, String?>,
    )

    /** Publishes with a write scan (the second) that throws [thrown]; the check scan is the real one. */
    private fun publishFailing(thrown: Exception): Outcome {
        var outcome: Outcome? = null
        LakeMinio.client().use { client ->
            h.withRequest { request, rows ->
                val failing =
                    object : LakeAppendInput by rows {
                        override fun scan(
                            orderBy: List<LakeOrderKey>,
                            consume: (ResultSet) -> Unit,
                        ) {
                            if (rows.scans == 1) throw thrown
                            rows.scan(orderBy, consume)
                        }
                    }
                val caller = shouldThrow<DatapipelinesException> { h.runner(client).publish(request.copy(rows = failing)) {} }.code
                rows.scans shouldBe 1
                val ledger =
                    h.jdbc
                        .query(
                            "SELECT state, failure_phase, failure_code FROM lake_deliveries WHERE id = ?",
                            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) },
                            request.delivery.id,
                        ).single()
                h.h.revisions(AppendPublicationHarness.NAMESPACE, AppendPublicationHarness.TABLE) shouldBe 1
                h.deliveries.committedEvidence(request.delivery.id) shouldBe null
                outcome = Outcome(caller, ledger)
            }
        }
        return requireNotNull(outcome)
    }
}
