package co.datapipelines.scheduler

import co.datapipelines.typesystem.guard.MetadataWriterGuard
import co.datapipelines.typesystem.guard.PrerequisiteKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) — the schedule seam of the writer protocol on a REAL PostgreSQL (brief §B schedules: guard → validate →
 * write; dependency-graph record §15.1). The service is wired with a stand-in guard that issues the PRODUCTION
 * workspace-row statement (`FOR NO KEY UPDATE` on `workspaces`) — the property under test is WHERE the service takes
 * it relative to the target's validation, not the JDBC guard's deadline (that is `MetadataWriterGuardIntegrationTest`'s).
 *
 * A raw holder connection stands in for a pipeline writer holding the same workspace guard (a discard or purge in
 * flight); the observer reads `pg_blocking_pids` on its own pooled connection. The executor counts and can park
 * [FakeExecutor.validate], so the ORDER is observed: nothing is validated while the create waits, and the guard is
 * held across the validation until the insert.
 */
class ScheduleWriterGuardIntegrationTest {
    private val pool = Executors.newCachedThreadPool()
    private lateinit var holder: Connection
    private lateinit var h: SchedulerHarness

    @BeforeEach
    fun setUp() {
        SchedulerTestDb.reset()
        h = SchedulerHarness(writeGuard = RowLockGuard)
        holder = SchedulerTestDb.dataSource.connection.apply { autoCommit = false }
    }

    @AfterEach
    fun tearDown() {
        holder.rollback()
        holder.close()
        pool.shutdownNow()
        pool.awaitTermination(ALLOWANCE_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `a create takes the workspace guard BEFORE validating its target - nothing is validated while it waits`() {
        holdWorkspace()
        val create = pool.submit<Schedule> { h.create(h.request(name = "guard/first")) }

        awaitBlockedByHolder(create)
        h.executor.validateCalls.get() shouldBe 0
        holder.commit()
        create.get(ALLOWANCE_SECONDS, TimeUnit.SECONDS).name shouldBe "guard/first"
        h.executor.validateCalls.get() shouldBe 1
    }

    @Test
    fun `the guard is held from the target's validation to the insert - a workspace writer cannot interleave`() {
        var rowFreeDuringValidate: Boolean? = null
        h.executor.duringValidate = { rowFreeDuringValidate = rowIsFree() }

        h.create(h.request(name = "guard/held"))

        // Without the guard ahead of validation, the only workspace lock (the cap's) comes AFTER it: the row is free.
        rowFreeDuringValidate shouldBe false
    }

    @Test
    fun `an edit that changes the target takes the guard - one that keeps the target stays on revision CAS alone`() {
        val schedule = h.create(h.request(name = "guard/edit"))
        holdWorkspace()

        // The reference (executor + payload) is unchanged: no guard, so the held workspace row does not stop it.
        val renamed =
            pool
                .submit<Schedule> {
                    h.service.update(
                        SchedulerTestDb.WORKSPACE,
                        schedule.id,
                        CREATOR,
                        1,
                        h.request(name = "guard/renamed"),
                    )
                }.get(ALLOWANCE_SECONDS, TimeUnit.SECONDS)
        val validatedBefore = h.executor.validateCalls.get()

        val retargeted =
            pool.submit<Schedule> {
                h.service.update(
                    SchedulerTestDb.WORKSPACE,
                    schedule.id,
                    CREATOR,
                    renamed.revision,
                    h.request(name = "guard/renamed", payload = FakeExecutor.payload("job-b")),
                )
            }
        awaitBlockedByHolder(retargeted)
        h.executor.validateCalls.get() shouldBe validatedBefore
        holder.commit()
        retargeted.get(ALLOWANCE_SECONDS, TimeUnit.SECONDS).payload shouldBe FakeExecutor.payload("job-b")
    }

    @Test
    fun `refusals keep their codes and write nothing under the guard - target not found and revision conflict`() {
        val missing =
            shouldThrow<ScheduleException> { h.create(h.request(name = "guard/missing", payload = FakeExecutor.payload("missing"))) }
        missing.code shouldBe ScheduleErrorCodes.TARGET_NOT_FOUND
        h.service.countLive(SchedulerTestDb.WORKSPACE) shouldBe 0

        val schedule = h.create(h.request(name = "guard/cas"))
        val stale =
            shouldThrow<ScheduleException> {
                h.service.update(
                    SchedulerTestDb.WORKSPACE,
                    schedule.id,
                    CREATOR,
                    schedule.revision + 1,
                    h.request(payload = FakeExecutor.payload("job-c")),
                )
            }
        stale.code shouldBe ScheduleErrorCodes.REVISION_CONFLICT
        h.service.countLive(SchedulerTestDb.WORKSPACE) shouldBe 1
    }

    private fun holdWorkspace() {
        holder.prepareStatement(LOCK_WORKSPACE).use { ps ->
            ps.setObject(1, SchedulerTestDb.WORKSPACE)
            ps.executeQuery().close()
        }
    }

    private fun holderPid(): Int =
        holder.createStatement().use { s ->
            s.executeQuery("SELECT pg_backend_pid()").use {
                it.next().let { _ -> it.getInt(1) }
            }
        }

    /** Polls, on its own connection, until a backend waits on the holder — or fails when [writer] finished first. */
    private fun awaitBlockedByHolder(writer: Future<*>) {
        val pid = holderPid()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (true) {
            check(!writer.isDone) { "the write finished without waiting on the held workspace row (guard absent?)" }
            check(System.nanoTime() < deadline) { "no backend waited on the holder within the probe window" }
            if (waitersOn(pid) > 0) return
            Thread.sleep(POLL_MS)
        }
    }

    /** Backends blocked by [pid], read on a connection of its own (inside a transaction the view would be frozen). */
    private fun waitersOn(pid: Int): Int =
        JdbcTemplate(SchedulerTestDb.dataSource)
            .queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid)
            ?: 0

    /** True when nobody holds the workspace row: a NOWAIT lock on a fresh connection, rolled back at once. */
    private fun rowIsFree(): Boolean =
        SchedulerTestDb.dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("$LOCK_WORKSPACE NOWAIT").use { ps ->
                    ps.setObject(1, SchedulerTestDb.WORKSPACE)
                    ps.executeQuery().close()
                }
                true
            } catch (e: SQLException) {
                if (e.sqlState != "55P03") throw e
                false
            } finally {
                c.rollback()
                c.autoCommit = true
            }
        }

    /** The production workspace-row statement, joined to the service's transaction through the shared DataSource. */
    private object RowLockGuard : MetadataWriterGuard {
        override fun prerequisites(
            keys: Collection<PrerequisiteKey>,
            busyCode: String,
        ) = error("the schedule writer takes no prerequisite key")

        override fun workspaces(
            workspaceIds: Collection<UUID>,
            busyCode: String,
        ) {
            workspaceIds.sorted().forEach { id ->
                SchedulerTestDb.jdbc.jdbcTemplate.queryForList(LOCK_WORKSPACE, id)
            }
        }

        override fun endpointRegistry(busyCode: String) = error("the schedule writer takes no registry key")

        override fun <T> withinOneDeadline(block: () -> T): T = block()
    }

    private companion object {
        const val LOCK_WORKSPACE = "SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE"
        const val ALLOWANCE_SECONDS = 30L
        const val WAIT_PROBE_SECONDS = 20L
        const val POLL_MS = 20L
        val CREATOR: UUID = SchedulerTestDb.CREATOR
    }
}
