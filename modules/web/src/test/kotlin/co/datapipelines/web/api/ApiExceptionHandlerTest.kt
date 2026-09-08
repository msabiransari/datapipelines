package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
                cause = java.sql.SQLException("Pool init failed", java.io.IOException("HTTP 403 Forbidden on listing")),
            )

        /**
         * 098 §C, request shape 1 — `POST /api/v1/endpoints` with the required `path` omitted.
         * Real binding through the message converter, so the exception is the one production
         * raises: Jackson's `MissingKotlinParameterException` (a [MismatchedInputException]),
         * wrapped by the converter in `HttpMessageNotReadableException`. The URI is the real one
         * because `malformedBodyCodeFor` keys the code on it.
         */
        @PostMapping("/api/v1/endpoints")
        fun createEndpoint(
            @RequestBody body: ProbeEndpointRequest,
        ): ProbeEndpointRequest = body

        /**
         * 098 §C, request shape 2 — `POST /api/v1/pipelines` with `source` an object instead of
         * a string. What `PipelineService.validate` does: the body is already bound, and the
         * service RE-READS the tree through `PipelineDeserializer.readOrThrow`. That read is
         * behind the message converter, so its failure is a RAW [MismatchedInputException] with
         * no `HttpMessageNotReadableException` around it — the reason it used to reach the
         * `Throwable` backstop.
         */
        @PostMapping("/api/v1/pipelines")
        fun createPipeline(
            @RequestBody body: JsonNode,
        ): Any = jacksonObjectMapper().treeToValue(body.get("nodes").get(0), ProbeNode::class.java)

        @GetMapping("/probe/query-failed")
        fun queryFailed(): Nothing =
            throw co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                message = "Syntax error in the rendered SQL.",
                details = mapOf("node_id" to "n1"),
            )
    }

    /** `endpoints_create`'s shape, reduced to the field 093 §5 omitted. */
    data class ProbeEndpointRequest(
        val path: String,
        val pipeline: String,
    )

    /** A pipeline node, reduced to the field 093 §5 sent as an object. */
    data class ProbeNode(
        val id: String,
        val source: String,
    )

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ProbeController())
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    /**
     * The same setup with a KOTLIN-aware ObjectMapper on the converter — without the module a
     * missing constructor parameter binds `null` and blows up as a NullPointerException, which
     * is not the exception production raises and would make these two tests measure the harness.
     */
    private val bodyMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(ProbeController())
            .setMessageConverters(MappingJackson2HttpMessageConverter(jacksonObjectMapper()))
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
        // The demotion keys on the ERROR CODE, not the status: only the codes meaning "the
        // caller's own downstream is down" (datasource_unreachable,
        // datasource_connection_failed) earn WARN — a 502 status alone proves nothing.
        val logger = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(get("/probe/unreachable")).andExpect(status().isBadGateway)

            val levels = appender.list.map { it.level.toString() }
            assertAll(
                { levels shouldContain "WARN" },
                { levels shouldNotContain "ERROR" },
                { appender.list.single().throwableProxy shouldBe null },
                // …but the driver's reason is IN the line: the static message alone had an
                // operator reproducing a lake connect by hand to learn it was an S3 403.
                { appender.list.single().formattedMessage shouldContain "IOException: HTTP 403 Forbidden on listing" },
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a 502 status outside the allowlist logs ERROR with the stack`() {
        // query_execution_failed also maps to 502, but it can be OUR bug (the rendered SQL we
        // produced) — keying the demotion on the status would bury it. It must log ERROR with
        // the throwable attached.
        val logger = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(get("/probe/query-failed")).andExpect(status().isBadGateway)

            val levels = appender.list.map { it.level.toString() }
            assertAll(
                { levels shouldContain "ERROR" },
                { levels shouldNotContain "WARN" },
                { appender.list.single().throwableProxy shouldNotBe null },
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    /**
     * 093 §5's table, row 1. Measured on the demo stack before the fix (2026-09-08): 400, but
     * `pipeline.validation.schema_version_unsupported` with the user message "This pipeline
     * isn't valid yet" — a pipeline verdict on a request that names no pipeline.
     */
    @Test
    fun `a malformed endpoints body is 400 with an endpoint code, not a pipeline one`() {
        bodyMvc
            .perform(
                post("/api/v1/endpoints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"pipeline":"nyc/mobility/weather_sensitivity_by_borough"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Endpoint.PATH_INVALID))
            .andExpect(jsonPath("$.error.details.reason").value(ApiErrors.MALFORMED_JSON))
            .andExpect(jsonPath("$.correlation_id").exists())
    }

    /**
     * 093 §5's table, row 2. Measured on the demo stack before the fix (2026-09-08):
     * `500 pipeline.execution.aborted` / "Unexpected server error." — the caller's malformed
     * body reported as the server's failure, with a stack trace in the operator's log.
     */
    @Test
    fun `a raw Jackson MismatchedInputException from inside the service is 400, not the 500 backstop`() {
        bodyMvc
            .perform(
                post("/api/v1/pipelines")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"nodes":[{"id":"n1","source":{"name":"sample-trips"}}]}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Validation.SCHEMA_VERSION_UNSUPPORTED))
            .andExpect(jsonPath("$.error.details.reason").value(ApiErrors.MALFORMED_JSON))
    }

    /** The caller error must not be logged as an operator incident (rules/02). */
    @Test
    fun `neither malformed body logs ERROR or attaches a stack`() {
        val logger = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            bodyMvc
                .perform(
                    post("/api/v1/pipelines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"nodes":[{"id":"n1","source":{"name":"sample-trips"}}]}"""),
                ).andExpect(status().isBadRequest)

            appender.list.map { it.level.toString() } shouldNotContain "ERROR"
        } finally {
            logger.detachAppender(appender)
        }
    }
}
