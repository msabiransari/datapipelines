package co.datapipelines.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
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

/**
 * The console encoder's guards (#337 D1/D2): both switch values START and scrub in a REAL logback
 * context. The JVM's own [LoggerFactory] context supplies the MDC adapter the structured formatter
 * reads; Boot's `defaults.xml` is processed through Joran so the pattern's `%clr`/`%wEx` words are
 * registered exactly as the production configuration registers them. The full packaged-output
 * proof (child JVM, both formats end to end) stays in [PlantOutputTest].
 */
class FormatSwitchingEncoderTest {
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val probe = context.getLogger("format-switching-probe")
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @BeforeEach
    fun registerBootConvertersAndAttach() {
        // defaults.xml is an <included> fragment: a minimal configuration wrapper lets Joran
        // process its conversion rules (clr/esb/wEx and friends) exactly as the production
        // logback-spring.xml include does.
        val joran = JoranConfigurator()
        joran.context = context
        joran.doConfigure(
            java.io.ByteArrayInputStream(
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
    }

    @Test
    fun `the console path starts and scrubs the message and exception text`() {
        val encoder = FormatSwitchingEncoder().apply { format = "console" }
        encoder.context = context
        encoder.start()

        probe.info("boot line password=planted-secret-console and \"api_key\": \"planted-secret-pair\" tail")
        probe.error(
            "boom",
            IllegalStateException("connect failed jdbc_url=jdbc:postgresql://svc:planted-secret-exc@db:5432/dp"),
        )

        val console = appender.list.joinToString("") { String(encoder.encode(it) ?: ByteArray(0)) }
        console.shouldContain("password=***")
        console.shouldContain("\"api_key\": \"***\"")
        console.shouldContain("jdbc_url=***")
        console.shouldNotContain("planted-secret-")
    }

    @Test
    fun `the console line holds no unparsed pattern text and every redaction word ran`() {
        // A redaction word the pattern parser did not recognise prints itself literally and scrubs
        // nothing (logback reads a `%` right after a composite's `)` as plain text): the line must
        // carry neither the word nor a stray pattern delimiter, and the secret must still be gone.
        val encoder = FormatSwitchingEncoder().apply { format = "console" }
        encoder.context = context
        encoder.start()
        probe.error("message password=planted-secret-one", IllegalStateException("cause secret=planted-secret-two"))
        val line = String(encoder.encode(appender.list.single()) ?: ByteArray(0))

        withClue("rendered line:\n$line") {
            line.shouldNotContain("dpRedact")
            line.shouldNotContain("%")
            line.shouldNotContain("PARSER_ERROR")
            line.shouldContain("password=***")
            line.shouldContain("IllegalStateException: cause secret=***")
            line.shouldNotContain("planted-secret-")
        }
        context.statusManager.copyOfStatusList
            .filter { it.level >= ch.qos.logback.core.status.Status.ERROR && it.message.contains("dpRedact") }
            .shouldBeEmpty()
    }

    @Test
    fun `the json path starts with the environment in the logger context and scrubs members`() {
        val environment = MockEnvironment()
        // The mapping the post-processor installs for json — the same properties, through the
        // same function, so the test cannot drift from the binding.
        ObservabilityLoggingFormatPostProcessor
            .structuredProperties("json")
            .forEach { (key, value) -> environment.setProperty(key, value) }
        context.putObject(Environment::class.java.name, environment)
        val encoder = FormatSwitchingEncoder().apply { format = "json" }
        encoder.context = context
        encoder.start()

        MDC.put("password", "planted-secret-member")
        try {
            probe.info("body line")
        } finally {
            MDC.remove("password")
        }
        val line = String(encoder.encode(appender.list.single()) ?: ByteArray(0))

        withClue("one JSON object with the member masked") {
            line.shouldContain("\"password\":\"***\"")
            line.shouldNotContain("planted-secret-member")
            line.trim().shouldStartWithJson()
        }
    }

    @Test
    fun `an unknown format is refused at the encoder too`() {
        val encoder = FormatSwitchingEncoder().apply { format = "yaml" }
        encoder.context = context
        val failure = runCatching { encoder.start() }.exceptionOrNull()
        failure?.message.shouldContain("expected 'json' or 'console'")
    }

    private fun String.shouldStartWithJson() {
        if (!trim().startsWith("{")) throw AssertionError("expected a JSON line, was: $this")
    }
}
