package co.datapipelines.web.dependencies

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.capturingLogEvents
import co.datapipelines.web.config.ReleaseDependencyProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Duration

/**
 * **The tree's deadline and bounds on the wire** (#875 G4 §D, record §25.4) — through the real [ApiExceptionHandler] and
 * the limits the production derivation builds from [ReleaseDependencyProperties] (`metadata-timeout-seconds` = 1). A
 * provider read "takes" time by moving the test's clock ([MovingTreeClock]) — what a sleeping provider does to the
 * deadline, without a sleeping test. Each case reads the handler's own log line: a retryable 503 is ONE WARN without a
 * stack; a byte-budget refusal is an ERROR with the stack (an operator's configuration fact).
 */
class DependenciesDeadlineTest {
    private val clock = MovingTreeClock()
    private val pipelines = StubFamily(FamilyKey.PIPELINE)

    private fun limits(
        properties: ReleaseDependencyProperties = ReleaseDependencyProperties(metadataTimeoutSeconds = 1, statementTimeoutSeconds = 1),
    ): GraphLimits = DependenciesConfiguration.treeLimits(properties.toBounds(), properties)

    private fun get(
        limits: GraphLimits = limits(),
        root: String = ROOT,
    ): Pair<MvcResult, List<ILoggingEvent>> {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(member(), null, emptyList())
        val mvc =
            MockMvcBuilders
                .standaloneSetup(
                    DependenciesController(
                        treeService(listOf(pipelines), listOf(PIPELINE_ROW), limits, clock = clock),
                    ),
                ).setControllerAdvice(ApiExceptionHandler())
                .build()
        lateinit var result: MvcResult
        val events = capturingLogEvents(HANDLER_LOG) { result = mvc.perform(tree("pipeline", "name=${enc(root)}&version=1")).andReturn() }
        return result to events.filter { it.level.isGreaterOrEqual(Level.WARN) }
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    /** A root with [depth] pipelines chained below it, each its own frontier — one provider read per level. */
    private fun chain(depth: Int) {
        var below: List<ObjectRequest> = emptyList()
        (depth downTo 1).forEach { level ->
            below = listOf(pipelines.put(exact(FamilyKey.PIPELINE, "acme/level_$level", 1), Lifecycle.RELEASED, *below.toTypedArray()))
        }
        pipelines.put(exact(FamilyKey.PIPELINE, ROOT, 1), Lifecycle.RELEASED, *below.toTypedArray())
    }

    @Test
    fun `a read running past metadata-timeout-seconds is 503 metadata unavailable, Retry-After 5, one WARN without stack`() {
        chain(1)
        pipelines.beforeBatch = { _, _ -> clock.advance(Duration.ofMillis(1_100)) }

        val (result, lines) = get()

        result.retryable503()
        withClue("the handler's lines at WARN and above: ${lines.map { it.formattedMessage }}") {
            lines.size shouldBe 1
            lines.single().level shouldBe Level.WARN
            lines.single().throwableProxy.shouldBeNull()
        }
        println("event=dependencies.deadline_warn line=${lines.single().formattedMessage}")
        pipelines.calls.size shouldBe 1
    }

    @Test
    fun `a last read that returns after the deadline is refused, though nothing was left to read`() {
        chain(0)
        pipelines.beforeBatch = { _, _ -> clock.advance(Duration.ofMillis(1_100)) }

        val (result, _) = get()

        result.retryable503()
        pipelines.calls.size shouldBe 1
    }

    @Test
    fun `a provider that throws is the same 503 - no partial tree and none of the failure's text`() {
        chain(1)
        pipelines.beforeBatch = { requests, _ ->
            if (requests.single().address.lookupKey != ROOT) throw DataAccessResourceFailureException(FAILURE)
        }

        val (result, lines) = get()

        result.retryable503()
        result.response.contentAsString shouldNotContain FAILURE
        result.response.contentAsString shouldNotContain "acme/"
        lines.single().throwableProxy.shouldBeNull()
        pipelines.calls.size shouldBe 2
    }

    @Test
    fun `a projected tree over max-wire-bytes is 503 too large with a stack-bearing ERROR and no tree`() {
        chain(2)

        val (result, lines) = get(limits().copy(wireBytes = SMALL_WIRE_BYTES))

        result.response.status shouldBe HTTP_UNAVAILABLE
        result.code() shouldBe PipelineErrorCodes.Dependencies.RESPONSE_TOO_LARGE
        result.response.getHeader("Retry-After").shouldBeNull()
        result.body().has("data") shouldBe false
        lines.single().level shouldBe Level.ERROR
        lines.single().throwableProxy.shouldNotBeNull()
    }

    @Test
    fun `a walk stopped by a traversal limit is 200 complete false with a LIMIT diagnostic`() {
        chain(3)

        val (result, lines) = get(limits().copy(nodes = 2))

        result.response.status shouldBe HTTP_OK
        val data = result.body().path("data")
        data.path("complete").asBoolean() shouldBe false
        data.path("diagnostics").map { it.path("code").asText() } shouldContain "LIMIT"
        lines shouldBe emptyList()
    }

    @Test
    fun `five reads of 0_4 s under a 1 s deadline refuse after the third - no single read reaches the statement cap`() {
        chain(4)
        pipelines.beforeBatch = { _, resolve ->
            withClue("each read is shorter than the statement cap: only the cumulative deadline can stop the walk") {
                (checkNotNull(resolve.deadline.statementLimit) > Duration.ofMillis(READ_MILLIS)) shouldBe true
            }
            clock.advance(Duration.ofMillis(READ_MILLIS))
        }

        val (result, _) = get()

        result.retryable503()
        pipelines.calls.size shouldBe 3
    }

    private fun MvcResult.body() = MAPPER.readTree(response.contentAsString)

    private fun MvcResult.code(): String = body().path("error").path("code").asText()

    /** The §4.2 envelope only — `schema_version`, `correlation_id`, `error` — with the retryable code and Retry-After 5. */
    private fun MvcResult.retryable503() {
        withClue(response.contentAsString) {
            response.status shouldBe HTTP_UNAVAILABLE
            code() shouldBe PipelineErrorCodes.Dependencies.METADATA_UNAVAILABLE
            response.getHeader("Retry-After") shouldBe "5"
            body().fieldNames().asSequence().toSet() shouldBe setOf("schema_version", "correlation_id", "error")
        }
    }

    private companion object {
        const val ROOT = "acme/root"
        const val FAILURE = "metadata replica pg-metadata-2 refused the connection"
        const val HTTP_OK = 200
        const val HTTP_UNAVAILABLE = 503
        const val READ_MILLIS = 400L

        /** At the configuration floor's order: a three-node tree projects to more than this. */
        const val SMALL_WIRE_BYTES = 256
        val MAPPER = jacksonObjectMapper()
        val HANDLER_LOG = LoggerFactory.getLogger(ApiExceptionHandler::class.java) as Logger
        val PIPELINE_ROW = DependencyFamilyTransport.production(DependenciesRequestTest.LENSES).row(FamilyKey.PIPELINE)
    }
}
