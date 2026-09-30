package co.datapipelines.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment
import java.io.ByteArrayInputStream

/**
 * The real encoders' emitted text (#337-b F1, F2, F4): the shared plant corpus goes through
 * [FormatSwitchingEncoder] under BOTH formats and BOTH text channels — the message and the
 * exception — and the bytes the encoder returns are what is asserted, not the appender's list.
 * The console half also pins the correlation fields: with distinct ids per event, no stale id from
 * a preceding event, and no raw-MDC dump.
 */
class EncoderOutputTest {
    private val mapper = ObjectMapper()
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val probe = context.getLogger("encoder-output-probe")
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun registerBootConvertersAndAttach() {
        val joran = JoranConfigurator()
        joran.context = context
        joran.doConfigure(
            ByteArrayInputStream(
                """
                <configuration>
                    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                </configuration>
                """.trimIndent().toByteArray(),
            ),
        )
        probe.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        probe.detachAppender(appender)
        context.removeObject(Environment::class.java.name)
        MDC.clear()
    }

    @Test
    fun `console message channel emits every plant scrubbed to the expected text`() {
        val encoder = started("console")
        (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).forEach { plant ->
            probe.info(plant.raw)
            val line = encode(encoder, appender.list.last())
            withClue("console message: ${plant.label}\n$line") { line shouldContain plant.scrubbed }
            SyntheticPlants.SECRET_MARKERS.forEach { withClue(plant.label) { line shouldNotContain it } }
        }
    }

    @Test
    fun `console exception channel emits every plant scrubbed to the expected text`() {
        val encoder = started("console")
        (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).forEach { plant ->
            probe.error("boom", IllegalStateException(plant.raw))
            val text = encode(encoder, appender.list.last())
            withClue("console exception: ${plant.label}\n$text") { text shouldContain "IllegalStateException: ${plant.scrubbed}" }
            SyntheticPlants.SECRET_MARKERS.forEach { withClue(plant.label) { text shouldNotContain it } }
        }
    }

    @Test
    fun `json message channel emits every plant scrubbed to the expected text`() {
        val encoder = started("json")
        (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).forEach { plant ->
            probe.info(plant.raw)
            val line = encode(encoder, appender.list.last())
            val message = mapper.readTree(line).path("message").asText()
            withClue("json message: ${plant.label}\n$line") { message shouldBe plant.scrubbed }
            SyntheticPlants.SECRET_MARKERS.forEach { withClue(plant.label) { line shouldNotContain it } }
        }
    }

    @Test
    fun `json exception channel emits every plant scrubbed to the expected text`() {
        val encoder = started("json")
        (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).forEach { plant ->
            probe.error("boom", IllegalStateException(plant.raw))
            val line = encode(encoder, appender.list.last())
            val trace = mapper.readTree(line).path("stack_trace").asText()
            withClue("json exception: ${plant.label}\n$line") { trace shouldContain "IllegalStateException: ${plant.scrubbed}" }
            withClue("json exception keeps the frames after an unterminated quote: ${plant.label}") {
                trace shouldContain "\tat "
            }
            SyntheticPlants.SECRET_MARKERS.forEach { withClue(plant.label) { line shouldNotContain it } }
        }
    }

    @Test
    fun `console lines carry the correlation and execution ids of their own event only`() {
        val encoder = started("console")
        val ids = listOf("3f2b8c1a-1111-4111-8111-aaaaaaaaaaaa" to "3f2b8c1a-2222-4222-8222-aaaaaaaaaaaa")
        val second = "3f2b8c1a-1111-4111-8111-bbbbbbbbbbbb" to "3f2b8c1a-2222-4222-8222-bbbbbbbbbbbb"

        val lines =
            (ids + second).map { (correlation, execution) ->
                MDC.put("correlation_id", correlation)
                MDC.put("execution_id", execution)
                probe.info("with context")
                encode(encoder, appender.list.last()).also { MDC.clear() }
            }
        probe.info("without context")
        val bare = encode(encoder, appender.list.last())

        withClue("first event\n${lines[0]}") {
            lines[0] shouldContain "correlation_id=${ids[0].first}"
            lines[0] shouldContain "execution_id=${ids[0].second}"
        }
        withClue("second event carries ITS ids, not the first's\n${lines[1]}") {
            lines[1] shouldContain "correlation_id=${second.first}"
            lines[1] shouldContain "execution_id=${second.second}"
            lines[1] shouldNotContain ids[0].first
            lines[1] shouldNotContain ids[0].second
        }
        withClue("an event with no context carries no stale id\n$bare") {
            bare shouldNotContain ids[0].first
            bare shouldNotContain second.first
            bare shouldNotContain second.second
            bare shouldContain "correlation_id=-"
            bare shouldContain "execution_id=-"
        }
    }

    @Test
    fun `console lines do not dump the raw MDC`() {
        val encoder = started("console")
        MDC.put("password", "planted-secret-mdc")
        MDC.put("some_other_key", "unrelated-value")
        probe.info("body")
        val line = encode(encoder, appender.list.last())

        line shouldNotContain "planted-secret-mdc"
        line shouldNotContain "unrelated-value"
    }

    @Test
    fun `json lines still carry both ids as members and nothing stale on a bare event`() {
        val encoder = started("json")
        MDC.put("correlation_id", "3f2b8c1a-1111-4111-8111-cccccccccccc")
        MDC.put("execution_id", "3f2b8c1a-2222-4222-8222-cccccccccccc")
        probe.info("with context")
        val withContext = mapper.readTree(encode(encoder, appender.list.last()))
        MDC.clear()
        probe.info("without context")
        val bare = mapper.readTree(encode(encoder, appender.list.last()))

        withContext.path("correlation_id").asText() shouldBe "3f2b8c1a-1111-4111-8111-cccccccccccc"
        withContext.path("execution_id").asText() shouldBe "3f2b8c1a-2222-4222-8222-cccccccccccc"
        bare.has("correlation_id") shouldBe false
        bare.has("execution_id") shouldBe false
    }

    private fun started(format: String): FormatSwitchingEncoder {
        if (format == "json") {
            val environment = MockEnvironment()
            ObservabilityLoggingFormatPostProcessor
                .structuredProperties("json")
                .forEach { (key, value) -> environment.setProperty(key, value) }
            context.putObject(Environment::class.java.name, environment)
        }
        return FormatSwitchingEncoder().apply {
            this.format = format
            context = this@EncoderOutputTest.context
            start()
        }
    }

    /** The bytes the encoder returns for [event] — encoded while this test's MDC is still set. */
    private fun encode(
        encoder: FormatSwitchingEncoder,
        event: ILoggingEvent,
    ): String = String(encoder.encode(event) ?: ByteArray(0))
}
