package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID

/**
 * #844: the settlement tick's keyset page of one table's unsettled deliveries is an index range scan on
 * `idx_lake_deliveries_unsettled_page (table_id, id) WHERE state IN (…)` that stops after the page: the key carries the
 * table filter and the order, so no plan reads the table's whole backlog to sort it. Real PostgreSQL, the SHIPPED
 * migrations, a backlog many pages deep on one table beside settled rows and a second table, the EXACT statement
 * `DeliveryLedgerRepository.unsettled` issues, with its parameters.
 */
class UnsettledPageIndexIntegrationTest {
    private val h = DeliverySettlementHarness()

    @BeforeEach
    fun seed() {
        h.reset()
        val other = h.addTable("returns")
        insert(h.tableId, UNSETTLED, PER_STATE)
        insert(h.tableId, SETTLED, SETTLED_PER_STATE)
        insert(other, UNSETTLED + SETTLED, OTHER_PER_STATE)
        h.jdbc.execute("ANALYZE lake_deliveries")
    }

    @Test
    fun `the first page and a keyset page are index range scans on the page index with no sort`() {
        val first = planOf(after = null)
        println("EXPLAIN unsettled first page\n$first")
        withClue("the first page's plan:\n$first") {
            assertPageScan(first)
            first shouldNotContain "id >"
        }

        val keyset = planOf(after = fiftiethUnsettledId())
        println("EXPLAIN unsettled keyset page\n$keyset")
        withClue("the keyset page's plan:\n$keyset") {
            assertPageScan(keyset)
            keyset shouldContain Regex("Index Cond: \\(\\(table_id = '[0-9a-f-]+'::uuid\\) AND \\(id > '[0-9a-f-]+'::uuid\\)\\)")
        }
    }

    @Test
    fun `a prepared page under the default plan cache keeps the keyset bound in its index condition`() {
        val (sql, _) = recorded(after = null)
        val prepared =
            sql
                .replace(":table", "$1")
                .replace(":after", "$2")
                .replace(":limit", "$3")
        val deep = deepUnsettledId()
        val plan =
            // Its own unpooled connection: the prepared statement and the setting end with it.
            LakeTestDb.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET plan_cache_mode = auto")
                    statement.execute("PREPARE page(uuid, uuid, int) AS $prepared")
                    // Past PostgreSQL's five custom plans, where the plan cache weighs a generic plan; both page shapes.
                    repeat(PREPARED_RUNS) { n ->
                        val after = if (n % 2 == 0) "NULL" else "'$deep'"
                        statement.executeQuery("EXECUTE page('${h.tableId}', $after, $PAGE)").close()
                    }
                    val lines =
                        statement.executeQuery("EXPLAIN EXECUTE page('${h.tableId}', '$deep', $PAGE)").use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                    lines.joinToString("\n")
                }
            }
        println("EXPLAIN EXECUTE prepared deep keyset page (plan_cache_mode=auto)\n$plan")
        withClue("the prepared keyset page's plan:\n$plan") {
            assertPageScan(plan)
            plan shouldContain Regex("Index Cond: \\(\\(table_id = '[0-9a-f-]+'::uuid\\) AND \\(id > '[0-9a-f-]+'::uuid\\)\\)")
        }
    }

    @Test
    fun `the page answers the table's unsettled rows in id order, a page at a time, under either index`() {
        val expected =
            h.jdbc.queryForList(
                "SELECT id FROM lake_deliveries WHERE table_id = ? AND state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN') ORDER BY id",
                UUID::class.java,
                h.tableId,
            )
        val walked = mutableListOf<UUID>()
        var after: UUID? = null
        do {
            val page = h.repository.unsettled(h.tableId, after, PAGE)
            walked += page.map { it.id }
            after = page.lastOrNull()?.id
        } while (page.size == PAGE)

        walked shouldBe expected
        walked.size shouldBe UNSETTLED.size * PER_STATE
    }

    private fun assertPageScan(plan: String) {
        plan shouldContain "Index Scan using $INDEX on lake_deliveries"
        plan shouldNotContain "Sort"
        plan shouldNotContain "Seq Scan"
        plan shouldNotContain "Filter:"
    }

    /** EXPLAIN of exactly the statement the page issues, with its parameters, on one connection. */
    private fun planOf(after: UUID?): String {
        val (sql, params) = recorded(after)
        return LakeTestDb.dataSource.connection.use { connection ->
            NamedParameterJdbcTemplate(SingleConnectionDataSource(connection, true))
                .queryForList("EXPLAIN $sql", params, String::class.java)
                .joinToString("\n")
        }
    }

    private fun recorded(after: UUID?): Pair<String, Map<String, *>> {
        // The harness's bounded template: the repository refuses one without a statement timeout.
        val recording = RecordingJdbc(h.jdbc)
        DeliveryLedgerRepository(recording).unsettled(h.tableId, after, PAGE)
        return requireNotNull(recording.last) { "the page issued no statement" }
    }

    private fun fiftiethUnsettledId(): UUID = unsettledIdAt(PAGE - 1)

    /** Deep in the backlog: a page there must not read the rows before it. */
    private fun deepUnsettledId(): UUID = unsettledIdAt(UNSETTLED.size * PER_STATE - DEEP_FROM_END)

    private fun unsettledIdAt(offset: Int): UUID =
        requireNotNull(
            h.jdbc.queryForObject(
                "SELECT id FROM lake_deliveries WHERE table_id = ? AND state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN') " +
                    "ORDER BY id OFFSET ? LIMIT 1",
                UUID::class.java,
                h.tableId,
                offset,
            ),
        )

    private fun insert(
        table: UUID,
        states: List<String>,
        perState: Int,
    ) {
        val rows =
            states.flatMap { state ->
                List(perState) { n ->
                    arrayOf<Any>(
                        UUID.randomUUID(),
                        table,
                        "${state.lowercase()}-$n",
                        state,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                    )
                }
            }
        h.jdbc.batchUpdate(
            "INSERT INTO lake_deliveries (id, table_id, feed, delivery_id, fingerprint, state, operation_id, execution_id, " +
                "pipeline_id) VALUES (?, ?, 'orders', ?, '${"a".repeat(FINGERPRINT_LENGTH)}', ?, ?, ?, ?)",
            rows,
        )
    }

    /** Records the last named-parameter query a repository issued, then runs it. */
    private class RecordingJdbc(
        jdbc: JdbcTemplate,
    ) : NamedParameterJdbcTemplate(jdbc) {
        var last: Pair<String, Map<String, *>>? = null

        override fun <T : Any?> query(
            sql: String,
            paramMap: Map<String, *>,
            rowMapper: RowMapper<T>,
        ): List<T> {
            last = sql to paramMap
            return super.query(sql, paramMap, rowMapper)
        }
    }

    private companion object {
        const val INDEX = "idx_lake_deliveries_unsettled_page"

        /** The settlement sweep's page, `LakeSettlementSweep.SETTLE_PAGE_SIZE`. */
        const val PAGE = 50

        /** 2 400 unsettled rows on the table, 48 pages, and 2 000 settled rows beside them. */
        const val PER_STATE = 800
        const val SETTLED_PER_STATE = 1_000
        const val OTHER_PER_STATE = 600
        const val PREPARED_RUNS = 10
        const val DEEP_FROM_END = 200
        const val FINGERPRINT_LENGTH = 64
        val UNSETTLED = listOf("RECEIVED", "PUBLISHING", "UNKNOWN")
        val SETTLED = listOf("PUBLISHED", "FAILED")
    }
}
