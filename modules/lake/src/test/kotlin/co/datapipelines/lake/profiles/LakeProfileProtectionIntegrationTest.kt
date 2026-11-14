package co.datapipelines.lake.profiles

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.RecordingAuditSink
import co.datapipelines.lake.delivery.DeliverySettlementHarness
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * P12 (brief 956; record M7, call 35 "Not in a profile"): two tables on different profiles get the same
 * `protected_until` from the settle sweep. The protection window is configuration, one value per deployment; a
 * profile carries no retention value, so the stamps of two supersessions are equal to the second and each is
 * `now()` plus the configured days.
 */
class LakeProfileProtectionIntegrationTest {
    private val h = DeliverySettlementHarness()
    private val profiles = LakeProfiles.over(h.named, h.transaction, RecordingAuditSink())

    @BeforeEach
    fun reset() {
        h.reset()
        h.jdbc.update("UPDATE lake_control SET protection_interval = INTERVAL '7 days' WHERE id = 1")
    }

    @AfterEach
    fun restore() {
        h.jdbc.update("UPDATE lake_control SET mode = 'normal', protection_interval = INTERVAL '7 days' WHERE id = 1")
        h.jdbc.execute("TRUNCATE lake_tables_managed, lake_namespaces CASCADE")
        h.jdbc.update("DELETE FROM lake_profiles WHERE name = ?", PROFILE)
    }

    @Test
    fun `two tables on different profiles get the same protected_until after a visible supersession on each`() {
        val returns = h.addTable("returns")
        profiles.create(
            PROFILE,
            LakeProfileFixtures.document(
                "maintenance.window" to "\"00:00-00:00\"",
                "maintenance.target_file_bytes" to "${16 * MIB}",
                "maintenance.max_bytes_rewritten_per_run" to "${32 * MIB}",
                "write.row_group_bytes" to "${256 * KIB}",
            ),
            h.actor,
        )
        profiles.assign(returns, PROFILE, h.actor)
        (profiles.profileNameOf(h.tableId) to profiles.profileNameOf(returns)) shouldBe ("production" to PROFILE)
        TABLES.forEach { pointer(it, "one") }

        TABLES.forEach { table ->
            pointer(table, "two")
            h.sweep(protectionDays = DAYS).finalizeProtection() shouldBe 1
        }

        val (orders, other) = TABLES.map { deadlineEpoch(it) }
        abs(orders - other) shouldBeLessThan 1.0
        TABLES.forEach { secondsShortOf(it).shouldBeBetween(0.0, SLACK, 0.0) }
    }

    /** Inserts (`one`) or moves (`two`) [table]'s catalog pointer under its fenced lease; a move supersedes a revision. */
    private fun pointer(
        table: String,
        file: String,
    ) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "settle.$table")
        try {
            h.fencedConnection(lease).use { connection ->
                val sql =
                    if (file == "one") {
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                            "VALUES ('lake', 'settle', ?, ?, 'TABLE')"
                    } else {
                        "UPDATE iceberg_tables SET metadata_location = ? WHERE table_namespace = 'settle' AND table_name = ?"
                    }
                connection.prepareStatement(sql).use { statement ->
                    val location = "s3://settle/$table/metadata/$file.json"
                    if (file == "one") {
                        statement.setString(1, table)
                        statement.setString(2, location)
                    } else {
                        statement.setString(1, location)
                        statement.setString(2, table)
                    }
                    statement.executeUpdate() shouldBe 1
                }
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun deadlineEpoch(table: String): Double =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM protected_until)::float8 FROM lake_table_revisions " +
                    "WHERE namespace = 'settle' AND table_name = ? AND superseded_at IS NOT NULL",
                Double::class.java,
                table,
            ),
        )

    private fun secondsShortOf(table: String): Double =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (clock_timestamp() + make_interval(days => ?) - protected_until))::float8 " +
                    "FROM lake_table_revisions WHERE namespace = 'settle' AND table_name = ? AND superseded_at IS NOT NULL",
                Double::class.java,
                DAYS,
                table,
            ),
        )

    private companion object {
        const val PROFILE = "p12_small"
        const val DAYS = 30
        const val SLACK = 30.0
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        val TABLES = listOf("orders", "returns")
    }
}
