package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceAuditEvent
import co.datapipelines.datasources.DatasourceAuditEvents
import co.datapipelines.datasources.DatasourceAuditSink
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The executor's small collaborators: config resolution (§5.3), identifier safety (§6.4.3),
 * stats collection (§7.2), the bounded dispatcher (§15.2), idempotency hashing (§11.2), and the
 * audit-cause wrapper (datasources §7.4).
 */
class ExecutorPrimitivesTest {
    // ------------------------------------------------------------------ config

    @Test
    fun `a datasource query timeout overrides the executor default, and null falls back`() {
        val config = ExecutorConfig(nodeQueryTimeoutSeconds = 60)

        config.queryTimeoutSecondsFor(5) shouldBe 5
        config.queryTimeoutSecondsFor(null) shouldBe 60
        // 0 means "no timeout" in JDBC and is a legitimate datasource setting — it must not be
        // treated as absent and silently replaced by the executor default.
        config.queryTimeoutSecondsFor(0) shouldBe 0
    }

    @Test
    fun `the result TTL is clamped, and an absent request uses the default`() {
        val result = ResultConfig(ttlDefaultSeconds = 300, ttlMinSeconds = 60, ttlMaxSeconds = 3600)

        result.effectiveTtlSeconds(null) shouldBe 300
        result.effectiveTtlSeconds(1) shouldBe 60
        result.effectiveTtlSeconds(99_999) shouldBe 3600
        result.effectiveTtlSeconds(600) shouldBe 600
    }

    @Test
    fun `the cursor limit defaults to the page size and is capped at page-max-rows`() {
        val result = ResultConfig(pageSizeRows = 1000, pageMaxRows = 100_000)

        result.effectiveLimit(null) shouldBe 1000
        result.effectiveLimit(0) shouldBe 1
        result.effectiveLimit(250_000) shouldBe 100_000
    }

    @Test
    fun `a request declares how it was triggered, defaulting to the programmatic value`() {
        // enums.md §18 / metadata-db §4.6: the CHECK constraint admits UI, REST and MCP only, and
        // this field is what the execution recorder writes into `triggered_via`.
        val pipeline = Fixtures.pipeline(listOf(Fixtures.node("a")))

        Fixtures.request(pipeline).triggeredVia shouldBe ExecutionTrigger.REST
        Fixtures.request(pipeline).copy(triggeredVia = ExecutionTrigger.MCP).triggeredVia shouldBe ExecutionTrigger.MCP
        Fixtures.request(pipeline).copy(triggeredVia = ExecutionTrigger.UI).triggeredVia shouldBe ExecutionTrigger.UI
    }

    @Test
    fun `the per-execution render budget never exceeds the engine-wide backstop`() {
        // Wiring a per-execution budget must never *raise* the global ceiling: the default 1024 MB
        // staging budget is ≈536M chars, well past the engine's 64M backstop.
        val config = ExecutorConfig(stagingMaxMemoryMb = 1024)

        config.renderOutputBudgetChars() shouldBe ExecutorConfig.ENGINE_OUTPUT_BACKSTOP_CHARS
        // A small pipeline-level override does bind, because it is below the backstop.
        config.renderOutputBudgetChars(effectiveStagingMaxMemoryMb = 1) shouldBe 1024L * 1024 / Char.SIZE_BYTES
    }

    @Test
    fun `nonsensical configuration is refused at construction`() {
        shouldThrow<IllegalArgumentException> { ExecutorConfig(maxParallelNodes = 0) }
        shouldThrow<IllegalArgumentException> { ExecutorConfig(executionTimeoutSeconds = 0) }
        shouldThrow<IllegalArgumentException> { ExecutorConfig(cancelPollIntervalSeconds = 0) }
        shouldThrow<IllegalArgumentException> { ExecutorConfig(maxCompositionDepth = 0) }
        shouldThrow<IllegalArgumentException> { ResultConfig(ttlMinSeconds = 100, ttlMaxSeconds = 10) }
        shouldThrow<IllegalArgumentException> { ResultConfig(pageSizeRows = 10, pageMaxRows = 5) }
    }

