package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.LeaseOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** §8b case E: a PRIVATE server's synchronous replication wait retains the fence lock after commit visibility. */
class ReplicationStallSettlementIntegrationTest {
    @Test
    @Suppress("LongMethod", "NestedBlockDepth") // One ordered PostgreSQL replication/fence experiment, with scoped cleanup.
    @Timeout(90)
    fun `replication stall cannot turn a committed in-flight attempt into failed`() {
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("dp799_replication")
            .withCommand("postgres", "-c", "synchronous_commit=local")
            .use { pg ->
                pg.start()
                val source = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
                source.connection.use { connection ->
                    val migrations =
                        LakeTestDb
                            .repoFile("modules/app/src/main/resources/db/migration")
                            .listFiles()
                            .orEmpty()
                            .filter { it.extension == "sql" }
                            .sortedBy {
                                it.name
                                    .substringAfter('V')
                                    .substringBefore("__")
                                    .toInt()
                            }
                    connection.createStatement().use { statement -> migrations.forEach { statement.execute(it.readText()) } }
                }
                val h = DeliverySettlementHarness(source, resetDatabase = {}, connect = source::getConnection)
                h.reset()
                val row = h.unknown()
                val leases = h.leases(Duration.ofMillis(100))
                val lease =
                    leases
                        .acquire(
                            LeaseKind.TABLE,
                            "settle.orders",
                            row.operationId,
                            Duration.ofSeconds(1),
                            leases.catalogNowPlus(Duration.ofMinutes(1)),
                            "append",
                        ).shouldBeInstanceOf<LeaseOutcome.Acquired>()
                        .lease
                h.fencedConnection(lease).use { writer ->
                    source.connection.use { control ->
                        control.createStatement().use { statement ->
                            statement.execute("ALTER SYSTEM SET synchronous_standby_names = 'FIRST 1 (dp799_missing)'")
                            statement.execute("SELECT pg_reload_conf()")
                        }
                    }
                    try {
                        await {
                            h.jdbc.queryForObject("SHOW synchronous_standby_names", String::class.java)?.contains("dp799_missing") ==
                                true
                        }
                        writer.createStatement().use { it.execute("SET synchronous_commit = remote_apply") }
                        writer.autoCommit = false
                        h.insertPointer(writer) shouldBe 1
                        val commit = CompletableFuture.runAsync { writer.commit() }
                        await {
                            h.jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event = 'SyncRep')",
                                Boolean::class.java,
                            ) == true
                        }
                        await {
                            h.jdbc.queryForObject(
                                "SELECT expires_at < now() FROM lake_leases WHERE operation_id = ?",
                                Boolean::class.java,
                                row.operationId,
                            ) == true
                        }
                        // Visibility does not release the FOR SHARE fence lock. Takeover still times out.
                        h.settler(leases).settle(h.tableId, leases.catalogNowPlus(Duration.ofSeconds(5))) shouldBe 0
                        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.UNKNOWN
                        commit.isDone shouldBe false
                        releaseReplication(source)
                        commit.get(10, TimeUnit.SECONDS)
                        h.settler(leases).settle(h.tableId, leases.catalogNowPlus(Duration.ofSeconds(5))) shouldBe 1
                        h.repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHED
                        h.attemptOutcome(row) shouldBe "PUBLISHED"
                        h.jdbc.queryForObject(
                            "SELECT count(*) FROM lake_table_revisions WHERE operation_id = ?",
                            Int::class.java,
                            row.operationId,
                        ) shouldBe 1
                        println("case=replication_stall revisions=1 during=UNKNOWN after=PUBLISHED")
                    } finally {
                        releaseReplication(source)
                    }
                }
            }
    }

    private fun releaseReplication(source: DriverManagerDataSource) {
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER SYSTEM SET synchronous_standby_names = ''")
                statement.execute("SELECT pg_reload_conf()")
            }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) {
            check(System.nanoTime() < until) { "The deterministic PostgreSQL wait state was not observed." }
            Thread.sleep(10)
        }
    }
}
