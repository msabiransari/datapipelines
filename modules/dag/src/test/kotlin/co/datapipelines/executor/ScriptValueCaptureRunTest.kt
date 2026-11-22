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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

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
            if (fails) throw IllegalStateException("diagnostic store down")
            values += value
            return true
        }

        override fun seal(seal: CaptureSeal): Boolean {
            if (fails) throw IllegalStateException("diagnostic store down")
            seals += seal
            return true
        }
    }

    private val now = Instant.parse("2026-11-22T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun capture(
        store: CaptureValueStore,
        budget: Long = 1_048_576,
        meters: SimpleMeterRegistry? = null,
        expiresAt: Instant = now.plus(Duration.ofHours(24)),
    ) = BoundedValueCapture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), expiresAt, budget, store, meters, clock)

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
        store.values.map { it.nodeId to it.outputKey } shouldBe listOf("shape_a" to "summary_a", "shape_b" to "summary_b")
        store.values.forEach { v ->
            v.omittedReason shouldBe null
            v.valueJson shouldBe """{"lines":2,"student":"$SELECTED_MARKER"}"""
            v.valueBytes shouldBe v.valueJson!!.toByteArray().size.toLong()
            v.valueJson shouldNotContain RAW_MARKER
            v.expiresAt shouldBe now.plus(Duration.ofHours(24))
        }
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_CAPTURED).count() shouldBe 2.0
        recorder.seal()
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
        harness().use { h -> run(h, capture(store, budget = one + one - 1)).status shouldBe ExecutionStatus.SUCCESS }
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
            h.emitter.events.filterIsInstance<NodeCompleted>().map { it.nodeId } shouldBe listOf("shape_a", "shape_b")
        }
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_FAILED).count() shouldBe 2.0
        recorder.seal()
        meters.counter(BoundedValueCapture.METER, BoundedValueCapture.OUTCOME_TAG, BoundedValueCapture.OUTCOME_SEAL_FAILED).count() shouldBe 1.0
    }

    @Test
    fun `a run past its detail expiry writes nothing`() {
        val store = RecordingStore()
        val expired = capture(store, expiresAt = now)
        harness().use { h -> run(h, expired).status shouldBe ExecutionStatus.SUCCESS }
        store.values.shouldBeEmpty()
        expired.seal()
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
                val terminal = h.emitter.events.filterIsInstance<PipelineCompleted>().single()
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
    }
}