    // ------------------------------------------------------------- identifiers

    /**
     * F11: the payloads that discriminate `matches()` from `find()`.
     *
     * The old list held only payloads that fail under *both* semantics, so it could not have caught
     * a refactor from `IDENTIFIER.matches(label)` to `containsMatchIn(label)` — and that refactor is
     * exactly how an injection guard usually rots. Every entry in [FIND_PASSES_MATCH_FAILS] contains
     * a valid identifier somewhere inside it, so a `find()`-based guard would accept all of them.
     *
     * Newlines are the sharp end: `Regex.matches` anchors the whole input, but `find()` sees the
     * clean first line and waves the payload through.
     */
    @Test
    fun `injection-shaped column labels are refused, never sanitised`() {
        (ALWAYS_INVALID + FIND_PASSES_MATCH_FAILS).forEach { label ->
            shouldThrow<DatapipelinesException> { SqlIdentifiers.validateColumnNames(listOf(label)) }
                .code shouldBe PipelineErrorCodes.Staging.INVALID_COLUMN_NAME
        }
        shouldThrow<DatapipelinesException> { SqlIdentifiers.validateColumnNames(listOf(null)) }
    }

    @Test
    fun `the same payloads are refused as table names`() {
        // Both entry points matter: the table name reaches generated DDL, the labels reach DML.
        (ALWAYS_INVALID + FIND_PASSES_MATCH_FAILS).forEach { name ->
            shouldThrow<DatapipelinesException> {
                SqlIdentifiers.requireValidTable(name, PipelineErrorCodes.Node.STAGING_FAILED)
            }.code shouldBe PipelineErrorCodes.Node.STAGING_FAILED
        }
    }

    /**
     * A generated identifier refused at **runtime** reports the phase's code, never the save-time
     * validation code.
     *
     * `pipeline.validation.invalid_identifier` is an HTTP-400, save-time code. Raising it from
     * inside a running execution makes §8.2 incoherent and points an operator at a bad request that
     * does not exist — the pipeline passed save-time validation, which is why this guard is defence
     * in depth in the first place. Each call site therefore reports its own phase.
     */
    @Test
    fun `a bad generated identifier at runtime carries the phase code, not the validation code`() {
        val bad = "not a valid identifier"

        val staging =
            shouldThrow<DatapipelinesException> {
                SqlIdentifiers.requireValidTable(bad, PipelineErrorCodes.Node.STAGING_FAILED)
            }
        val writeback =
            shouldThrow<DatapipelinesException> {
                SqlIdentifiers.requireValidTable(bad, PipelineErrorCodes.Node.WRITEBACK_FAILED)
            }
        val writebackColumn =
            shouldThrow<DatapipelinesException> {
                SqlIdentifiers.validateColumnNames(listOf(bad), PipelineErrorCodes.Node.WRITEBACK_FAILED)
            }

        staging.code shouldBe PipelineErrorCodes.Node.STAGING_FAILED
        writeback.code shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
        writebackColumn.code shouldBe PipelineErrorCodes.Node.WRITEBACK_FAILED
        listOf(staging, writeback, writebackColumn).forEach {
            (it.code != PipelineErrorCodes.Validation.INVALID_IDENTIFIER) shouldBe true
        }

        // The staging default stands where §8.2 names it: a staged column label is still
        // `pipeline.staging.invalid_column_name`, which is a runtime code, not a validation one.
        shouldThrow<DatapipelinesException> { SqlIdentifiers.validateColumnNames(listOf(bad)) }
            .code shouldBe PipelineErrorCodes.Staging.INVALID_COLUMN_NAME
    }

