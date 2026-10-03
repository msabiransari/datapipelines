package co.datapipelines.parameters

import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.parameters.EvaluatorFixtures.attempt
import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The durable evaluation history against the SHIPPED schema (#376; spec §2, §12 scenarios 3, 5–7): V48 applied to the
 * module's Postgres, the real [StoredParameterEvaluationRecorder] over the real [ParameterEvaluationRepository], and
 * either a scripted selector runtime whose statements can be HELD (the once-per-attempt and overlap invariants are
 * read while a statement is in flight) or the real [SelectorRunner] (the scenario-6 arms). Every assertion reads the
 * rows the database holds — the persisted level is where a lost terminal write shows (the coroutine lesson).
 */
class ParameterEvaluationHistoryIntegrationTest {
    private val jdbc = ParametersTestDb.jdbc
    private val workspace = ParametersTestDb.WORKSPACE
    private val repository = ParameterEvaluationRepository(jdbc)
    private val recorder = StoredParameterEvaluationRecorder(repository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val release = CountDownLatch(1)
    private var harness: ParametersHarness? = null
    private var customers: CustomerRegistry? = null

    @BeforeEach
    fun seed() {
        ParametersTestDb.reset()
        // The fixture set's index row, so the history's FK to parameter_sets holds (the fixtures build versions in memory).
        jdbc.update(
            "INSERT INTO parameter_sets (id, workspace_id, name, display_name, created_by) VALUES (:id, :ws, :name, 'Region filters', :by)",
            mapOf(
                "id" to EvaluatorFixtures.SET_ID,
                "ws" to workspace,
                "name" to "acme/sales/region_filters",
                "by" to ParametersTestDb.AUTHOR,
            ),
        )
    }

    @AfterEach
    fun stop() {
        release.countDown()
        scope.cancel()
        customers?.close()
        harness?.engines?.close()
    }

    private fun evaluator(
        selectors: SelectorTasks,
        config: ParametersConfig = ParametersConfig(),
        pool: SelectorPool = SelectorPool(4, 64),
    ) = ParameterEvaluator(selectors, pool, config, recorder = recorder)

    private fun set(vararg parameters: String) = EvaluatorFixtures.version(*parameters, workspace = workspace)

    // ---- B: once per attempt -------------------------------------------------------------------------------

    @Test
    fun `while a statement is held the record is ONE running row with ONE query row - released, the terminal write lands once`() {
        val held = Holding(release)
        val evaluation = attempt()
        val job = scope.async { evaluator(held).evaluate(workspace, set(templateSelect("a")), emptyMap(), evaluation) }
        held.awaitStarted(1)

        withClue("in flight: exactly one RUNNING record and one open query row") {
            count("SELECT count(*) FROM parameter_evaluations") shouldBe 1
            status(evaluation.evaluationId) shouldBe "RUNNING"
            count("SELECT count(*) FROM parameter_evaluations WHERE finished_at IS NULL") shouldBe 1
            count("SELECT count(*) FROM parameter_evaluation_queries") shouldBe 1
            count("SELECT count(*) FROM parameter_evaluation_queries WHERE outcome IS NULL AND queued_at IS NOT NULL") shouldBe 1
        }
        release.countDown()
        val response = runBlocking { withTimeout(DONE_MS) { job.await() } }

        val row = evaluationRow(evaluation.evaluationId)
        row["status"] shouldBe "COMPLETED"
        row["valid"] shouldBe response.valid
        row["finished_at"] shouldNotBe null
        row["caller"] shouldBe "REST"
        row["principal_user_id"] shouldBe EvaluatorFixtures.USER
        row["parameter_set_version"] shouldBe 4
        count("SELECT count(*) FROM parameter_evaluations") shouldBe 1
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "EXECUTED"
            it["row_count"] shouldBe 1
            it["parameter"] shouldBe "a"
            it["datasource"] shouldBe "warehouse"
            (it["template_id"] to it["template_version"]) shouldBe ("acme/sales/a.sql" to 1)
        }

        // A second evaluate of the same set is a second record, under a fresh id (never re-issued, C29).
        val second = attempt()
        evaluator(Holding(release)).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), second)
        second.evaluationId shouldNotBe evaluation.evaluationId
        count("SELECT count(*) FROM parameter_evaluations") shouldBe 2
        count("SELECT count(*) FROM parameter_evaluation_queries") shouldBe 2
    }

    @Test
    fun `a second evaluate while the first is held - both records land complete (scenario 7 without a page)`() {
        val held = Holding(release)
        val first = attempt()
        val second = attempt()
        val one = scope.async { evaluator(held).evaluate(workspace, set(templateSelect("a")), emptyMap(), first) }
        held.awaitStarted(1)
        val two = scope.async { evaluator(held).evaluate(workspace, set(templateSelect("a")), emptyMap(), second) }
        held.awaitStarted(2)
        count("SELECT count(*) FROM parameter_evaluations WHERE status = 'RUNNING'") shouldBe 2

        release.countDown()
        runBlocking { withTimeout(DONE_MS) { one.await() to two.await() } }

        listOf(first, second).map { status(it.evaluationId) } shouldContainExactly listOf("COMPLETED", "COMPLETED")
        count("SELECT count(*) FROM parameter_evaluation_queries WHERE outcome = 'EXECUTED'") shouldBe 2
    }

    // ---- C: one query row per statement attempt, concurrency preserved -----------------------------------------

    @Test
    fun `two independent selectors overlap in the evidence - their started and ended stamps are never serialised`() {
        val bothStarted = CountDownLatch(2)
        val selectors = Rendezvous(bothStarted)
        val evaluation = attempt()

        evaluator(selectors).evaluateBlocking(workspace, set(templateSelect("a"), templateSelect("b")), emptyMap(), evaluation)

        val rows = queryRows(evaluation.evaluationId).associateBy { it["parameter"] }
        rows.keys shouldContainExactlyInAnyOrder setOf("a", "b")
        val a = rows.getValue("a")
        val b = rows.getValue("b")
        withClue("a.started < b.ended AND b.started < a.ended — the two ranges overlap") {
            (instant(a, "started_at") < instant(b, "ended_at")) shouldBe true
            (instant(b, "started_at") < instant(a, "ended_at")) shouldBe true
        }
        listOf(a, b).forEach { row ->
            (instant(row, "queued_at") <= instant(row, "started_at")) shouldBe true
            row["outcome"] shouldBe "EXECUTED"
        }
    }

    @Test
    fun `a constants selector and a plain default INPUT record no query row - a template selector beside them records one`() {
        val evaluation = attempt()

        evaluator(ScriptedSelectors()).evaluateBlocking(
            workspace,
            set(
                ParameterSetFixtures.countryJson(),
                ParameterSetFixtures.amountJson(),
                templateSelect("state", dependsOn = listOf("country")),
            ),
            emptyMap(),
            evaluation,
        )

        withClue("positive control: the template-backed parameter's statement IS a row") {
            queryRows(evaluation.evaluationId).map { it["parameter"] } shouldContainExactly listOf("state")
        }
        withClue("constants and free inputs are resolution steps: they ride outcomes_json, never a query row") {
            outcomes(evaluation.evaluationId).map { it.name } shouldContainExactly listOf("country", "min_order_amount", "state")
        }
    }

    @Test
    fun `a full bulkhead is a REFUSED attempt with selectors_saturated - never an executed one`() {
        val pool = SelectorPool(size = 1, waiting = 0)
        val occupant = scope.async { pool.run(SelectorLabel("x", "y", "z"), Holding(release).task(TemplateRef("occupant", 1))) }
        waitUntil { pool.admitted() == 1 }
        val evaluation = attempt()

        evaluator(ScriptedSelectors(), pool = pool).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), evaluation)

        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "REFUSED"
            it["refusal_code"] shouldBe ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED
            it["started_at"] shouldBe null
            it["row_count"] shouldBe null
        }
        status(evaluation.evaluationId) shouldBe "COMPLETED"
        release.countDown()
        runBlocking { withTimeout(DONE_MS) { occupant.await() } }
    }

    // ---- the whole-request endings -----------------------------------------------------------------------------

    @Test
    fun `an unknown selection key is refused before admission and records nothing`() {
        val refused =
            shouldThrow<DatapipelinesException> {
                evaluator(ScriptedSelectors()).evaluateBlocking(
                    workspace,
                    set(templateSelect("a")),
                    EvaluatorFixtures.selections("nope" to "x"),
                    attempt(),
                )
            }

        refused.code shouldBe ParameterErrorCodes.EVALUATE_UNKNOWN_PARAMETER
        count("SELECT count(*) FROM parameter_evaluations") shouldBe 0
        // Positive control: the same set with a valid body records.
        evaluator(ScriptedSelectors()).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), attempt())
        count("SELECT count(*) FROM parameter_evaluations") shouldBe 1
    }

    @Test
    fun `the evaluate deadline ends the record TIMEOUT with its code - the held statement TIMEOUT with no claim it stopped`() {
        val held = Holding(release)
        val evaluation = attempt()
        val oneSecond = ParametersConfig(evaluateTimeoutSeconds = 1, selectorQueryTimeoutSeconds = 1)

        val refused =
            shouldThrow<DatapipelinesException> {
                evaluator(held, oneSecond).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), evaluation)
            }

        refused.code shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT
        evaluationRow(evaluation.evaluationId).let {
            it["status"] shouldBe "TIMEOUT"
            it["outcome_code"] shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT
            it["valid"] shouldBe null
            it["finished_at"] shouldNotBe null
        }
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "TIMEOUT"
            it["started_at"] shouldNotBe null
            withClue("the worker may still be running: the row never stamps an end it did not see") { it["ended_at"] shouldBe null }
        }
    }

    @Test
    fun `the caller's cancellation ends the record ABORTED - the abandoned statement ABORTED, never a TIMEOUT`() {
        val held = Holding(release)
        val evaluation = attempt(EvaluationCaller.MCP)
        val job = scope.async { evaluator(held).evaluate(workspace, set(templateSelect("a")), emptyMap(), evaluation) }
        held.awaitStarted(1)

        job.cancel()
        runBlocking { withTimeout(DONE_MS) { runCatching { job.await() } } }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()

        evaluationRow(evaluation.evaluationId).let {
            it["status"] shouldBe "ABORTED"
            it["outcome_code"] shouldBe null
            it["caller"] shouldBe "MCP"
        }
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "ABORTED"
            it["ended_at"] shouldBe null
        }
    }

    @Test
    fun `a response over the budget is FAILED with its catalogued code - the statement itself EXECUTED`() {
        val selectors =
            ScriptedSelectors().apply {
                this["acme/sales/a.sql"] = {
                    SelectorRun.Rows(ScriptedSelectors.SELECT_COLUMNS, (1..150).map { listOf("x".repeat(1_000) + it, "option $it", false) })
                }
            }
        val evaluation = attempt()

        shouldThrow<DatapipelinesException> {
            evaluator(selectors, ParametersConfig(maxEvaluateResponseBytes = 65_536))
                .evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), evaluation)
        }.code shouldBe ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE

        evaluationRow(evaluation.evaluationId).let {
            it["status"] shouldBe "FAILED"
            it["outcome_code"] shouldBe ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE
        }
        queryRows(evaluation.evaluationId).single()["outcome"] shouldBe "EXECUTED"
    }

    @Test
    fun `a task that throws is a defect - the record FAILED with no code, its statement FAILED`() {
        val selectors = ScriptedSelectors().apply { this["acme/sales/a.sql"] = { error("a defect in the runtime") } }
        val evaluation = attempt()

        shouldThrow<IllegalStateException> {
            evaluator(selectors).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), evaluation)
        }

        evaluationRow(evaluation.evaluationId).let {
            it["status"] shouldBe "FAILED"
            it["outcome_code"] shouldBe null
        }
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "FAILED"
            it["error_code"] shouldBe null
        }
    }

    @Test
    fun `a key principal records its key id and no user - the person XOR key rule`() {
        val evaluation =
            EvaluationAttempt.of(
                EvaluationCaller.DASHBOARD,
                ParametersTestDb.AUTHOR,
                keyId = "dpk_abcdefghijkl",
                correlationId = "refresh-1",
            )

        evaluator(ScriptedSelectors()).evaluateBlocking(workspace, set(ParameterSetFixtures.countryJson()), emptyMap(), evaluation)

        evaluationRow(evaluation.evaluationId).let {
            (it["principal_user_id"] to it["principal_key_id"]) shouldBe (null to "dpk_abcdefghijkl")
            it["correlation_id"] shouldBe "refresh-1"
            it["caller"] shouldBe "DASHBOARD"
        }
    }

    @Test
    fun `the largest legal set's outcomes always fit the stored bound - the terminal write lands`() {
        storedOutcomes((1..256).map { "p".repeat(60) + it.toString().padStart(3, '0') }, "256 × 63-character names")
    }

    @Test
    fun `the tiniest entries - where JSONB spends the most per text byte - fit the stored bound too`() {
        storedOutcomes((1..256).map { "p" + it.toString().padStart(3, '0') }, "256 × 4-character names")
    }

    /** Every parameter a required INPUT with nothing to resolve — an `error` entry each, the encoding's worst content. */
    private fun storedOutcomes(
        names: List<String>,
        shape: String,
    ) {
        val evaluation = attempt()
        val parameters = names.map { """{ "name": "$it", "label": "L", "type": "STRING", "kind": "INPUT", "required": true }""" }
        val config = ParametersConfig(maxParametersPerSet = 256)
        val document =
            ParameterSetReader(
                config,
            ).readOrThrow(ParameterSetFixtures.tree(ParameterSetFixtures.setJson(*parameters.toTypedArray())))
        val largest = set(ParameterSetFixtures.countryJson()).copy(body = document.body)

        evaluator(ScriptedSelectors(), config).evaluateBlocking(workspace, largest, emptyMap(), evaluation)

        status(evaluation.evaluationId) shouldBe "COMPLETED"
        val stored = count("SELECT pg_column_size(outcomes_json) FROM parameter_evaluations WHERE id = '${evaluation.evaluationId}'")
        val text = count("SELECT octet_length(outcomes_json::text) FROM parameter_evaluations WHERE id = '${evaluation.evaluationId}'")
        // A freshly built datum is never TOAST-compressed: its pg_column_size is the binary JSONB size the CHECK would see
        // if compression did not apply (the stored size above is compressed — measured, not assumed).
        val uncompressed =
            count("SELECT pg_column_size(outcomes_json::text::jsonb) FROM parameter_evaluations WHERE id = '${evaluation.evaluationId}'")
        val kept = outcomes(evaluation.evaluationId)
        println(
            "OUTCOMES_JSON $shape, every one an error: stored=$stored bytes, uncompressed=$uncompressed bytes, " +
                "text=$text bytes, entries kept=${kept.size}",
        )
        stored shouldBeLessThanOrEqual 8_192
        uncompressed shouldBeLessThanOrEqual 8_192
        withClue("non-vacuity: the degradation keeps entries, it does not empty the list") { (kept.isNotEmpty()) shouldBe true }
        kept.all { it.outcome == ParameterOutcome.ERROR } shouldBe true
    }

    // ---- scenario 6, both arms, on the REAL runtime -------------------------------------------------------------

    @Test
    fun `scenario 6 - an unreachable datasource is a REFUSED attempt, distinguishable by column from an executed one`() {
        val real = realRuntime()
        val states =
            real.template(
                "acme/sales/states.sql",
                "SELECT code AS value, name AS display_value, FALSE AS is_default FROM sel.state ORDER BY 1",
            )
        customers().register(
            CustomerDb
                .datasource(jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nothing")
                .copy(properties = DatasourceProperties(hikari = mapOf("connectionTimeout" to UNREACHABLE_TIMEOUT_MS))),
        )
        val evaluation = attempt()

        val response =
            evaluator(
                SelectorRunner(real.engines, customers()),
            ).evaluateBlocking(workspace, set(select("state", states)), emptyMap(), evaluation)

        response.parameters
            .single()
            .state.errors
            .single()
            .code shouldBe "pipeline.execution.datasource_unreachable"
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "REFUSED"
            it["refusal_code"] shouldBe "pipeline.execution.datasource_unreachable"
            it["error_code"] shouldBe null
        }
        status(evaluation.evaluationId) shouldBe "COMPLETED"
        outcomes(evaluation.evaluationId).single().errorCode shouldBe "pipeline.execution.datasource_unreachable"
    }

    @Test
    fun `scenario 6 - a pg_sleep selector past the deadline is a TIMEOUT record and a TIMEOUT attempt`() {
        val real = realRuntime()
        val slow =
            real.template(
                "acme/sales/slow.sql",
                "SELECT 'B' AS value, 'B' AS display_value, TRUE AS is_default FROM pg_sleep(30) ORDER BY 1",
            )
        val twoSeconds = ParametersConfig(evaluateTimeoutSeconds = 2, selectorQueryTimeoutSeconds = 2)
        val evaluation = attempt()

        shouldThrow<DatapipelinesException> {
            evaluator(SelectorRunner(real.engines, customers(), twoSeconds), twoSeconds)
                .evaluateBlocking(workspace, set(select("slow", slow)), emptyMap(), evaluation)
        }.code shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT

        status(evaluation.evaluationId) shouldBe "TIMEOUT"
        queryRows(evaluation.evaluationId).single().let {
            it["outcome"] shouldBe "TIMEOUT"
            it["ended_at"] shouldBe null
        }
    }

    @Test
    fun `the save-time probe is authoring validation - it records nothing`() {
        val real = realRuntime()
        val states =
            real.template(
                "acme/sales/states.sql",
                "SELECT code AS value, name AS display_value, FALSE AS is_default FROM sel.state ORDER BY 1",
            )

        val created = real.create(real.document(ParameterSetFixtures.setJson(select("state", states), name = "acme/sales/probed")))

        withClue("the probe ran (the real runner leased a connection) and wrote no history") {
            (customers().leases.get() >= 1) shouldBe true
            count("SELECT count(*) FROM parameter_evaluations") shouldBe 0
            count("SELECT count(*) FROM parameter_evaluation_queries") shouldBe 0
        }
        // Positive control: an evaluate of the saved set does record.
        evaluator(SelectorRunner(real.engines, customers())).evaluateBlocking(workspace, created, emptyMap(), attempt())
        count("SELECT count(*) FROM parameter_evaluation_queries WHERE outcome = 'EXECUTED'") shouldBe 1
    }

    // ---- housekeeping statements ---------------------------------------------------------------------------------

    @Test
    fun `the stale sweep closes only old RUNNING rows INCOMPLETE - retention deletes only old finished rows, their queries cascading`() {
        val oldRunning = seedRecord("RUNNING", startedHoursAgo = 2)
        val youngRunning = seedRecord("RUNNING", startedHoursAgo = 0)
        val oldFinished = seedRecord("COMPLETED", startedHoursAgo = 24 * 8)
        val recentFinished = seedRecord("COMPLETED", startedHoursAgo = 24 * 6)
        seedQuery(oldFinished)

        repository.sweepStale(staleAfterSeconds = 90) shouldContainExactly listOf(oldRunning)
        repository.deleteFinishedOlderThan(retentionDays = 7, batchSize = 5_000) shouldBe 1

        status(oldRunning) shouldBe "INCOMPLETE"
        evaluationRow(oldRunning)["finished_at"] shouldNotBe null
        status(youngRunning) shouldBe "RUNNING"
        count("SELECT count(*) FROM parameter_evaluations WHERE id = '$oldFinished'") shouldBe 0
        count("SELECT count(*) FROM parameter_evaluation_queries WHERE evaluation_id = '$oldFinished'") shouldBe 0
        status(recentFinished) shouldBe "COMPLETED"
    }

    @Test
    fun `retention deletes one bounded batch per tick, oldest first`() {
        val ids = (1..3).map { seedRecord("COMPLETED", startedHoursAgo = 24L * 10 + it) }

        repository.deleteFinishedOlderThan(retentionDays = 7, batchSize = 2) shouldBe 2

        withClue("the newest of the three is the one left for the next tick") {
            count("SELECT count(*) FROM parameter_evaluations") shouldBe 1
            status(ids.first()) shouldBe "COMPLETED"
        }
    }

    // ---- the observed route's reuse read (#417) --------------------------------------------------------------------

    @Test
    fun `exists is true for a recorded id - after the START insert, whatever the record's status`() {
        val started = attempt()
        release.countDown() // nothing is held: the evaluate runs to its end, and the START insert is real, not seeded
        evaluator(Holding(release)).evaluateBlocking(workspace, set(templateSelect("a")), emptyMap(), started)
        val running = seedRecord("RUNNING", startedHoursAgo = 0)

        repository.exists(workspace, started.evaluationId) shouldBe true
        repository.exists(workspace, running) shouldBe true
    }

    @Test
    fun `exists is false for an id nothing recorded`() {
        seedRecord("COMPLETED", startedHoursAgo = 0)

        repository.exists(workspace, UUID.randomUUID()) shouldBe false
    }

    @Test
    fun `exists is false for a recorded id under another workspace id - no cross-workspace existence signal`() {
        val id = seedRecord("COMPLETED", startedHoursAgo = 0)
        val otherWorkspace = UUID.randomUUID()

        withClue("sanity: the record is there for its own workspace") { repository.exists(workspace, id) shouldBe true }
        repository.exists(otherWorkspace, id) shouldBe false
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun select(
        name: String,
        ref: TemplateRef,
    ): String =
        """
        { "name": "$name", "label": "${name.replaceFirstChar {
            it.uppercase()
        }}", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE",
          "source": { "template": { "id": "${ref.id}", "version": ${ref.version} }, "datasource": "warehouse" }, "depends_on": [] }
        """.trimIndent()

    private fun realRuntime(): ParametersHarness {
        CustomerDb.seed()
        customers = CustomerRegistry(CustomerDb.datasource())
        return ParametersHarness(customers = customers).also { harness = it }
    }

    private fun customers(): CustomerRegistry = requireNotNull(customers) { "realRuntime() first" }

    private fun seedRecord(
        status: String,
        startedHoursAgo: Long,
    ): UUID {
        val id = UUID.randomUUID()
        val started = Instant.now().minusSeconds(startedHoursAgo * SECONDS_PER_HOUR + 1)
        jdbc.update(
            "INSERT INTO parameter_evaluations (id, workspace_id, parameter_set_id, parameter_set_version, caller, principal_user_id," +
                " status, valid, started_at, finished_at) VALUES (:id, :ws, :set, 1, 'REST', :user, :status, :valid, :started, :finished)",
            mapOf(
                "id" to id,
                "ws" to workspace,
                "set" to EvaluatorFixtures.SET_ID,
                "user" to ParametersTestDb.AUTHOR,
                "status" to status,
                "valid" to (if (status == "COMPLETED") true else null),
                "started" to Timestamp.from(started),
                "finished" to (if (status == "RUNNING") null else Timestamp.from(started.plusSeconds(1))),
            ),
        )
        return id
    }

    private fun seedQuery(evaluation: UUID) {
        jdbc.update(
            "INSERT INTO parameter_evaluation_queries (id, evaluation_id, parameter, datasource, template_id, template_version, outcome)" +
                " VALUES (:id, :evaluation, 'a', 'warehouse', 'acme/sales/a.sql', 1, 'EXECUTED')",
            mapOf("id" to UUID.randomUUID(), "evaluation" to evaluation),
        )
    }

    private fun count(sql: String): Int = requireNotNull(jdbc.jdbcTemplate.queryForObject(sql, Int::class.java))

    private fun status(id: UUID): String? = evaluationRow(id)["status"] as String?

    private fun evaluationRow(id: UUID): Map<String, Any?> =
        jdbc.queryForList("SELECT * FROM parameter_evaluations WHERE id = :id", mapOf("id" to id)).single()

    private fun queryRows(evaluation: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT * FROM parameter_evaluation_queries WHERE evaluation_id = :id ORDER BY queued_at",
            mapOf("id" to evaluation),
        )

    private fun outcomes(id: UUID): List<ParameterOutcome> =
        requireNotNull(repository.find(workspace, EvaluatorFixtures.SET_ID, id, versions = null)) { "no record $id" }.outcomes

    private fun instant(
        row: Map<String, Any?>,
        column: String,
    ): Instant = (requireNotNull(row[column]) { "$column is null" } as Timestamp).toInstant()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DONE_MS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(POLL_MS)
        }
    }

    /** Tasks that block on [release] until the test lets them go — a statement held in flight, observable. */
    private class Holding(
        private val release: CountDownLatch,
    ) : SelectorTasks {
        private val started = AtomicInteger()

        override fun resolver(workspaceId: UUID) = DatasourceResolver { null }

        override fun task(
            request: SelectorRequest,
            resolver: DatasourceResolver,
        ): SelectorTask = task(request.template)

        fun task(template: TemplateRef): SelectorTask =
            object : SelectorTask {
                override fun run(): SelectorRun {
                    started.incrementAndGet()
                    // The stand-in ignores interrupts and abandons, like a driver that never returns until told to.
                    release.await(HOLD_MS, TimeUnit.MILLISECONDS)
                    return ScriptedSelectors.options("A-${template.id}")
                }

                override fun abandon() = Unit
            }

        fun awaitStarted(n: Int) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DONE_MS)
            while (started.get() < n) {
                check(System.nanoTime() < deadline) { "only ${started.get()} of $n statements started" }
                Thread.sleep(POLL_MS)
            }
        }
    }

    /** Each task waits until BOTH have started before it returns — two statements provably in flight together. */
    private class Rendezvous(
        private val bothStarted: CountDownLatch,
    ) : SelectorTasks {
        override fun resolver(workspaceId: UUID) = DatasourceResolver { null }

        override fun task(
            request: SelectorRequest,
            resolver: DatasourceResolver,
        ): SelectorTask =
            object : SelectorTask {
                override fun run(): SelectorRun {
                    bothStarted.countDown()
                    check(bothStarted.await(DONE_MS, TimeUnit.MILLISECONDS)) { "the two statements never ran together" }
                    return ScriptedSelectors.options("X")
                }

                override fun abandon() = Unit
            }
    }

    private companion object {
        const val DONE_MS = 15_000L
        const val HOLD_MS = 60_000L
        const val POLL_MS = 10L
        const val SECONDS_PER_HOUR = 3_600L
        const val UNREACHABLE_TIMEOUT_MS = 250
    }
}
