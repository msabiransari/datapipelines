package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Real metadata and catalog operations, with the overlap forced at an existing row lock or commit hook. */
@Timeout(90)
class LakeLifecycleApplyRaceIntegrationTest {
    private var hook: (UUID, UUID) -> Unit = { _, _ -> }
    private val h = LakeDefinitionsHarness(hooks = LakeApplyHooks { table, op -> hook(table, op) })

    @BeforeEach
    fun reset() {
        h.reset()
        hook = { _, _ -> }
    }

    @Test
    fun `a newer release cannot hide the version still being applied from discard`() {
        val id = appliedV1()
        draft(id, LakeDefinitionFixtures.ORDERS_V2)
        h.landWithoutApply(id, 2)
        draft(id, LakeDefinitionFixtures.ORDERS_V3)
        var reached = false
        var discardFailure: Throwable? = null
        hook = { _, _ ->
            reached = true
            h.service
                .release(id, 3, h.actor, WriteSurface.SESSION)
                .apply
                .shouldBeInstanceOf<LakeApplyOutcome.Busy>()
            discardFailure = runCatching { h.service.discard(id, 2, h.actor, WriteSurface.SESSION) }.exceptionOrNull()
        }

        h.applier.apply(id, 2, deadline(), h.actor).shouldBeInstanceOf<LakeApplyOutcome.Applied>()

        reached shouldBe true
        withClue("discard must refuse the older attempt's version while its apply is still in flight") {
            discardFailure.shouldBeInstanceOf<DatapipelinesException>().code shouldBe PipelineErrorCodes.Lake.DEFINITION_TABLE_BUSY
        }
        requireNotNull(h.definitions.findVersion(id, 2)).status shouldBe PipelineVersionStatus.RELEASED
    }

    @Test
    fun `discard eligibility is serialized against an apply claiming the same version`() {
        val id = appliedV1()
        draft(id, LakeDefinitionFixtures.ORDERS_V2)
        h.landWithoutApply(id, 2)
        val workers = Executors.newFixedThreadPool(2)
        var discard: Future<*>? = null
        var apply: Future<*>? = null
        try {
            h.tx.executeWithoutResult {
                h.jdbc.queryForObject(
                    "SELECT version FROM lake_table_definition_versions WHERE table_id = ? AND version = 2 FOR UPDATE",
                    Int::class.java,
                    id,
                ) shouldBe 2
                discard = workers.submit { h.service.discard(id, 2, h.actor, WriteSurface.SESSION) }
                awaitCondition("discard reaches its version update") {
                    blocked("UPDATE lake_table_definition_versions SET status = 'DISCARDED'")
                }
                apply = workers.submit { h.applier.apply(id, 2, deadline(), h.actor) }
                awaitCondition("apply either finishes or waits on discard's managed-table lock") {
                    requireNotNull(apply).isDone || blocked("UPDATE lake_tables_managed SET apply_state = 'applying'")
                }
            }
            requireNotNull(discard).get(20, TimeUnit.SECONDS)
            requireNotNull(apply).get(20, TimeUnit.SECONDS)
            val table = h.table(id)
            listOfNotNull(table.currentDefinitionVersion, table.appliedVersionHere).forEach { version ->
                withClue("current/applied version $version must remain RELEASED after the controlled overlap") {
                    requireNotNull(h.definitions.findVersion(id, version)).status shouldBe PipelineVersionStatus.RELEASED
                }
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(20, TimeUnit.SECONDS)
        }
    }

    private fun appliedV1(): UUID {
        val created = h.service.createDraft("review798", "orders", LakeDefinitionFixtures.ORDERS_V1, h.actor, WriteSurface.SESSION)
        h.service
            .release(created.tableId, 1, h.actor, WriteSurface.SESSION)
            .apply
            .shouldBeInstanceOf<LakeApplyOutcome.Applied>()
        return created.tableId
    }

    private fun draft(
        id: UUID,
        body: String,
    ) {
        val current = requireNotNull(h.table(id).currentDefinitionVersion)
        val hash = requireNotNull(h.definitions.findVersion(id, current)).bodyHash
        h.service.writeDraft(id, body, hash, h.actor, WriteSurface.SESSION)
    }

    private fun deadline() = LakeTestDb.leases.catalogNowPlus(Duration.ofSeconds(30))

    private fun blocked(prefix: String): Boolean =
        // A raw, separate autocommit connection: pg_stat_activity must not reuse the control transaction's snapshot.
        LakeTestDb.dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?)",
                ).use { statement ->
                    statement.setString(1, "$prefix%")
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getBoolean(1)
                    }
                }
        }

    private fun awaitCondition(
        label: String,
        condition: () -> Boolean,
    ) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            check(System.nanoTime() < limit) { "Timed out: $label" }
            Thread.sleep(10)
        }
    }
}
