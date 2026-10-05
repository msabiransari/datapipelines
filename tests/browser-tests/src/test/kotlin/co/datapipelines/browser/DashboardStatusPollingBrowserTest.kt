package co.datapipelines.browser

import com.microsoft.playwright.Route
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Real GET observations must reject a completed false result before accepting a matching row.
 * Transport instrumentation forwards real responses; only a JDBC lock holds the terminal write.
 */
class DashboardStatusPollingBrowserTest : DashboardBrowserSuite() {
    @ParameterizedTest(name = "single refresh rejects {0}")
    @ValueSource(strings = ["RUNNING", "missing", "wrong-id"])
    fun `single refresh retries completed nonmatching GETs`(rejection: String) {
        exercisePoll(rejection, multiple = false)
    }

    @ParameterizedTest(name = "every holder rejects {0}")
    @ValueSource(strings = ["RUNNING", "missing", "wrong-id"])
    fun `every holder is read even when a ready row precedes a nonmatching row`(rejection: String) {
        exercisePoll(rejection, multiple = true)
    }

    @Test
    fun `owned dashboard sources contain no async waitForFunction status predicates`() {
        val module = Path.of("").toAbsolutePath()
        val directory = module.resolve("src/test/kotlin/co/datapipelines/browser")
        val asyncWait = Regex("waitForFunction\\(\\s*(?:\"\"\"|\")\\s*async\\b")
        val sources = listOf("DashboardBrowserSuite.kt", "DashboardConformanceBrowserTest.kt", "DashboardPageConformanceBrowserTest.kt")
        val offenders = sources.filter { asyncWait.containsMatchIn(Files.readString(directory.resolve(it))) }
        check(offenders.isEmpty()) { "async status polls in owned sources: $offenders" }
    }

    @Suppress("LongMethod") // the lock, abort, forwarded reads and final oracle are one ordered experiment
    private fun exercisePoll(
        rejection: String,
        multiple: Boolean,
    ) {
        val root = ready("dppoll")
        installHostPage()
        val board = seedHungBoard(root)
        openHost(board)
        val holders = startHolderStreams(board)
        val readyId = holders.first()
        val heldId = holders[1]
        val reads = mutableMapOf<String, MutableList<Observation>>()
        val readUrls = listOf(readyId, heldId).map { "**/api/v1/dashboards/$board/refreshes/$it" }
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { lock ->
            lock.autoCommit = false
            try {
                // Setup deliberately avoids the helpers under test: restoring an old helper must
                // fail the post-wait oracle, rather than outrun setup or time out elsewhere.
                page.waitForCondition { holders.map { readRow(board, it)["status"] }.all { it == "RUNNING" } }
                abortHolder(board, readyId)
                page.waitForCondition { readRow(board, readyId)["status"] == "ABORTED" }
                if (rejection == "RUNNING") holdRunningRow(lock, heldId)
                abortHolder(board, heldId)
                if (rejection != "RUNNING") {
                    page.waitForCondition { readRow(board, heldId)["status"] == "ABORTED" }
                }
                val missingId = UUID.randomUUID().toString()
                listOf(readyId, heldId).zip(readUrls).forEach { (id, url) ->
                    page.route(url) { route ->
                        val sequence = reads.getOrPut(id) { mutableListOf() }
                        if (id == heldId && sequence.isNotEmpty() && rejection == "RUNNING") lock.rollback()
                        val forwardedId =
                            if (id == heldId && sequence.isEmpty()) {
                                firstForwardedId(rejection, missingId, readyId, heldId)
                            } else {
                                id
                            }
                        // A refusal/wrong-id plant forwards another REAL route response unchanged.
                        // No status, error envelope or terminal row is synthesized here.
                        val response =
                            route.fetch(Route.FetchOptions().setUrl("$baseUrl/api/v1/dashboards/$board/refreshes/$forwardedId"))
                        try {
                            val data =
                                page.evaluate(
                                    "text => { const d = JSON.parse(text).data;" +
                                        " return { id: d && d.refresh_id, status: d && d.status }; }",
                                    response.text(),
                                ) as Map<*, *>
                            sequence.add(Observation(response.status(), data["id"] as String?, data["status"] as String?))
                            println("452 transport requested=$id GET=${sequence.size} observation=${sequence.last()}")
                            route.fulfill(Route.FulfillOptions().setResponse(response))
                        } finally {
                            response.dispose()
                        }
                    }
                }
                val requested = if (multiple) listOf(readyId, heldId) else listOf(heldId)
                if (multiple) {
                    awaitHolderRows(board, requested, "ABORTED")
                } else {
                    awaitRefreshStatus(board, heldId, "ABORTED")
                }
                assertCompletedObservations(requested, heldId, readyId, rejection, reads)
            } finally {
                lock.rollback()
                readUrls.forEach { page.unroute(it) }
                holders.forEach { abortHolder(board, it) }
            }
        }
    }

    private fun firstForwardedId(
        rejection: String,
        missingId: String,
        readyId: String,
        heldId: String,
    ): String =
        when (rejection) {
            "missing" -> missingId
            "wrong-id" -> readyId
            else -> heldId
        }

    // The oracle is outside the wait and is recorded by the forwarding transport.
    // An old Promise-valued predicate ends after the forced first false response.
    private fun assertCompletedObservations(
        requested: List<String>,
        heldId: String,
        readyId: String,
        rejection: String,
        reads: Map<String, List<Observation>>,
    ) {
        val evidence = requested.joinToString { id -> "requested=$id GETs=${reads[id]?.size ?: 0} last=${reads[id]?.lastOrNull()}" }
        val allMatched = requested.all { id -> reads[id]?.lastOrNull() == Observation(200, id, "ABORTED") }
        check(allMatched && reads[heldId].orEmpty().size >= 2) {
            "the status wait returned before matching completed GETs: $evidence"
        }
        val first = reads[heldId].orEmpty().first()
        when (rejection) {
            "RUNNING" -> check(first == Observation(200, heldId, "RUNNING")) { evidence }
            "missing" -> check(first == Observation(404, null, null)) { evidence }
            "wrong-id" -> check(first == Observation(200, readyId, "ABORTED")) { evidence }
        }
        println("452 terminal $evidence")
    }

    private fun holdRunningRow(
        lock: Connection,
        refreshId: String,
    ) {
        lock.autoCommit = false
        lock.prepareStatement("SELECT status FROM dashboard_refreshes WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, UUID.fromString(refreshId))
            statement.executeQuery().use { row ->
                check(row.next() && row.getString("status") == "RUNNING") { "the bound row must be RUNNING before abort" }
            }
        }
    }

    private data class Observation(
        val http: Int,
        val id: String?,
        val status: String?,
    )
}
