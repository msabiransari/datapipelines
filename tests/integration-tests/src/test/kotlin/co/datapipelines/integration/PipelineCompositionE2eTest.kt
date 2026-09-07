package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Pipeline composition E2E (design 2026-08-13-pipeline-node-type, §8): a `PIPELINE` node
 * executes a version-pinned child pipeline as a real, separate execution and consumes its
 * caller result directly (delivery mode `direct`, internal-only).
 *
 * Infrastructure: Postgres for the metadata DB (Flyway V1–V3 on startup, V3 carrying the
 * lineage columns) and Redis for the result store. The child's datasource is an **in-memory
 * H2** database — the app runs in this JVM, so the test seeds it over the same JDBC URL the
 * registered datasource uses. `DB_CLOSE_DELAY=-1` keeps the database alive across the app's
 * pooled connections; it is not a §5.6 refused key.
 *
 * Scenario 1 asserts the full contract: the parent's result rows equal the child's data,
 * exactly two execution rows exist for the family, the child row carries
 * `parent_execution_id`/`root_execution_id` = the parent's id and `triggered_via = 'PIPELINE'`,
 * and the parent's node stats (durable `node_stats_json` and the `node_completed` SSE event)
 * carry the child execution id. Scenario 2 runs a grandchild depth-3 chain; scenario 3 proves
 * the static save-time guard refuses a depth-6 chain with
 * `pipeline.validation.composition_too_deep` (max-composition-depth default 5).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PipelineCompositionE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `parent with PIPELINE node runs child as a linked execution and returns its rows`() {
        seedAuthRows()
        seedH2()
        registerH2Datasource()
        createTemplate(
            "test/comp_users.sql",
            "H2",
            "Composition Users",
            "SELECT id, email FROM comp_users ORDER BY id",
        )

        // Pipeline A (child): one DQL caller node over the H2 datasource.
        createPipeline(
            "test/comp_leaf",
            "Composition Leaf",
            listOf(
                mapOf(
                    "id" to "fetch_users",
                    "description" to "Fetch users from H2",
                    "type" to "DQL",
                    "source" to H2_DATASOURCE,
                    "template" to mapOf("id" to "test/comp_users.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        )

        // Pipeline B (parent): one PIPELINE node pinning A by name+version, result to caller.
        val parentId =
            createPipeline(
                "test/comp_parent",
                "Composition Parent",
                listOf(pipelineNode("run_leaf", "test/comp_leaf", 1)),
            )

        val cancelBudget = Duration.ofSeconds(CANCEL_BUDGET_SECONDS)
        val correlationId = UUID.randomUUID().toString()
        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, parentId, correlationId)
            }
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        val parentExecutionId = events.last().second["execution_id"].asText()

        // The parent's result rows equal the child's data (§4.2 direct delivery, caller target).
        assertResultRows(parentExecutionId)

        // Exactly TWO execution rows in the family, with the lineage links of §5.
        val childExecutionId = assertFamilyOfTwo(parentExecutionId)

        // F5 — the whole family is joinable by the id of the request that started it (rest-api
        // §3.4, observability §3.3). The child used to carry a fresh random id, which made
        // correlation id the one field that could not do the one job it exists for.
        queryExecutions(
            "SELECT correlation_id::text FROM pipeline_executions WHERE root_execution_id = '$parentExecutionId'",
        ) { it.getString(1) }.toSet() shouldBe setOf(correlationId)

        // The parent's node stats carry the child execution id — both the durable
        // node_stats_json and the SSE node_completed event (design §5/§7).
        assertNodeStatsCarryChild(parentExecutionId, childExecutionId)
        // Per-execution stats separation (design §8): the child's own stats record its DQL
        // node's rows, even though its result streamed `direct` to the parent.
        assertChildNodeStats(childExecutionId)
        val nodeCompleted = events.single { it.first == "node_completed" }.second
        nodeCompleted["node_id"].asText() shouldBe "run_leaf"
        nodeCompleted["child_execution_id"].asText() shouldBe childExecutionId
    }

    @Test
    @Order(2)
    fun `grandchild depth-3 chain succeeds with per-generation lineage`() {
        // comp_mid → comp_leaf (depth 2), comp_root → comp_mid (depth 3).
        createPipeline(
            "test/comp_mid",
            "Composition Mid",
            listOf(pipelineNode("run_leaf", "test/comp_leaf", 1)),
        )
        val rootId =
            createPipeline(
                "test/comp_root",
                "Composition Root",
                listOf(pipelineNode("run_mid", "test/comp_mid", 1)),
            )

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, rootId, UUID.randomUUID().toString())
            }
        events.last().first shouldBe "data_ready"
        val rootExecutionId = events.last().second["execution_id"].asText()

        assertResultRows(rootExecutionId)

        // Three rows: root, mid (child of root), leaf (child of mid); both children PIPELINE.
        val (familySize, children) = loadFamily(rootExecutionId)
        familySize shouldBe 3
        children.size shouldBe 2
        children.values.forEach { (parent, triggeredVia, status) ->
            parent shouldNotBe null
            triggeredVia shouldBe "PIPELINE"
            status shouldBe "SUCCESS"
        }
        // One generation each: mid's parent is the root, leaf's parent is mid's execution.
        val midExecutionId = children.entries.single { it.value.parent == rootExecutionId }.key
        val leafParent =
            children.entries
                .single { it.key != midExecutionId }
                .value
                .parent
        leafParent shouldBe midExecutionId
    }

    /** The execution family under a root: its size, and each non-root row's lineage triple. */
    private data class ChildRow(
        val parent: String?,
        val triggeredVia: String,
        val status: String,
    )

    private fun loadFamily(rootExecutionId: String): Pair<Int, Map<String, ChildRow>> {
        val familySize =
            queryExecutions(
                "SELECT count(*) FROM pipeline_executions WHERE root_execution_id = '$rootExecutionId'",
            ) { it.getInt(1) }
                .single()
        val children =
            queryExecutions(
                "SELECT execution_id::text, parent_execution_id::text, triggered_via, status " +
                    "FROM pipeline_executions WHERE root_execution_id = '$rootExecutionId' " +
                    "AND execution_id != '$rootExecutionId'",
            ) { rs ->
                rs.getString(1) to ChildRow(rs.getString(2), rs.getString(3), rs.getString(4))
            }.toMap()
        return familySize to children
    }

    /**
     * F1 at the level a user sees it: the child's `pipeline_executions` row.
     *
     * ## The machinery, named
     *
     * The child execution runs inside the parent PIPELINE node's coroutine. When the parent's
     * `withTimeout(datapipelines.executor.execution-timeout-seconds)` fires, **structured
     * concurrency** cancels the child's own `coroutineScope`, and it throws a
     * `JobCancellationException` — a type none of `PipelineExecutor.runExecution`'s catch clauses
     * match. So `emitTerminal` never ran, `WebEventEmitter.completeExecutionRow` never fired, and
     * the child's row stayed `status = RUNNING`, `completed_at = NULL` **permanently**, invisible
     * to every event-level assertion because no event was ever emitted to assert on.
     *
     * The parent-timeout case is the deterministic one: no cancel flag is ever set, so the child's
     * own `pollCancelFlag` cannot rescue it. This test therefore asserts on the ROW, not on events.
     */
    @Test
    @Order(4)
    fun `a child killed by the parent's timeout ends terminal in pipeline_executions, not RUNNING`() {
        val parentId = createSlowFamily("slow")

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, parentId, UUID.randomUUID().toString())
            }
        // The parent's own outcome is unchanged by this fix: a timeout is a FAILURE (§8.1).
        events.last().first shouldBe "pipeline_failed"
        events.last().second["error"]["code"].asText() shouldBe "pipeline.execution.timeout"
        val parentExecutionId = events.first().second["execution_id"].asText()

        val child =
            queryExecutions(
                "SELECT execution_id::text, status, completed_at FROM pipeline_executions " +
                    "WHERE parent_execution_id = '$parentExecutionId'",
            ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getTimestamp(3)) }.single()

        // The whole finding: without the structural-cancellation handler this row reads
        // ("<id>", "RUNNING", null) forever — a child a user sees as still running, hours later.
        child.second shouldBe "ABORTED"
        child.third shouldNotBe null

        queryExecutions("SELECT status FROM pipeline_executions WHERE execution_id = '$parentExecutionId'") {
            it.getString(1)
        }.single() shouldBe "FAILED"
    }

    /**
     * 086 A3 — a `DELETE` on the parent ends the CHILD, promptly and as a cancellation.
     *
     * The child of a PIPELINE node runs as its own execution with its own `CancellationHandle`,
     * and `DELETE /executions/{parent}` never touches it: the family's cancel sets an abort reason
     * on the ROOT's handle only, so the child's `withStatement` entry guard — which reads the
     * child's own reason — cannot fire. What stops the child is structured concurrency: the
     * parent's job cancels the child's scope, whose `pollCancelFlag` `finally` then calls
     * `cancelStatements()` on the child's handle. Both halves of A1 ride that route — the latch's
     * second read is the coroutine's own liveness (true for the child the instant the ancestor
     * cancels), and the re-issue hangs off `cancelStatements()` itself.
     *
     * ## What this pins, and what it does NOT
     *
     * It pins the family property: a `DELETE` on the parent ends the child `ABORTED` with a
     * completed row, and the parent's terminal event is `execution_aborted`/`cancelled` inside
     * [CANCEL_BUDGET_SECONDS] — not a `pipeline_failed`/timeout at the context's 15-second
     * deadline, which is what a child left running would produce, because the parent's
     * `coroutineScope` cannot rethrow until every child coroutine has finished and the child's is
     * blocked inside `executeQuery`. Until this test the `DELETE` trigger had no composition
     * coverage at all; only the parent-*timeout* trigger did (@Order(4)).
     *
     * It does **not** falsify A1: measured, it stays green with the latch and the re-issue both
     * reverted. The DELETE cannot be aimed at the child's registration window from out here —
     * by the time it lands the child's statement is executing and H2 honours the cancel on its
     * own. Pinning the window needs the descheduling injected, which only the `dag`-level tests
     * can do (`CancellationTest`'s two race cases and `CancelRaceStressTest`). Saying so is the
     * point: this is a regression test for the family path, not evidence for the race fix.
     */
    @Test
    @Order(5)
    fun `a DELETE on the parent aborts a child whose statement is still in the registration window`() {
        val parentId = createSlowFamily("cancel")

        val cancelBudget = Duration.ofSeconds(CANCEL_BUDGET_SECONDS)
        val correlationId = UUID.randomUUID().toString()
        val stream = CompletableFuture.supplyAsync { consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, parentId, correlationId) }

        // The child's row is written as the child execution starts — before its node runs, and so
        // before the statement exists. Cancelling on that signal puts the DELETE inside the window
        // rather than politely after it. The parent is found by its CORRELATION id: a subquery
        // into `pipelines` would silently correlate (that table's key is `id`, so `pipeline_id`
        // resolves to the OUTER query's column and the predicate becomes a tautology).
        val parentExecutionId = awaitExecution("correlation_id = '$correlationId' AND parent_execution_id IS NULL", cancelBudget)
        awaitExecution("parent_execution_id = '$parentExecutionId'", cancelBudget)

        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .delete("/api/v1/executions/$parentExecutionId")
            .then()
            .statusCode(204)

        val events = stream.get(CANCEL_BUDGET_SECONDS, TimeUnit.SECONDS)

        // A cancel, not a deadline: the reason names the DELETE, and arriving at all inside the
        // budget means the child's statement was really interrupted.
        events.last().first shouldBe "execution_aborted"
        events.last().second["reason"].asText() shouldBe "cancelled"

        val child =
            queryExecutions(
                "SELECT status, completed_at FROM pipeline_executions WHERE parent_execution_id = '$parentExecutionId'",
            ) { rs -> rs.getString(1) to rs.getTimestamp(2) }.single()
        child.first shouldBe "ABORTED"
        child.second shouldNotBe null
    }

    @Test
    @Order(3)
    fun `a depth-6 chain is refused at save with composition_too_deep`() {
        // comp_root is depth 3; comp_d4 → depth 4, comp_d5 → depth 5 (the configured max),
        // comp_d6 → depth 6 must fail validation at save (§12.9, static reference-tree walk).
        createPipeline("test/comp_d4", "Composition Depth 4", listOf(pipelineNode("run_root", "test/comp_root", 1)))
        createPipeline("test/comp_d5", "Composition Depth 5", listOf(pipelineNode("run_d4", "test/comp_d4", 1)))

        postPipeline("test/comp_d6", "Composition Depth 6", listOf(pipelineNode("run_d5", "test/comp_d5", 1)))
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo("pipeline.validation.composition_too_deep"))
    }

    // -------------------------------------------- 078 A5-composition: parent calculator → child input

    /**
     * The fixture family for the mapping legs: a child whose DQL node filters a SECOND H2
     * datasource by `:run_fiscal_quarter` — a value its own CALCULATOR node computes from
     * `$current_date` with a `01-01` fiscal start, unless a caller (here: the parent's mapping)
     * supplies the key.
     */
    private fun seedQuarterFamily(): String {
        seedH2Second()
        registerH2Datasource(H2_DATASOURCE_2, H2_JDBC_URL_2)
        createTemplate(
            "test/comp_quarter_rows.sql",
            "H2",
            "Composition Quarter Rows",
            "SELECT id, label FROM comp_quarters WHERE quarter = :run_fiscal_quarter ORDER BY id",
        )
        return createPipeline(
            "test/comp_quarter_child",
            "Composition Quarter Child",
            listOf(
                calculatorNode("fq", "run_fiscal_quarter", "01-01"),
                mapOf(
                    "id" to "fetch_quarter",
                    "description" to "Rows of the run quarter, from the SECOND datasource",
                    "type" to "DQL",
                    "source" to H2_DATASOURCE_2,
                    "template" to mapOf("id" to "test/comp_quarter_rows.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to listOf("fq"),
                ),
            ),
        )
    }

    private fun calculatorNode(
        id: String,
        contextKey: String,
        fiscalStart: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Fiscal quarter of the run date, $fiscalStart start",
            "type" to "CALCULATOR",
            "kind" to "fiscal_quarter",
            "inputs" to mapOf("date" to "\$current_date", "fiscal_start" to fiscalStart),
            "context_key" to contextKey,
            "depends_on" to emptyList<String>(),
        )

    /**
     * 078 A5-composition, legs (a) and (b): the parent's calculator output is mapped EXPLICITLY
     * onto the child's calculator `context_key`, so the child's node is skipped and the child's
     * SQL binds the supplied value — and the child runs on a DIFFERENT datasource than the
     * parent's own SQL node (the spec's cross-source leg): the parent reads `h2-comp`, the
     * child `h2-comp-2`.
     *
     * The fiscal starts make the two computations disagree (2026-09: quarter 1 on a 07-01
     * start, 3 on a 01-01 start), so the rows prove WHOSE value the child bound.
     */
    @Test
    @Order(5)
    fun `a parent calculator output mapped into the child skips the child's node and binds the supplied value`() {
        seedQuarterFamily()

        val parentId =
            createPipeline(
                "test/comp_quarter_parent",
                "Composition Quarter Parent",
                listOf(
                    calculatorNode("parent_q", "parent_quarter", "07-01"),
                    // The parent's own SQL node reads the FIRST datasource — the child below
                    // reads the second (the B4 cross-source leg).
                    mapOf(
                        "id" to "parent_users",
                        "description" to "Parent-side read of h2-comp",
                        "type" to "DQL",
                        "source" to H2_DATASOURCE,
                        "template" to mapOf("id" to "test/comp_users.sql", "version" to 1),
                        "output" to mapOf("target" to "tempdb", "table" to "stg_comp_users"),
                        "depends_on" to emptyList<String>(),
                    ),
                    mapOf(
                        "id" to "run_child",
                        "description" to "Invoke test/comp_quarter_child v1 with the parent's quarter",
                        "type" to "PIPELINE",
                        "pipeline" to mapOf("name" to "test/comp_quarter_child", "version" to 1),
                        "parameters" to mapOf("run_fiscal_quarter" to "\${parent_quarter}"),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to listOf("parent_q"),
                    ),
                ),
            )

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, parentId, UUID.randomUUID().toString())
            }
        events.last().first shouldBe "data_ready"
        val parentExecutionId = events.last().second["execution_id"].asText()

        // (a) the child consumed the SUPPLIED value: the quarter-1 rows, computed by the
        // parent's calculator — not the 3 the child's own node would have computed.
        assertQuarterRows(parentExecutionId, QUARTER_ONE_ROWS)

        // The parent execution's stats carry its calculator AND its own ds1 DQL node.
        val parentStats = nodeStats(parentExecutionId)
        parentStats.map { it["node_id"].asText() }.toSet() shouldBe setOf("parent_q", "parent_users", "run_child")

        // The child's calculator was SKIPPED: provided_by "caller", the supplied value reported.
        val childExecutionId =
            queryExecutions(
                "SELECT execution_id::text FROM pipeline_executions WHERE parent_execution_id = '$parentExecutionId'",
            ) { it.getString(1) }.single()
        val childStats = nodeStats(childExecutionId)
        val skipped = childStats.single { it["node_id"].asText() == "fq" }
        skipped["status"].asText() shouldBe "SUCCESS"
        skipped["provided_by"].asText() shouldBe "caller"
        skipped["context_value"].asText() shouldBe "1"
        childStats.single { it["node_id"].asText() == "fetch_quarter" }["rows_out"].asLong() shouldBe
            QUARTER_ONE_ROWS.size.toLong()
    }

    /** Leg (c): the SAME child, run standalone with no mapping, computes the value itself. */
    @Test
    @Order(6)
    fun `the child run standalone computes the quarter itself`() {
        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, childPipelineId(), UUID.randomUUID().toString())
            }
        events.last().first shouldBe "data_ready"
        val executionId = events.last().second["execution_id"].asText()

        // 2026-09 on a 01-01 fiscal start is quarter 3 — the child's own computation.
        assertQuarterRows(executionId, QUARTER_THREE_ROWS)
        val computed = nodeStats(executionId).single { it["node_id"].asText() == "fq" }
        computed["provided_by"] shouldBe null
        computed["context_value"].asText() shouldBe "3"
    }

    /**
     * Leg (d), the NO AUTO-PASSTHROUGH pin (owner ruling 2026-09-05: hidden coupling): the
     * parent's calculator key is spelled EXACTLY like the child's, and nothing is mapped — the
     * child's node RUNS and computes its own value. Only explicit mappings cross the boundary.
     */
    @Test
    @Order(7)
    fun `an identically named parent calculator key is not implicitly passed to the child`() {
        val parentId =
            createPipeline(
                "test/comp_echo_parent",
                "Composition Echo Parent",
                listOf(
                    // Same context_key spelling as the child's `fq` — deliberately.
                    calculatorNode("parent_q", "run_fiscal_quarter", "07-01"),
                    mapOf(
                        "id" to "run_child",
                        "description" to "Invoke test/comp_quarter_child v1 mapping NOTHING",
                        "type" to "PIPELINE",
                        "pipeline" to mapOf("name" to "test/comp_quarter_child", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to listOf("parent_q"),
                    ),
                ),
            )

        val events =
            assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
                consumeExecutionStream(port, ADMIN_KEY.plaintext, mapper, parentId, UUID.randomUUID().toString())
            }
        events.last().first shouldBe "data_ready"
        val parentExecutionId = events.last().second["execution_id"].asText()

        // The child computed its OWN quarter (3 on its 01-01 start) — the parent's identically
        // named value (1) never crossed, mapping or no shared spelling.
        assertQuarterRows(parentExecutionId, QUARTER_THREE_ROWS)
        val childExecutionId =
            queryExecutions(
                "SELECT execution_id::text FROM pipeline_executions WHERE parent_execution_id = '$parentExecutionId'",
            ) { it.getString(1) }.single()
        val ran = nodeStats(childExecutionId).single { it["node_id"].asText() == "fq" }
        ran["provided_by"] shouldBe null
        ran["context_value"].asText() shouldBe "3"
    }

    /** The quarter child's id, looked up by name through the metadata DB (created in Order 5). */
    private fun childPipelineId(): String =
        queryExecutions("SELECT id::text FROM pipelines WHERE name = 'test/comp_quarter_child'") { it.getString(1) }.single()

    /** `node_stats_json` of one execution, parsed. */
    private fun nodeStats(executionId: String): List<JsonNode> =
        queryExecutions(
            "SELECT node_stats_json::text FROM pipeline_executions WHERE execution_id = '$executionId'",
        ) { rs -> mapper.readTree(rs.getString(1)).toList() }.single()

    /** The execution's result rows equal [expected] `(id, label)` pairs, in `id` order. */
    private fun assertQuarterRows(
        executionId: String,
        expected: List<Pair<Int, String>>,
    ) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe expected.size.toLong()
        val rows: List<List<Any?>> = resultResponse.jsonPath().get("data.rows")
        rows.map { (it[0] as Number).toInt() } shouldContainExactly expected.map { it.first }
        rows.map { it[1] } shouldContainExactly expected.map { it.second }
    }

    // ------------------------------------------------------------ helpers

    /**
     * A parent → child family over [SLOW_H2_SQL], named by [suffix]; returns the parent's id.
     *
     * Both long-running scenarios need the identical shape and differ only in what stops it —
     * the parent's deadline (@Order(4)) or a `DELETE` on the parent (@Order(5), 086 A3).
     */
    private fun createSlowFamily(suffix: String): String {
        createTemplate("test/comp_$suffix.sql", "H2", "Composition ${suffix.replaceFirstChar { it.uppercase() }}", SLOW_H2_SQL)
        createPipeline(
            "test/comp_${suffix}_leaf",
            "Composition $suffix Leaf",
            listOf(
                mapOf(
                    "id" to "slow_scan",
                    "description" to "A query H2 really iterates, so the stop lands mid-flight",
                    "type" to "DQL",
                    "source" to H2_DATASOURCE,
                    "template" to mapOf("id" to "test/comp_$suffix.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        )
        return createPipeline(
            "test/comp_${suffix}_parent",
            "Composition $suffix Parent",
            listOf(pipelineNode("run_${suffix}_leaf", "test/comp_${suffix}_leaf", 1)),
        )
    }

    private fun pipelineNode(
        id: String,
        childName: String,
        childVersion: Int,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Invoke $childName v$childVersion",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to childName, "version" to childVersion),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    /** §7.2 — the result cursor returns exactly the seeded H2 rows, in `id` order. */
    private fun assertResultRows(executionId: String) {
        val resultResponse =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        resultResponse.jsonPath().getLong("data.total_rows") shouldBe SEED_EMAILS.size.toLong()
        val rows: List<List<Any?>> = resultResponse.jsonPath().get("data.rows")
        rows.map { (it[0] as Number).toInt() } shouldContainExactly listOf(1, 2)
        rows.map { it[1] } shouldContainExactly SEED_EMAILS
    }

    /**
     * §5 — the family of a root execution: two rows total; the child row links
     * `parent_execution_id` and `root_execution_id` to the parent and records
     * `triggered_via = 'PIPELINE'`; the root's own root is itself with no parent.
     * Returns the child execution id.
     */
    private fun assertFamilyOfTwo(parentExecutionId: String): String {
        var childExecutionId = ""
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) FROM pipeline_executions WHERE root_execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 2
                    }
                statement
                    .executeQuery(
                        "SELECT root_execution_id::text, parent_execution_id::text FROM pipeline_executions " +
                            "WHERE execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe parentExecutionId
                        rs.getString(2) shouldBe null
                    }
                statement
                    .executeQuery(
                        "SELECT execution_id::text, parent_execution_id::text, root_execution_id::text, " +
                            "triggered_via, parent_node_id, status, result_row_count FROM pipeline_executions " +
                            "WHERE parent_execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        childExecutionId = rs.getString(1)
                        rs.getString(2) shouldBe parentExecutionId
                        rs.getString(3) shouldBe parentExecutionId
                        rs.getString(4) shouldBe "PIPELINE"
                        rs.getString(5) shouldBe "run_leaf"
                        rs.getString(6) shouldBe "SUCCESS"
                        // `direct` delivery: nothing is materialized for the child (design §4.2),
                        // so there is no stored result to count — the delivered rows are visible
                        // in the child's own node stats (asserted below).
                        rs.getLong(7)
                        rs.wasNull() shouldBe true
                    }
            }
        }
        return childExecutionId
    }

    /** §5 — the parent execution's durable `node_stats_json` carries `child_execution_id`. */
    private fun assertNodeStatsCarryChild(
        parentExecutionId: String,
        childExecutionId: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT node_stats_json::text FROM pipeline_executions WHERE execution_id = '$parentExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        val stats = mapper.readTree(rs.getString(1))
                        stats.size() shouldBe 1
                        val node = stats[0]
                        node["node_id"].asText() shouldBe "run_leaf"
                        node["status"].asText() shouldBe "SUCCESS"
                        node["child_execution_id"].asText() shouldBe childExecutionId
                    }
            }
        }
    }

    /** §5/§8 — the child execution's own stats: its DQL caller node succeeded with the seeded rows. */
    private fun assertChildNodeStats(childExecutionId: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT node_stats_json::text FROM pipeline_executions WHERE execution_id = '$childExecutionId'",
                    ).use { rs ->
                        rs.next() shouldBe true
                        val stats = mapper.readTree(rs.getString(1))
                        stats.size() shouldBe 1
                        val node = stats[0]
                        node["node_id"].asText() shouldBe "fetch_users"
                        node["status"].asText() shouldBe "SUCCESS"
                        node["rows_out"].asLong() shouldBe SEED_EMAILS.size.toLong()
                    }
            }
        }
    }

    private fun registerH2Datasource(
        name: String = H2_DATASOURCE,
        jdbcUrl: String = H2_JDBC_URL,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$name", "display_name": "Composition H2", "dialect": "H2",
                 "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        dialect: String,
        displayName: String,
        body: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "$dialect", "display_name": "$displayName",
                 "description": "Composition E2E template", "imports": [],
                 "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val response = postPipeline(name, displayName, nodes)
        if (response.statusCode() != 201) {
            throw AssertionError(
                "Pipeline '$name' creation failed (status=${response.statusCode()}): ${response.body().asString()}",
            )
        }
        return response.jsonPath().getString("data.id")
    }

    private fun postPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): Response {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to displayName,
                    "description" to "Composition E2E pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(bodyJson)
            .`when`()
            .post("/api/v1/pipelines")
    }

    /** The H2 seed runs before datasource registration: first connection creates the database. */
    private fun seedH2() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE comp_users (id INT PRIMARY KEY, email VARCHAR(255) NOT NULL)")
                statement.execute(
                    "INSERT INTO comp_users (id, email) VALUES (1, '${SEED_EMAILS[0]}'), (2, '${SEED_EMAILS[1]}')",
                )
            }
        }
    }

    /** The SECOND H2 database (078 A5-composition's cross-source leg): the quarter-tagged rows. */
    private fun seedH2Second() {
        DriverManager.getConnection(H2_JDBC_URL_2, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE comp_quarters (id INT PRIMARY KEY, label VARCHAR(255) NOT NULL, quarter INT NOT NULL)",
                )
                QUARTER_ONE_ROWS.forEach { (id, label) ->
                    statement.execute("INSERT INTO comp_quarters (id, label, quarter) VALUES ($id, '$label', 1)")
                }
                QUARTER_THREE_ROWS.forEach { (id, label) ->
                    statement.execute("INSERT INTO comp_quarters (id, label, quarter) VALUES ($id, '$label', 3)")
                }
            }
        }
    }

    private fun seedAuthRows() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-composition@datapipelines.test', 'E2E Composition', 'test', 'e2e-comp-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001')",
                ).use { ps ->
                    ps.setString(1, ADMIN_KEY.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, ADMIN_KEY.name)
                    ps.setString(4, ADMIN_KEY.hash)
                    ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                    ps.executeUpdate()
                }
        }
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val SSE_BUDGET_MINUTES = 2L

        /**
         * 086 A3's budget. Below the context's 15-second execution deadline, so a run that reaches
         * this bound cannot have been rescued by the parent's timeout, and far below the child's
         * own 60-second `node-query-timeout-seconds` backstop.
         */
        private const val CANCEL_BUDGET_SECONDS = 12L
        private const val API_KEY_HEADER = "DP-API-Key"

        private const val H2_DATASOURCE = "h2-comp"
        private const val H2_JDBC_URL = "jdbc:h2:mem:compdb;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        /** 078 A5-composition's cross-source leg: the child's datasource, a second database. */
        private const val H2_DATASOURCE_2 = "h2-comp-2"
        private const val H2_JDBC_URL_2 = "jdbc:h2:mem:compdb2;DB_CLOSE_DELAY=-1"

        /** `comp_quarters` rows by quarter, in `id` order — the two computations disagree by design. */
        private val QUARTER_ONE_ROWS = listOf(1 to "q1-alpha", 2 to "q1-beta")
        private val QUARTER_THREE_ROWS = listOf(3 to "q3-gamma")

        /**
         * The execution deadline for this app context, lowered so the parent-timeout scenario is a
         * test rather than a ten-minute wait. Comfortably above scenarios 1–3 (milliseconds of H2
         * work each) and comfortably below `node-query-timeout-seconds` (60, unchanged), so the
         * child is killed by the PARENT's deadline and not by its own statement timeout.
         */
        private const val EXECUTION_TIMEOUT_SECONDS = 15

        /**
         * A query H2 genuinely iterates — the `Fixtures.SLOW_SQL` shape from `dag`'s cancellation
         * suite, scaled up. `SELECT COUNT(*) FROM SYSTEM_RANGE(...)` alone is answered from the
         * range's cardinality without visiting a row; the cross join plus a predicate forces ~3.6·10⁹
         * row visits (minutes), so the parent's 15-second deadline always lands mid-flight.
         * `a."X"` is quoted because `SYSTEM_RANGE`'s column is `X`.
         */
        private const val SLOW_H2_SQL =
            """SELECT COUNT(*) AS n FROM SYSTEM_RANGE(1, 60000) a, SYSTEM_RANGE(1, 60000) b """ +
                """WHERE MOD(a."X" + b."X", 7) = 0"""

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** Seed rows in `id` order, the order the template returns them. */
        private val SEED_EMAILS = listOf("first@datapipelines.test", "second@datapipelines.test")

        private val random = SecureRandom()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-composition-key", arrayOf("admin"))

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("datapipelines.executor.execution-timeout-seconds") { EXECUTION_TIMEOUT_SECONDS }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}