    @Test
    fun `the length boundary is exactly 63 characters, on both entry points`() {
        val maxLength = "a".repeat(63)
        val overLength = "a".repeat(64)

        SqlIdentifiers.requireValidTable(maxLength, PipelineErrorCodes.Node.STAGING_FAILED) shouldBe maxLength
        SqlIdentifiers.validateColumnNames(listOf(maxLength)) shouldBe listOf(maxLength)
        shouldThrow<DatapipelinesException> {
            SqlIdentifiers.requireValidTable(overLength, PipelineErrorCodes.Node.STAGING_FAILED)
        }
        shouldThrow<DatapipelinesException> { SqlIdentifiers.validateColumnNames(listOf(overLength)) }
    }

    @Test
    fun `duplicate labels are refused case-insensitively, matching H2's folding`() {
        shouldThrow<DatapipelinesException> { SqlIdentifiers.validateColumnNames(listOf("total", "TOTAL")) }
            .code shouldBe PipelineErrorCodes.Staging.INVALID_COLUMN_NAME

        // A valid mixed-case label round-trips with its case intact.
        SqlIdentifiers.validateColumnNames(listOf("Total", "other")) shouldBe listOf("Total", "other")
    }

    @Test
    fun `a table name is validated before it is quoted, and quoting doubles embedded quotes`() {
        SqlIdentifiers.requireValidTable("stg_orders", PipelineErrorCodes.Node.STAGING_FAILED) shouldBe "stg_orders"
        shouldThrow<DatapipelinesException> {
            SqlIdentifiers.requireValidTable("x; DROP TABLE y", PipelineErrorCodes.Node.STAGING_FAILED)
        }.code shouldBe PipelineErrorCodes.Node.STAGING_FAILED

        SqlIdentifiers.quote("plain") shouldBe "\"plain\""
        SqlIdentifiers.quote("a\"b") shouldBe "\"a\"\"b\""
    }

    // ------------------------------------------------------------------ stats

    @Test
    fun `the snapshot fills every DAG node, recorded or not`() {
        val collector = NodeStatsCollector()
        val started = Instant.now()
        collector.started("done", started)
        collector.completed(NodeResult.of("done", rowsOut = 7, startedAt = started))
        collector.started("failed", started)
        collector.failed("failed", MappedError("code.x", "boom"), started.plusMillis(5))

        val snapshot = collector.snapshot(listOf("done", "failed", "never")).associateBy { it.nodeId }

        snapshot.getValue("done").status shouldBe NodeStatus.SUCCESS
        snapshot.getValue("done").rowsOut shouldBe 7
        snapshot.getValue("failed").status shouldBe NodeStatus.FAILED
        snapshot.getValue("failed").errorCode shouldBe "code.x"
        snapshot.getValue("failed").rowsOut shouldBe NodeResult.NOT_MEASURED
        // Never started, never recorded — the §7.2 ABORTED row, with no timings to report.
        snapshot.getValue("never").status shouldBe NodeStatus.ABORTED
        snapshot.getValue("never").startedAt.shouldBeNull()
        snapshot.getValue("never").completedAt.shouldBeNull()
    }

    @Test
    fun `runningNodeIds names the nodes that started but never reported`() {
        val collector = NodeStatsCollector()
        collector.started("a")
        collector.started("b")
        collector.completed(NodeResult.of("a", rowsOut = 0, startedAt = Instant.now()))

        collector.runningNodeIds() shouldBe setOf("b")
    }

    @Test
    fun `a node that was running when the execution was cancelled keeps its start time`() {
        val collector = NodeStatsCollector()
        val at = Instant.now()
        collector.started("running", at)

        val stats = collector.snapshot(listOf("running")).single()
        stats.status shouldBe NodeStatus.ABORTED
        stats.startedAt shouldBe at
    }

    // ------------------------------------------------------------- dispatcher

    @Test
    fun `the dispatcher is bounded and never Dispatchers-IO`() {
        // §15.2: sharing the JVM-wide IO pool makes executor throughput a function of unrelated
        // load. Only reading the size can tell a bounded pool from an unbounded one.
        val config = ExecutorConfig(maxConcurrentExecutionsGlobal = 3, maxParallelNodes = 2)

        ExecutorDispatcher.forConfig(config).use { it.threadCount shouldBe 6 }
        ExecutorDispatcher.forConfig(config, maxThreads = 2).use { it.threadCount shouldBe 2 }
        shouldThrow<IllegalArgumentException> { ExecutorDispatcher.forConfig(config, maxThreads = 0) }
    }

