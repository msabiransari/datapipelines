package co.datapipelines.web.config

import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.LeaseKind
import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real periodic coroutines, independent of executor/scheduler thread saturation; metadata failures cannot keep authority alive. */
@ExtendWith(OutputCaptureExtension::class)
class LakeAppendHeartbeatTest {
    @Test
    @Timeout(45)
    fun `periodic false and failed renewals both stop authority without exposing database messages`(output: CapturedOutput) {
        val scope = WebSurfaceConfiguration.ExecutionCoroutineScope()
        val heartbeat = LakeAppendConfiguration().lakeAppendHeartbeat(scope)
        val attempts = CountDownLatch(2)
        val falseCalls = AtomicInteger()
        val failedCalls = AtomicInteger()
        val failedOperation = UUID.randomUUID()
        val deadline = Instant.now().plusSeconds(120)
        val falseWatch =
            heartbeat.start(lease(UUID.randomUUID()), deadline) {
                if (falseCalls.incrementAndGet() == 1) {
                    true
                } else {
                    attempts.countDown()
                    false
                }
            }
        val failedWatch =
            heartbeat.start(lease(failedOperation), deadline) {
                if (failedCalls.incrementAndGet() == 1) {
                    true
                } else {
                    attempts.countDown()
                    throw SQLException("heartbeat-secret-799", "55P03")
                }
            }
        try {
            falseWatch.checkActive()
            failedWatch.checkActive()
            attempts.await(40, TimeUnit.SECONDS) shouldBe true
            runBlocking {
                withTimeout(5_000) {
                    scope.coroutineContext.job.children
                        .toList()
                        .joinAll()
                }
            }
            shouldThrow<DatapipelinesException> { falseWatch.checkActive() }.code shouldBe Lake.APPEND_AUTHORITY_LOST
            shouldThrow<DatapipelinesException> { failedWatch.checkActive() }.code shouldBe Lake.APPEND_AUTHORITY_LOST
            falseCalls.get() shouldBe 2
            failedCalls.get() shouldBe 2
            output.toString().contains("heartbeat-secret-799") shouldBe false
            output.toString().contains(
                "event=lake.append.heartbeat_failed operation_id=$failedOperation cause=SQLException sql_state=55P03",
            ) shouldBe
                true
        } finally {
            falseWatch.close()
            failedWatch.close()
            scope.close()
        }
    }

    private fun lease(operation: UUID) = LeaseHandle(LeaseKind.TABLE, "namespace.table", operation, UUID.randomUUID())
}
