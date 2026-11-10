package co.datapipelines.web.release

import co.datapipelines.application.endpoints.EndpointInvalidationPublisher
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.guard.EndpointRegistryLock
import co.datapipelines.web.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * #841 (G0) R2 of the landing review — the endpoint registry's invalidation (a local snapshot drop AND a Redis publish to
 * every peer) runs only after the transaction that OWNS the write commits, never after a rollback, and never while the
 * write's workspace row and registry key are held.
 *
 * The production shape, on the real PostgreSQL: the REST routes are `@Transactional("metadataTransactionManager")` and
 * the service's own `TransactionTemplate` joins that outer transaction (default propagation), so returning from the
 * service's callback is NOT a commit. Here the outer transaction is a `TransactionTemplate` on the SAME transaction
 * manager the service uses — exactly that join. The publisher is a recording stand-in that, at the moment it is
 * notified, opens a FRESH connection and observes (1) whether the write is visible — committed — and (2) whether the
 * workspace row (`FOR NO KEY UPDATE NOWAIT`) and the registry key (`pg_try_advisory_xact_lock`) can be acquired, i.e.
 * the writer released them. Everything else is production code: the repository, the guard, the registry.
 */
class EndpointInvalidationAfterCommitIntegrationTest {
    private val rows = GuardRows()
    private val dataSource = SharedPostgres.dataSource()
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private val outer = TransactionTemplate(transactionManager)
    private val workspace = rows.workspace()
    private val pipelineId = UUID.randomUUID()
    private val pipelineName = "g0_r2_${pipelineId.toString().take(8)}"
    private val path = "/g0r2/p${pipelineId.toString().take(8)}/x"
    private val notifications = mutableListOf<Observation>()
    private val audit = mockk<AuditEventSink>(relaxed = true)

