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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place a thrown failure becomes an HTTP response (rest-api §4.2), exercised through a
 * real MVC pipeline (standalone setup — no Spring context, no security chain; the filters' own
 * tests cover those). Proves the envelope shape, the catalog-driven status, and the correlation
 * id landing in the body.
 */
class ApiExceptionHandlerTest {
    @Test
    fun `stream constraints retain safe limit guidance without reflecting the converter message`() {
        val mapper =
            co.datapipelines.templates.TransformBlocks.mapper
                .copy()
        mapper.factory.setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints
                .builder()
                .maxNestingDepth(2)
                .build(),
        )
        val cause =
            io.kotest.assertions.throwables.shouldThrow<com.fasterxml.jackson.core.exc.StreamConstraintsException> {
                mapper.readTree("[[[\"sentinel987654321\"]]]")
            }
        val error =
            org.springframework.http.converter.HttpMessageNotReadableException(
                "sentinel987654321",
                cause,
                org.springframework.mock.http
                    .MockHttpInputMessage(byteArrayOf()),
            )
        val response =
            ApiExceptionHandler().onUnreadableBody(
                error,
                org.springframework.mock.web
                    .MockHttpServletRequest("POST", "/api/v1/pipelines"),
            )
        response.statusCode.value() shouldBe 400
        val tree = mapper.valueToTree<JsonNode>(response.body)
        tree["error"]["code"].asText() shouldBe PipelineErrorCodes.Validation.SCHEMA_VERSION_UNSUPPORTED
        tree["error"]["details"] shouldBe mapper.valueToTree<JsonNode>(mapOf("reason" to "malformed_json"))
        tree["error"]["message"].asText() shouldContain "nesting depth"
        mapper.writeValueAsString(response.body).contains("987654321") shouldBe false
        mapper.readTree("[[\"sentinel987654321\"]]").isArray shouldBe true
    }

    @Test
    fun `raw and converter wrapped mapping refusals hide values and preserve the body envelope`() {
        val strictMvc =
            MockMvcBuilders
                .standaloneSetup(ProbeController())
                .setMessageConverters(MappingJackson2HttpMessageConverter(co.datapipelines.templates.TransformBlocks.mapper))
                .setControllerAdvice(ApiExceptionHandler())
                .build()
        val cases =
            listOf(
                Triple("/api/v1/endpoints", """{"path":987654321,"pipeline":"safe"}""", PipelineErrorCodes.Endpoint.PATH_INVALID),
                Triple(
                    "/api/v1/pipelines",
                    """{"nodes":[{"id":"n1","source":["sentinel987654321"]}]}""",
                    PipelineErrorCodes.Validation.SCHEMA_VERSION_UNSUPPORTED,
                ),
                Triple("/api/v1/parameter-sets/x/current", "sentinel987654321", PipelineErrorCodes.Parameters.BODY_INVALID),
            )
        cases.forEachIndexed { index, (uri, body, code) ->
            val response =
                strictMvc
                    .perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value(code))
                    .andExpect(jsonPath("$.correlation_id").exists())
                    .andReturn()
                    .response.contentAsString
            response.contains("987654321") shouldBe false
            val error = jacksonObjectMapper().readTree(response)["error"]
            error["details"] shouldBe jacksonObjectMapper().valueToTree<JsonNode>(mapOf("reason" to "malformed_json"))
            if (index < 2) error["message"].asText().contains("must be a string") shouldBe true
        }
        strictMvc
            .perform(get("/probe/unexpected"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.message").value("Unexpected server error."))
    }

    @Test
    fun `unreadable unknown keys are clipped and other unreadable causes use fixed prose`() {
        val mapper = co.datapipelines.templates.TransformBlocks.mapper
        val longKey = "bad\n\t" + "x".repeat(200)
        val wrapper =
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                mapper.convertValue(
                    mapOf("path" to "safe", "pipeline" to "safe", longKey to "sentinel987654321"),
                    ProbeEndpointRequest::class.java,
                )
            }
        val request =
            org.springframework.mock.web
                .MockHttpServletRequest("POST", "/api/v1/endpoints")
        val unreadable =
            org.springframework.http.converter.HttpMessageNotReadableException(
                "sentinel987654321",
                wrapper,
                org.springframework.mock.http
                    .MockHttpInputMessage(byteArrayOf()),
            )
        val response = ApiExceptionHandler().onUnreadableBody(unreadable, request)
        val tree = mapper.valueToTree<JsonNode>(response.body)
        val message = tree["error"]["message"].asText()
        message.contains("987654321") shouldBe false
        message.contains(longKey) shouldBe false
        message.any { it.isISOControl() } shouldBe false
        val reflected = message.substringAfter(": '").substringBefore("' is not")
        reflected.length shouldBe 161
        println("381 HTTP reflected key length=${reflected.length}, controls=${reflected.any { it.isISOControl() }}")
        tree["error"]["details"] shouldBe mapper.valueToTree<JsonNode>(mapOf("reason" to "malformed_json"))
        val generic =
            org.springframework.http.converter.HttpMessageNotReadableException(
                "sentinel987654321",
                org.springframework.mock.http
                    .MockHttpInputMessage(byteArrayOf()),
            )
        mapper.writeValueAsString(ApiExceptionHandler().onUnreadableBody(generic, request).body).contains("987654321") shouldBe false
        val media = org.springframework.web.HttpMediaTypeNotSupportedException("sentinel987654321")
        mapper.writeValueAsString(ApiExceptionHandler().onUnreadableBody(media, request).body).contains("987654321") shouldBe false
    }

    @RestController
    class ProbeController {
        @GetMapping("/probe/domain")
        fun domain(): Nothing = throw ApiErrors.pipelineNotFound("pipe-1")

        @GetMapping("/probe/unexpected")
        fun unexpected(): Nothing = error("boom")

        /** #298 — the binder's mixed refusal: the caller's bad value first, the stored declaration second. */
        @GetMapping("/probe/mixed-refusal")
        fun mixedRefusal(): Nothing =
            throw co.datapipelines.pipeline.PipelineValidationException(
                co.datapipelines.pipeline.ValidationResult(
                    listOf(
                        co.datapipelines.pipeline.ValidationFailure(
                            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                            "parameters.count",
                            "not an integer",
                        ),
                        co.datapipelines.pipeline.ValidationFailure(
                            PipelineErrorCodes.Execution.PARAMETER_DECLARATION_INVALID,
                            "parameters.limit",
                            "stored with a declaration today's rules refuse",
                        ),
                    ),
                ),
            )

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

        /**
         * #323 — the parameter-set switch's shape (`POST /api/v1/parameter-sets/{id}/current`): its
         * body is bound by the message converter, so an unreadable one is the converter's
         * `HttpMessageNotReadableException` and [ApiExceptionHandler]'s `malformedBodyCodeFor`
         * picks the code from this URI.
         */
        @PostMapping("/api/v1/parameter-sets/{id}/current")
        fun switchParameterSet(
            @RequestBody body: JsonNode,
        ): JsonNode = body

        /** #408's REST half: a typed path variable and a typed query parameter, as `GET /api/v1/dashboards/{id}` binds them. */
        @GetMapping("/probe/typed/{id}")
        fun typed(
            @PathVariable id: UUID,
            @RequestParam(defaultValue = "1") version: Int,
        ): Map<String, Any> = mapOf("id" to id, "version" to version)

        @GetMapping("/probe/query-failed")
        fun queryFailed(): Nothing =
            throw co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED,
                message = "Syntax error in the rendered SQL.",
                details = mapOf("node_id" to "n1"),
            )
    }

    /**
     * #222's shape: a route that produces `text/plain` only, as `GET /partials/mcp-key/secret`
     * does. [calls] proves the refusal happens before the handler runs, so nothing it would
     * return (here a stand-in secret) can reach the response.
     */
    @RestController
    class PlainProbeController {
        val calls = AtomicInteger()

        @GetMapping("/probe/plain", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun plain(): String {
            calls.incrementAndGet()
            return PLAIN_SECRET
        }
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
            .andExpect(jsonPath("$.error.doc_url").value("https://datapipelines.co/docs/pipeline-contract#133-pipeline-execution-run-time"))
            .andExpect(jsonPath("$.correlation_id").exists())
    }

    @Test
    fun `a mixed refusal answers the server's status - the stored declaration outranks the caller's value (#298)`() {
        mvc
            .perform(get("/probe/mixed-refusal"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Execution.PARAMETER_DECLARATION_INVALID))
            // Both failures still travel, in the order they were found.
            .andExpect(jsonPath("$.error.details.failures[0].code").value(PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE))
            .andExpect(jsonPath("$.error.details.failures[1].code").value(PipelineErrorCodes.Execution.PARAMETER_DECLARATION_INVALID))
    }

    /**
     * `onBadParameter`'s type-mismatch half, which had no test (#408 found it): a malformed typed
     * path or query value is 400 `invalid_parameter_type` naming the PARAMETER in the message and
     * in `details.parameter` — the value, a markup payload here, appears nowhere in the body.
     * The UI advice answers the query case with this same code and message (#408).
     */
    @Test
    fun `a malformed typed path or query value is 400 invalid_parameter_type naming the parameter, never the value (#408)`() {
        listOf(
            get(URI.create("/probe/typed/%3Cscript%3Ealert(1)%3C%2Fscript%3E")) to "id",
            get("/probe/typed/{id}", UUID.randomUUID()).param("version", "<script>alert(1)</script>") to "version",
        ).forEach { (request, parameter) ->
            val result =
                mvc
                    .perform(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE))
                    .andExpect(jsonPath("$.error.message").value("Parameter '$parameter' is missing or not of the expected type."))
                    .andExpect(jsonPath("$.error.details.parameter").value(parameter))
                    .andReturn()
            result.response.contentAsString.contains("script") shouldBe false
        }

        // Unchanged: well-formed values reach the handler.
        mvc.perform(get("/probe/typed/{id}", UUID.randomUUID()).param("version", "2")).andExpect(status().isOk)
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

    /**
     * #323 — the parameter-set routes had no `malformedBodyCodeFor` row, so an unreadable body on a
     * converter-bound route of the family (the switch) answered the PIPELINE family's
     * `schema_version_unsupported` — a pipeline verdict on a request that names no pipeline (098 §C's
     * shape). `parameter.validation.body_invalid` is the family's own 400 for a body it cannot use.
     */
    @Test
    fun `a malformed parameter-sets body is 400 with the parameter family's code, not a pipeline one`() {
        bodyMvc
            .perform(
                post("/api/v1/parameter-sets/0b6f1c1e-0000-4000-8000-000000000001/current")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Parameters.BODY_INVALID))
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

    /**
     * #222 — an `Accept` the route cannot produce is the caller's mismatch: 406
     * `endpoint.not_acceptable`, never the 500 backstop. Spring refuses it while MAPPING the
     * request, so the handler never runs. The response names what the route produces and echoes
     * nothing of the attacker-controlled `Accept`.
     */
    @Test
    fun `an Accept the route cannot produce is 406 endpoint not_acceptable - never the 500 backstop, and nothing echoed`() {
        val probe = PlainProbeController()
        val plainMvc = MockMvcBuilders.standaloneSetup(probe).setControllerAdvice(ApiExceptionHandler()).build()
        val logger = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val appender =
            ch.qos.logback.core.read
                .ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            val result =
                plainMvc
                    .perform(get("/probe/plain").header("Accept", "application/json, $ACCEPT_CANARY"))
                    .andExpect(status().isNotAcceptable)
                    .andExpect(jsonPath("$.error.code").value(PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE))
                    .andExpect(jsonPath("$.error.details.produces[0]").value(MediaType.TEXT_PLAIN_VALUE))
                    .andExpect(jsonPath("$.error.user_message").value("This address can't answer in the format the request asked for."))
                    .andExpect(jsonPath("$.correlation_id").exists())
                    .andReturn()
            val body = result.response.contentAsString
            assertAll(
                { result.response.contentType shouldBe MediaType.APPLICATION_JSON_VALUE },
                { body.contains(ACCEPT_CANARY) shouldBe false },
                { body.contains(PLAIN_SECRET) shouldBe false },
                { probe.calls.get() shouldBe 0 },
                { appender.list.map { it.level.toString() } shouldNotContain "ERROR" },
            )
        } finally {
            logger.detachAppender(appender)
        }

        // Unchanged: an Accept the route CAN satisfy still reaches the handler.
        plainMvc.perform(get("/probe/plain").header("Accept", "*/*")).andExpect(status().isOk)
        plainMvc.perform(get("/probe/plain").accept(MediaType.TEXT_PLAIN)).andExpect(status().isOk)
        probe.calls.get() shouldBe 2
    }

    private companion object {
        /** A value no real client sends; if the 406 body ever carries it, the Accept header was echoed. */
        const val ACCEPT_CANARY = "application/x-accept-canary-222"

        /** What [PlainProbeController] would return; it must never appear in a refusal. */
        const val PLAIN_SECRET = "dpk_PROBESECRET.never-in-a-refusal"
    }
}
