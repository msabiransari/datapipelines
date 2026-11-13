package co.datapipelines.application.dependencies

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.scheduler.MissedRunPolicy
import co.datapipelines.scheduler.NewSchedule
import co.datapipelines.scheduler.Schedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * #874 G3 — rows for the two non-artifact providers' PostgreSQL tests: a user, two workspaces, datasources (granted,
 * soft-deleted) and schedules (any payload, any condition), written with plain SQL or the scheduler's own insert so the
 * providers read rows they never wrote.
 */
class NonArtifactSeed(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    var userId: UUID = UUID.randomUUID()
        private set
    var workspaceId: UUID = UUID.randomUUID()
        private set
    var otherWorkspaceId: UUID = UUID.randomUUID()
        private set

    private val schedules = ScheduleRepository(jdbc, SchedulerAutoConfiguration.JSON)

    fun reset() {
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        userId = UUID.randomUUID()
        workspaceId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        adopt(userId, workspaceId, create = true)
    }

    /**
     * Seeds into [user]'s existing [workspace] (another seed's reset made them, e.g. `ReleaseArtifactSeed` for a walk
     * over real pipelines) and creates the other workspace; [create] also inserts [workspace] itself.
     */
    fun adopt(
        user: UUID,
        workspace: UUID,
        create: Boolean = false,
    ) {
        userId = user
        workspaceId = workspace
        otherWorkspaceId = UUID.randomUUID()
        listOfNotNull(workspace.takeIf { create }, otherWorkspaceId).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
                mapOf("id" to id, "name" to "w_${id.toString().replace("-", "")}", "owner" to userId),
            )
        }
    }

    /** A live datasource [name] granted to [grantedTo]; soft-deleted when [deleted]. */
    fun datasource(
        name: String,
        grantedTo: List<UUID> = listOf(workspaceId),
        readonly: Boolean = false,
        deleted: Boolean = false,
    ) {
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, is_readonly, is_deleted) " +
                "VALUES (:name, :display, 'H2', 'jdbc:h2:mem:x', 'none', :by, :readonly, :deleted)",
            mapOf("name" to name, "display" to "DS $name", "by" to userId, "readonly" to readonly, "deleted" to deleted),
        )
        grantedTo.forEach { workspace ->
            jdbc.update(
                "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (:name, :ws, :by)",
                mapOf("name" to name, "ws" to workspace, "by" to userId),
            )
        }
    }

    /** The stored row's identity facts, read directly (the oracle the providers are compared with). */
    fun datasourceIdentity(name: String): Pair<UUID, Long> =
        jdbc.queryForObject(
            "SELECT incarnation_id, generation FROM datasources WHERE name = :name",
            mapOf("name" to name),
        ) { rs, _ -> rs.getObject("incarnation_id", UUID::class.java) to rs.getLong("generation") }!!

    /** A schedule of [workspace] with [payload] at [schema] for [executorId]. */
    fun schedule(
        name: String,
        payload: JsonNode = pipelinePayload("acme/rides/daily", 3),
        schema: Int = 2,
        executorId: String = "pipeline",
        workspace: UUID = workspaceId,
    ): Schedule =
        schedules.insert(
            NewSchedule(
                id = UUID.randomUUID(),
                workspaceId = workspace,
                name = name,
                executorId = executorId,
                payloadSchemaVersion = schema,
                payload = payload,
                parameters = SchedulerAutoConfiguration.JSON.createObjectNode(),
                targetRef = "pipeline:acme/rides/daily",
                cron = "0 * * * *",
                timezone = "UTC",
                missedRunPolicy = MissedRunPolicy.SKIP,
                nextDueAt = Instant.parse("2026-11-12T00:00:00Z"),
                createdBy = userId,
                idempotencyKey = null,
                idempotencyHash = null,
                notificationRecipients = listOf("ops@example.com"),
            ),
        )

    fun pause(schedule: Schedule) {
        jdbc.update("UPDATE schedules SET enabled = FALSE WHERE id = :id", mapOf("id" to schedule.id))
    }

    fun block(schedule: Schedule) {
        jdbc.update(
            "UPDATE schedules SET blocked_at = NOW(), blocked_reason = 'target_not_found' WHERE id = :id",
            mapOf("id" to schedule.id),
        )
    }

    fun softDelete(schedule: Schedule) {
        jdbc.update("UPDATE schedules SET deleted_at = NOW() WHERE id = :id", mapOf("id" to schedule.id))
    }

    fun pipelinePayload(
        name: String,
        version: Int,
    ): JsonNode =
        SchedulerAutoConfiguration.JSON
            .createObjectNode()
            .put("pipeline", name)
            .put("version", version)

    /** A provider context: the member's (or the system) authority, [deadline] (default: 30 s, statements 30 s). */
    fun context(
        authority: Authority = Authority.Member(userId),
        deadline: Deadline = Deadline.after(BUDGET).withStatementLimit(BUDGET),
        workspace: UUID = workspaceId,
    ) = ResolveContext(workspace, MetadataSnapshot.READ_COMMITTED, Purpose.INTERACTIVE_READ, authority, deadline, ReadIsolation.NONE)

    companion object {
        val BUDGET: Duration = Duration.ofSeconds(30)
    }
}

/** A clock a test moves by hand (the injected deadline clock). */
class HandClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-11-11T00:00:00Z")

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * Another session's ACCESS EXCLUSIVE lock on one table, held in an open transaction until [release]; [waiting] reads
 * `pg_locks` on a THIRD connection for a backend waiting on that table — the in-flight witness, no sleep.
 */
class HeldTableLock(
    private val dataSource: DataSource,
) : AutoCloseable {
    private var holder: Connection? = null
    private var timer: Thread? = null

    /** Locks [table]; a daemon lets go after [HOLD] on its own, so a read with NO bound ends (late) instead of hanging. */
    fun lock(table: String) {
        val connection = dataSource.connection
        connection.autoCommit = false
        connection.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
        holder = connection
        timer =
            Thread {
                try {
                    Thread.sleep(HOLD.toMillis())
                    close()
                } catch (_: InterruptedException) {
                    // The test ended first and released the lock itself.
                }
            }.apply {
                isDaemon = true
                start()
            }
    }

    /** Whether some backend other than the holder waits (ungranted) for a lock on [table]. */
    fun waiting(table: String): Boolean =
        dataSource.connection.use { probe ->
            probe
                .prepareStatement("SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = to_regclass(?)")
                .use { statement ->
                    statement.setString(1, table)
                    statement.executeQuery().use { rows -> rows.next() && rows.getLong(1) > 0 }
                }
        }

    @Synchronized
    override fun close() {
        holder?.let {
            it.rollback()
            it.close()
        }
        holder = null
        timer?.takeIf { it !== Thread.currentThread() }?.interrupt()
    }

    private companion object {
        /** Far past every bound the in-flight cases set (one second), well inside a test run. */
        val HOLD: Duration = Duration.ofSeconds(8)
    }
}
