package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The window between a release's native prepare and its guarded section, forced open (#873 §F.3 c, round 4 ruling 1).
 *
 * A release's guarded transaction locks its workspace row `FOR NO KEY UPDATE` (`JdbcReleaseMutationGuard`, lock class
 * 2) AFTER the root's native prepare ran outside it (§C.8). This holds that row on a connection of its own, fires
 * [release] on another thread, waits until the release's backend is queued behind the hold, runs [inWindow], then lets
 * go: what the release then sees is the state [inWindow] left, after its native validation already passed. The wait is
 * observed in `pg_stat_activity` on a fresh connection (a snapshot inside a transaction would be frozen), never slept
 * for; a release that answers without waiting is RED.
 */
internal class WorkspaceRowHold(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
) {
    fun <T> around(
        workspaceId: String,
        release: () -> T,
        inWindow: () -> Unit,
    ): T {
        val holder = DriverManager.getConnection(jdbcUrl, user, password)
        holder.autoCommit = false
        try {
            val pid = pidOf(holder)
            holder.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { s ->
                s.setString(1, workspaceId)
                s.executeQuery().use { check(it.next()) { "workspace $workspaceId has a row to hold" } }
            }
            val answer = CompletableFuture.supplyAsync { release() }
            awaitWaiter(pid, answer)
            inWindow()
            holder.rollback()
            return answer.get(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            holder.close()
        }
    }

    private fun pidOf(connection: Connection): Int =
        connection.createStatement().use { s -> s.executeQuery("SELECT pg_backend_pid()").use { it.next().let { _ -> it.getInt(1) } } }

    private fun awaitWaiter(
        pid: Int,
        answer: CompletableFuture<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_SECONDS)
        while (waitersOn(pid) < 1) {
            withClue("the release answered without waiting on its workspace row: ${if (answer.isDone) answer.join() else ""}") {
                answer.isDone shouldBe false
            }
            withClue("no release queued behind the held workspace row within ${PROBE_SECONDS}s") {
                (System.nanoTime() < deadline) shouldBe true
            }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
    }

    private fun waitersOn(pid: Int): Int =
        JdbcTemplate(DriverManagerDataSource(jdbcUrl, user, password))
            .queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid) ?: 0

    private companion object {
        /** Below the production `lock-wait-seconds` default (5), so a release that never queues is RED, not busy. */
        const val PROBE_SECONDS = 4L
        const val PROBE_INTERVAL_MS = 20L
        const val RELEASE_TIMEOUT_SECONDS = 30L
    }
}
