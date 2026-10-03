package co.datapipelines.web.parameters

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.EvaluationCaller
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.PointerMove
import co.datapipelines.parameters.Purged
import co.datapipelines.parameters.Switched
import co.datapipelines.parameters.VersionMoved
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrorCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
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

    private val audit = RecordingAudit()

    private val controller =
        ParameterSetsController(sets, repository, evaluator, transfer, config, co.datapipelines.web.EVERYTHING_LENS, audit)

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
            audit,
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
        every { released.templatesReleased } returns listOf(TemplateRef("acme/templates/t", 2))
        every { sets.release(workspaceId, setId, "hash-v1", userId, true) } returns released

        controller.release(setId, "hash-v1", releasePinnedTemplates = true)

        verify(exactly = 1) { sets.release(workspaceId, setId, "hash-v1", userId, true) }
        // #332 — the release audit, the auditRelease twin: the cascaded template's event first,
        // then the set's own naming it.
        audit.events shouldBe
            listOf("template.version.released", "parameter_set.version.released")
        audit.details.first()["cascade_from_parameter_set_id"] shouldBe setId.toString()
        audit.details.last()["templates_released"] shouldBe listOf(mapOf("template_id" to "acme/templates/t", "version" to 2))
        audit.details.last()["parameter_set_name"] shouldBe record.name
    }

    @Test
    fun `a session reaches every human verb through the service`() {
        authenticate()
        // #372 — the audit rows' pre-reads are BODY-FREE and ride the caller's lens (Everything for this principal).
        every { sets.auditIdentity(workspaceId, co.datapipelines.pipeline.ReadLens.Everything, setId) } returns (record.name to 1)
        every { sets.auditVersionIdentity(workspaceId, co.datapipelines.pipeline.ReadLens.Everything, setId, 1) } returns
            (record.name to 1)
        every { sets.auditVersionIdentity(workspaceId, co.datapipelines.pipeline.ReadLens.Everything, setId, 2) } returns
            (record.name to 2)
        every { sets.purgeDraft(workspaceId, setId, "hash-v1") } returns Purged.Version
        every { sets.discardVersion(workspaceId, setId, 1, userId) } returns
            VersionMoved(draftDetail.copy(status = PipelineVersionStatus.DISCARDED), PointerMove(before = 1, after = null))
        every { sets.restoreVersion(workspaceId, setId, 1) } returns
            VersionMoved(draftDetail.copy(status = PipelineVersionStatus.RELEASED), PointerMove(before = null, after = 1))
        every { sets.purgeVersion(workspaceId, setId, 2) } returns Purged.Version
        every { sets.purgeEntity(workspaceId, setId) } returns Purged.Entity
        every { sets.switchCurrent(workspaceId, setId, 1) } returns Switched(record.name, PointerMove(before = 2, after = 1))

        controller.purgeDraft(setId, "hash-v1")
        controller.discardVersion(setId, 1).data["status"] shouldBe "DISCARDED"
        controller.restoreVersion(setId, 1).data["status"] shouldBe "RELEASED"
        controller.purgeVersion(setId, 2)
        controller.delete(setId)
        controller.switchCurrent(setId, ParameterSetJson.mapper.readTree("""{"version": 1}""")).data["current_version"] shouldBe 1

        verify(exactly = 1) { sets.purgeEntity(workspaceId, setId) }
        // #332 — every verb audited, in the order they ran, the pipelines mould's event per verb.
        audit.events shouldBe
            listOf(
                "parameter_set.version.purged",
                "parameter_set.version.discarded",
                "parameter_set.version.restored",
                "parameter_set.version.purged",
                "parameter_set.purged",
                "parameter_set.current_switched",
            )
        // #372 — the rows carry the pipelines mould's full shape; the exact key SET of each row is pinned,
        // so a missing key cannot pass, and the values come from the verb's result, not a re-read.
        val names = listOf(record.name, record.name, record.name, record.name, record.name, record.name)
        audit.details.map { it["parameter_set_name"] } shouldBe names
        audit.details.map { it["version"] } shouldBe listOf(1, 1, 1, 2, 1, null)
        audit.details.map { it["scope"] } shouldBe listOf("version", null, null, "version", "entity", null)
        audit.details.map { it["current_version_before"] } shouldBe listOf(null, 1, null, null, null, null)
        audit.details.map { it["current_version_after"] } shouldBe listOf(null, null, 1, null, null, null)
        audit.details.map { it["from"] } shouldBe listOf(null, null, null, null, null, 2)
        audit.details.map { it["to"] } shouldBe listOf(null, null, null, null, null, 1)
        val withPointer =
            setOf("parameter_set_id", "parameter_set_name", "version", "workspace_id", "current_version_before", "current_version_after")
        audit.details.map { it.keys } shouldBe
            listOf(
                setOf("parameter_set_id", "parameter_set_name", "version", "workspace_id", "scope"),
                withPointer,
                withPointer,
                setOf("parameter_set_id", "parameter_set_name", "version", "workspace_id", "scope"),
                setOf("parameter_set_id", "parameter_set_name", "version", "workspace_id", "scope"),
                setOf("parameter_set_id", "parameter_set_name", "workspace_id", "from", "to"),
            )
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
        // #376: the REST route names itself — the history's caller is REST, the principal the session's person.
        every { evaluator.evaluateBlocking(workspaceId, loaded, emptyMap(), match { it.caller == EvaluationCaller.REST }) } returns runtime

        val response = controller.evaluate(setId, """{"selections":{}}""")

        response.data.has("selections") shouldBe false
        response.data.has("version") shouldBe true
        response.data.get("valid").asBoolean() shouldBe true
        response.data.get("values").size() shouldBe 0
    }

    @Test
    fun `an evaluate body over the stated bound is the platform 413 before the JSON is parsed`() {
        authenticate()
        val oversized = """{"selections":{"x":"${"y".repeat(MAX_EVALUATE_REQUEST_BYTES)}"}}"""

        val error = shouldThrow<co.datapipelines.web.api.ApiException> { controller.evaluate(setId, oversized) }

        error.code shouldBe "request.body_too_large"
        statusOf(error.code) shouldBe HttpStatus.PAYLOAD_TOO_LARGE
        error.details["limit_bytes"] shouldBe MAX_EVALUATE_REQUEST_BYTES
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
    }

    @Test
    fun `the switch refuses a missing version as body_invalid - never a 404 that lies about the set`() {
        authenticate()
        every { repository.findRecord(any(), any()) } returns record

        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.switchCurrent(setId, ParameterSetJson.mapper.readTree("{}"))
            }

        error.code shouldBe "parameter.validation.body_invalid"
        statusOf(error.code) shouldBe HttpStatus.BAD_REQUEST
        error.details["path"] shouldBe "version"
        error.details["reason"] shouldBe "missing"
        verify(exactly = 0) { repository.findRecord(any(), any()) }
    }

    @Test
    fun `the switch refuses a non-integer version as body_invalid wrong_type`() {
        authenticate()

        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.switchCurrent(setId, ParameterSetJson.mapper.readTree("""{"version":"2"}"""))
            }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details["path"] shouldBe "version"
        error.details["reason"] shouldBe "wrong_type"
        verify(exactly = 0) { repository.findRecord(any(), any()) }
    }

    @Test
    fun `evaluate refuses a present non-integer version as body_invalid - an explicit version never falls back silently`() {
        authenticate()

        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.evaluate(setId, """{"version":"2","selections":{}}""")
            }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details["path"] shouldBe "version"
        error.details["reason"] shouldBe "wrong_type"
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.findCurrent(any(), any()) }
    }

    @Test
    fun `evaluate refuses a present non-object selections as body_invalid - it never becomes the empty map`() {
        authenticate()

        val error =
            shouldThrow<co.datapipelines.web.api.ApiException> {
                controller.evaluate(setId, """{"selections":["country=US"]}""")
            }

        error.code shouldBe "parameter.validation.body_invalid"
        error.details["path"] shouldBe "selections"
        error.details["reason"] shouldBe "wrong_type"
        verify(exactly = 0) { evaluator.evaluateBlocking(any(), any(), any(), any()) }
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
        statusOf("parameter.validation.body_invalid") shouldBe HttpStatus.BAD_REQUEST
        statusOf("request.body_too_large") shouldBe HttpStatus.PAYLOAD_TOO_LARGE
    }

    @Test
    fun `browse reports the level's truthful total and has_more - the sets, never the folders`() {
        authenticate()
        every { sets.listChildFolders(workspaceId, any(), "acme") } returns emptyList()
        every { sets.listChildSets(workspaceId, any(), "acme", 0, 2) } returns listOf(loaded, loaded)
        every { sets.countChildSets(workspaceId, any(), "acme") } returns 3

        val firstPage = controller.browse("acme", offset = null, limit = 2).data

        assertAll(
            { firstPage["total"] shouldBe 3 },
            { firstPage["has_more"] shouldBe true },
        )

        every { sets.listChildSets(workspaceId, any(), "acme", 2, 2) } returns listOf(loaded)

        val lastPage = controller.browse("acme", offset = 2, limit = 2).data

        assertAll(
            { lastPage["total"] shouldBe 3 },
            { lastPage["has_more"] shouldBe false },
        )
    }

    @Test
    fun `browse under a narrowing lens reports the lens-admitted total - the workspace's size is never leaked`() {
        authenticate()
        every { sets.listChildFolders(workspaceId, any(), "acme") } returns emptyList()
        every { sets.listChildSets(workspaceId, any(), "acme", 0, 50) } returns listOf(loaded, loaded)
        // The lens-truth of this count is the SERVICE's contract, integration-proven over real
        // tables (ParameterSetServiceIntegrationTest); the controller reports what the lensed
        // service counted - here 2, not the workspace's 3.
        every { sets.countChildSets(workspaceId, any(), "acme") } returns 2

        val page = narrowed("acme/sales/a").browse("acme", offset = null, limit = null).data

        assertAll(
            { page["total"] shouldBe 2 },
            { page["has_more"] shouldBe false },
        )
    }

    @Test
    fun `the flat listing paginates against the whole workspace's total, not the page size`() {
        authenticate()
        // A blank/absent q IS the listing: the surface calls the service's search with a null
        // needle, and the service delegates to listAll (its contract, not the mock's).
        every { sets.search(workspaceId, any(), null, 0, 2) } returns listOf(loaded, loaded)
        every { sets.countSearch(workspaceId, any(), null) } returns 3

        val response = controller.list(offset = null, limit = 2)

        assertAll(
            { response.data.items.size shouldBe 2 },
            { response.data.pagination.total shouldBe 3L },
            { response.data.pagination.hasMore shouldBe true },
        )

        every { sets.search(workspaceId, any(), null, 2, 2) } returns listOf(loaded)

        val lastPage = controller.list(offset = 2, limit = 2).data

        assertAll(
            { lastPage.items.size shouldBe 1 },
            { lastPage.pagination.total shouldBe 3L },
            { lastPage.pagination.hasMore shouldBe false },
        )
    }

    @Test
    fun `the flat listing under a narrowing lens reports the lens-admitted total - never the workspace's size`() {
        authenticate()
        every { sets.search(workspaceId, any(), null, 0, 50) } returns listOf(loaded, loaded)
        // Lens-truth is the SERVICE's contract, integration-proven over real tables
        // (ParameterSetServiceIntegrationTest); the controller reports what the lensed service
        // counted — here 2, not the workspace's 3.
        every { sets.countSearch(workspaceId, any(), null) } returns 2

        val page = narrowed("acme/sales/a").list(offset = null, limit = null).data

        assertAll(
            { page.items.size shouldBe 2 },
            { page.pagination.total shouldBe 2L },
            { page.pagination.hasMore shouldBe false },
        )
    }

    @Test
    fun `the flat listing passes q verbatim to the search and reports the search total`() {
        authenticate()
        // Verbatim: the controller neither trims nor case-folds — the service owns the needle
        // rule, so a raw term reaches both the page and the total unchanged.
        every { sets.search(workspaceId, any(), "  Region  ", 0, 50) } returns listOf(loaded)
        every { sets.countSearch(workspaceId, any(), "  Region  ") } returns 7

        val response = controller.list(q = "  Region  ", offset = null, limit = null)

        assertAll(
            { response.data.items.size shouldBe 1 },
            { response.data.pagination.total shouldBe 7L },
        )
        verify(exactly = 1) { sets.search(workspaceId, any(), "  Region  ", 0, 50) }
        verify(exactly = 1) { sets.countSearch(workspaceId, any(), "  Region  ") }
        // The unfiltered listing is never consulted for a search: the two are one call apart.
        verify(exactly = 0) { sets.listAll(any(), any(), any(), any()) }
        verify(exactly = 0) { sets.countAll(any(), any()) }
    }

    /** The recording fake the #332 assertions read: the events in order, and the details beside each. */
    private class RecordingAudit : co.datapipelines.auth.AuditEventSink {
        val events = mutableListOf<String>()
        val details = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events.add(event)
            this.details.add(details)
        }
    }

    private companion object {
        const val MAX_EVALUATE_REQUEST_BYTES = 1_048_576
    }
}
