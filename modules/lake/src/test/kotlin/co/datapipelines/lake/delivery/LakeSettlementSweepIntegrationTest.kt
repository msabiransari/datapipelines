package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.append.AppendPublicationHarness
import co.datapipelines.lake.append.LakeAppendHeartbeat
import co.datapipelines.lake.append.LakeAppendRuntime
import co.datapipelines.pipeline.DatasourceRegistry
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual production bounded recovery graph over PostgreSQL and the real managed Iceberg table. */
class LakeSettlementSweepIntegrationTest {
    private val h = AppendPublicationHarness()

    @BeforeEach
    fun reset() {
        h.reset()
        h.jdbc.update("UPDATE lake_control SET mode = 'normal', last_sweep_at = NULL WHERE id = 1")
    }

    @Test
    fun `normal sweep finalizes visible supersession once and records its heartbeat`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            h.withRequest { request, _ -> h.runner(client).publish(request) {} shouldBe 3L }
            val runtime = runtime(client)
            h.jdbc.queryForObject(
                "SELECT count(*) FROM lake_table_revisions WHERE superseded_at IS NOT NULL AND protected_until IS NULL",
                Int::class.java,
            ) shouldBe 1
            runtime.sweep.runOnce()
            val protection =
                h.jdbc
                    .queryForObject(
                        "SELECT protected_until FROM lake_table_revisions WHERE superseded_at IS NOT NULL",
                        OffsetDateTime::class.java,
                    ).shouldNotBeNull()
            h.jdbc.queryForObject("SELECT last_sweep_at FROM lake_control WHERE id = 1", OffsetDateTime::class.java).shouldNotBeNull()
            runtime.sweep.runOnce()
            h.jdbc.queryForObject(
                "SELECT protected_until FROM lake_table_revisions WHERE superseded_at IS NOT NULL",
                OffsetDateTime::class.java,
            ) shouldBe protection
        }
    }

    @Test
    fun `restoring and replaying remain silent including protection and last_sweep_at`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            h.withRequest { request, _ -> h.runner(client).publish(request) {} }
            val runtime = runtime(client)
            for (mode in listOf("restoring", "replaying")) {
                h.jdbc.update("UPDATE lake_control SET mode = ? WHERE id = 1", mode)
                runtime.sweep.runOnce()
                h.jdbc.queryForObject("SELECT last_sweep_at FROM lake_control WHERE id = 1", OffsetDateTime::class.java) shouldBe null
                h.jdbc.queryForObject(
                    "SELECT count(*) FROM lake_table_revisions WHERE superseded_at IS NOT NULL AND protected_until IS NULL",
                    Int::class.java,
                ) shouldBe 1
            }
        }
    }

    @Test
    fun `a crashed received delivery becomes failed validation after its execution ended`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                runtime(client).sweep.runOnce()
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.FAILED
                h.jdbc.queryForObject(
                    "SELECT failure_phase FROM lake_deliveries WHERE id = ?",
                    String::class.java,
                    request.delivery.id,
                ) shouldBe "validation"
            }
        }
    }

    @Test
    fun `commit before published stamp is recovered from permanent revision evidence`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                h.runner(client).publish(request) {} shouldBe 3L
                // Persisted state at the crash boundary: catalog commit exists, stamp has not landed.
                h.jdbc.update(
                    "UPDATE lake_deliveries SET state = 'UNKNOWN', published_at = NULL, metadata_location_after = NULL WHERE id = ?",
                    request.delivery.id,
                )
                h.jdbc.update(
                    "UPDATE lake_delivery_attempts SET outcome = 'UNKNOWN', ended_at = NULL WHERE operation_id = ?",
                    request.delivery.operationId,
                )
                runtime(client).sweep.runOnce()
                h.deliveries.find(request.delivery.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                h.deliveries
                    .find(request.delivery.id)
                    ?.metadataLocationAfter
                    .shouldNotBeNull()
                h.jdbc.queryForObject(
                    "SELECT count(*) FROM lake_table_revisions WHERE operation_id = ?",
                    Int::class.java,
                    request.delivery.operationId,
                ) shouldBe 1
            }
        }
    }

    @Test
    fun `semantics rebuild only when its applied definition differs`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            h.withRequest { request, _ ->
                h.jdbc.update(
                    "UPDATE lake_column_semantics SET applied_version = 2, doc = 'stale' WHERE table_id = ?",
                    request.delivery.tableId,
                )
                val runtime = runtime(client)
                runtime.sweep.runOnce()
                h.jdbc.queryForObject(
                    "SELECT applied_version FROM lake_column_semantics WHERE table_id = ?",
                    Int::class.java,
                    request.delivery.tableId,
                ) shouldBe 1
                val version =
                    h.jdbc.queryForObject(
                        "SELECT xmin::text FROM lake_column_semantics WHERE table_id = ?",
                        String::class.java,
                        request.delivery.tableId,
                    )
                runtime.sweep.runOnce()
                h.jdbc.queryForObject(
                    "SELECT xmin::text FROM lake_column_semantics WHERE table_id = ?",
                    String::class.java,
                    request.delivery.tableId,
                ) shouldBe version
            }
        }
    }

    @Test
    fun `a locked control stamp times out and the single scheduler thread runs its next job`() {
        co.datapipelines.lake.LakeMinio.client().use { client ->
            val runtime = runtime(client)
            LakeTestDb.connect().use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.executeQuery("SELECT id FROM lake_control WHERE id = 1 FOR UPDATE").close() }
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val failure = CompletableFuture.supplyAsync({ runCatching { runtime.sweep.runOnce() }.exceptionOrNull() }, worker)
                    val nextJob = CompletableFuture.supplyAsync({ "progressed" }, worker)
                    failure.get(12, TimeUnit.SECONDS).shouldNotBeNull()
                    nextJob.get(1, TimeUnit.SECONDS) shouldBe "progressed"
                } finally {
                    lock.rollback()
                    worker.shutdownNow()
                }
            }
        }
    }

    private fun runtime(client: software.amazon.awssdk.services.s3.S3Client): LakeAppendRuntime =
        LakeAppendRuntime(
            LakeTestDb.dataSource,
            org.springframework.jdbc.datasource
                .DataSourceTransactionManager(LakeTestDb.dataSource),
            h.properties,
            h.h.catalogs,
            client,
            LakeStagingStorage { _, _, _ -> error("No ingestion during recovery") },
            LakeAppendHeartbeat { _, _, _ -> error("No append during recovery") },
            LakeProjectionInvalidation { },
            mockk<DatasourceRegistry>(),
            h.h.audit,
        )
}
