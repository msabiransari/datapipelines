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
        val failures =
            (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).mapNotNull { plant ->
                probe.info(plant.raw)
                val line = encode(encoder, appender.list.last())
                failure("console message", plant, line, line.contains(plant.scrubbed))
            }
        expectNone(failures)
    }

    @Test
    fun `console exception channel emits every plant scrubbed to the expected text`() {
        val encoder = started("console")
        val failures =
            (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).mapNotNull { plant ->
                probe.error("boom", IllegalStateException(plant.raw))
                val text = encode(encoder, appender.list.last())
                failure("console exception", plant, text, text.contains("IllegalStateException: ${plant.scrubbed}"))
            }
        expectNone(failures)
    }

    @Test
    fun `json message channel emits every plant scrubbed to the expected text`() {
        val encoder = started("json")
        val failures =
            (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).mapNotNull { plant ->
                probe.info(plant.raw)
                val line = encode(encoder, appender.list.last())
                val message = mapper.readTree(line).path("message").asText()
                failure("json message", plant, line, message == plant.scrubbed)
            }
        expectNone(failures)
    }

    @Test
    fun `json exception channel emits every plant scrubbed to the expected text`() {
        val encoder = started("json")
        val failures =
            (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).mapNotNull { plant ->
                probe.error("boom", IllegalStateException(plant.raw))
                val line = encode(encoder, appender.list.last())
                val trace = mapper.readTree(line).path("stack_trace").asText()
                // The frames after an unterminated quote must survive too: a stray quote masks its
                // own line and never the rest of the trace.
                failure(
                    "json exception",
                    plant,
                    line,
                    trace.contains("IllegalStateException: ${plant.scrubbed}") && trace.contains("\tat "),
                )
            }
        expectNone(failures)
    }

    @Test
    fun `hostile long inputs come through every format and channel unchanged and without error`() {
        // #337-c F6 smoke through the REAL encoders: text that holds no secret must be emitted
        // byte-for-byte however many sensitive-looking prefixes it repeats, with no stack overflow
        // and no error. The BOUND on the work is asserted by counted scan steps in LogRedactorTest
        // (a timing here would measure the machine); this proves the encoders survive the shapes.
        val hostile =
            listOf(
                "password_".repeat(8_000) + "z:",
                "password_secret_api_key_".repeat(3_000) + "z:",
                "\"" + "password_".repeat(8_000) + "\" z:",
                "a_".repeat(40_000) + "z=1",
                "\\\"".repeat(20_000) + ":",
            )
        listOf("console", "json").forEach { format ->
            val encoder = started(format)
            hostile.forEachIndexed { index, text ->
                probe.info(text)
                val message = messageText(format, encode(encoder, appender.list.last()))
                withClue("$format message, hostile input #$index (${text.length} chars)") {
                    if (format == "json") message shouldBe text else message shouldContain text
                }
                probe.error("boom", IllegalStateException(text))
                val trace = traceText(format, encode(encoder, appender.list.last()))
                withClue("$format exception, hostile input #$index") { trace shouldContain text.take(4_000) }
            }
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

    /**
     * A failure line for [plant] when [matched] is false OR any secret marker survives in [output];
     * null when the channel did exactly what §9.2 says. Collected so one red run lists every plant
     * that leaks, not just the first.
     */
    private fun failure(
        channel: String,
        plant: Plant,
        output: String,
        matched: Boolean,
    ): String? {
        val leaked = SyntheticPlants.SECRET_MARKERS.filter { output.contains(it) }
        return if (matched && leaked.isEmpty()) null else "$channel / ${plant.label}: leaked $leaked\n$output"
    }

    private fun expectNone(failures: List<String>) {
        withClue("plants the encoder did not scrub exactly:\n${failures.joinToString("\n")}") { failures.isEmpty() shouldBe true }
    }

    /** The message as the format carries it: the `message` member under json, the whole line under console. */
    private fun messageText(
        format: String,
        line: String,
    ): String = if (format == "json") mapper.readTree(line).path("message").asText() else line

    /** The exception text as the format carries it: the `stack_trace` member under json, the whole output under console. */
    private fun traceText(
        format: String,
        line: String,
    ): String = if (format == "json") mapper.readTree(line).path("stack_trace").asText() else line

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
