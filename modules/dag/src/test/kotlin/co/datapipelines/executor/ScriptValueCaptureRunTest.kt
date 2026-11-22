package co.datapipelines.executor

import co.datapipelines.events.NodeCompleted
import co.datapipelines.events.PipelineCompleted
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.RequestBodyDeclaration
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.scripting.JsonataEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.templates.ScriptContract
import co.datapipelines.templates.ScriptInput
import co.datapipelines.templates.ScriptMode
import co.datapipelines.templates.ScriptOutput
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #1043 — an administrator's runtime capture through the real executor: only declared value-mode transformer outputs are
 * recorded, keyed by node and declared output, under one byte budget across the whole execution; a failing diagnostic
 * store never changes the business run; and a run whose node values are private keeps them out of its ordinary history
 * (node stats, lifecycle events, the terminal Context snapshot) whether or not it is captured.
 */
class ScriptValueCaptureRunTest {
    private val engine = JsonataEngine()

    private val objectContract = ScriptContract(ScriptMode.VALUE, mapOf("order" to ScriptInput.Body()), ScriptOutput.Obj())

    /** Extracts the student field and leaves the rest of the body behind. */
    private val program = """{"student": inputs.order.student, "lines": ${'$'}count(inputs.order.lines)}"""

    private val support =
        ScriptSupport(
            resolver =
                ScriptVersionResolver { _, _ ->
                    ResolvedScript(
                        co.datapipelines.pipeline.TemplateType.JSONATA,
                        co.datapipelines.pipeline.PipelineVersionStatus.DRAFT,
                        program,
                        objectContract,
                        emptyList(),
                    )
                },
            pool = ScriptEvaluationPool(2, 8, Duration.ofSeconds(2), ScriptEvaluationPool.SYSTEM),
            engines = mapOf(ScriptLanguage.JSONATA to engine),
        )

    private fun valueNode(
        id: String,
        key: String,
        dependsOn: List<String> = emptyList(),
    ) = Node(
        id = id,
        description = "extracts the student",
        type = NodeType.SCRIPT,
        source = "",
        template = TemplateRef("acme/shape/student.jsonata", 1),
        output = null,
        contextKey = key,
        dependsOn = dependsOn,
        inputs = mapOf("order" to JsonNodeFactory.instance.textNode(RequestBodyDeclaration.REFERENCE)),
    )

    private val pipeline =
        Fixtures
            .pipeline(listOf(valueNode("shape_a", "summary_a"), valueNode("shape_b", "summary_b", listOf("shape_a"))))
            .copy(requestBody = RequestBodyDeclaration(required = true))

    private fun body(json: String): StructuredBody =
        StructuredBodyDecoder().decode(json.toByteArray()).shouldBeInstanceOf<StructuredBodyDecoding.Decoded>().body

    private val orderBody = body("""{"student":"$SELECTED_MARKER","secret":"$RAW_MARKER","lines":[{"sku":"A"},{"sku":"B"}]}""")

    /** A store that records what it is given, or throws when [fails]. */
    private class RecordingStore(
        private val fails: Boolean = false,
    ) : CaptureValueStore {
        val values = CopyOnWriteArrayList<CapturedValue>()
        val seals = CopyOnWriteArrayList<CaptureSeal>()

        override fun write(value: CapturedValue): Boolean {
            if (fails) error("diagnostic store down")
            values += value
            return true
        }

        override fun seal(seal: CaptureSeal): Boolean {
            if (fails) error("diagnostic store down")
            seals += seal
            return true
        }
    }

    private val now = Instant.parse("2026-11-22T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** The diagnostic write scope, as CaptureRecorders owns it in production: no business run is its parent. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun closeWrites() {
        writes.cancel()
    }

    private fun capture(
        store: CaptureValueStore,
        budget: Long = 1_048_576,
        meters: SimpleMeterRegistry? = null,
        expiresAt: Instant = now.plus(Duration.ofHours(24)),
    ) = BoundedValueCapture(
        executionId = UUID.randomUUID(),
        workspaceId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        expiresAt = expiresAt,
        budgetBytes = budget,
        store = store,
        writes = writes,
        meters = meters,
        clock = clock,
    )

    /** Ends recording as the launcher does, then waits — off the business path — for the drain to finish. */
    private fun sealAndDrain(recorder: BoundedValueCapture) {
        recorder.seal()
        runBlocking { withTimeout(DRAIN_TIMEOUT_MILLIS) { recorder.join() } }
    }

    private fun run(
        h: ExecutorHarness,
        capture: NodeValueCapture?,
        private: Boolean = true,
    ): ExecutionResult =
        runBlocking {
            h.executor.execute(
                Fixtures.request(pipeline).copy(requestBody = orderBody, privateNodeValues = private, valueCapture = capture),
            )
        }

