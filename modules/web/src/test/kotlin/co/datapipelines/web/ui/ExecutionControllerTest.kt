package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.AbortReason
import co.datapipelines.executor.ExecutionCancellationService
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ExecutorJson
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.executions.ResultCursor
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID
import kotlin.enums.EnumEntries

class ExecutionControllerTest {
    private val executions = mockk<ExecutionRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val resultStore = mockk<ResultStore>()
    private val resultUrls = mockk<ResultUrlFactory>()
    private val cursor = mockk<ResultCursor>()
    private val cancellation = mockk<ExecutionCancellationService>()

    private val pageController = ExecutionHistoryController(pipelines)
    private val partialController = ExecutionHistoryPartialController(executions)
    private val detailController = ExecutionDetailController(executions, pipelines, resultStore, resultUrls)
    private val detailPartialController = ExecutionDetailPartialController(executions, resultStore, cursor, cancellation)

    private val owner = UUID.randomUUID()
    private val executionId = UUID.randomUUID()
    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        id: UUID,
        scopes: Set<Scope>,
    ) {
        val principal =
            AuthenticatedPrincipal(
                id,
                "u@d.p",
                "User",
                scopes,
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun record(status: ExecutionStatus = ExecutionStatus.SUCCESS) =
        ExecutionRecord(
            executionId = executionId,
            pipelineId = pipelineId,
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            triggeredBy = owner,
            triggeredVia = ExecutionTrigger.REST,
            startedAt = Instant.parse("2026-08-10T14:30:00Z"),
            durationMs = 1500,
        )

    private fun pipelineRecord() =
        PipelineRecord(
            id = pipelineId,
            name = "test-pipe",
            displayName = "Test Pipeline",
            description = "desc",
            ownerId = owner,
            currentVersion = 3,
            isDeleted = false,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `history page returns pipelines and statuses`() {
        authenticate(owner, setOf(Scope.READ))
        every { pipelines.findAll(any()) } returns listOf(pipelineRecord())
        val model = ExtendedModelMap()
        val viewName = pageController.list(model)

        viewName shouldBe "executions/list"
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<*>).shouldHaveSize(1)
        @Suppress("UNCHECKED_CAST")
        (model["statuses"] as EnumEntries<*>).shouldHaveSize(4)
    }

    @Test
    fun `history partial returns paginated executions`() {
        authenticate(owner, setOf(Scope.READ))
        val records = (1..21).map { record() }
        every { executions.findByUser(any(), owner, null, null, null, null, limit = 21, offset = 0) } returns records

        val model = ExtendedModelMap()
        val viewName = partialController.listPartial(null, null, null, null, 0, model)

        viewName shouldBe "partials/executions"
        @Suppress("UNCHECKED_CAST")
        (model["executions"] as List<*>).shouldHaveSize(20)
        model["hasMore"] shouldBe true
        model["nextOffset"] shouldBe 20
    }

    @Test
    fun `history partial empty state when no executions`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findByUser(any(), owner, null, null, null, null, limit = 21, offset = 0) } returns emptyList()

        val model = ExtendedModelMap()
        partialController.listPartial(null, null, null, null, 0, model)

        @Suppress("UNCHECKED_CAST")
        (model["executions"] as List<*>).shouldBeEmpty()
        model["hasMore"] shouldBe false
    }

    @Test
    fun `detail page shows execution data`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns record().copy(resultRowCount = 100)
        every { executions.findByRoot(any(), executionId) } returns listOf(record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        val viewName = detailController.detail(executionId, model)

        viewName shouldBe "executions/detail"
        model["resultState"] shouldBe "expired"
        model["canCancel"] shouldBe false
    }

    @Test
    fun `detail shows 404 for non-owner`() {
        authenticate(UUID.randomUUID(), setOf(Scope.READ))
        val otherRecord = record().copy(triggeredBy = owner)
        every { executions.findById(any(), executionId) } returns otherRecord

        shouldThrow<ResponseStatusException> {
            detailController.detail(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `detail shows 404 for missing execution`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns null

        shouldThrow<ResponseStatusException> {
            detailController.detail(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `admin can view any execution`() {
        val adminId = UUID.randomUUID()
        authenticate(adminId, setOf(Scope.ADMIN))
        every { executions.findById(any(), executionId) } returns record()
        every { executions.findByRoot(any(), executionId) } returns listOf(record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        val viewName = detailController.detail(executionId, model)

        viewName shouldBe "executions/detail"
        model["isAdmin"] shouldBe true
    }

    @Test
    fun `detail exposes the whole execution family via the root`() {
        authenticate(owner, setOf(Scope.READ))
        val childId = UUID.randomUUID()
        val child =
            record().copy(
                executionId = childId,
                parentExecutionId = executionId,
                parentNodeId = "revenue",
                rootExecutionId = executionId,
                triggeredVia = ExecutionTrigger.PIPELINE,
            )
        every { executions.findById(any(), executionId) } returns record()
        every { executions.findByRoot(any(), executionId) } returns listOf(child, record())
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        detailController.detail(executionId, model)

        @Suppress("UNCHECKED_CAST")
        (model["family"] as List<ExecutionRecord>).map { it.executionId } shouldBe listOf(childId, executionId)
    }

    @Test
    fun `detail shows canCancel for running execution with execute scope`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { executions.findByRoot(any(), executionId) } returns listOf(record(ExecutionStatus.RUNNING))
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        detailController.detail(executionId, model)

        model["canCancel"] shouldBe true
    }

    @Test
    fun `detail exposes the parsed failure record for a failed execution`() {
        authenticate(owner, setOf(Scope.READ))
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.FAILED).copy(
                failedNodeId = "stage_daily_trips",
                errorJson = FAILURE_JSON,
            )
        every { executions.findByRoot(any(), executionId) } returns listOf(record(ExecutionStatus.FAILED))
        every { pipelines.findById(any(), pipelineId) } returns pipelineRecord()
        every { resultStore.keyFor(executionId) } returns "result:key"
        every { resultStore.describe("result:key") } returns null
        every { resultUrls.urlFor(executionId) } returns "/api/v1/executions/$executionId/result"

        val model = ExtendedModelMap()
        detailController.detail(executionId, model)

        model["errorCode"] shouldBe "pipeline.node.datasource_connection_failed"
        model["errorCorrelationId"] shouldBe "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21"
        model["errorSql"] shouldBe "SELECT * FROM trips WHERE borough = :borough"
        model["errorNodeLine"] shouldBe "stage_daily_trips · DQL · sample-trips (POSTGRES) · sample_trips_daily.sql @ v1"
        // Root cause FIRST — the reversal is the fragment's contract.
        @Suppress("UNCHECKED_CAST")
        val chain = model["errorChain"] as List<Map<String, String?>>
        chain.first()["cls"] shouldBe "org.postgresql.util.PSQLException"
        chain.last()["label"] shouldBe "Raised at: "
    }

    @Test
    fun `the result partial on a failed execution carries the record, not one code string`() {
        authenticate(owner, setOf(Scope.READ))
        every { cursor.readable(executionId, any()) } throws
            co.datapipelines.web.api.ApiException(
                co.datapipelines.pipeline.PipelineErrorCodes.Result.EXECUTION_FAILED,
                "failed",
            )
        every { executions.findById(any(), executionId) } returns
            record(ExecutionStatus.FAILED).copy(
                failedNodeId = "stage_daily_trips",
                errorJson = FAILURE_JSON,
            )

        val model = ExtendedModelMap()
        detailPartialController.result(executionId, 0, model)

        model["errorCode"] shouldBe "pipeline.node.datasource_connection_failed"
        model["errorCorrelationId"] shouldBe "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21"
    }

    @Test
    fun `the error fragment renders code, message, correlation id, sql and the root-first chain`() {
        val html =
            engine().process(
                "partials/execution-error",
                webContext().apply { ExecutionErrorView.attributes(FIXTURE_ERROR_NODE).forEach(::setVariable) },
            )

        html shouldContain "pipeline.node.datasource_connection_failed"
        html shouldContain "Failed to initialize pool"
        html shouldContain "1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21"
        html shouldContain "SELECT * FROM trips WHERE borough = :borough"
        // Root cause FIRST in the rendered order; the raised-at entry LAST.
        html.indexOf("org.postgresql.util.PSQLException") shouldBeGreaterThan -1
        html.indexOf("Root cause: org.postgresql.util.PSQLException") shouldBeLessThan
            html.indexOf("Raised at: java.lang.RuntimeException")
    }

    @Test
    fun `cancel by owner requests cancellation`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.RUNNING)
        every { cancellation.cancel(executionId, AbortReason.CANCELLED) } returns true

        val model = ExtendedModelMap()
        detailPartialController.cancel(executionId, model)

        model["cancelled"] shouldBe true
        verify(exactly = 1) { cancellation.cancel(executionId, AbortReason.CANCELLED) }
    }

    @Test
    fun `cancel requires execute scope`() {
        authenticate(owner, setOf(Scope.READ))

        shouldThrow<ResponseStatusException> {
            detailPartialController.cancel(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `cancel non-running execution returns conflict`() {
        authenticate(owner, setOf(Scope.EXECUTE))
        every { executions.findById(any(), executionId) } returns record(ExecutionStatus.SUCCESS)

        shouldThrow<ResponseStatusException> {
            detailPartialController.cancel(executionId, ExtendedModelMap())
        }.statusCode shouldBe HttpStatus.CONFLICT
    }

    @Test
    fun `cancel renders the cancelled state and an OOB toast`() {
        val html =
            engine().process(
                "partials/execution-cancelled",
                webContext().apply {
                    setVariable("cancelled", true)
                    setVariable("executionId", executionId)
                },
            )

        // The badge stays the persistent state; the toast rides along, WRAPPED — the
        // attribute on the wrapper, the .ds-toast as its first element child.
        html shouldContain "Cancellation requested"
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "Execution cancelled"
        Regex("""hx-swap-oob="beforeend:#toast"[^>]*>(?:\s|<!--[\s\S]*?-->)*<div class="ds-toast""")
            .containsMatchIn(html) shouldBe true
    }

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

    private companion object {
        val FAILURE_JSON =
            """
            {"code":"pipeline.node.datasource_connection_failed",
             "message":"Failed to initialize pool",
             "user_message":"We couldn't reach the database this step uses.",
             "details":{"phase":"connect"},
             "correlation_id":"1b0e6a52-9c3d-4f8e-9a2b-7c6d5e4f3a21",
             "node":{"id":"stage_daily_trips","type":"DQL","datasource":"sample-trips","dialect":"POSTGRES",
                     "template":"sample_trips_daily.sql","template_version":1},
             "sql":"SELECT * FROM trips WHERE borough = :borough",
             "exception":{"class":"java.lang.RuntimeException","message":"Failed to initialize pool",
                          "frames":["Boom.f0(Boom.kt:1)"],
                          "caused_by":[{"class":"org.postgresql.util.PSQLException",
                                        "message":"FATAL: password authentication failed for user \"dp_demo_ro\"",
                                        "frames":["org.postgresql.util.PSQLException.parseServerError(PSQLException.java:285)"]}]}}
            """.trimIndent()

        val FIXTURE_ERROR_NODE: JsonNode = ExecutorJson.mapper.readTree(FAILURE_JSON)
    }
}
