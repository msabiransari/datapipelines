package co.datapipelines.logging

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stdout of a NORMAL Spring Boot startup (#337-b banner ruling; observability.md §3.1): under
 * `json` every non-blank line is one JSON object — Boot's banner, which prints to stdout before
 * logging exists, is suppressed by the switch's binding; under `console` the banner is still
 * there, which is the non-vacuity witness for the json case (the same boot, one line of
 * difference). An unknown value refuses loudly and BEFORE startup: no banner, no context.
 *
 * Each case runs `PlantBootMain` in a fresh JVM over this test's runtime classpath, so logging's
 * process-global state stays out of the test JVM.
 */
class NormalBootOutputTest {
    private val mapper = ObjectMapper()

    @Test
    fun `json startup stdout is JSON lines only`() {
        val run = boot("json")

        val lines = run.stdout.lines().filter { it.isNotBlank() }
        withClue("non-vacuity: a normal boot writes startup lines\n${run.stderr}") { lines.size shouldBeGreaterThanOrEqual 3 }
        val parsed =
            lines.mapIndexed { index, line ->
                withClue("stdout line ${index + 1} must be one JSON object: $line") { mapper.readTree(line) }
            }
        withClue("Boot's own startup line arrived as a record") {
            parsed.any { it.path("message").asText("").startsWith("Started PlantBootMainKt") } shouldBe true
        }
        run.stdout shouldNotContain ":: Spring Boot ::"
        run.stdout shouldNotContain "planted-secret-"
        withClue("the planted line is masked") {
            parsed.any { it.path("message").asText("").contains("password=*** tail") } shouldBe true
        }
    }

    @Test
    fun `console startup keeps the banner and scrubs the plant`() {
        val run = boot("console")

        withClue("non-vacuity: the banner IS printed when nothing suppresses it") {
            run.stdout shouldContain ":: Spring Boot ::"
        }
        run.stdout shouldNotContain "planted-secret-"
        run.stdout shouldContain "password=*** tail"
    }

    @Test
    fun `an unknown format refuses loudly before startup`() {
        val run = boot("yaml", expectSuccess = false)

        val everything = run.stdout + run.stderr
        withClue("the refusal names the value and the closed set\n$everything") {
            everything shouldContain "yaml"
            everything shouldContain "expected 'json' or 'console'"
        }
        run.exitCode shouldNotBe 0
        withClue("pre-startup: no banner and no started line") {
            everything shouldNotContain ":: Spring Boot ::"
            everything shouldNotContain "Started PlantBootMainKt"
        }
    }

    private data class Run(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** Runs the boot payload in a fresh JVM with the switch pinned. */
    private fun boot(
        format: String,
        expectSuccess: Boolean = true,
    ): Run {
        val errFile = File.createTempFile("normal-boot", ".err")
        try {
            val process =
                ProcessBuilder(
                    "${System.getProperty("java.home")}/bin/java",
                    "-D${ObservabilityLoggingFormatPostProcessor.SWITCH_KEY}=$format",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "co.datapipelines.logging.PlantBootMainKt",
                ).redirectError(errFile).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            withClue("the child must exit on its own") { finished shouldBe true }
            val run = Run(process.exitValue(), stdout, errFile.readText())
            if (expectSuccess) {
                withClue("child exit ${run.exitCode}\n${run.stderr}") { run.exitCode shouldBe 0 }
            }
            return run
        } finally {
            errFile.delete()
        }
    }
}
