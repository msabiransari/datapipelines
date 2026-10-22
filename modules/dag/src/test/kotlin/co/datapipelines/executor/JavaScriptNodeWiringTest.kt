package co.datapipelines.executor

import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Node adapters over real staging and a recording engine; ScriptNodeE2eTest uses the real isolate. */
class JavaScriptNodeWiringTest {
    private val executionId = UUID.randomUUID()
    private val staging = Fixtures.stagingFactory().create(executionId)
    private val engine = JsonataEngine()
    private val columns =
        listOf(
            ContractColumn("order_id", LogicalType.INTEGER),
            ContractColumn("amount_cents", LogicalType.INTEGER),
            ContractColumn("customer_id", LogicalType.STRING),
        )

    @AfterEach
    fun close() = staging.close()

    @Test
    fun `javascript console logs every level with template version node execution and text fields`() {
        val logger = org.slf4j.LoggerFactory.getLogger("script.js.console") as ch.qos.logback.classic.Logger
        val events =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
                .apply { start() }
        logger.addAppender(events)
        try {
            val javascript =
                object : co.datapipelines.scripting.ScriptEngine by engine {
                    override val type = ScriptLanguage.JAVASCRIPT

                    override fun evaluate(
                        script: co.datapipelines.scripting.CompiledScript,
                        input: Any?,
                        limits: EvaluationLimits,
                    ): Any? {
                        co.datapipelines.scripting.ConsoleLevel.entries.forEach {
                            limits.console?.accept(co.datapipelines.scripting.ConsoleLine(it, "<img src=x onerror=alert(1)>"))
                        }
                        return emptyList<Any>()
                    }
                }
            runBlocking {
                stageOrders(listOf(listOf(1, 10, "alice")))
                run(
                    node(output = NodeOutput.Tempdb("order_lines")),
                    support(
                        resolved(rowContract(false), "[]", type = co.datapipelines.pipeline.TemplateType.JAVASCRIPT),
                        javascript = javascript,
                    ),
                )
            }
            events.list.size shouldBe 5
            events.list.forEachIndexed { index, event ->
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["template"] shouldBe TEMPLATE
                fields["version"] shouldBe 3
                fields["node"] shouldBe "shape_orders"
                fields["execution"] shouldBe executionId
                fields["text"] shouldBe "<img src=x onerror=alert(1)>"
                fields["level"] shouldBe
                    co.datapipelines.scripting.ConsoleLevel.entries[index]
                        .name
                        .lowercase()
                event.level shouldBe if (index >= 3) ch.qos.logback.classic.Level.WARN else ch.qos.logback.classic.Level.INFO
                event.message shouldContain "text={}"
                event.formattedMessage shouldContain "<img src=x onerror=alert(1)>"
            }
        } finally {
            logger.detachAppender(events)
            events.stop()
        }
    }

    @Test
    fun `javascript output result-byte and input-byte refusals preserve their code and kind at the node boundary`() {
        runBlocking { stageOrders(listOf(listOf(1, 10, "alice"))) }
        for (kind in listOf(
            co.datapipelines.scripting.ScriptResourceLimitException.Kind.OUTPUT,
            co.datapipelines.scripting.ScriptResourceLimitException.Kind.RESULT_BYTES,
            co.datapipelines.scripting.ScriptResourceLimitException.Kind.INPUT_BYTES,
        )) {
            val javascript =
                object : co.datapipelines.scripting.ScriptEngine by engine {
                    override val type = ScriptLanguage.JAVASCRIPT

                    override fun evaluate(
                        script: co.datapipelines.scripting.CompiledScript,
                        input: Any?,
                        limits: EvaluationLimits,
                    ): Any? = throw co.datapipelines.scripting.ScriptResourceLimitException(kind, "node boundary witness")
                }
            runBlocking {
                val failure =
                    failureOf(
                        node(output = NodeOutput.Tempdb("order_lines")),
                        support(
                            resolved(rowContract(false), "[]", type = co.datapipelines.pipeline.TemplateType.JAVASCRIPT),
                            javascript = javascript,
                        ),
                    )
                failure.code shouldBe PipelineErrorCodes.Script.RESOURCE_LIMIT
                failure.message shouldContain kind.name
            }
        }
    }

    private fun rowContract(rejects: Boolean) =
        ScriptContract(
            ScriptMode.ROW,
            mapOf("orders" to ScriptInput.Table(columns)),
            ScriptOutput.Table(columns),
            rejects,
        )

    private fun resolved(
        contract: ScriptContract,
        body: String,
        type: co.datapipelines.pipeline.TemplateType,
    ) = ResolvedScript(type, co.datapipelines.pipeline.PipelineVersionStatus.DRAFT, body, contract, emptyList())

    private fun support(
        resolved: ResolvedScript,
        javascript: co.datapipelines.scripting.ScriptEngine,
    ) = ScriptSupport(
        resolver = ScriptVersionResolver { _, _ -> resolved },
        pool = ScriptEvaluationPool(1, 1, Duration.ofMillis(200), ScriptEvaluationPool.SYSTEM),
        engines = mapOf(ScriptLanguage.JSONATA to engine, ScriptLanguage.JAVASCRIPT to javascript),
    )

    private fun node(output: NodeOutput) =
        ExecutableNode.from(
            Node(
                id = "shape_orders",
                description = "JavaScript wiring",
                type = NodeType.SCRIPT,
                source = "",
                template = TemplateRef(TEMPLATE, 3),
                output = output,
                dependsOn = emptyList(),
                inputs =
                    mapOf(
                        "orders" to
                            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                                .textNode("stg_orders"),
                    ),
            ),
        )

    private fun context() =
        NodeExecutionContext(
            executionId = executionId,
            staging = staging,
            handle = InMemoryCancellationRegistry().register(executionId),
            values = RunContext.of(mapOf(ContextKeys.CURRENT_TIMESTAMP to Instant.EPOCH)),
            warnings = WarningSink(),
            resultTtlSeconds = 300,
            renderBudgetChars = 1_000_000,
            stagingMaxMemoryMb = 1024,
            tempdbDialect = Dialect.H2,
            userId = UUID.randomUUID(),
            rootExecutionId = executionId,
            workspaceId = UUID.randomUUID(),
        )

    private suspend fun stageOrders(rows: List<List<Any?>>) {
        staging.stageRows("stg_orders", columns.map { ColumnSchema(it.name, it.type) }, rows.asSequence())
    }

    private suspend fun run(
        node: ExecutableNode,
        support: ScriptSupport,
    ) = ScriptNodeRuns.run(node, context(), Instant.now(), support, ExecutorConfig(), InMemoryResultStore())

    private suspend fun failureOf(
        node: ExecutableNode,
        support: ScriptSupport,
    ): MappedError = shouldThrow<NodeFailedSignal> { run(node, support) }.error

    private companion object {
        const val TEMPLATE = "test/js_wiring.javascript"
    }
}
