package co.datapipelines.application.dashboards

import co.datapipelines.application.dashboards.RefreshFixtures.bindParameter
import co.datapipelines.application.dashboards.RefreshFixtures.columns
import co.datapipelines.application.dashboards.RefreshFixtures.dashboard
import co.datapipelines.application.dashboards.RefreshFixtures.number
import co.datapipelines.application.dashboards.RefreshFixtures.occurrence
import co.datapipelines.application.dashboards.RefreshFixtures.ref
import co.datapipelines.application.dashboards.RefreshFixtures.source
import co.datapipelines.executor.ExecutionSlots
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.InputColumn
import co.datapipelines.visualization.RefreshStatus
import co.datapipelines.visualization.TransformBinding
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RefreshEngine] end to end over REAL collaborators wherever the property lives in them (a real [ExecutionSlots], a
 * real [BoundedCollector], the real [RefreshPlanner]) and recording fakes at the seams (the starter, the ledger, the
 * sink) — every assertion reads the RECORDED effect, never a call count on a strict double (MISTAKES.md: a strict
 * mock makes a missing call unobservable).
 *
 * The scenarios are the spec's (the authoring record's 2, 4, 15, 18): sharing, mixed outcomes, timeouts by stage,
 * abort — plus the lessons the engine exists to keep: the row is closed however the refresh ends (cancelled included),
 * a source is linked before it is announced, and no multi-input transform ever runs on a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshEngineTest {
    private val log = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<RefreshEvent>()
    private val links = CopyOnWriteArrayList<Link>()
    private val finishes = CopyOnWriteArrayList<Finish>()
    private val audited = CopyOnWriteArrayList<RefreshResult>()
    private val cancelled = CopyOnWriteArrayList<UUID>()
    private val abortFlag = AtomicBoolean(false)

    /** When set, the abort signal's read itself fails — a store fault at the job-start check (356 merge follow-up). */
    private var abortThrows = false
    private val launches = CopyOnWriteArrayList<SourceLaunch>()
    private val transformCalls = CopyOnWriteArrayList<Map<String, List<Map<String, Any?>>>>()
    private var scripts: (String) -> Script = { Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1), listOf(2))) }
    private var transform: (Map<String, List<Map<String, Any?>>>) -> TransformOutcome = { TransformOutcome.Rows(listOf(mapOf("x" to 99))) }
    private var finishThrows = false

    private data class Link(
        val source: String,
        val executionId: UUID,
        val shared: Boolean,
    )

    private data class Finish(
        val status: RefreshStatus,
        val summary: ObjectNode,
    )

    private sealed interface Script {
        data class Rows(
            val schema: List<ColumnSchema>,
            val rows: List<List<Any?>>,
        ) : Script

        data class Fail(
            val code: String,
        ) : Script

        data object Hang : Script

        data object Aborted : Script

        /** The source ends (with [outcome]) after [millis] of virtual time — mid-work raises and polls become orderable. */
        data class After(
            val millis: Long,
            val outcome: Script,
        ) : Script
    }

    private val starter =
        object : SourceStarter {
            override suspend fun run(
                launch: SourceLaunch,
                onRecorded: (UUID) -> Unit,
            ): SourceOutcome {
                launches += launch
                val id = UUID.nameUUIDFromBytes("exec-${launch.sourceName}".toByteArray())
                onRecorded(id)
                return when (val script = scripts(launch.sourceName)) {
                    is Script.Rows -> {
                        try {
                            launch.sink.accept(script.schema, script.rows.asSequence())
                            SourceOutcome.Succeeded(id)
                        } catch (_: ResultTooLarge) {
                            // What the executor does with a sink that throws: the node fails, the execution FAILS.
                            SourceOutcome.Failed(id, "pipeline.node.failed")
                        }
                    }

                    is Script.Fail -> {
                        SourceOutcome.Failed(id, script.code)
                    }

                    Script.Aborted -> {
                        SourceOutcome.Aborted(id)
                    }

                    is Script.After -> {
                        delay(script.millis).let {
                            when (val outcome = script.outcome) {
                                is Script.Rows -> {
                                    launch.sink.accept(outcome.schema, outcome.rows.asSequence())
                                    SourceOutcome.Succeeded(id)
                                }

                                Script.Aborted -> {
                                    SourceOutcome.Aborted(id)
                                }

                                else -> {
                                    error("After() wraps a terminal Rows or Aborted script, was $outcome")
                                }
                            }
                        }
                    }

                    Script.Hang -> {
                        awaitCancellation()
                    }
                }
            }
        }

    private val ports =
        RefreshPorts(
            events =
                RefreshEvents { event ->
                    events += event
                    log += "event:${event.eventName}:${sourceOf(event)}"
                    true
                },
            ledger =
                object : RefreshLedger {
                    override fun link(
                        refreshId: UUID,
                        source: String,
                        executionId: UUID,
                        shared: Boolean,
                    ) {
                        links += Link(source, executionId, shared)
                        log += "link:$source"
                    }

                    override fun finish(
                        refreshId: UUID,
                        status: RefreshStatus,
                        summary: ObjectNode,
                    ): Boolean {
                        check(!finishThrows) { "the store is down" }
                        finishes += Finish(status, summary)
                        return true
                    }
                },
            audit = RefreshAudit { _, result -> audited += result },
            abort = AbortSignal { if (abortThrows) error("abort store unreachable") else abortFlag.get() },
            canceller = ExecutionCanceller { cancelled += it },
        )

    private fun sourceOf(event: RefreshEvent): String? =
        when (event) {
            is RefreshEvent.SourceStarted -> event.source
            is RefreshEvent.SourceCompleted -> event.source
            is RefreshEvent.SourceFailed -> event.source
            else -> null
        }

    private fun TestScope.engine(config: DashboardRuntimeConfig = DashboardRuntimeConfig()) =
        RefreshEngine(
            starter = starter,
            transformer =
                DashboardTransformer { _, _, tables, _ ->
                    transformCalls += tables
                    transform(tables)
                },
            config = config,
            blocking = StandardTestDispatcher(testScheduler),
        )

    private val year = mapOf("year" to bindParameter("year"))

    @Test
    fun `three sources, one shared - two executions for three consumers, each linked BEFORE it is announced`() =
        runTest {
            val body =
                dashboard(
                    sources = listOf(source("s1", "p/a", year), source("s2", "p/a", year), source("s3", "p/b")),
                    visualizations =
                        listOf(
                            occurrence("v1", inputs = mapOf("main" to "s1")),
                            occurrence("v2", inputs = mapOf("main" to "s2")),
                            occurrence("v3", inputs = mapOf("main" to "s3")),
                        ),
                )
            val slots = ExecutionSlots(maxPerUser = 1, maxPerInstance = 10)

            val result = engine().run(RefreshFixtures.job(body, values = mapOf("year" to number(2026)), slots = slots), ports)

            launches.size shouldBe 2
            result.status shouldBe RefreshStatus.COMPLETED
            result.targets.values.forEach { it.shouldBeInstanceOf<TargetOutcome.Ok>() }
            links.map { it.source to it.shared } shouldContainExactly listOf("s1" to true, "s2" to true, "s3" to false)
            links.first { it.source == "s1" }.executionId shouldBe links.first { it.source == "s2" }.executionId
            listOf("s1", "s2", "s3").forEach { source ->
                (log.indexOf("link:$source") < log.indexOf("event:source_started:$source")) shouldBe true
            }
            slots.inFlight shouldBe 0 // every reserved slot came back
        }

    @Test
    fun `refresh_started names the targets, the sources with their shared flag and the deadline - and refresh_completed is always last`() =
        runTest {
            val body =
                dashboard(
                    sources = listOf(source("s1", "p/a", year), source("s2", "p/a", year)),
                    visualizations =
                        listOf(
                            occurrence("v1", inputs = mapOf("main" to "s1")),
                            occurrence("v2", inputs = mapOf("main" to "s2")),
                        ),
                )

            engine().run(RefreshFixtures.job(body, values = mapOf("year" to number(1)), deadlineSeconds = 120), ports)

            val started = events.first().shouldBeInstanceOf<RefreshEvent.Started>()
            started.targets shouldContainExactly listOf("v1", "v2")
            started.sources shouldContainExactly listOf(SourceRef("s1", true), SourceRef("s2", true))
            started.deadlineAt shouldBe RefreshFixtures.NOW.plusSeconds(120).toString()
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>()
            events.count { it is RefreshEvent.Completed } shouldBe 1
        }

    @Test
    fun `a failed source fails every target that reads it and a multi-input transform never runs on a stand-in`() =
        runTest {
            val body =
                dashboard(
                    sources = listOf(source("s1"), source("s2")),
                    visualizations =
                        listOf(
                            occurrence("v1", inputs = mapOf("main" to "s1")),
                            occurrence("v2", inputs = mapOf("main" to "s2")),
                            occurrence("v3", inputs = mapOf("a" to "s2", "b" to "s1")),
                        ),
                )
            scripts =
                {
                    if (it ==
                        "s1"
                    ) {
                        Script.Fail("pipeline.node.failed")
                    } else {
                        Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(7)))
                    }
                }
            val viz3 =
                RefreshFixtures.visualization(
                    inputs =
                        mapOf(
                            "a" to listOf(InputColumn("x", LogicalType.INTEGER)),
                            "b" to listOf(InputColumn("x", LogicalType.INTEGER)),
                        ),
                    transform = TransformBinding(ref("t/join"), mapOf("left" to "a", "right" to "b")),
                )

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("v3" to viz3)), ports)

            result.status shouldBe RefreshStatus.PARTIAL
            result.targets["v1"].shouldBeInstanceOf<TargetOutcome.Error>().stage shouldBe "source"
            result.targets["v3"].shouldBeInstanceOf<TargetOutcome.Error>().code shouldBe "pipeline.node.failed"
            result.targets["v2"].shouldBeInstanceOf<TargetOutcome.Ok>()
            transformCalls.shouldBeEmpty()
            events.filterIsInstance<RefreshEvent.SourceFailed>().map { it.source } shouldContainExactly listOf("s1")
            events.filterIsInstance<RefreshEvent.VisualizationData>().map { it.name } shouldContainExactly listOf("v2")
        }

    @Test
    fun `a transform runs once over its inputs' rows and the bindings resolve from ITS output`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            transform = { TransformOutcome.Rows(listOf(mapOf("x" to 10), mapOf("x" to 20))) }
            val viz = RefreshFixtures.visualization(transform = TransformBinding(ref("t/one"), mapOf("only" to "main")))

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("v" to viz)), ports)

            transformCalls.size shouldBe 1
            transformCalls.single().getValue("only") shouldContainExactly listOf(mapOf("x" to 1L), mapOf("x" to 2L))
            events.filterIsInstance<RefreshEvent.VisualizationData>().single().bindings shouldBe mapOf("cells.x" to listOf<Any?>(10, 20))
            result.status shouldBe RefreshStatus.COMPLETED
        }

    @Test
    fun `without a transform the bindings are the one input's columns`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))

            engine().run(RefreshFixtures.job(body), ports)

            events.filterIsInstance<RefreshEvent.VisualizationData>().single().let {
                it.bindings shouldBe mapOf("cells.x" to listOf<Any?>(1L, 2L))
                it.rows shouldBe 2
            }
        }

    @Test
    fun `a transform that refuses its input fails the target at the transform stage with the refusal's code`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            transform = { TransformOutcome.Refused(PipelineErrorCodes.Execution.TIMEOUT) }
            val viz = RefreshFixtures.visualization(transform = TransformBinding(ref("t/one"), mapOf("only" to "main")))

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("v" to viz)), ports)

            result.targets.getValue("v") shouldBe TargetOutcome.Error("transform", PipelineErrorCodes.Execution.TIMEOUT)
            result.status shouldBe RefreshStatus.FAILED
        }

    @Test
    fun `a source whose columns do not satisfy the input contract fails the target naming the column - the first refresh judges it`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Rows(columns("x" to LogicalType.STRING), listOf(listOf("a"))) } // the contract says INTEGER

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.targets.getValue("v") shouldBe TargetOutcome.Error("source", DashboardErrorCodes.INPUT_CONTRACT_MISMATCH, "x")
            events.filterIsInstance<RefreshEvent.VisualizationStatus>().last().reasonCode shouldBe
                DashboardErrorCodes.INPUT_CONTRACT_MISMATCH
            events.filterIsInstance<RefreshEvent.VisualizationData>().shouldBeEmpty()
        }

    @Test
    fun `an empty result is the no-data state, not an error and not a blank chart`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Rows(columns("x" to LogicalType.INTEGER), emptyList()) }

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.targets.getValue("v") shouldBe TargetOutcome.NoData
            result.status shouldBe RefreshStatus.COMPLETED
            events.filterIsInstance<RefreshEvent.VisualizationStatus>().last().state shouldBe "no-data"
        }

    @Test
    fun `a source over its byte cap fails result_too_large at the budget stage while the others continue`() =
        runTest {
            val body =
                dashboard(
                    listOf(source("big"), source("small")),
                    listOf(occurrence("v1", inputs = mapOf("main" to "big")), occurrence("v2", inputs = mapOf("main" to "small"))),
                )
            val many = (1..500).map { listOf<Any?>(it) }
            scripts =
                {
                    if (it ==
                        "big"
                    ) {
                        Script.Rows(columns("x" to LogicalType.INTEGER), many)
                    } else {
                        Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1)))
                    }
                }

            val result = engine(DashboardRuntimeConfig(maxBytesPerSource = 1_024)).run(RefreshFixtures.job(body), ports)

            result.targets.getValue("v1") shouldBe TargetOutcome.Error("budget", DashboardErrorCodes.REFRESH_RESULT_TOO_LARGE)
            result.targets["v2"].shouldBeInstanceOf<TargetOutcome.Ok>()
            result.status shouldBe RefreshStatus.PARTIAL
            events.filterIsInstance<RefreshEvent.SourceFailed>().single().code shouldBe DashboardErrorCodes.REFRESH_RESULT_TOO_LARGE
        }

    @Test
    fun `the refresh cap ends the refresh PARTIAL with the remaining targets errored`() =
        runTest {
            val body =
                dashboard(
                    listOf(source("a"), source("b")),
                    listOf(occurrence("v1", inputs = mapOf("main" to "a")), occurrence("v2", inputs = mapOf("main" to "b"))),
                )
            val rows = (1..200).map { listOf<Any?>(it) } // ~890 bytes each: under the 1,024 source cap, twice over the 1,100 refresh cap
            scripts = { Script.Rows(columns("x" to LogicalType.INTEGER), rows) }

            // each source fits its own cap; together they cross the refresh's
            val result =
                engine(
                    DashboardRuntimeConfig(maxBytesPerSource = 1_024, maxBytesPerRefresh = 1_100),
                ).run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.PARTIAL
            result.targets.values.count { it is TargetOutcome.Ok } shouldBe 1
            result.summary.path("budget_exceeded").asBoolean() shouldBe true
        }

    @Test
    fun `a hung source ends source_failed with the timeout code when its only consumer's deadline passes - the refresh does not hang`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"), timeoutSeconds = 5)))
            scripts = { Script.Hang }

            val result = engine().run(RefreshFixtures.job(body, deadlineSeconds = 600), ports)

            result.targets.getValue("v") shouldBe TargetOutcome.Error("timeout", PipelineErrorCodes.Execution.TIMEOUT)
            events.filterIsInstance<RefreshEvent.SourceFailed>().single().code shouldBe PipelineErrorCodes.Execution.TIMEOUT
            cancelled shouldContainExactly listOf(UUID.nameUUIDFromBytes("exec-s1".toByteArray())) // the execution is stopped, not orphaned
            finishes.single().status shouldBe RefreshStatus.FAILED
            testScheduler.currentTime shouldBe 5_000L // the EARLIEST deadline that applied, not the refresh's 600 s
        }

    @Test
    fun `the refresh's own deadline ends it TIMED_OUT - the row is closed and the running source is cancelled`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Hang }

            val result = engine().run(RefreshFixtures.job(body, deadlineSeconds = 30), ports)

            result.status shouldBe RefreshStatus.TIMED_OUT
            finishes.single().status shouldBe RefreshStatus.TIMED_OUT
            finishes
                .single()
                .summary
                .path("reason")
                .asText() shouldBe "deadline_passed"
            result.targets.getValue("v") shouldBe TargetOutcome.Error("timeout", PipelineErrorCodes.Execution.TIMEOUT)
            cancelled.size shouldBe 1
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>().status shouldBe "TIMED_OUT"
        }

    /**
     * An occurrence deadline EQUAL to the refresh's is not a second timer: the refresh's is the only authority, so the
     * ending is TIMED_OUT by construction — no source-level timer exists to fire, and no `source_failed` is ever sent.
     * This pins the OUTCOME; it cannot tell the implementations apart (the refresh's timer is created first and would
     * win a tie anyway), which is why the rule is stated in the engine rather than left to the timer queue's order.
     */
    @Test
    fun `an occurrence deadline equal to the refresh's is not a second timer - the refresh's deadline decides`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"), timeoutSeconds = 30)))
            scripts = { Script.Hang }

            val result = engine().run(RefreshFixtures.job(body, deadlineSeconds = 30), ports)

            result.status shouldBe RefreshStatus.TIMED_OUT
            events.filterIsInstance<RefreshEvent.SourceFailed>().shouldBeEmpty() // no source-level timer fired
        }

    @Test
    fun `an abort ends the refresh ABORTED, cancels the running execution, and a pending transform never runs`() =
        runTest {
            val body =
                dashboard(
                    listOf(source("s1"), source("s2")),
                    listOf(occurrence("v", inputs = mapOf("a" to "s1", "b" to "s2"))),
                )
            scripts = { if (it == "s1") Script.Hang else Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1))) }
            val viz =
                RefreshFixtures.visualization(
                    inputs =
                        mapOf(
                            "a" to listOf(InputColumn("x", LogicalType.INTEGER)),
                            "b" to listOf(InputColumn("x", LogicalType.INTEGER)),
                        ),
                    transform = TransformBinding(ref("t/join"), mapOf("left" to "a", "right" to "b")),
                )
            launch {
                delay(1_000)
                abortFlag.set(true)
            }

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("v" to viz)), ports)

            result.status shouldBe RefreshStatus.ABORTED
            result.targets.getValue("v") shouldBe TargetOutcome.Aborted
            transformCalls.shouldBeEmpty() // the pending transform did not run to completion after the abort
            cancelled shouldContainExactly listOf(UUID.nameUUIDFromBytes("exec-s1".toByteArray()))
            finishes.single().status shouldBe RefreshStatus.ABORTED
            audited.single().status shouldBe RefreshStatus.ABORTED
            events.filterIsInstance<RefreshEvent.VisualizationStatus>().last().state shouldBe "abort"
        }

    @Test
    fun `an execution the executor aborted on its own fails its targets at the abort stage without ending the refresh ABORTED`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Aborted }

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.targets.getValue("v") shouldBe TargetOutcome.Error("abort", PipelineErrorCodes.Execution.ABORTED)
            result.status shouldBe RefreshStatus.FAILED
        }

    @Test
    fun `an abort requested while its own cancellation ends the execution first is still an ABORT, not a failure`() {
        runTest {
            // The abort route cancels the execution AND raises the refresh flag; the execution can end before the
            // watcher's next poll, so the work finishes with every target failed and nobody yet told the engine why.
            // The flag is raised WHILE the refresh runs (#356's start check only owns a flag already there at job
            // start), the execution ends aborted 20 ms later, still before the first poll.
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.After(30, Script.Aborted) }
            launch {
                delay(10)
                abortFlag.set(true)
            }

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.ABORTED
            // The target had already failed at the abort stage when the execution ended; the row and the last frame say ABORTED.
            result.targets.getValue("v") shouldBe TargetOutcome.Error("abort", PipelineErrorCodes.Execution.ABORTED)
            finishes.single().status shouldBe RefreshStatus.ABORTED
        }
    }

    @Test
    fun `an abort flag raised while the work ran does not turn a refresh whose every target succeeded into an abort`() {
        runTest {
            // The flag lands mid-work (t=10) but the source's rows are delivered at t=30 and the
            // watcher's poll never fires: the abort cancelled nothing, so the refresh is COMPLETED —
            // the ending consults the BODY's outcome, not the flag's timing (#370).
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.After(30, Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1)))) }
            launch {
                delay(10)
                abortFlag.set(true)
            }

            engine().run(RefreshFixtures.job(body), ports).status shouldBe RefreshStatus.COMPLETED
        }
    }

    @Test
    fun `an abort the engine reads only after the work joined - every target delivered - ends DONE and the notice records the request`() {
        runTest {
            // The #370 CI face, deterministically: the request is recorded at the same instant the
            // last rows deliver (this task is queued before the source's continuation), the watcher's
            // poll never fires, and noticeLateAbort reads the port AFTER every target completed. The
            // old unconditional flag read at the DONE/ABORTED fork ended this ABORTED under chips the
            // client had already settled to success; the rule is the body's outcome.
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.After(30, Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1)))) }
            launch {
                delay(30)
                abortFlag.set(true)
            }

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.COMPLETED
            result.targets.getValue("v").shouldBeInstanceOf<TargetOutcome.Ok>()
            finishes.single().status shouldBe RefreshStatus.COMPLETED
            audited.single().status shouldBe RefreshStatus.COMPLETED
            events.filterIsInstance<RefreshEvent.VisualizationData>().map { it.name } shouldBe listOf("v")
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>().status shouldBe "COMPLETED"
        }
    }

    @Test
    fun `an abort flag raised late does not rescue a refresh whose target failed - it still ends ABORTED`() {
        runTest {
            // The "did not fully succeed" half of the #370 rule: the target failed at the abort
            // stage on its own (the execution was aborted by the route's cancellation), the flag
            // rides along — the row says ABORTED, as every viewer's abort of a failing refresh did.
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.After(30, Script.Aborted) }
            launch {
                delay(30)
                abortFlag.set(true)
            }

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.ABORTED
            result.targets.getValue("v") shouldBe TargetOutcome.Error("abort", PipelineErrorCodes.Execution.ABORTED)
            finishes.single().status shouldBe RefreshStatus.ABORTED
        }
    }

    @Test
    fun `an abort recorded before the job starts ends the refresh ABORTED before any source runs - no execution, the row closed`() {
        runTest {
            // #356: the flag was written while the refresh was still starting — the runtime answered 202 before the
            // row existed. The engine's check at job start ends it through the one terminal path: every target
            // aborted, the row closed ABORTED, audited, the last frame sent — and NO source was ever launched.
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Rows(columns("x" to LogicalType.INTEGER), listOf(listOf(1))) }
            abortFlag.set(true)

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.ABORTED
            result.targets.getValue("v") shouldBe TargetOutcome.Aborted
            launches.shouldBeEmpty() // no source execution was started for an aborted-at-start refresh
            cancelled.shouldBeEmpty() // nothing ran, so there is nothing to cancel
            finishes.single().status shouldBe RefreshStatus.ABORTED
            audited.single().status shouldBe RefreshStatus.ABORTED
            events.first().shouldBeInstanceOf<RefreshEvent.Started>()
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>().status shouldBe "ABORTED"
            events.filterIsInstance<RefreshEvent.SourceStarted>().shouldBeEmpty()
            events.filterIsInstance<RefreshEvent.VisualizationData>().shouldBeEmpty()
        }
    }

    @Test
    fun `cancelling the caller - the process stopping - STILL closes the row, audits it and sends the last frame`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Hang }
            val running = launch { engine().run(RefreshFixtures.job(body), ports) }
            runCurrent()

            running.cancel(CancellationException("shutdown"))
            running.join()

            finishes.single().status shouldBe RefreshStatus.ABORTED // not RUNNING forever
            audited.single().status shouldBe RefreshStatus.ABORTED
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>().status shouldBe "ABORTED"
            cancelled.size shouldBe 1
        }

    @Test
    fun `a fault in the job-start abort read ends the refresh FAILED like any bug in the work - never a row left RUNNING`() =
        runTest {
            // The #356 check reads the abort store before the fan-out; a read that THROWS (not a DataAccessException,
            // which the store already maps to false) was outside the try and escaped run() — the row stayed RUNNING
            // with no terminal frame. The read now sits inside the try (356 merge follow-up).
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            abortThrows = true

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.FAILED
            finishes.single().status shouldBe RefreshStatus.FAILED
            launches.shouldBeEmpty()
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>().status shouldBe "FAILED"
        }

    @Test
    fun `a bug in the work still ends the refresh - the row is closed FAILED and nothing escapes`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            val viz = RefreshFixtures.visualization(transform = TransformBinding(ref("t/one"), mapOf("only" to "main")))
            transform = { error("boom") }

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("v" to viz)), ports)

            result.status shouldBe RefreshStatus.FAILED
            finishes.single().status shouldBe RefreshStatus.FAILED
            finishes
                .single()
                .summary
                .path("reason")
                .asText() shouldBe "internal_error"
            result.targets.getValue("v") shouldBe TargetOutcome.Error("source", PipelineErrorCodes.Execution.INSTANCE_LOST)
        }

    @Test
    fun `a ledger that cannot write does not stop the audit or the last frame`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            finishThrows = true

            val result = engine().run(RefreshFixtures.job(body), ports)

            result.status shouldBe RefreshStatus.COMPLETED
            finishes.shouldBeEmpty()
            audited.size shouldBe 1
            events.last().shouldBeInstanceOf<RefreshEvent.Completed>()
        }

    @Test
    fun `the summary carries each target's outcome and each source's rows and bytes - never a row`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))

            engine().run(RefreshFixtures.job(body), ports)

            val summary = finishes.single().summary
            summary.path("status").asText() shouldBe "COMPLETED"
            summary
                .path("targets")
                .path("v")
                .path("outcome")
                .asText() shouldBe "ok"
            summary
                .path("sources")
                .path("s1")
                .path("rows")
                .asInt() shouldBe 2
            summary.toString().contains("\"x\"") shouldBe false
        }

    @Test
    fun `a target that reads no source completes without any execution or slot`() =
        runTest {
            val body = dashboard(emptyList(), listOf(occurrence("static", inputs = emptyMap())))
            val viz = RefreshFixtures.visualization(inputs = emptyMap(), bindings = emptyMap())

            val result = engine().run(RefreshFixtures.job(body, bodies = mapOf("static" to viz)), ports)

            launches.shouldBeEmpty()
            // no inputs and no transform: nothing to bind — a source-stage input_unbound, not a crash
            result.targets.getValue("static") shouldBe TargetOutcome.Error("source", DashboardErrorCodes.INPUT_UNBOUND)
            result.status shouldBe RefreshStatus.FAILED
        }

    @Test
    fun `the abort poll is cheap and the watcher does not delay a refresh that finishes at once`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))

            engine().run(RefreshFixtures.job(body), ports)

            testScheduler.currentTime shouldBe 0L // no virtual time passed: the watcher was cancelled with the work
        }

    @Test
    fun `an advance past the poll interval sees the abort flag within one poll`() =
        runTest {
            val body = dashboard(listOf(source("s1")), listOf(occurrence("v", inputs = mapOf("main" to "s1"))))
            scripts = { Script.Hang }
            val running = launch { engine().run(RefreshFixtures.job(body), ports) }
            runCurrent()

            abortFlag.set(true)
            advanceTimeBy(101)
            runCurrent()

            finishes.single().status shouldBe RefreshStatus.ABORTED
            running.join()
        }
}
