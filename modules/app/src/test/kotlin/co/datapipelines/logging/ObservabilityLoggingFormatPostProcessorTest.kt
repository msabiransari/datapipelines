package co.datapipelines.logging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/**
 * The switch's binding (#337 D2): the closed value set, the refusal, and what each mapping puts in
 * the environment BEFORE logging initialises. The ordering fact — that these properties are read by
 * the logging system and not by anything later — is proven end to end by [PlantOutputTest] and by
 * the lane instance's `docker logs`; this suite pins the mapping itself.
 */
class ObservabilityLoggingFormatPostProcessorTest {
    @Test
    fun `json maps to the structured side's redaction wiring`() {
        val mapped = ObservabilityLoggingFormatPostProcessor.structuredProperties("json")

        mapped["logging.structured.json.customizer"] shouldBe
            "co.datapipelines.logging.RedactingJsonMembersCustomizer"
        mapped["logging.structured.json.stacktrace.printer"] shouldBe
            "co.datapipelines.logging.RedactingStackTracePrinter"
        mapped["logging.structured.json.rename.logger_name"] shouldBe "logger"
        mapped["logging.structured.json.rename.thread_name"] shouldBe "thread"
    }

    @Test
    fun `json also switches the banner off so stdout carries records only`() {
        ObservabilityLoggingFormatPostProcessor.structuredProperties("json")["spring.main.banner-mode"] shouldBe "off"
    }

    @Test
    fun `console adds nothing - the encoder carries its own redacting pattern`() {
        ObservabilityLoggingFormatPostProcessor.structuredProperties("console") shouldBe emptyMap()
    }

    @Test
    fun `an unknown value refuses with the offending value named`() {
        val failure =
            shouldThrow<IllegalStateException> {
                ObservabilityLoggingFormatPostProcessor.structuredProperties("yaml")
            }
        failure.message.shouldContain("datapipelines.observability.logging.format")
        failure.message.shouldContain("yaml")
        failure.message.shouldContain("'json' or 'console'")
    }

    @Test
    fun `the post processor installs the mapped properties as one property source`() {
        val environment = MockEnvironment()
        environment.setProperty(ObservabilityLoggingFormatPostProcessor.SWITCH_KEY, "json")

        ObservabilityLoggingFormatPostProcessor().postProcessEnvironment(environment, null as SpringApplication?)

        environment.getProperty("logging.structured.json.customizer") shouldBe
            "co.datapipelines.logging.RedactingJsonMembersCustomizer"
        environment
            .propertySources
            .get(ObservabilityLoggingFormatPostProcessor.PROPERTY_SOURCE_NAME)
            .shouldNotBeNull()
    }

    @Test
    fun `a value from the environment wins and an unknown one is refused at the entry point`() {
        val environment = MockEnvironment()
        environment.setProperty(ObservabilityLoggingFormatPostProcessor.SWITCH_KEY, "yolo")

        val failure =
            shouldThrow<IllegalStateException> {
                ObservabilityLoggingFormatPostProcessor().postProcessEnvironment(environment, null as SpringApplication?)
            }
        failure.message.shouldContain("yolo")
    }

    private fun <T : Any> T?.shouldNotBeNull(): T = this ?: throw AssertionError("expected a property source, found null")
}
