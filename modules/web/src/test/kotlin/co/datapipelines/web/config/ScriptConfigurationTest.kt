package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.scripting.ScriptLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Registry membership and the once-at-INFO boot report, with no native isolate needed. */
class ScriptConfigurationTest {
    @Test
    fun `only an available isolate registers javascript`() {
        val runtime = mockk<JavaScriptRuntime>()
        every { runtime.availability() } returns Availability.Available
        ScriptConfiguration().scriptEngines(runtime).keys shouldBe setOf(ScriptLanguage.JSONATA, ScriptLanguage.JAVASCRIPT)
        every { runtime.availability() } returns Availability.Unavailable("not installed")
        ScriptConfiguration().scriptEngines(runtime).keys shouldBe setOf(ScriptLanguage.JSONATA)
    }

    @Test
    fun `boot reports each availability state in exactly one INFO line`() {
        val logger = LoggerFactory.getLogger(JavaScriptStartupCheck::class.java) as Logger
        // raw-list-appender: synchronous — the startup check runs on the test thread over a mock
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val runtime = mockk<JavaScriptRuntime>()
            every { runtime.availability() } returns Availability.Unavailable("not installed")
            JavaScriptStartupCheck(runtime, ScriptProperties()).check()
            appender.list.size shouldBe 1
            appender.list.single().level shouldBe Level.INFO
            appender.list.single().formattedMessage shouldBe
                "script.js: isolate not installed (datapipelines.script.js.isolate-dir=): javascript templates are refused at save"
            appender.list.clear()
            every { runtime.availability() } returns Availability.Available
            val properties = ScriptProperties(js = ScriptProperties.Js(isolateDir = "/opt/graaljs"))
            JavaScriptStartupCheck(runtime, properties).check()
            appender.list.size shouldBe 1
            appender.list.single().level shouldBe Level.INFO
            appender.list.single().formattedMessage shouldContain "isolate loaded from /opt/graaljs"
            appender.list.single().formattedMessage shouldContain "engine per jvm; heap 256 MB, isolate 1280 MB"
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `the isolate ceiling sizes every pool slot with headroom and honours an override`() {
        ScriptProperties(poolSize = 8).isolateMemoryMb shouldBe 2304
        ScriptProperties(js = ScriptProperties.Js(isolateMemoryMb = 2048)).isolateMemoryMb shouldBe 2048
        ScriptConfiguration().javaScriptRuntime(ScriptProperties()).use { runtime ->
            runtime.options.isolateMemoryBytes shouldBe 1280L * 1024 * 1024
            runtime.options.maxHeapBytes shouldBe 256L * 1024 * 1024
            runtime.options.maxStatements shouldBe 100_000_000
        }
    }
}
