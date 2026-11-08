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
 * #844, corrected by #859: the settlement tick's keyset page of one table's unsettled deliveries is an index range scan
 * on `idx_lake_deliveries_unsettled_page (table_id, id) WHERE state IN (…)` that stops after the page — and stays one
 * whatever the plan cache does. `DeliveryLedgerRepository.unsettled` issues TWO statement texts (#859): the first page
 * without a cursor predicate and a cursor page with a direct `id > :after`, because a single nullable-cursor OR under a
 * generic prepared plan cannot fold away its null branch — the bound then leaves the index condition and degrades to a
 * `Filter` over the table's whole unsettled backlog. Real PostgreSQL, the SHIPPED migrations, a backlog many pages deep
 * on one table beside settled rows and a second table, and the EXACT statement texts `unsettled` issues, captured by
 * calling it, then PREPAREd with their own parameter arities on unpooled connections under `plan_cache_mode = auto` and
 * `plan_cache_mode = force_generic_plan`.
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
        // The two static shapes: the first page's text carries no cursor predicate and no cursor parameter at all.
        val (firstSql, _) = recorded(after = null)
        firstSql shouldNotContain "id >"
        firstSql shouldNotContain ":after"

        val first = planOf(after = null)
        println("EXPLAIN unsettled first page\n$first")
        withClue("the first page's plan:\n$first") {
            assertPageScan(first)
            first shouldNotContain "id >"
            first shouldContain Regex("Index Cond: \\(table_id = '[0-9a-f-]+'::uuid\\)")
        }

        val keyset = planOf(after = fiftiethUnsettledId())
        println("EXPLAIN unsettled keyset page\n$keyset")
        withClue("the keyset page's plan:\n$keyset") {
            assertPageScan(keyset)
            keyset shouldContain Regex("Index Cond: \\(\\(table_id = '[0-9a-f-]+'::uuid\\) AND \\(id > '[0-9a-f-]+'::uuid\\)\\)")
        }
    }

    @Test
    fun `a prepared first page keeps its plan on the page index under any plan cache mode`() {
        val (sql, _) = recorded(after = null)
        val prepared = sql.replace(":table", "$1").replace(":limit", "$2")
        for (mode in PLAN_MODES) {
            val plan = preparedPlan(mode, prepared, "uuid, int") { "EXECUTE page('${h.tableId}', $PAGE)" }
            println("EXPLAIN EXECUTE prepared first page (plan_cache_mode=$mode)\n$plan")
            withClue("the prepared first page's plan under plan_cache_mode=$mode:\n$plan") {
                assertPageScan(plan)
                plan shouldContain Regex("Index Cond: \\(table_id = ('[0-9a-f-]+'::uuid|\\$1)\\)")
                plan shouldNotContain "id >"
            }
        }
    }

    @Test
    fun `a prepared keyset page keeps the cursor bound in its index condition under any plan cache mode`() {
        val (sql, _) = recorded(after = fiftiethUnsettledId())
        sql shouldContain "id > :after"
        val prepared = sql.replace(":table", "$1").replace(":after", "$2").replace(":limit", "$3")
        val deep = deepUnsettledId()
        for (mode in PLAN_MODES) {
            val plan = preparedPlan(mode, prepared, "uuid, uuid, int") { "EXECUTE page('${h.tableId}', '$deep', $PAGE)" }
            println("EXPLAIN EXECUTE prepared deep keyset page (plan_cache_mode=$mode)\n$plan")
            withClue("the prepared deep keyset page's plan under plan_cache_mode=$mode:\n$plan") {
                assertPageScan(plan)
                val boundCond =
                    Regex("Index Cond: \\(\\(table_id = ('[0-9a-f-]+'::uuid|\\$1)\\) AND \\(id > ('[0-9a-f-]+'::uuid|\\$2)\\)\\)")
                plan shouldContain boundCond
            }
        }
    }

    @Test
    fun `a forced generic deep keyset page is analyzed as a bounded page scan, with its buffer evidence`() {
        val (sql, _) = recorded(after = fiftiethUnsettledId())
        val prepared = sql.replace(":table", "$1").replace(":after", "$2").replace(":limit", "$3")
        val deep = deepUnsettledId()
        val analyzed =
            preparedPlan("force_generic_plan", prepared, "uuid, uuid, int", explainPrefix = "EXPLAIN (ANALYZE, BUFFERS)") {
                "EXECUTE page('${h.tableId}', '$deep', $PAGE)"
            }
        println("EXPLAIN (ANALYZE, BUFFERS) EXECUTE prepared deep keyset page (plan_cache_mode=force_generic_plan)\n$analyzed")
        // Evidence, not a gate: machine-sensitive buffer counts are printed above; the assertions stay plan-shape ones.
        withClue("the analyzed deep keyset page under a forced generic plan:\n$analyzed") {
            assertPageScan(analyzed)
            analyzed shouldContain Regex("Index Cond: .*id >")
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

    @Test
    fun `the first page is exact, a cursor at the last row is empty, and the last page is short`() {
        val ordered =
            h.jdbc.queryForList(
                "SELECT id FROM lake_deliveries WHERE table_id = ? AND state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN') ORDER BY id",
                UUID::class.java,
                h.tableId,
            )

        // The first page (null cursor) is exactly the first PAGE unsettled rows in id order — the limit binds.
        val first = h.repository.unsettled(h.tableId, null, PAGE)
        first.map { it.id } shouldBe ordered.take(PAGE)
        first.size shouldBe PAGE
        h.repository.unsettled(h.tableId, null, 3).map { it.id } shouldBe ordered.take(3)

        // The page at the second-to-last row is the terminal short page; past the last row the page is empty.
        h.repository.unsettled(h.tableId, ordered[ordered.size - 2], PAGE).map { it.id } shouldBe listOf(ordered.last())
        h.repository.unsettled(h.tableId, ordered.last(), PAGE).map { it.id } shouldBe emptyList<UUID>()
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

    /**
     * PREPAREs a captured production text — its named parameters substituted by the ordinal placeholders PREPARE
     * declares, the text itself untouched (#859: the test must not manufacture replacement SQL) — on its own unpooled
     * connection under `plan_cache_mode = [mode]`, runs it [PREPARED_RUNS] times so the plan cache is warm, and answers
     * [explainPrefix] of the prepared EXECUTE. The connection ends with the statement and the session setting.
     */
    private fun preparedPlan(
        mode: String,
        prepared: String,
        arity: String,
        explainPrefix: String = "EXPLAIN",
        execute: () -> String,
    ): String =
        LakeTestDb.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET plan_cache_mode = $mode")
                statement.execute("PREPARE page($arity) AS $prepared")
                repeat(PREPARED_RUNS) { statement.executeQuery(execute()).close() }
                statement
                    .executeQuery("$explainPrefix ${execute()}")
                    .use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                    .joinToString("\n")
            }
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
        val PLAN_MODES = listOf("auto", "force_generic_plan")
    }
}