/**
 * Runs a metadata-DB query against the Testcontainers Postgres, collecting every row.
 *
 * At file scope because it needs only the shared container, and keeping it out of the test class: they need only the
 * shared container, and keeping them out of the test class keeps that class inside detekt's
 * `LargeClass` bound as scenarios are added.
 */
private fun <T> queryExecutions(
    sql: String,
    read: (java.sql.ResultSet) -> T,
): List<T> =
    DriverManager
        .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() }
            }
        }

/** Polls `pipeline_executions` for the single row matching [predicate], and returns its id. */
private fun awaitExecution(
    predicate: String,
    budget: Duration,
): String {
    val deadline = System.nanoTime() + budget.toNanos()
    while (System.nanoTime() < deadline) {
        queryExecutions("SELECT execution_id::text FROM pipeline_executions WHERE $predicate") { it.getString(1) }
            .firstOrNull()
            ?.let { return it }
        Thread.sleep(EXECUTION_POLL_MS)
    }
    val dump =
        queryExecutions(
            "SELECT execution_id::text, status, parent_execution_id::text, triggered_via FROM pipeline_executions " +
                "ORDER BY started_at DESC LIMIT 10",
        ) { rs -> "${rs.getString(1)} ${rs.getString(2)} parent=${rs.getString(3)} via=${rs.getString(4)}" }
    throw AssertionError("no pipeline_executions row appeared for: $predicate; recent rows:\n" + dump.joinToString("\n"))
}

private const val EXECUTION_POLL_MS = 25L

/**
 * Reads the SSE stream to its end (EOF is the completion signal — see TracerBulletE2eTest),
 * returning (event name, payload) pairs. Callers wrap this in assertTimeoutPreemptively:
 * the client sets no read timeout of its own.
 */
private fun consumeExecutionStream(
    port: Int,
    apiKey: String,
    mapper: ObjectMapper,
    pipelineId: String,
    correlationId: String,
): List<Pair<String, JsonNode>> {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
            .header("DP-API-Key", apiKey)
            .header("DP-Correlation-Id", correlationId)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
            .build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    response.statusCode() shouldBe 200

    return E2eSse.parseEvents(response.body(), mapper)
}
