package co.datapipelines.visualization

import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.lang.reflect.Proxy
import javax.sql.DataSource

/**
 * #331's bound, at the JDBC boundary: `used_by` for a 200-name page is ONE statement under the whole view and
 * ONE under a narrowing lens — a constant independent of the row count, where the pre-#331 `pinnedBy` per row
 * ran one containment scan (whole view) or one body read per admitted dashboard (narrowing) per ROW. Red if
 * anyone loops the page inside the answer; the tool's own call shape is held by `VisualizationsToolsTest` in
 * `mcp-server` (one `pinnedByAll` call, never per-row `pinnedBy`).
 */
class PinnedByBatchedStatementsTest {
    private lateinit var h: LifecycleHarness
    private lateinit var counted: DashboardRepository
    private lateinit var countedService: DashboardService
    private lateinit var counting: CountingDataSource

    @BeforeEach
    fun reset() {
        VisualizationTestDb.reset()
        h = LifecycleHarness()
        counting = CountingDataSource(VisualizationTestDb.dataSource)
        counted = DashboardRepository(NamedParameterJdbcTemplate(counting))
        countedService =
            DashboardService(
                repository = counted,
                validator = DashboardValidator(h.fakes.pipelineFacts, h.fakes.setFacts, h.visualizationRepository.pins),
                visualizations = h.visualizations,
                sets = h.fakes.setFacts,
                authoring = co.datapipelines.pipeline.AuthoringGuard(true),
                transactions = h.transactions,
            )
    }

    @Test
    fun `a 200-name page is ONE statement under the whole view and ONE under a narrowing lens`() {
        val names = (1..ROW_COUNT).map { "finance/visualizations/bulk_$it" }
        names.forEach { h.createVisualization(it) }
        // Three of the page's visualizations are pinned by three RELEASED dashboards (D61's consent
        // releases each draft pin through its own gate); the other 197 names are honest empty answers.
        val boardNames = (0..2).map { n -> "finance/dashboards/bulk_pin_$n" }
        boardNames.forEachIndexed { n, boardName ->
            val document =
                h.dashboardDocument(1, name = boardName) { tree ->
                    (tree.get("visualizations").get(0).get("visualization") as ObjectNode).put("name", names[n])
                }
            val board = h.dashboards.create(WORKSPACE, document, AUTHOR, WriteSurface.MCP)
            h.dashboards.release(WORKSPACE, board.record.id, board.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)
        }

        // (a) the whole view: every live pin, one statement.
        counting.statements = 0
        val whole = countedService.pinnedByAll(WORKSPACE, ReadLens.Everything, names)
        counting.statements shouldBe 1
        whole[names[0]] shouldBe listOf("finance/dashboards/bulk_pin_0@1")
        whole[names[1]] shouldBe listOf("finance/dashboards/bulk_pin_1@1")
        whole[names[3]] shouldBe emptyList()

        // (b) a narrowing lens: the admitted dashboards' current RELEASED pins, still one statement —
        // the lens filters the dashboard names in memory, never in a second query. The hidden
        // dashboards' pins are absent (the 404 rule through the arm).
        counting.statements = 0
        val lensed = countedService.pinnedByAll(WORKSPACE, ReadLens.Only(setOf(boardNames[0])), names)
        counting.statements shouldBe 1
        lensed[names[0]] shouldBe listOf("finance/dashboards/bulk_pin_0@1")
        lensed[names[1]] shouldBe emptyList()
    }

    @Test
    fun `one name under the whole view is ONE containment probe - the statement V46 indexes, not the page scan`() {
        h.createVisualization("finance/visualizations/probed")
        val document =
            h.dashboardDocument(1, name = "finance/dashboards/probing") { tree ->
                (tree.get("visualizations").get(0).get("visualization") as ObjectNode).put("name", "finance/visualizations/probed")
            }
        val board = h.dashboards.create(WORKSPACE, document, AUTHOR, WriteSurface.MCP)
        h.dashboards.release(WORKSPACE, board.record.id, board.detail.bodyHash, AUTHOR, releasePinnedVisualizations = true)

        // `visualizations_get`'s `used_by` and the pin guards read ONE name: the `@>` probe over
        // `body_json -> 'visualizations'` — the expression `idx_dashboard_versions_pins` serves. The
        // page answer reads the pins whole and does not use the index; one name must not take that path.
        counting.statements = 0
        counting.prepared.clear()
        countedService.pinnedBy(WORKSPACE, ReadLens.Everything, "finance/visualizations/probed") shouldBe
            listOf("finance/dashboards/probing@1")
        counting.statements shouldBe 1
        counting.prepared.single() shouldContain "-> 'visualizations' @> CAST("
    }

    companion object {
        /** The page cap the tool answers (`ArtifactTools.MAX_LIMIT`); the bound is independent of it. */
        const val ROW_COUNT = 200

        /** The statement count the whole answer may cost — the #331 constant. */
        const val STATEMENT_BOUND = 1
    }

    /** The statement counter at the JDBC boundary — the `dag` module's `CountingDataSource` shape, no library. */
    private class CountingDataSource(
        private val target: DataSource,
    ) : DelegatingDataSource(target) {
        var statements = 0

        /** The SQL text of every statement prepared through this source, in order — the shape beside the count. */
        val prepared = mutableListOf<String>()

        override fun getConnection(): java.sql.Connection = wrap(target.connection)

        private fun wrap(connection: java.sql.Connection): java.sql.Connection =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                if (method.name == "prepareStatement") prepared.add(args[0] as String)
                val result = method.invoke(connection, *(args ?: emptyArray()))
                if (result is java.sql.PreparedStatement) statement(result) else result
            } as java.sql.Connection

        private fun statement(ps: java.sql.PreparedStatement): java.sql.PreparedStatement =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(java.sql.PreparedStatement::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch" -> statements++
                }
                method.invoke(ps, *(args ?: emptyArray()))
            } as java.sql.PreparedStatement
    }
}