    private val service =
        EndpointPublishService(
            endpoints = PublishedEndpointRepository(NamedParameterJdbcTemplate(dataSource)),
            pipelines = pipelines(),
            pipelineRepository = pipelineRepository(),
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
            registry =
                EndpointRegistry(
                    PublishedEndpointRepository(NamedParameterJdbcTemplate(dataSource)),
                    EndpointInvalidationPublisher { notifications += observe() },
                ),
            audit = audit,
            timeouts = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300),
            transactions = TransactionTemplate(transactionManager),
            writeGuard = JdbcReleaseMutationGuard(JdbcTemplate(dataSource), BOUND_SECONDS),
        )

    init {
        rows.jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id) VALUES (?, ?, 'G0 R2', ?, 1, ?)",
            pipelineId,
            pipelineName,
            rows.user,
            workspace,
        )
    }

    @AfterEach
    fun tearDown() {
        rows.jdbc.update("DELETE FROM published_endpoints WHERE pipeline_id = ?", pipelineId)
        rows.jdbc.update("DELETE FROM pipelines WHERE id = ?", pipelineId)
        rows.close()
    }

    @Test
    fun `a publish joined to an outer transaction notifies only after the outer commit - committed and unlocked by then`() {
        outer.executeWithoutResult {
            service.publish(principal(), path, pipelineName, null, "")
            withClue("no notification while the owning (outer) transaction is still open") { notifications.shouldBeEmpty() }
        }

        notifications shouldContainExactly listOf(Observation(rows = 1, workspaceFree = true, registryFree = true))
        verify(exactly = 1) { audit.log(event = "endpoint.published", userId = any(), keyId = any(), details = any()) }
    }

    @Test
    fun `a publish whose outer transaction rolls back notifies nobody - and nothing was published`() {
        shouldThrow<RolledBack> {
            outer.executeWithoutResult {
                service.publish(principal(), path, pipelineName, null, "")
                throw RolledBack()
            }
        }

        notifications.shouldBeEmpty()
        storedRows() shouldBe 0
    }

    @Test
    fun `an unpublish joined to an outer transaction notifies only after the outer commit - the row is gone and unlocked`() {
        outer.executeWithoutResult { service.publish(principal(), path, pipelineName, null, "") }
        notifications.clear()

        outer.executeWithoutResult {
            service.unpublish(principal(), path) shouldBe true
            withClue("no notification while the owning (outer) transaction is still open") { notifications.shouldBeEmpty() }
        }

        notifications shouldContainExactly listOf(Observation(rows = 0, workspaceFree = true, registryFree = true))
        verify(exactly = 1) { audit.log(event = "endpoint.unpublished", userId = any(), keyId = any(), details = any()) }
    }

    @Test
    fun `an unpublish whose outer transaction rolls back notifies nobody - and the endpoint stays`() {
        outer.executeWithoutResult { service.publish(principal(), path, pipelineName, null, "") }
        notifications.clear()

        shouldThrow<RolledBack> {
            outer.executeWithoutResult {
                service.unpublish(principal(), path) shouldBe true
                throw RolledBack()
            }
        }

        notifications.shouldBeEmpty()
        storedRows() shouldBe 1
    }

    @Test
    fun `with no outer transaction the service's own transaction owns the write - notified after its commit`() {
        service.publish(principal(), path, pipelineName, null, "")

        notifications shouldContainExactly listOf(Observation(rows = 1, workspaceFree = true, registryFree = true))
    }

    /** What a fresh connection sees at the moment of notification. */
    private data class Observation(
        val rows: Int,
        val workspaceFree: Boolean,
        val registryFree: Boolean,
    )

    private fun observe(): Observation =
        LockHolder.open().use { c ->
            c.autoCommit = false
            try {
                Observation(committedRows(c), workspaceFree(c), registryFree(c))
            } finally {
                c.rollback()
            }
        }

    /** The committed rows for [path], as a fresh connection sees them. */
    private fun committedRows(c: Connection): Int =
        c.prepareStatement("SELECT count(*) FROM published_endpoints WHERE path_pattern = ?").use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use {
                it.next()
                it.getInt(1)
            }
        }

    /** True when the workspace row can be locked NOWAIT (nobody holds it); a `55P03` rolls back and answers false. */
    private fun workspaceFree(c: Connection): Boolean =
        try {
            c.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE NOWAIT").use { ps ->
                ps.setObject(1, workspace)
                ps.executeQuery().close()
            }
            true
        } catch (e: SQLException) {
            if (e.sqlState != LOCK_NOT_AVAILABLE) throw e
            c.rollback()
            false
        }

    /** True when the registry's advisory key can be taken at once (nobody holds it). */
    private fun registryFree(c: Connection): Boolean =
        c.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { ps ->
            ps.setLong(1, EndpointRegistryLock.KEY)
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun storedRows(): Int =
        rows.jdbc.queryForObject("SELECT count(*) FROM published_endpoints WHERE path_pattern = ?", Int::class.java, path) ?: 0

    private fun principal() =
        AuthenticatedPrincipal(
            userId = rows.user,
            email = "g0-r2@example.com",
            displayName = "G0 R2",
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_ABCDEFGHIJKL",
            workspaceName = "g0-r2",
            workspace = WorkspaceContext(workspace, "g0-r2"),
        )

    private fun record() =
        PipelineRecord(
            id = pipelineId,
            name = pipelineName,
            displayName = "G0 R2",
            description = "",
            ownerId = rows.user,
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    /** The pipeline reads are not under test here (their guard proofs are elsewhere): a released, read-only version. */
    private fun pipelines(): PipelineService =
        mockk<PipelineService>().also { pipelines ->
            every { pipelines.findCurrentVersion(workspace, any(), pipelineId) } returns
                PipelineVersionDetail(
                    pipelineId = pipelineId,
                    version = 1,
                    status = PipelineVersionStatus.RELEASED,
                    bodyHash = "hash",
                    createdAt = Instant.EPOCH,
                    createdBy = rows.user,
                    releasedAt = null,
                    releasedBy = null,
                    updatedBy = null,
                    updatedAt = Instant.EPOCH,
                )
            every { pipelines.findExecutable(workspace, any(), any(), 1) } returns
                PipelineService.ExecutablePipeline(record(), 1, "{}", body())
        }

    private fun pipelineRepository(): PipelineRepository =
        mockk<PipelineRepository>().also { every { it.findByName(workspace, pipelineName) } returns record() }

    private fun body() =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = pipelineName,
            displayName = "G0 R2",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes =
                listOf(
                    Node(
                        id = "rows",
                        description = "",
                        type = NodeType.DQL,
                        source = "pg",
                        template = TemplateRef("test/rows.sql", 1),
                        output = NodeOutput.Caller,
                        dependsOn = emptyList(),
                    ),
                ),
        )

    /** The marker that rolls an outer transaction back after the service returned. */
    private class RolledBack : RuntimeException("roll the outer transaction back")

    private companion object {
        const val BOUND_SECONDS = 5
        const val MAX_DEPTH = 5
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
