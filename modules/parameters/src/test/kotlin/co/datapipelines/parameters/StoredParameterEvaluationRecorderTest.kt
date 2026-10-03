package co.datapipelines.parameters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.parameters.EvaluatorFixtures.WORKSPACE
import co.datapipelines.parameters.EvaluatorFixtures.attempt
import co.datapipelines.parameters.EvaluatorFixtures.templateSelect
import co.datapipelines.parameters.EvaluatorFixtures.version
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * The recorder's two promises (#376; spec §2.3, §11.10): **persistence failure never fails an evaluation** — a START or
 * terminal write that throws a `DataAccessException` is one structured ERROR line (ids, class, SQLState; never the
 * message) and the evaluation's response is unchanged — and the **dormant `PIPELINE` caller is refused at the entry**.
 * The repository is a double here so the failure can be FORCED; the container suite proves the real statements.
 */
class StoredParameterEvaluationRecorderTest {
    private val logger = LoggerFactory.getLogger(StoredParameterEvaluationRecorder::class.java) as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
    }

    private val key = EvaluationKey(UUID.randomUUID(), EvaluatorFixtures.WORKSPACE, EvaluatorFixtures.SET_ID)

    /** A storage fault whose message carries text that must never reach a log line. */
    private fun fault() = DataAccessResourceFailureException("SECRET-ROW-TEXT", SQLException("driver says SECRET-ROW-TEXT", "08006"))

    private fun errors(): List<ILoggingEvent> = appender.list.filter { it.level == Level.ERROR }

    @Test
    fun `a failed START is one structured ERROR, answers unrecorded, and never throws`() {
        val repository = mockk<ParameterEvaluationRepository>()
        every { repository.insertRunning(any()) } throws fault()

        val recorded = StoredParameterEvaluationRecorder(repository).started(EvaluationStarted(key, attempt(), 4, Instant.EPOCH))

        recorded shouldBe false
        val line = errors().single().formattedMessage
        line shouldContain "event=parameter.evaluation_record_failed write=start evaluation_id=${key.evaluationId}"
        line shouldContain "workspace_id=${key.workspaceId} parameter_set_id=${key.parameterSetId}"
        line shouldContain "error=DataAccessResourceFailureException sql_state=08006"
        withClue("the class and the SQLState only — never the exception's message") { line shouldNotContain "SECRET" }
    }

    @Test
    fun `a failed terminal write is one structured ERROR and never throws`() {
        val repository = mockk<ParameterEvaluationRepository>()
        every { repository.finish(any()) } throws fault()
        val ended = EvaluationEnded(key, ParameterEvaluationStatus.COMPLETED, null, true, emptyList(), emptyList(), Instant.EPOCH)

        StoredParameterEvaluationRecorder(repository).ended(ended)

        errors().single().formattedMessage shouldContain "write=end evaluation_id=${key.evaluationId}"
        verify(exactly = 0) { repository.finishQueries(any()) }
    }

    @Test
    fun `the evaluation's response is byte-identical whether its START write fails or lands`() {
        val set = version(ParameterSetFixtures.countryJson(), templateSelect("state", dependsOn = listOf("country")))
        val selectors = ScriptedSelectors().apply { this["acme/sales/state.sql"] = { ScriptedSelectors.options("NY", "NJ") } }
        val failing = mockk<ParameterEvaluationRepository>()
        every { failing.insertRunning(any()) } throws fault()
        val landing = mockk<ParameterEvaluationRepository>(relaxed = true)
        every { landing.insertRunning(any()) } returns true

        val unrecorded =
            evaluator(
                selectors,
                StoredParameterEvaluationRecorder(failing),
            ).evaluateBlocking(WORKSPACE, set, emptyMap(), attempt())
        val recorded =
            evaluator(
                selectors,
                StoredParameterEvaluationRecorder(landing),
            ).evaluateBlocking(WORKSPACE, set, emptyMap(), attempt())

        EvaluateResponseJson.bytes(unrecorded).decodeToString() shouldBe EvaluateResponseJson.bytes(recorded).decodeToString()
        withClue("unrecorded means no further write: no query row, no terminal write after the failed START") {
            verify(exactly = 0) { failing.insertQuery(any()) }
            verify(exactly = 0) { failing.finish(any()) }
        }
        // Non-vacuity: the landing recorder did write the statement attempt and the terminal row.
        verify(exactly = 1) { landing.insertQuery(any()) }
        verify(exactly = 1) { landing.finish(any()) }
    }

    @Test
    fun `the evaluation's response is unchanged when its terminal write fails`() {
        val set = version(ParameterSetFixtures.countryJson())
        val failing = mockk<ParameterEvaluationRepository>(relaxed = true)
        every { failing.insertRunning(any()) } returns true
        every { failing.finish(any()) } throws fault()

        val response =
            evaluator(
                ScriptedSelectors(),
                StoredParameterEvaluationRecorder(failing),
            ).evaluateBlocking(WORKSPACE, set, emptyMap(), attempt())

        response.values shouldBe mapOf("country" to "USA")
        errors().single().formattedMessage shouldContain "write=end"
    }

    @Test
    fun `the dormant PIPELINE caller is refused at the recorder's entry - and nothing is written`() {
        val repository = mockk<ParameterEvaluationRepository>(relaxed = true)
        val set = version(ParameterSetFixtures.countryJson())

        val refused =
            shouldThrow<IllegalArgumentException> {
                evaluator(ScriptedSelectors(), StoredParameterEvaluationRecorder(repository))
                    .evaluateBlocking(WORKSPACE, set, emptyMap(), attempt(EvaluationCaller.PIPELINE))
            }

        refused.message shouldContain "PIPELINE caller is dormant"
        verify(exactly = 0) { repository.insertRunning(any()) }
        // Non-vacuity: every other caller passes the same entry.
        EvaluationCaller.entries.filter { it != EvaluationCaller.PIPELINE }.forEach { caller ->
            StoredParameterEvaluationRecorder(repository).started(EvaluationStarted(key, attempt(caller), 1, Instant.EPOCH))
        }
        verify(exactly = 4) { repository.insertRunning(any()) }
    }

    @Test
    fun `an attempt names exactly one principal - a person or a key`() {
        EvaluationAttempt.of(EvaluationCaller.MCP, EvaluatorFixtures.USER, keyId = "dpk_abcdefghijkl").let {
            (it.principalUserId to it.principalKeyId) shouldBe (null to "dpk_abcdefghijkl")
        }
        EvaluationAttempt.of(EvaluationCaller.REST, EvaluatorFixtures.USER, keyId = null).let {
            (it.principalUserId to it.principalKeyId) shouldBe (EvaluatorFixtures.USER to null)
        }
        shouldThrow<IllegalArgumentException> { EvaluationAttempt(UUID.randomUUID(), EvaluationCaller.REST, null, null, null) }
        shouldThrow<IllegalArgumentException> {
            EvaluationAttempt(
                UUID.randomUUID(),
                EvaluationCaller.REST,
                EvaluatorFixtures.USER,
                "k",
                null,
            )
        }
    }

    @Test
    fun `every SelectorRun variant maps to its outcome column - REFUSED never collapses into FAILED`() {
        val cases =
            listOf(
                SelectorRun.Rows(ScriptedSelectors.SELECT_COLUMNS, emptyList()) to (QueryAttemptOutcome.EXECUTED to null),
                SelectorRun.Unreachable("refused") to (QueryAttemptOutcome.REFUSED to "pipeline.execution.datasource_unreachable"),
                failed("pipeline.node.template_render_failed") to (QueryAttemptOutcome.REFUSED to "pipeline.node.template_render_failed"),
                failed("pipeline.node.template_not_found") to (QueryAttemptOutcome.REFUSED to "pipeline.node.template_not_found"),
                failed("datasource.not_found") to (QueryAttemptOutcome.REFUSED to "datasource.not_found"),
                failed("pipeline.node.sql_parameter_missing") to (QueryAttemptOutcome.REFUSED to "pipeline.node.sql_parameter_missing"),
                failed("parameter.evaluate.too_many_binds") to (QueryAttemptOutcome.REFUSED to "parameter.evaluate.too_many_binds"),
                failed("pipeline.node.query_execution_failed", "read_only_gate") to
                    (QueryAttemptOutcome.REFUSED to "pipeline.node.query_execution_failed"),
                failed("pipeline.node.query_execution_failed", "placeholders") to
                    (QueryAttemptOutcome.REFUSED to "pipeline.node.query_execution_failed"),
                failed("pipeline.node.query_execution_failed") to (QueryAttemptOutcome.FAILED to "pipeline.node.query_execution_failed"),
                failed("pipeline.node.query_timeout", "timeout") to (QueryAttemptOutcome.FAILED to "pipeline.node.query_timeout"),
                failed("datasource.table_forbidden") to (QueryAttemptOutcome.FAILED to "datasource.table_forbidden"),
            )

        cases.forEach { (run, expected) -> withClue(run) { QueryOutcomes.of(run) shouldBe expected } }
    }

    @Test
    fun `outcomes_json degrades to the diagnosing entries instead of overflowing its budget`() {
        val many =
            (1..256).map { i ->
                if (i % 2 == 0) {
                    ParameterOutcome(nameOf(i), ParameterOutcome.ERROR, "parameter.evaluate.required_missing", "x".repeat(200))
                } else {
                    ParameterOutcome(nameOf(i), ParameterOutcome.RESOLVED, null, null)
                }
            }

        val encoded = OutcomesJson.encode(many)

        (encoded.toByteArray().size <= OutcomesJson.BUDGET_BYTES) shouldBe true
        val kept = OutcomesJson.decode(encoded)
        withClue("only error entries survive the last degradation, in declaration order, without their detail") {
            kept.all { it.outcome == ParameterOutcome.ERROR && it.detail == null } shouldBe true
            kept.map { it.name } shouldBe many.filter { it.outcome == ParameterOutcome.ERROR }.take(kept.size).map { it.name }
        }
        // Non-vacuity: a small set keeps every entry verbatim.
        val small = many.take(3)
        OutcomesJson.decode(OutcomesJson.encode(small)) shouldBe small
        OutcomesJson.decode(OutcomesJson.encode(emptyList())) shouldHaveSize 0
    }

    @Test
    fun `a query attempt's start and end are stamped on the worker - nothing else of the task changes`() {
        val query = QueryAttempt(UUID.randomUUID(), java.time.Clock.systemUTC())
        var abandoned = false
        val task =
            object : SelectorTask {
                override fun run(): SelectorRun = ScriptedSelectors.options("A", "B")

                override fun abandon() {
                    abandoned = true
                }
            }

        val stamped = query.stamping(task)
        val run = stamped.run()
        stamped.abandon()

        run shouldBe ScriptedSelectors.options("A", "B")
        abandoned shouldBe true
        val ended = query.ended(key, SelectorAdmission.Completed(run))
        ended.outcome shouldBe QueryAttemptOutcome.EXECUTED
        ended.rowCount shouldBe 2
        val started = requireNotNull(ended.startedAt) { "the worker stamps its start" }
        requireNotNull(ended.endedAt) { "the worker stamps its end" }.isBefore(started) shouldBe false
    }

    /** A 63-character parameter name, the grammar's longest, unique per [i]. */
    private fun nameOf(i: Int): String = "p".repeat(60) + i.toString().padStart(3, '0')

    private fun failed(
        code: String,
        reason: String? = null,
    ) = SelectorRun.Failed(code, "driver text", reason?.let { mapOf("reason" to it) } ?: emptyMap())

    private fun evaluator(
        selectors: SelectorTasks,
        recorder: ParameterEvaluationRecorder,
    ) = ParameterEvaluator(selectors, SelectorPool(4, 64), recorder = recorder)
}
