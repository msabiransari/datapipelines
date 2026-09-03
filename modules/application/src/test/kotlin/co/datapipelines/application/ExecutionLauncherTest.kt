package co.datapipelines.application

import co.datapipelines.executor.IdempotencyOutcome
import co.datapipelines.executor.IdempotencyStore
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The cross-aggregate launch decision (056/D6): bind, then reserve — and in that order.
 *
 * The store here is a recording fake rather than a strict mock, deliberately. The question this
 * suite asks most often is "was the reservation NOT made?", and a strict mock answers that by
 * throwing on an unstubbed call — which makes the absence of a call pass as green rather than be
 * observed. A fake that counts is the double that can see the defect.
 */
class ExecutionLauncherTest {
    private val store = RecordingIdempotencyStore()
    private val metrics = CountingMetrics()
    private val launcher = ExecutionLauncher(store, TTL_SECONDS, metrics)

    @Test
    fun `no idempotency key means no reservation at all`() {
        val decision = launcher.decide(launch(key = null))

        decision.shouldBeInstanceOf<LaunchDecision.Start>()
        decision.executionId shouldBe null
        withClue("a request without a key must not touch the store") { store.reservations shouldBe 0 }
    }

    @Test
    fun `a first key reserves and the reserved id becomes the execution id`() {
        val decision = launcher.decide(launch(key = "k1"))

        decision.shouldBeInstanceOf<LaunchDecision.Start>()
        withClue("dag-executor §11.2 — the reservation's id IS the execution id, with no alias layer") {
            decision.executionId shouldBe store.lastReserved
        }
        store.reservations shouldBe 1
    }

    @Test
    fun `a repeat of the same key attaches to the original and counts the hit`() {
        val first = launcher.decide(launch(key = "k1")) as LaunchDecision.Start

        val retry = launcher.decide(launch(key = "k1"))

        retry.shouldBeInstanceOf<LaunchDecision.Attach>()
        retry.executionId shouldBe first.executionId
        metrics.hits shouldBe 1
    }

    @Test
    fun `the same key with a different request is refused and counted as a conflict`() {
        launcher.decide(launch(key = "k1"))

        val error =
            shouldThrow<DatapipelinesException> {
                launcher.decide(launch(key = "k1", parametersJson = """{"month":"2026-08"}"""))
            }

        error.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED
        metrics.conflicts shouldBe 1
    }

    @Test
    fun `a rejected parameter is refused BEFORE the key is claimed`() {
        // The ordering rule, and the reason it is a rule: a reservation burned by a request that
        // never ran would make the caller's retry attach to an execution that does not exist.
        val required =
            pipeline().copy(parameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true)))

        shouldThrow<DatapipelinesException> { launcher.decide(launch(key = "k1", pipeline = required)) }

        withClue("nothing may be reserved when binding failed") { store.reservations shouldBe 0 }
    }

    @Test
    fun `the request hash covers the parameters, so a different key over the same body still reserves`() {
        launcher.decide(launch(key = "k1"))

        val other = launcher.decide(launch(key = "k2"))

        other.shouldBeInstanceOf<LaunchDecision.Start>()
        store.reservations shouldBe 2
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun launch(
        key: String?,
        pipeline: Pipeline = pipeline(),
        parametersJson: String = """{"month":"2026-07"}""",
    ) = ExecutionLaunch(
        pipelineId = PIPELINE_ID,
        pipelineVersion = 1,
        pipeline = pipeline,
        userId = USER,
        parameters = parameters(parametersJson),
        parametersJson = parametersJson,
        idempotencyKey = key,
    )

    private fun parameters(json: String): Map<String, JsonNode> = MAPPER.readTree(json).properties().associate { it.key to it.value }

    private fun pipeline() =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "monthly_revenue",
            displayName = "Monthly Revenue",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = emptyList(),
        )

    /** `SET NX` semantics in a map: first writer wins, an identical retry reads it, a different one is refused. */
    private class RecordingIdempotencyStore : IdempotencyStore {
        private val claims = mutableMapOf<String, Pair<String, UUID>>()

        var reservations = 0
            private set

        var lastReserved: UUID? = null
            private set

        override fun reserve(
            userId: UUID,
            idempotencyKey: String,
            requestHash: String,
            executionId: UUID,
            ttlSeconds: Long,
        ): IdempotencyOutcome {
            reservations++
            val key = "$userId:$idempotencyKey"
            val held = claims[key]
            if (held == null) {
                claims[key] = requestHash to executionId
                lastReserved = executionId
                return IdempotencyOutcome.Reserved(executionId)
            }
            if (held.first != requestHash) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED,
                    message = "Idempotency-Key was already used with a different request body.",
                    details = mapOf("idempotency_key" to idempotencyKey),
                )
            }
            return IdempotencyOutcome.Existing(held.second)
        }
    }

    private class CountingMetrics : IdempotencyMetrics {
        var hits = 0
            private set

        var conflicts = 0
            private set

        override fun reservationHit() {
            hits++
        }

        override fun reservationConflict() {
            conflicts++
        }
    }

    private companion object {
        const val TTL_SECONDS = 86_400L
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val PIPELINE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
