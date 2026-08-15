package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The one place a thrown failure becomes an HTTP response (rest-api §4.2), exercised through a
 * real MVC pipeline (standalone setup — no Spring context, no security chain; the filters' own
 * tests cover those). Proves the envelope shape, the catalog-driven status, and the correlation
 * id landing in the body.
 */
class ApiExceptionHandlerTest {
    @RestController
    class ProbeController {
        @GetMapping("/probe/domain")
        fun domain(): Nothing = throw ApiErrors.pipelineNotFound("pipe-1")

        @GetMapping("/probe/unexpected")
        fun unexpected(): Nothing = error("boom")

        @GetMapping("/probe/unreachable")
        fun unreachable(): Nothing =
            throw co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                message = "Datasource 'pg-prod' could not be reached for schema introspection.",
                details = mapOf("datasource" to "pg-prod"),
            )
    }

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ProbeController())
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    @Test
    fun `a catalogued failure renders the full error envelope with its mapped status`() {
        mvc
            .perform(get("/probe/domain"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.schema_version").value(1))
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Execution.NOT_FOUND))
            .andExpect(jsonPath("$.error.user_message").exists())
            .andExpect(jsonPath("$.error.doc_url").value("https://docs.datapipelines.co/errors/pipeline-execution-not-found"))
            .andExpect(jsonPath("$.correlation_id").exists())
    }

    @Test
    fun `an unexpected failure is a 500 envelope, never a container error page`() {
        val result = mvc.perform(get("/probe/unexpected")).andExpect(status().isInternalServerError).andReturn()
        result.response.contentAsString shouldContain "\"reason\":\"internal_error\""
        result.response.contentAsString.contains("IllegalStateException") shouldBe false
    }

    @Test
    fun `a customer database being down is 502 logged at WARN - not a 5xx ERROR stack`() {
        // 502 means the DOWNSTREAM is broken (the caller's own database, typically) — an ERROR
        // with a stack per request would bury the operator's real incidents.
        val logger = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(get("/probe/unreachable")).andExpect(status().isBadGateway)

            val levels = appender.list.map { it.level.toString() }
            assert(levels.contains("WARN")) { "expected a WARN event for 502, got $levels" }
            assert(!levels.contains("ERROR")) { "a customer DB being down must not log ERROR, got $levels" }
        } finally {
            logger.detachAppender(appender)
        }
    }
}