    // ------------------------------------------------------------ idempotency

    @Test
    fun `the request hash is stable, serialization-derived and parameter-sensitive`() {
        val pipeline = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val a = IdempotencyKeys.requestHash(pipeline, 1, """{"x":1}""")
        val b = IdempotencyKeys.requestHash(pipeline, 1, """{"x":1}""")
        val differentParams = IdempotencyKeys.requestHash(pipeline, 1, """{"x":2}""")
        val differentVersion = IdempotencyKeys.requestHash(pipeline, 2, """{"x":1}""")

        a shouldBe b
        (a != differentParams).shouldBeTrue()
        (a != differentVersion).shouldBeTrue()
        a.length shouldBe 64 // SHA-256, hex
    }

    // ------------------------------------------------------------------ audit

    @Test
    fun `the audit wrapper stamps the executing node onto a pool_build and nothing else`() {
        val recorded = CopyOnWriteArrayList<DatasourceAuditEvent>()
        val sink = ExecutionAwareAuditSink(DatasourceAuditSink { recorded += it })
        val executionId = UUID.randomUUID()

        sink.withCause(executionId, "fetch_orders") {
            sink.record(event(DatasourceAuditEvents.POOL_BUILD))
            // Operator-initiated events already carry a real actor; inventing an execution for
            // them would be a lie in the audit trail.
            sink.record(event(DatasourceAuditEvents.POOL_REBUILD))
            sink.record(event(DatasourceAuditEvents.CONNECTION_TEST))
        }
        // Outside the scope there is no cause to stamp.
        sink.record(event(DatasourceAuditEvents.POOL_BUILD))

        recorded[0].cause.shouldNotBeNull().executionId shouldBe executionId.toString()
        recorded[0].cause.shouldNotBeNull().nodeId shouldBe "fetch_orders"
        recorded[1].cause.shouldBeNull()
        recorded[2].cause.shouldBeNull()
        recorded[3].cause.shouldBeNull()
    }

    @Test
    fun `nested causes restore the outer one rather than clearing it`() {
        val recorded = CopyOnWriteArrayList<DatasourceAuditEvent>()
        val sink = ExecutionAwareAuditSink(DatasourceAuditSink { recorded += it })
        val outer = UUID.randomUUID()

        sink.withCause(outer, "outer") {
            sink.withCause(UUID.randomUUID(), "inner") { sink.record(event(DatasourceAuditEvents.POOL_BUILD)) }
            sink.record(event(DatasourceAuditEvents.POOL_BUILD))
        }

        recorded[0].cause.shouldNotBeNull().nodeId shouldBe "inner"
        recorded[1].cause.shouldNotBeNull().nodeId shouldBe "outer"
    }

    private companion object {
        /** Fail under `matches()` and `find()` alike — the original, weaker list. */
        val ALWAYS_INVALID = listOf("", " ", "1leading_digit", "has space", "has\"quote", "semi;colon", "drop table users")

        /**
         * Each contains a valid identifier, so a `find()`-based guard accepts them all; `matches()`
         * anchors the whole input and refuses them.
         */
        val FIND_PASSES_MATCH_FAILS =
            listOf(
                "stg\nDROP",
                "stg_orders\n",
                "stg\rDROP",
                "stg x",
                "stg orders",
                "stg\"; DROP--",
                "stg--comment",
                "stg;SELECT 1",
                "\nstg",
            )
    }

    private fun event(name: String) =
        DatasourceAuditEvent(
            timestamp = Instant.now(),
            datasourceName = "warehouse",
            event = name,
            actor = DatasourceAuditEvent.SYSTEM_ACTOR,
        )
}
