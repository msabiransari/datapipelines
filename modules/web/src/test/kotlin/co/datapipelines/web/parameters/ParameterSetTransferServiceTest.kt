package co.datapipelines.web.parameters

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetDocument
import co.datapipelines.parameters.ParameterSetExport
import co.datapipelines.parameters.ParameterSetImported
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetReader
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The §21.4 transfer over the REAL service (#299): `export` builds the envelope in memory over
 * stubbed repository reads, and `import` must bind it — the `parameter_set` node's lifecycle
 * keys stripped, the strict mapper still refusing a typo — and hand the bundle's ROOT
 * `templates` to the template import before the set. Both classes were mockk-only before
 * #299, so a real envelope never bound and nothing could have shown it.
 */
class ParameterSetTransferServiceTest {
    private val sets = mockk<ParameterSetService>()
    private val repository = mockk<ParameterSetRepository>()
    private val templates = mockk<co.datapipelines.templates.TemplateRepository>()
    private val templateImport = mockk<TemplateImportService>()

    private val service = ParameterSetTransferService(sets, repository, templates, templateImport)

    private val workspaceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    private val document: ParameterSetDocument =
        ParameterSetReader().readOrThrow(ParameterSetJson.mapper.readTree(DOCUMENT))

    private val record =
        ParameterSetRecord(
            id = setId,
            workspaceId = workspaceId,
            name = SET_NAME,
            displayName = "Region filters",
            description = "transfer round trip",
            currentVersion = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = actor,
        )
    private val detail =
        ParameterSetVersionDetail(
            parameterSetId = setId,
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = BODY_HASH,
            createdAt = now,
            createdBy = actor,
            releasedAt = now,
        )

    private val calls = mutableListOf<String>()

    init {
        every { repository.findRecord(workspaceId, setId) } returns record
        every { repository.findVersionDetail(workspaceId, setId, 1) } returns detail
        every { repository.findVersion(workspaceId, setId, 1) } returns ParameterSetVersion(record, detail, document.body)
        every { templates.lookupVersion(workspaceId, TEMPLATE_ID, 1) } returns
            TemplateVersion(
                id = TEMPLATE_ID,
                version = 1,
                isLibrary = false,
                imports = emptyList(),
                body = TEMPLATE_BODY,
                createdAt = now,
                createdBy = actor,
            )
        every { templates.findVersion(workspaceId, TEMPLATE_ID, 1) } returns
            Template(
                id = TEMPLATE_ID,
                version = 1,
                dialect = Dialect.H2,
                displayName = TEMPLATE_ID,
                description = "the pinned selector",
                body = TEMPLATE_BODY,
                createdAt = now,
                createdBy = actor,
            )
        every { templateImport.import(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `a real export envelope re-imports - the body binds through the lifecycle keys`() {
        val envelope = WIRE.writeValueAsString(service.export(workspaceId, setId))
        var imported: ParameterSetExport? = null
        every { sets.import(workspaceId, any(), actor) } answers {
            imported = secondArg()
            ParameterSetImported(mockk(), created = true, unchanged = false)
        }

        service.import(envelope, workspaceId, actor)

        val export = checkNotNull(imported)
        export.id shouldBe setId
        export.name shouldBe SET_NAME
        export.version shouldBe 1
        export.bodyHash shouldBe BODY_HASH
        export.releasedAt shouldBe now
        export.body.displayName shouldBe "Region filters"
        val parameters = export.body.parameters
        parameters.size shouldBe 2
        val state = parameters[1].source
        state?.template?.id shouldBe TEMPLATE_ID
    }

    @Test
    fun `a typo in the parameter_set node refuses - the strict mapper survives the strip`() {
        val envelope = service.export(workspaceId, setId)
        val parameterSet = envelope["parameter_set"] as ObjectNode
        parameterSet.set<JsonNode>("parameterz", ParameterSetJson.mapper.createArrayNode())
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { service.import(WIRE.writeValueAsString(envelope), workspaceId, actor) }

        refusal.code shouldBe ParameterErrorCodes.BODY_INVALID
    }

    /** #319: the transfer binds WITHOUT the reader's pre-scan, so a number where a String is declared
     * must stop at the mapper — never bind as the text "5". */
    @Test
    fun `an envelope whose display_name is a number refuses body_invalid - never bound as text`() {
        val envelope = service.export(workspaceId, setId)
        (envelope["parameter_set"] as ObjectNode).put("display_name", 5)
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { service.import(WIRE.writeValueAsString(envelope), workspaceId, actor) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["reason"] shouldBe "wrong_type" },
            { verify { templateImport wasNot Called } },
            { verify { sets wasNot Called } },
        )
    }

    @Test
    fun `the bundle's templates ride the envelope root and land before the set`() {
        val envelope = WIRE.writeValueAsString(service.export(workspaceId, setId))
        var templatesEnvelope: ObjectNode? = null
        every { templateImport.import(any(), workspaceId, actor) } answers {
            calls += "templates"
            templatesEnvelope = WIRE.readTree(firstArg<String>()) as ObjectNode
            emptyList()
        }
        every { sets.import(workspaceId, any(), actor) } answers {
            calls += "sets"
            ParameterSetImported(mockk(), created = true, unchanged = false)
        }

        service.import(envelope, workspaceId, actor)

        calls shouldBe listOf("templates", "sets")
        val bundled = checkNotNull(templatesEnvelope)
        bundled["templates"].size() shouldBe 1
        bundled["templates"][0]["id"].asText() shouldBe TEMPLATE_ID
    }

    /**
     * #344, the owner's ruling of 2026-10-02: the bundle is what the set PINS — the pin and its transitive `imports`
     * closure, exactly — read without the template lens; only the ROOT is lensed. Driven through the REAL controller
     * over this REAL service (the controller test mocks the transfer, so it cannot see a lens threaded into the
     * bundle): the caller's view admits the set and its template arm admits only [UNPINNED_ID], which the repository
     * also holds. A bundle filtered through that arm, one that drops a pinned version, or one that lists instead of
     * walking the pins is red.
     */
    @Test
    fun `export still bundles every pinned template the template lens hides, and its imports closure (#344)`() {
        every { templates.lookupVersion(workspaceId, TEMPLATE_ID, 1) } returns
            templateVersion(TEMPLATE_ID, imports = listOf(co.datapipelines.templates.TemplateImport(LIBRARY_ID, 1, "lib")))
        listOf(LIBRARY_ID, UNPINNED_ID).forEach { id ->
            every { templates.lookupVersion(workspaceId, id, 1) } returns templateVersion(id)
            every { templates.findVersion(workspaceId, id, 1) } returns storedTemplate(id)
        }
        val hidingTemplates =
            ParameterSetsController(
                sets,
                repository,
                mockk(),
                service,
                ParametersConfig(),
                {
                    LensedView(
                        ReadLens.Everything,
                        ReadLens.Only(setOf(UNPINNED_ID)),
                        parameterSets = ReadLens.Only(setOf(SET_NAME)),
                    )
                },
                mockk(),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(actor, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme")),
                null,
                emptyList(),
            )

        val bundled =
            try {
                hidingTemplates.export(setId).data["templates"] as List<*>
            } finally {
                SecurityContextHolder.clearContext()
            }

        bundled.map { (it as Template).id to it.version } shouldContainExactly listOf(TEMPLATE_ID to 1, LIBRARY_ID to 1)
    }

    private fun templateVersion(
        id: String,
        imports: List<co.datapipelines.templates.TemplateImport> = emptyList(),
    ) = TemplateVersion(
        id = id,
        version = 1,
        isLibrary = id == LIBRARY_ID,
        imports = imports,
        body = TEMPLATE_BODY,
        createdAt = now,
        createdBy = actor,
    )

    private fun storedTemplate(id: String) =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.H2,
            displayName = id,
            description = "",
            body = TEMPLATE_BODY,
            createdAt = now,
            createdBy = actor,
        )

    @Test
    fun `an envelope without a parameter_set node refuses before any template lands`() {
        val envelope = ParameterSetJson.mapper.createObjectNode()
        envelope.set<JsonNode>("templates", ParameterSetJson.mapper.createArrayNode())

        val refusal =
            shouldThrow<ApiException> { service.import(WIRE.writeValueAsString(envelope), workspaceId, actor) }

        refusal.code shouldBe ParameterErrorCodes.BODY_INVALID
        verify { templateImport wasNot Called }
        verify { sets wasNot Called }
    }

    private companion object {
        const val SET_NAME = "acme/sales/region_filters"
        const val TEMPLATE_ID = "acme/sales/states_of_country.sql"
        const val LIBRARY_ID = "acme/sales/region_macros.sql"
        const val UNPINNED_ID = "acme/sales/not_pinned.sql"
        const val BODY_HASH = "b3a6e0dad1f5b9f0e2a1c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a"
        const val TEMPLATE_BODY = "SELECT state_code AS \"value\", state_name AS \"display_value\" FROM dim_state"

        /**
         * The wire mapper for the ENVELOPE string: the envelope carries the bundled templates as
         * whole objects, and the strict parameter mapper does not write `Instant` — on the real
         * surfaces it is the HTTP layer's mapper that serializes the export answer.
         */
        val WIRE: ObjectMapper =
            ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        /** A real document the reader accepts: constants country, template-backed state. */
        val DOCUMENT =
            """
            {
              "name": "$SET_NAME",
              "display_name": "Region filters",
              "description": "transfer round trip",
              "parameters": [
                {
                  "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT",
                  "cardinality": "MULTI", "required": true,
                  "source": {"constants": [{"value": "US", "display_value": "United States"}]}
                },
                {
                  "name": "state", "label": "State", "type": "STRING", "kind": "SELECT", "required": true,
                  "source": {"template": {"id": "$TEMPLATE_ID", "version": 1}, "datasource": "transfer-e2e"},
                  "depends_on": ["country"]
                }
              ]
            }
            """.trimIndent()
    }
}
