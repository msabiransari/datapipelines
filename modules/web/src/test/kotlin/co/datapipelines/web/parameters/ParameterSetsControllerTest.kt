package co.datapipelines.web.parameters

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrorCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The parameter-set controller over mocked collaborators (the `PipelinesControllerTest` shape):
 * the §4 envelope around every §13.20 refusal, the working-version read rule, the hash
 * precondition's placement, the release cascade's consent flag, and the evaluate route's own
 * bounds — the stated body cap refused BEFORE any parse (the #279 gap is this route's to bound),
 * the selections handed to the runtime verbatim, and the runtime's JSON answered inside the
 * envelope with no echo of the request.
 */
class ParameterSetsControllerTest {
    private val sets = mockk<ParameterSetService>()
    private val repository = mockk<ParameterSetRepository>()
    private val evaluator = mockk<ParameterEvaluator>()
    private val transfer = mockk<ParameterSetTransferService>()
    private val config = ParametersConfig()

    private val controller =
        ParameterSetsController(sets, repository, evaluator, transfer, config, co.datapipelines.web.EVERYTHING_LENS)

    private val userId = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val body = ParameterSetJson.mapper.readTree("""{"display_name":"Region filters","parameters":[]}""")
    private val record =
        ParameterSetRecord(
            id = setId,
            workspaceId = workspaceId,
            name = "acme/sales/region_filters",
            displayName = "Region filters",
            description = "",
            currentVersion = null,
            createdAt = Instant.parse("2026-09-28T00:00:00Z"),
            updatedAt = Instant.parse("2026-09-28T00:00:00Z"),
            createdBy = userId,
        )
    private val draftDetail =
        ParameterSetVersionDetail(
            parameterSetId = setId,
            version = 1,
            status = PipelineVersionStatus.DRAFT,
            bodyHash = "hash-v1",
            createdAt = Instant.parse("2026-09-28T00:00:00Z"),
            createdBy = userId,
        )
    private val loaded = ParameterSetVersion(record, draftDetail, bodyOf())

    private fun bodyOf(): co.datapipelines.parameters.ParameterSetBody =
        ParameterSetJson.mapper.treeToValue(body, co.datapipelines.parameters.ParameterSetBody::class.java)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun statusOf(code: String): HttpStatus = ApiErrorCatalog.statusFor(code)

    @Test
    fun `create parses the document and lands version 1 DRAFT through the service`() {
        authenticate()
        every {
            sets.create(workspaceId, any(), userId, co.datapipelines.pipeline.WriteSurface.SESSION)
        } returns loaded

        val response =
            controller.create("""{"name":"acme/sales/region_filters","display_name":"Region filters","parameters":[]}""")

        response.data.get("id").asText() shouldBe setId.toString()
        response.data.get("version").asInt() shouldBe 1
        response.data.get("status").asText() shouldBe "DRAFT"
    }

    @Test
    fun `an unknown set is the catalogued 404 - the lensed read never confirms existence`() {
        authenticate()
        every { sets.findWorking(workspaceId, any(), setId) } returns null
        every { repository.findDraft(workspaceId, setId) } returns null

        val error = shouldThrow<co.datapipelines.web.api.ApiException> { controller.get(setId) }

        error.code shouldBe "parameter.not_found"
        statusOf(error.code) shouldBe HttpStatus.NOT_FOUND
    }

    /** The same controller under a lens that admits only [admitted] — the promoter's view (178). */
    private fun narrowed(vararg admitted: String) =
        ParameterSetsController(
            sets,
            repository,
            evaluator,
            transfer,
            config,
            PromoterLens { LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(admitted.toSet())) },
        )

    @Test
    fun `export under a narrowing lens that hides the set is the catalogued 404 - no body, no existence oracle`() {
        authenticate()
        every { repository.findRecord(workspaceId, setId) } returns record

        val error = shouldThrow<co.datapipelines.web.api.ApiException> { narrowed("acme/other/set").export(setId) }

        error.code shouldBe "parameter.not_found"
        statusOf(error.code) shouldBe HttpStatus.NOT_FOUND
        verify(exactly = 0) { transfer.export(any(), any()) }
    }

    @Test
    fun `get under a narrowing lens that admits the set carries no draft pointer - the draft is never even read`() {
        authenticate()
        val released =
            ParameterSetVersion(
                record.copy(currentVersion = 1),
                draftDetail.copy(status = PipelineVersionStatus.RELEASED),
                bodyOf(),
            )
        every { sets.findWorking(workspaceId, any(), setId) } returns released
        every { repository.findDraft(workspaceId, setId) } returns draftDetail.copy(version = 2)

        val response = narrowed(record.name).get(setId)

        response.data.has("draft") shouldBe false
        verify(exactly = 0) { repository.findDraft(any(), any()) }
    }

    @Test
    fun `a missing If-Match is a protocol error before the body is read`() {
        authenticate()
        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.update(setId, null, """{"name":"acme/sales/region_filters","display_name":"R","parameters":[]}""")
            }
        error.details["reason"] shouldBe "precondition_missing"
    }

    @Test
    fun `release passes the pinned-templates consent through to the service`() {
        authenticate()
        val released = mockk<co.datapipelines.parameters.ParameterSetReleased>()
        every { released.version } returns loaded
        every { sets.release(workspaceId, setId, "hash-v1", userId, true) } returns released

        controller.release(setId, "hash-v1", releasePinnedTemplates = true)

        verify(exactly = 1) { sets.release(workspaceId, setId, "hash-v1", userId, true) }
    }

    @Test
    fun `evaluate answers the runtime's JSON verbatim - no echo of the request rides it`() {
        authenticate()
        every { repository.findCurrent(workspaceId, setId) } returns loaded
        val runtime =
            co.datapipelines.parameters.EvaluateResponse(
                id = setId,
                name = record.name,
                version = 1,
                org = co.datapipelines.parameters.OrgEcho(null, null),
                parameters = emptyList(),
            )
        every { evaluator.evaluateBlocking(workspaceId, loaded, emptyMap()) } returns runtime

        val response = controller.evaluate(setId, """{"selections":{}}""")

        response.data.has("selections") shouldBe false
        response.data.has("version") shouldBe true
        response.data.get("valid").asBoolean() shouldBe true
        response.data.get("values").size() shouldBe 0
    }

    @Test
    fun `an evaluate body over the stated bound is refused before the JSON is parsed`() {
        authenticate()
        val oversized = """{"selections":{"x":"${"y".repeat(MAX_EVALUATE_REQUEST_BYTES)}"}}"""

        val error = shouldThrow<co.datapipelines.web.api.ApiException> { controller.evaluate(setId, oversized) }

        error.details["reason"] shouldBe "request_too_large"
        statusOf(error.code) shouldBe HttpStatus.BAD_REQUEST
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any()) }
    }

    @Test
    fun `an explicit version resolves through the lensed read and an absent one is the catalogued 404`() {
        authenticate()
        every { sets.findVersion(workspaceId, any(), setId, 3) } returns null
        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.evaluate(setId, """{"version":3,"selections":{}}""")
            }
        error.code shouldBe "parameter.not_found"
    }

    @Test
    fun `import reports created and unchanged through the transfer service`() {
        authenticate()
        every { transfer.import(any(), workspaceId, userId) } returns
            ParameterSetImported(detail = draftDetail, created = true, unchanged = false)
        every { repository.findVersion(workspaceId, setId, 1) } returns loaded

        val response = controller.import("""{"parameter_set":{"id":"$setId","name":"acme/sales/region_filters"}}""")

        response.data.get("import_created").asBoolean() shouldBe true
        response.data.get("import_unchanged").asBoolean() shouldBe false
    }

    @Test
    fun `the catalog owns the route-reachable evaluate statuses`() {
        statusOf("parameter.evaluate.timeout") shouldBe HttpStatus.GATEWAY_TIMEOUT
        statusOf("parameter.evaluate.response_too_large") shouldBe HttpStatus.PAYLOAD_TOO_LARGE
        statusOf("parameter.evaluate.unknown_parameter") shouldBe HttpStatus.BAD_REQUEST
        statusOf("parameter.not_found") shouldBe HttpStatus.NOT_FOUND
        statusOf("parameter.validation.duplicate_name") shouldBe HttpStatus.CONFLICT
        statusOf("parameter.authoring.disabled") shouldBe HttpStatus.FORBIDDEN
    }

    private companion object {
        const val MAX_EVALUATE_REQUEST_BYTES = 1_048_576
    }
}
