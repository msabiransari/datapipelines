package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.DependencySource
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ResolvedNode
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #786 R3 (landing review r3; record §25.4, brief E.3) on a REAL PostgreSQL: every metadata statement a release walk's
 * provider issues runs under `min(statement-timeout-seconds, remaining deadline)`. Another session holds an ACCESS
 * EXCLUSIVE lock on the table the provider's own statement reads, so that statement waits; the bound cancels it
 * (SQLSTATE 57014) and the walk answers the ROOT family's `release.metadata_unavailable` — no closure, and in a guarded
 * caller's transaction nothing it wrote survives. Without the bound the statement waits for the lock holder, which lets
 * go after [HOLD]: a walk that returns, or that takes [HOLD], is the defect.
 *
 * The API provider is the production [EndpointDependencySource] over the real versions repository; the pipeline provider
 * the production [PipelineDependencySource]. Both run under the production [PostgresStatementCeiling].
 */
class ReleaseStatementCeilingPostgresTest {
    private val dataSource = SharedPostgres.dataSource()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val manager = DataSourceTransactionManager(dataSource)
    private val ceiling = PostgresStatementCeiling(dataSource)
    private var userId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to "w_${workspaceId.toString().replace("-", "")}", "owner" to userId),
        )
        val pipelineId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES (:id, :name, 'P', :owner, NULL, :ws)",
            mapOf("id" to pipelineId, "name" to PIPELINE, "owner" to userId, "ws" to workspaceId),
        )
        val endpointId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by)" +
                " VALUES (:id, :ws, :path, :pipeline, 30, :owner)",
            mapOf("id" to endpointId, "ws" to workspaceId, "path" to PATH, "pipeline" to pipelineId, "owner" to userId),
        )
        jdbc.update(
            "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, timeout_seconds," +
                " body_hash, created_by) VALUES (:id, 1, 'DRAFT', :pipeline, 1, 30, 'h1', :owner)",
            mapOf("id" to endpointId, "pipeline" to PIPELINE, "owner" to userId),
        )
        jdbc.update("UPDATE published_endpoints SET current_version = 1 WHERE id = :id", mapOf("id" to endpointId))
    }

    @Test
    fun `control - with nothing holding the table the API root plans, and its statement ran under the bound`() {
        val closure = checkNotNull(walk(statementLimit = Duration.ofSeconds(1)).plan(apiRoot(), userId))
        closure.nodes.map { it.identity.family } shouldContainExactly listOf(ReleaseFamily.ENDPOINT, ReleaseFamily.PIPELINE)
    }

    @Test
    fun `the API provider's statement waits on a lock and is cancelled at the statement limit - endpoint metadata_unavailable`() {
        val (refusal, took) =
            holding("published_endpoint_versions") {
                timed { shouldThrow<DatapipelinesException> { walk(statementLimit = Duration.ofSeconds(1)).plan(apiRoot(), userId) } }
            }
        refusal.code shouldBe "endpoint.release.metadata_unavailable"
        refusal.details shouldBe emptyMap()
        withClue("the database cancelled the provider's own statement (57014), at the bound and not at the lock's release") {
            PostgresStatementCeiling.cancelled(refusal) shouldBe true
            took shouldBeLessThan HOLD.minusSeconds(2)
        }
    }

    @Test
    fun `the remaining deadline bounds a statement below the statement limit - cancelled when the walk's time runs out`() {
        val (refusal, took) =
            holding("published_endpoint_versions") {
                timed {
                    shouldThrow<DatapipelinesException> {
                        walk(statementLimit = Duration.ofSeconds(30), budget = Duration.ofSeconds(2)).plan(apiRoot(), userId)
                    }
                }
            }
        refusal.code shouldBe "endpoint.release.metadata_unavailable"
        PostgresStatementCeiling.cancelled(refusal) shouldBe true
        took shouldBeLessThan HOLD.minusSeconds(2)
    }

    @Test
    fun `the pipeline provider's statements are bounded the same way - pipeline metadata_unavailable`() {
        val pipelines = PipelineRepository(ceiling.jdbc)
        val sources = mapOf<ReleaseFamily, DependencySource>(ReleaseFamily.PIPELINE to PipelineDependencySource(pipelines))
        val root = ReleaseNodeIdentity(workspaceId, ReleaseFamily.PIPELINE, PIPELINE, 1)
        val (refusal, took) =
            holding("pipelines") {
                timed { shouldThrow<DatapipelinesException> { walk(Duration.ofSeconds(1), sources = sources).plan(root, userId) } }
            }
        refusal.code shouldBe "pipeline.release.metadata_unavailable"
        PostgresStatementCeiling.cancelled(refusal) shouldBe true
        took shouldBeLessThan HOLD.minusSeconds(2)
    }

    @Test
    fun `in a guarded caller's transaction the cancellation rolls back what the caller wrote, and a returning walk restores the setting`() {
        val transactions = TransactionTemplate(manager)
        val marker = UUID.randomUUID()
        val refusal =
            holding("published_endpoint_versions") {
                shouldThrow<DatapipelinesException> {
                    transactions.executeWithoutResult {
                        jdbc.update(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :e, 'M', 'google', :s)",
                            mapOf("id" to marker, "e" to "m$marker@example.com", "s" to "m-$marker"),
                        )
                        walk(statementLimit = Duration.ofSeconds(1)).walkInCurrentTransaction(apiRoot(), userId)
                    }
                }
            }
        refusal.code shouldBe "endpoint.release.metadata_unavailable"
        withClue("no partial effect: the caller's own write before the walk is rolled back with the cancelled statement") {
            jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id", mapOf("id" to marker), Int::class.java) shouldBe 0
        }

        val settings =
            checkNotNull(
                transactions.execute {
                    val before = jdbc.jdbcTemplate.queryForObject("SHOW statement_timeout", String::class.java)
                    checkNotNull(walk(statementLimit = Duration.ofSeconds(1)).walkInCurrentTransaction(apiRoot(), userId))
                    before to jdbc.jdbcTemplate.queryForObject("SHOW statement_timeout", String::class.java)
                },
            )
        withClue("the caller's statements after a walk keep the setting they had before it") { settings.second shouldBe settings.first }
    }

    private fun apiRoot() = ReleaseNodeIdentity(workspaceId, ReleaseFamily.ENDPOINT, PATH, 1)

    /** The production walk: planning transaction, savepoint fence, finite deadline, the PostgreSQL statement ceiling. */
    private fun walk(
        statementLimit: Duration,
        budget: Duration = Duration.ofSeconds(30),
        sources: Map<ReleaseFamily, DependencySource> =
            mapOf(
                ReleaseFamily.ENDPOINT to EndpointDependencySource(PublishedEndpointVersionRepository(ceiling.jdbc)),
                // The pinned pipeline as a released leaf: this suite is about the API provider's statement.
                ReleaseFamily.PIPELINE to DependencySource { identity -> leaf(identity) },
            ),
    ) = ReleasePlanningWalk(
        ReleaseDependencyFamilies.registry(sources, ceiling),
        ReleaseBounds(),
        ReleasePlanningWalk.planningTransaction(manager),
        ReleasePlanningWalk.speculativeReads(manager),
        ReleasePlanningDeadline.after(budget),
        planning = ReleasePlanningLimits(statementTimeout = statementLimit),
    )

    private fun leaf(identity: ReleaseNodeIdentity) =
        ResolvedNode(identity, identity.entityId, ReleaseNodeStatus.RELEASED, "hp", identity.version, emptyList(), UUID.randomUUID())

    /**
     * Runs [block] while another session holds an ACCESS EXCLUSIVE lock on [table]; the holder lets go after [HOLD] on
     * its own, so an unbounded statement finishes (late) instead of hanging the suite.
     */
    private fun <T> holding(
        table: String,
        block: () -> T,
    ): T {
        val holder: Connection = dataSource.connection
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                holder.rollback()
                holder.close()
            }
        }
        holder.autoCommit = false
        holder.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
        val timer =
            Thread {
                try {
                    Thread.sleep(HOLD.toMillis())
                    release()
                } catch (_: InterruptedException) {
                    // The block ended first and released the lock itself.
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            return block()
        } finally {
            release()
            timer.interrupt()
        }
    }

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val started = System.nanoTime()
        val value = block()
        return value to Duration.ofNanos(System.nanoTime() - started)
    }

    private companion object {
        const val PIPELINE = "acme/rides/daily"
        const val PATH = "/acme/v1/rides"

        /** How long the lock holder keeps the table; a bounded statement is cancelled well before it lets go. */
        val HOLD: Duration = Duration.ofSeconds(8)
    }
}
