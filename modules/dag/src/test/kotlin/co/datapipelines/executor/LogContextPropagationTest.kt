package co.datapipelines.executor

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.events.ExecutionStarted
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingEngine
import co.datapipelines.staging.StagingFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.util.UUID

/**
 * The execution's ids ride every thread its work runs on (#337, observability.md §3.3), asserted as
 * the MDC of REAL log events on three different carriers:
 *
 * 1. **The executor's own coroutine** — the node-failed ERROR ([PipelineExecutor]'s logger, the
 *    caller side of the fan-out), which inherits the context element installed in `execute`.
 * 2. **Inside the node body** — the script pool's abandonment ERROR, logged by the caller of
 *    `pool.run` on the node's deadline scope. A fresh `CoroutineScope` inherits NO context
 *    elements, so this line carries the ids only when the deadline scope re-adds the element
 *    explicitly — the two lines fail for INDEPENDENT reasons, which is what makes each
 *    falsification mean something.
 * 3. **A pool thread** (second test) — a log statement inside a submitted script evaluation,
 *    carrying the MDC the submitter held, by the pool's own capture at submission.
 */
class LogContextPropagationTest {
    @Test
    @Suppress("LongMethod")
    fun `the executor's and the node body's failure lines carry the request's correlation id and the execution id`() =
        runBlocking<Unit> {
            val correlationId = UUID.randomUUID()
            // A single builtin that never reaches a step boundary: the pool's abandon rule fires,
            // the node fails on time, and BOTH carriers log within one execution.
            val padBody =
                """[ rows.{ "order_id": order_id, "amount": amount_cents, "customer_id": ${'$'}pad(customer_id, 100000000) } ]"""
            val pool = ScriptEvaluationPool(1, 4, Duration.ofSeconds(1), ScriptEvaluationPool.SYSTEM)
            val contractNode =
                co.datapipelines.pipeline.Node(
                    id = "shape_orders",
                    description = "transform shape_orders",
                    type = NodeType.TRANSFORM,
                    source = "",
                    template = TemplateRef("shape_orders", 1),
                    output =
                        co.datapipelines.pipeline.NodeOutput
                            .Tempdb("order_lines", rejects = "order_lines_rejected"),
                    dependsOn = emptyList(),
                    inputs =
                        mapOf(
                            "orders" to
                                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                    .textNode("stg_orders"),
                        ),
                    settings = co.datapipelines.pipeline.NodeSettings(timeoutSeconds = 6),
                )
            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(emptyMap()),
                stagingFactory = stagingWithOrders(),
                transforms =
                    TransformSupport(
                        resolver = TransformVersionResolver { _, _ -> resolvedTransform(padBody) },
                        pool = pool,
                        engines = mapOf(ScriptLanguage.JSONATA to JsonataEngine()),
                        maxInputRows = 100_000,
                        readBatchSize = 10_000,
                    ),
                config = ExecutorConfig(cancelGraceSeconds = 5),
            ).use { harness ->
                val executorLogger = LoggerFactory.getLogger(PipelineExecutor::class.java) as Logger
                val poolLogger = LoggerFactory.getLogger(ScriptEvaluationPool::class.java) as Logger
                val (executorLines, poolLines) =
                    captured(executorLogger, poolLogger) {
                        shouldThrow<PipelineExecutionFailed> {
                            harness.executor.execute(
                                Fixtures.request(Fixtures.pipeline(listOf(contractNode)), correlationId = correlationId),
                            )
                        }
                    }
                val executionId = harness.emitter.firstOf<ExecutionStarted>().executionId

                withClue("non-vacuity: the node-failed line really fired, naming this node") {
                    executorLines.count { it.formattedMessage.startsWith("node shape_orders failed") } shouldBeGreaterThanOrEqual 1
                }
                withClue("non-vacuity: the abandonment line really fired from inside the node body") {
                    poolLines.count { it.formattedMessage.contains("script_evaluation_abandoned") } shouldBeGreaterThanOrEqual 1
                }
                withClue("the executor's node-failed line carries both ids") {
                    executorLines.any {
                        it.formattedMessage.startsWith("node shape_orders failed") &&
                            it.mdcPropertyMap[LogContext.MDC_CORRELATION_ID] == correlationId.toString() &&
                            it.mdcPropertyMap[LogContext.MDC_EXECUTION_ID] == executionId.toString()
                    } shouldBe true
                }
                withClue("the node body's abandonment line carries both ids - the deadline scope re-added the element") {
                    poolLines.any {
                        it.formattedMessage.contains("script_evaluation_abandoned") &&
                            it.mdcPropertyMap[LogContext.MDC_CORRELATION_ID] == correlationId.toString() &&
                            it.mdcPropertyMap[LogContext.MDC_EXECUTION_ID] == executionId.toString()
                    } shouldBe true
                }
            }
        }

    @Test
    fun `a script evaluation runs with the submitter's MDC on the pool's own thread`() {
        val correlationId = UUID.randomUUID().toString()
        val executionId = UUID.randomUUID().toString()
        val probe = LoggerFactory.getLogger("log-context-pool-probe") as Logger
        val pool = ScriptEvaluationPool(1, 4, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM)
        MDC.put(LogContext.MDC_CORRELATION_ID, correlationId)
        MDC.put(LogContext.MDC_EXECUTION_ID, executionId)
        val events =
            try {
                captured(probe) {
                    pool.run(EvaluationLimits(Duration.ofSeconds(5), 100), "probe") {
                        // On the script-eval-N thread: the submitter's MDC must be installed here.
                        LoggerFactory.getLogger("log-context-pool-probe").info("logged from inside the evaluation")
                    }
                }
            } finally {
                MDC.remove(LogContext.MDC_CORRELATION_ID)
                MDC.remove(LogContext.MDC_EXECUTION_ID)
            }.single()

        withClue("non-vacuity: the probe line really fired on the pool thread") {
            events.count { it.formattedMessage.contains("logged from inside the evaluation") } shouldBeGreaterThanOrEqual 1
        }
        events
            .first { it.formattedMessage.contains("logged from inside the evaluation") }
            .let {
                it.mdcPropertyMap[LogContext.MDC_CORRELATION_ID] shouldBe correlationId
                it.mdcPropertyMap[LogContext.MDC_EXECUTION_ID] shouldBe executionId
            }
    }

    /** Attaches appenders to [loggers], runs [block], returns each logger's captured events. */
    private inline fun captured(
        vararg loggers: Logger,
        block: () -> Unit,
    ): List<List<ILoggingEvent>> {
        val appenders = loggers.map { logger -> logger to ListAppender<ILoggingEvent>().apply { start() } }
        appenders.forEach { (logger, appender) -> logger.addAppender(appender) }
        try {
            block()
        } finally {
            appenders.forEach { (logger, appender) -> logger.detachAppender(appender) }
        }
        return appenders.map { (_, appender) -> appender.list.toList() }
    }

    /**
     * A staging factory whose databases arrive with the transform's input table already staged —
     * the state an upstream DQL node would have left, so the node under test reaches its script.
     */
    private fun stagingWithOrders(): StagingFactory =
        object : StagingFactory {
            val delegate = Fixtures.stagingFactory()

            override fun create(
                executionId: UUID,
                engine: StagingEngine,
            ): Staging =
                delegate.create(executionId, engine).also { staged ->
                    kotlinx.coroutines.runBlocking {
                        staged.stageRows(
                            "stg_orders",
                            listOf(
                                co.datapipelines.typesystem.ColumnSchema(
                                    "order_id",
                                    co.datapipelines.typesystem.LogicalType.INTEGER,
                                    nullable = false,
                                ),
                                co.datapipelines.typesystem.ColumnSchema(
                                    "amount_cents",
                                    co.datapipelines.typesystem.LogicalType.INTEGER,
                                    nullable = false,
                                ),
                                co.datapipelines.typesystem.ColumnSchema(
                                    "customer_id",
                                    co.datapipelines.typesystem.LogicalType.STRING,
                                    nullable = true,
                                ),
                            ),
                            listOf(listOf(1, 1250, "alice")).asSequence(),
                        )
                    }
                }
        }

    private fun resolvedTransform(body: String) =
        ResolvedTransform(
            type = co.datapipelines.pipeline.TemplateType.JSONATA,
            status = co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
            body = body,
            contract =
                co.datapipelines.templates.TransformContract(
                    mode = co.datapipelines.templates.TransformMode.ROW,
                    inputs =
                        mapOf(
                            "orders" to
                                co.datapipelines.templates.TransformInput.Table(
                                    listOf(
                                        co.datapipelines.templates.ContractColumn(
                                            "order_id",
                                            co.datapipelines.typesystem.LogicalType.INTEGER,
                                        ),
                                        co.datapipelines.templates.ContractColumn(
                                            "amount_cents",
                                            co.datapipelines.typesystem.LogicalType.INTEGER,
                                        ),
                                        co.datapipelines.templates.ContractColumn(
                                            "customer_id",
                                            co.datapipelines.typesystem.LogicalType.STRING,
                                            nullable = true,
                                        ),
                                    ),
                                ),
                        ),
                    output =
                        co.datapipelines.templates.TransformOutput.Table(
                            listOf(
                                co.datapipelines.templates.ContractColumn("order_id", co.datapipelines.typesystem.LogicalType.INTEGER),
                            ),
                        ),
                    rejects = true,
                ),
            invariants = emptyList(),
        )
}
