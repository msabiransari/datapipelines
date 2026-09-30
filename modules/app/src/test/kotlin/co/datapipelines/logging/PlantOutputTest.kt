package co.datapipelines.logging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The packaged-output proof (#337 D4b, the issue's acceptance): with the format at `json`, stdout
 * is one JSON object per line carrying the standard fields, EVERY planted secret is `***`, and the
 * never-redacted context stays readable. The same run under `console` proves the redaction runs
 * under every format value (§9.2 is normative — no format is a bypass).
 *
 * Each case spawns a FRESH JVM ([PlantMain]) over this test's own runtime classpath: logging
 * initialisation is process-global, so the proof never re-initialises logging inside this JVM and
 * poisons no later suite. Each case is also its own non-vacuity witness: the parsed-line count and
 * the per-plant key sightings are asserted, not assumed.
 */
class PlantOutputTest {
    private val mapper = ObjectMapper()

    @Test
    fun `json output is one JSON object per line with every plant scrubbed and the context intact`() {
        val (out, err) = runPlant("json")

        val lines = out.lines().filter { it.isNotBlank() }
        withClue("non-vacuity: the child logged its lines\n$err") { lines.size shouldBeGreaterThanOrEqual 3 }

        val parsed =
            lines.mapIndexed { index, line ->
                withClue("line ${index + 1} must be one JSON object: $line") {
                    mapper.readTree(line)
                }
            }

        parsed.forEach { node ->
            withClue("standard fields on $node") {
                node.has("@timestamp") shouldBe true
                node.has("level") shouldBe true
                node.has("logger") shouldBe true
                node.has("thread") shouldBe true
                node.has("message") shouldBe true
            }
        }

        out.shouldNotContain("planted-secret-")

        val texts = parsed.map { it.toString() }
        withClue("the MDC plant is masked, key kept") { texts.any { it.contains("\"password\":\"***\"") } } shouldBe true
        withClue("the key-value pair plant is masked, key kept") { texts.any { it.contains("\"jdbc_url\":\"***\"") } } shouldBe true
        withClue("the message's key=value plant is scrubbed in the rendered text") {
            texts.any { messageOf(it).contains("password=***") }
        } shouldBe true
        withClue("the message's quoted-pair plant is scrubbed") {
            texts.any { messageOf(it).contains("\"api_key\": \"***\"") }
        } shouldBe true
        withClue("the exception's quoted URL is scrubbed in the stack trace") {
            val stackTrace = texts.filter { it.contains("stack_trace") }.map { stackTraceOf(it) }
            stackTrace.any { it.contains("jdbc_url=***") || it.contains("jdbc:postgresql://svc:***") }
        } shouldBe true

        withClue("never redacted: the correlation id survives verbatim") {
            texts.any { it.contains("3f2b8c1a-1111-4111-8111-111111111111") }
        } shouldBe true
        withClue("never redacted: the execution id survives verbatim") {
            texts.any { it.contains("3f2b8c1a-2222-4222-8222-222222222222") }
        } shouldBe true
    }

    @Test
    fun `console output scrubs the message and the stack trace too`() {
        val (out, err) = runPlant("console")

        withClue("non-vacuity: the child logged its lines\n$err") {
            out.lines().count { it.isNotBlank() } shouldBeGreaterThanOrEqual 3
        }
        out.shouldNotContain("planted-secret-")
        out.shouldContain("password=***")
        out.shouldContain("\"api_key\": \"***\"")
    }

    /** Runs [PlantMain] in a fresh JVM with the format pinned; returns stdout and stderr. */
    private fun runPlant(format: String): Pair<String, String> {
        val classpath = System.getProperty("java.class.path")
        withClue("the test JVM must expose its full runtime classpath to hand the child") {
            classpath.shouldContain("logback")
        }
        val process =
            ProcessBuilder(
                "${System.getProperty("java.home")}/bin/java",
                "-D${ObservabilityLoggingFormatPostProcessor.SWITCH_KEY}=$format",
                "-cp",
                classpath,
                "co.datapipelines.logging.PlantMainKt",
            ).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        withClue("the child must exit on its own") { finished shouldBe true }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        withClue("child exit ${process.exitValue()}\n$err") { process.exitValue() shouldBe 0 }
        return out to err
    }

    /** The `message` member's JSON text, unescaped once. */
    private fun messageOf(jsonLine: String): String =
        mapper
            .readTree(jsonLine)
            .let { node: JsonNode -> node.path("message").asText("") }

    private fun stackTraceOf(jsonLine: String): String = mapper.readTree(jsonLine).path("stack_trace").asText("")
}