    private fun harness() = ExecutorHarness(Fixtures.templateEngine("SELECT 1").first, scripts = support)

    @Test
    fun `a captured run records each declared output keyed by node and output, and no input`() {
        val store = RecordingStore()
        val meters = SimpleMeterRegistry()
        val recorder = capture(store, meters = meters)
        harness().use { h ->
            run(h, recorder).status shouldBe ExecutionStatus.SUCCESS
        }
        sealAndDrain(recorder)
        store.values.map { it.nodeId to it.outputKey } shouldBe listOf("shape_a" to "summary_a", "shape_b" to "summary_b")
        store.values.forEach { v ->
            v.omittedReason shouldBe null
            v.valueJson shouldBe """{"lines":2,"student":"$SELECTED_MARKER"}"""
            v.valueBytes shouldBe
                v.valueJson!!
                    .toByteArray()
                    .size
                    .toLong()
            v.valueJson shouldNotContain RAW_MARKER
            v.expiresAt shouldBe now.plus(Duration.ofHours(24))
        }
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_CAPTURED).count() shouldBe
            2.0
        store.seals.single().let {
            it.valuesCaptured shouldBe 2
            it.valuesOmitted shouldBe 0
            it.writeFailures shouldBe 0
            it.bytesUsed shouldBe store.values.sumOf { v -> v.valueBytes!! }
        }
    }

    @Test
    fun `the byte budget spans the execution - the value that does not fit is omitted with its reason and no preview`() {
        val store = RecordingStore()
        val one = """{"lines":2,"student":"$SELECTED_MARKER"}""".toByteArray().size.toLong()
        val recorder = capture(store, budget = one + one - 1)
        harness().use { h -> run(h, recorder).status shouldBe ExecutionStatus.SUCCESS }
        sealAndDrain(recorder)
        store.values[0].omittedReason shouldBe null
        store.values[1].let {
            it.nodeId shouldBe "shape_b"
            it.omittedReason shouldBe CapturedValue.OmittedReason.BYTE_BUDGET_EXHAUSTED
            it.valueJson shouldBe null
            it.valueBytes shouldBe one
        }
    }

    @Test
    fun `a failing diagnostic store leaves the business run successful and is counted without a value`() {
        val meters = SimpleMeterRegistry()
        val failing = RecordingStore(fails = true)
        val recorder = capture(failing, meters = meters)
        harness().use { h ->
            val result = run(h, recorder)
            result.status shouldBe ExecutionStatus.SUCCESS
            h.emitter.events
                .filterIsInstance<NodeCompleted>()
                .map { it.nodeId } shouldBe listOf("shape_a", "shape_b")
        }
        sealAndDrain(recorder)
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_FAILED).count() shouldBe 2.0
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_SEAL_FAILED).count() shouldBe
            1.0
    }

    /** A store whose writes block until [release] — diagnostic storage that is slow, or not answering. */
    private class HeldStore : CaptureValueStore {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val values = CopyOnWriteArrayList<CapturedValue>()
        val seals = CopyOnWriteArrayList<CaptureSeal>()

        override fun write(value: CapturedValue): Boolean {
            entered.countDown()
            check(release.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)) { "the held store was never released" }
            values += value
            return true
        }

        override fun seal(seal: CaptureSeal): Boolean {
            seals += seal
            return true
        }
    }

    @Test
    fun `slow diagnostic storage past the node deadline keeps the uncaptured business outcome, then drains and seals`() {
        fun bounded() =
            ExecutorHarness(
                Fixtures.templateEngine("SELECT 1").first,
                config = ExecutorConfig(nodeTimeoutSeconds = 1, executionTimeoutSeconds = 30),
                scripts = support,
            )
        // The control: the same script, input and one-second node deadline, uncaptured.
        val control = bounded().use { h -> run(h, null) }
        control.status shouldBe ExecutionStatus.SUCCESS
        val held = HeldStore()
        val recorder = capture(held)
        try {
            // Diagnostic storage holds its first write for longer than the node deadline (released only afterwards).
            val captured = bounded().use { h -> run(h, recorder) }
            held.entered.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            captured.status shouldBe control.status
            held.values.shouldBeEmpty() // the business run finished while its diagnostics were still waiting
            recorder.seal()
            Thread.sleep(NODE_DEADLINE_MILLIS * 2)
        } finally {
            held.release.countDown()
        }
        runBlocking { withTimeout(DRAIN_TIMEOUT_MILLIS) { recorder.join() } }
        held.values.map { it.nodeId } shouldBe listOf("shape_a", "shape_b")
        held.seals.single().valuesCaptured shouldBe 2
    }

    @Test
    fun `storage slower than the detail expiry - the drain ends at the expiry, writes nothing more and never seals`() {
        val held = HeldStore()
        val recorder = capture(held, expiresAt = now.plusSeconds(1))
        harness().use { h -> run(h, recorder).status shouldBe ExecutionStatus.SUCCESS }
        recorder.seal()
        held.entered.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(EXPIRY_MILLIS * 2) // the expiry passes while the first write is still blocked
        held.release.countDown()
        runBlocking { withTimeout(DRAIN_TIMEOUT_MILLIS) { recorder.join() } }
        held.values.map { it.nodeId } shouldBe listOf("shape_a") // the write in flight returns; nothing after it
        held.seals.shouldBeEmpty() // no summary: the diagnostic view reads the run as incomplete
    }

    @Test
    fun `record never waits on storage - past the queue's capacity a value is counted as failed, without a value`() {
        val held = HeldStore()
        val meters = SimpleMeterRegistry()
        val recorder = capture(held, meters = meters)
        try {
            recorder.record("node_0", "out", "first")
            held.entered.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val extra = 43
            (1..BoundedValueCapture.QUEUE_CAPACITY + extra).forEach { recorder.record("node_$it", "out", "v$it") }
            meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_FAILED).count() shouldBe
                extra.toDouble()
        } finally {
            held.release.countDown()
        }
        sealAndDrain(recorder)
        held.values.size shouldBe 1 + BoundedValueCapture.QUEUE_CAPACITY
        held.seals.single().writeFailures shouldBe 43
    }

    @Test
    fun `a run past its detail expiry writes nothing`() {
        val store = RecordingStore()
        val expired = capture(store, expiresAt = now)
        harness().use { h -> run(h, expired).status shouldBe ExecutionStatus.SUCCESS }
        sealAndDrain(expired)
        store.values.shouldBeEmpty()
        store.seals.shouldBeEmpty()
    }

    @Test
    fun `a private run keeps node values out of its stats, events and terminal snapshot - captured or not`() {
        listOf(capture(RecordingStore()), null).forEach { recorder ->
            harness().use { h ->
                run(h, recorder, private = true).status shouldBe ExecutionStatus.SUCCESS
                val completed = h.emitter.events.filterIsInstance<NodeCompleted>()
                completed.map { it.stats.contextKey } shouldBe listOf("summary_a", "summary_b")
                completed.forEach {
                    it.stats.contextValue shouldBe null
                    it.stats.contextValues shouldBe null
                }
                val terminal =
                    h.emitter.events
                        .filterIsInstance<PipelineCompleted>()
                        .single()
                terminal.nodeStats.forEach { it.contextValue shouldBe null }
                terminal.contextSnapshot.keys.contains("summary_a") shouldBe false
                terminal.contextSnapshot.keys.contains("summary_b") shouldBe false
                terminal.contextSnapshot.isEmpty() shouldBe false
                h.emitter.events.joinToString().let {
                    it shouldNotContain SELECTED_MARKER
                    it shouldNotContain RAW_MARKER
                }
            }
        }
    }

    @Test
    fun `an ordinary run keeps its history contract - the node value stays in its stats and snapshot`() {
        harness().use { h ->
            run(h, null, private = false).status shouldBe ExecutionStatus.SUCCESS
            h.emitter.events
                .filterIsInstance<NodeCompleted>()
                .first()
                .stats.contextValue!! shouldContain SELECTED_MARKER
            h.emitter.events
                .filterIsInstance<PipelineCompleted>()
                .single()
                .contextSnapshot.keys
                .contains("summary_a") shouldBe true
        }
    }

    @Test
    fun `scalar values serialize as JSON, and a value with no JSON form is null`() {
        BoundedValueCapture.serialize("a\"b") shouldBe "\"a\\\"b\""
        BoundedValueCapture.serialize(java.math.BigDecimal("12.50")) shouldBe "12.50"
        BoundedValueCapture.serialize(3) shouldBe "3"
        BoundedValueCapture.serialize(true) shouldBe "true"
        BoundedValueCapture.serialize(null) shouldBe "null"
        BoundedValueCapture.serialize(Double.NaN) shouldBe null
        BoundedValueCapture.serialize(java.time.LocalDate.parse("2026-11-22")) shouldBe "\"2026-11-22\""
    }

    private companion object {
        const val SELECTED_MARKER = "selected-out-7f3e"
        const val RAW_MARKER = "raw-in-91ac"
        const val DRAIN_TIMEOUT_MILLIS = 30_000L
        const val HOLD_LIMIT_SECONDS = 30L
        const val NODE_DEADLINE_MILLIS = 1_000L
        const val EXPIRY_MILLIS = 1_000L
    }
}
