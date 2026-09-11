package co.datapipelines.web.templates

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateFolder
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §8 over a mocked repository/engine: CRUD, versioned reads, render, import — and the gate C
 * `template.not_found` code on every miss path.
 */
class TemplatesControllerTest {
    private val repository = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val engine = mockk<TemplateEngine>()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { engineFor(any()) } returns engine
        }

    private val drafts = mockk<TemplateDraftService>()
    private val releases = mockk<TemplateReleaseService>()

    // Import moved to TemplateImportService (extracted for the D9 seeder); the real service is
    // used so the import cases still exercise the shipped parsing and per-entry semantics.
    private val guard = co.datapipelines.pipeline.AuthoringGuard(true)

    /** A RECORDING sink, never a strict mock: a missing audit call must be able to fail a test. */
    private val audited = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val audit =
        object : co.datapipelines.auth.AuditEventSink {
            override fun log(
                event: String,
                userId: java.util.UUID?,
                keyId: String?,
                sourceIp: String?,
                userAgent: String?,
                details: Map<String, Any?>,
            ) {
                audited += event to details
            }
        }

    private val controller =
        TemplatesController(
            repository,
            validator,
            engines,
            TemplateImportService(repository, validator),
            drafts,
            releases,
            guard,
            audit,
        )

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun template(version: Int = 1) =
        Template(
            id = "test/fetch_orders.sql",
            version = version,
            dialect = Dialect.POSTGRES,
            displayName = "Fetch Orders",
            description = "d",
            body = "SELECT 1",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = userId,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private val createBody =
        """{"dialect":"POSTGRES","display_name":"Fetch Orders","description":"d","body":"SELECT 1"}"""

    private val updateBody =
        """{"id":"test/fetch_orders.sql","dialect":"POSTGRES","display_name":"Fetch Orders","description":"d","body":"SELECT 1"}"""

    @Test
    fun `create validates and stores, returning version 1`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.create(any(), any(), userId, co.datapipelines.pipeline.CreateLifecycle.DRAFT, WriteSurface.SESSION) } returns
            template()

        val stored = controller.create(createBody).data
        stored.id shouldBe "test/fetch_orders.sql"
        stored.version shouldBe 1
    }

    @Test
    fun `create and delete refuse with the catalogued code when authoring is disabled`() {
        // versioning §5.5's template mirror: a promotion receiver fails closed.
        authenticate()
        val receiver =
            TemplatesController(
                repository,
                validator,
                engines,
                TemplateImportService(repository, validator),
                drafts,
                releases,
                co.datapipelines.pipeline.AuthoringGuard(false),
                audit,
            )
        // The authoring refusal for delete comes from the service the controller delegates to.
        every { releases.purgeEntity(any(), "test/fetch_orders.sql") } throws
            DatapipelinesException(
                code = PipelineErrorCodes.Template.AUTHORING_DISABLED,
                message = "authoring disabled",
                details = mapOf("config_key" to co.datapipelines.pipeline.AuthoringGuard.CONFIG_KEY),
            )

        val create =
            shouldThrow<DatapipelinesException> {
                receiver.create("""{"dialect":"POSTGRES","display_name":"X","description":"d","body":"SELECT 1"}""")
            }
        create.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
        create.details["config_key"] shouldBe co.datapipelines.pipeline.AuthoringGuard.CONFIG_KEY

        val delete = shouldThrow<DatapipelinesException> { receiver.delete("test/fetch_orders.sql") }
        delete.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
    }

    @Test
    fun `get latest and get specific version`() {
        authenticate()
        every { repository.findLatest(any(), "test/fetch_orders.sql") } returns template(2)
        // The import's type inheritance and the draft service both read the WORKING version (D55);
        // the released read stays for the paths that mean "what is released".
        every { repository.findWorking(any(), "test/fetch_orders.sql") } returns template(2)
        every { repository.findDraftDetail(any(), "test/fetch_orders.sql") } returns null
        val latest = controller.get("test/fetch_orders.sql").data
        latest.get("version").asInt() shouldBe 2

        every { repository.findVersion(any(), "test/fetch_orders.sql", 1) } returns template(1)
        controller.getVersion("test/fetch_orders.sql", 1).data.version shouldBe 1
    }

    @Test
    fun `get returns the working version - the draft's projection when one exists`() {
        authenticate()
        // §7.1's template mirror: the default read is the DRAFT, else an author rebases on
        // the released body and quietly discards the draft with the next write.
        every { repository.findDraftDetail(any(), "test/fetch_orders.sql") } returns
            TemplateVersionDetail(
                templateId = "test/fetch_orders.sql",
                version = 2,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v2",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
            )
        every { repository.findVersion(any(), "test/fetch_orders.sql", 2) } returns
            template(2).copy(body = "SELECT 2", status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v2")

        val data = controller.get("test/fetch_orders.sql").data

        data.get("version").asInt() shouldBe 2
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body").asText() shouldBe "SELECT 2"
        data.get("body_hash").asText() shouldBe "hash-v2"
        data.get("draft").get("version").asInt() shouldBe 2
    }

    @Test
    fun `misses are template-not_found, with the version in details for a versioned miss`() {
        authenticate()
        every { repository.findDraftDetail(any(), "nope.sql") } returns null
        every { repository.findLatest(any(), "nope.sql") } returns null
        shouldThrow<ApiException> { controller.get("nope.sql") }.code shouldBe "template.not_found"

        every { repository.findVersion(any(), "test/fetch_orders.sql", 9) } returns null
        every { repository.existsId(any(), "test/fetch_orders.sql") } returns true
        val error = shouldThrow<ApiException> { controller.getVersion("test/fetch_orders.sql", 9) }
        error.code shouldBe "template.not_found"
        error.details["version"] shouldBe 9

        // 101: the entity purge's 404 — the service raises template.not_found; the controller
        // is a thin session-gated delegate.
        every { releases.purgeEntity(any(), "nope.sql") } throws
            co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Template.NOT_FOUND,
                message = "Template 'nope.sql' does not exist.",
                details = mapOf("template_id" to "nope.sql"),
            )
        val missing = shouldThrow<co.datapipelines.typesystem.DatapipelinesException> { controller.delete("nope.sql") }
        missing.code shouldBe "template.not_found"
    }

    @Test
    fun `delete delegates to the entity purge and propagates its in_use refusal`() {
        authenticate()
        // 204 path: the controller is a thin delegate; the purge rules are the service's.
        every { releases.purgeEntity(any(), "test/fetch_orders.sql") } returns Unit
        controller.delete("test/fetch_orders.sql")

        // The refusal propagates untouched: template.in_use with the pinner names (the
        // service's guard read them through the live pin scan).
        every { releases.purgeEntity(any(), "pinned.sql") } throws
            co.datapipelines.typesystem.DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message = "Version of template 'pinned.sql' is pinned by 1 live pipeline version(s): p1.",
                details = mapOf("template_id" to "pinned.sql", "pinned_by" to listOf("p1")),
            )
        val refusal = shouldThrow<co.datapipelines.typesystem.DatapipelinesException> { controller.delete("pinned.sql") }
        refusal.code shouldBe PipelineErrorCodes.Template.IN_USE
    }

    @Test
    fun `update requires If-Match, writes the draft branch, and 404s on an unknown id`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }

        // §4.2: no If-Match, no participation in the protocol at all — a 400, not a conflict.
        val missing = shouldThrow<ApiException> { controller.update(null, updateBody) }
        missing.details["reason"] shouldBe "precondition_missing"

        val detail =
            TemplateVersionDetail(
                templateId = "test/fetch_orders.sql",
                version = 3,
                status = PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v3",
                createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                createdBy = userId,
            )
        every { drafts.write(any(), "test/fetch_orders.sql", any(), "hash-v2", userId, WriteSurface.SESSION) } returns detail
        every { repository.findVersion(any(), "test/fetch_orders.sql", 3) } returns
            template(3).copy(status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v3")
        val data = controller.update("hash-v2", updateBody).data
        data.get("version").asInt() shouldBe 3
        data.get("status").asText() shouldBe "DRAFT"
        data.get("body_hash").asText() shouldBe "hash-v3"

        val notFoundError =
            DatapipelinesException(
                PipelineErrorCodes.Template.NOT_FOUND,
                "Template 'nope.sql' not found.",
                emptyMap(),
            )
        every { drafts.write(any(), "nope.sql", any(), any(), userId, WriteSurface.SESSION) } throws notFoundError
        val thrown =
            shouldThrow<DatapipelinesException> { controller.update("hash-v2", updateBody.replace("test/fetch_orders.sql", "nope.sql")) }
        thrown.code shouldBe "template.not_found"
    }

    @Test
    fun `update without an id in the body is refused - the name never travels in the path`() {
        // §9.6: PUT /api/v1/templates carries the name in the body's `id` field; a body
        // without one cannot be addressed at all, so it is a 400, never a silent create.
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }

        val thrown = shouldThrow<ApiException> { controller.update("hash-v2", createBody) }
        thrown.code shouldBe PipelineErrorCodes.Template.ID_INVALID
        thrown.details["reason"] shouldBe "id_missing"
    }

    @Test
    fun `release and discard require If-Match and delegate to the lifecycle service`() {
        authenticate()
        val releaseMissing = shouldThrow<ApiException> { controller.release(null, """{"name":"test/fetch_orders.sql"}""") }
        releaseMissing.details["reason"] shouldBe "precondition_missing"
        val discardMissing = shouldThrow<ApiException> { controller.discard(null, """{"name":"test/fetch_orders.sql"}""") }
        discardMissing.details["reason"] shouldBe "precondition_missing"

        every { releases.release(any(), "test/fetch_orders.sql", "hash-v3", userId) } returns
            TemplateReleaseService.Released(
                TemplateVersionDetail(
                    templateId = "test/fetch_orders.sql",
                    version = 3,
                    status = PipelineVersionStatus.RELEASED,
                    bodyHash = "hash-v3",
                    createdAt = Instant.parse("2026-08-02T00:00:00Z"),
                    createdBy = userId,
                ),
                template(3),
            )
        val released = controller.release("hash-v3", """{"name":"test/fetch_orders.sql"}""").data
        released.get("status").asText() shouldBe "RELEASED"
        // T187 — the release is audited on the REST surface too, with the version and the `via`.
        audited.map { it.first } shouldBe listOf("template.version.released")
        audited.single().second["version"] shouldBe 3
        audited.single().second["via"] shouldBe "session"

        every { releases.purge(any(), "test/fetch_orders.sql", "hash-v3") } returns Unit
        controller.discard("hash-v3", """{"name":"test/fetch_orders.sql"}""")
    }

    @Test
    fun `release and discard without a name in the body are refused`() {
        // §9.6: the name is a body field on these routes; a missing one is a 400 with the
        // same invalid_parameter_type shape the render endpoint uses for a missing context.
        authenticate()
        shouldThrow<ApiException> { controller.release("hash-v3", """{}""") }
            .details["reason"] shouldBe "name_missing"
        shouldThrow<ApiException> { controller.discard("hash-v3", """{"name":"  "}""") }
            .details["reason"] shouldBe "name_missing"
    }

    @Test
    fun `list paginates and filters by dialect`() {
        authenticate()
        every { repository.list(any(), Dialect.POSTGRES, null, null, 0, 3) } returns listOf(template(), template(2))
        val data = controller.list(dialect = "POSTGRES", type = null, q = null, offset = 0, limit = 2).data
        data.items.size shouldBe 2
        data.pagination.hasMore shouldBe false

        shouldThrow<ApiException> { controller.list(dialect = "DB2", type = null, q = null, offset = null, limit = null) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
    }

    @Test
    fun `list filters by type and refuses an unknown type value`() {
        authenticate()
        every { repository.list(any(), null, TemplateType.HTML, null, 0, 3) } returns listOf(template())
        val data = controller.list(dialect = null, type = "html", q = null, offset = 0, limit = 2).data
        data.items.size shouldBe 1

        shouldThrow<ApiException> { controller.list(dialect = null, type = "csv", q = null, offset = null, limit = null) }
            .code shouldBe "pipeline.execution.invalid_parameter_type"
    }

    // The `prefix` browse presentation — the REST mirror of `templates_list {prefix}` (067),
    // whose cases in TemplateToolsTest these mirror.

    @Test
    fun `browse returns ONE level - folders with counts and the level's own leaves`() {
        authenticate()
        // `?prefix=` (empty) is the ROOT: present-but-empty, a different request from an
        // absent prefix (the flat listing).
        every { repository.listChildFolders(workspaceId, null, null, null, TemplateRepository.MAX_PAGE_LIMIT) } returns
            listOf(TemplateFolder("acme", "acme", 3), TemplateFolder("test", "test", 1))
        every { repository.listChildTemplates(workspaceId, null, null, null, 0, 51) } returns listOf(template())
        every { repository.countChildTemplates(workspaceId, null, null, null) } returns 1

        val data = controller.browse(prefix = "", dialect = null, type = null, offset = null, limit = null).data

        data["prefix"] shouldBe ""
        (data["folders"] as List<*>).map { (it as Map<*, *>)["path"] } shouldContainExactly listOf("acme", "test")
        (data["folders"] as List<*>).map { (it as Map<*, *>)["template_count"] } shouldContainExactly listOf(3, 1)
        (data["templates"] as List<*>).map { (it as Template).id } shouldContainExactly listOf("test/fetch_orders.sql")
        data["total"] shouldBe 1
        data["has_more"] shouldBe false
        // The flat listing is NOT consulted for a browse — one level per request, and browse
        // and search are different presentations.
        verify(exactly = 0) { repository.list(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `browse asks for exactly the named level, narrowed by the dialect filter`() {
        authenticate()
        every { repository.listChildFolders(workspaceId, "acme/finance", Dialect.MYSQL, null, TemplateRepository.MAX_PAGE_LIMIT) } returns
            emptyList()
        every { repository.listChildTemplates(workspaceId, "acme/finance", Dialect.MYSQL, null, 0, 51) } returns
            listOf(template())
        every { repository.countChildTemplates(workspaceId, "acme/finance", Dialect.MYSQL, null) } returns 1

        val data = controller.browse(prefix = "acme/finance", dialect = "MYSQL", type = null, offset = null, limit = null).data

        data["prefix"] shouldBe "acme/finance"
        (data["folders"] as List<*>).size shouldBe 0
        (data["templates"] as List<*>).map { (it as Template).id } shouldContainExactly listOf("test/fetch_orders.sql")
        verify(exactly = 1) {
            repository.listChildFolders(workspaceId, "acme/finance", Dialect.MYSQL, null, TemplateRepository.MAX_PAGE_LIMIT)
        }
    }

    @Test
    fun `browse with an illegal prefix answers an empty level and never reaches the database`() {
        authenticate()

        val data = controller.browse(prefix = "acme/../etc", dialect = null, type = null, offset = null, limit = null).data

        data["prefix"] shouldBe "acme/../etc"
        (data["folders"] as List<*>).size shouldBe 0
        (data["templates"] as List<*>).size shouldBe 0
        data["total"] shouldBe 0
        data["has_more"] shouldBe false
        verify(exactly = 0) { repository.listChildFolders(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.listChildTemplates(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.countChildTemplates(any(), any(), any(), any()) }
    }

    @Test
    fun `create accepts an html payload without a dialect and echoes the type`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.create(any(), any(), userId, co.datapipelines.pipeline.CreateLifecycle.DRAFT, WriteSurface.SESSION) } returns
            template().copy(type = TemplateType.HTML, dialect = null)

        val stored =
            controller
                .create(
                    """{"id":"report.html","type":"html","display_name":"R","description":"d","body":"<p>${'$'}{x}</p>"}""",
                ).data
        stored.type shouldBe TemplateType.HTML
        stored.dialect shouldBe null
    }

    @Test
    fun `update refuses a payload that changes the template's type`() {
        authenticate()
        val latest = template()
        every { repository.findLatest(any(), "test/fetch_orders.sql") } returns latest
        // The import's type inheritance and the draft service both read the WORKING version (D55);
        // the released read stays for the paths that mean "what is released".
        every { repository.findWorking(any(), "test/fetch_orders.sql") } returns latest
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        // The refusal lives in the REAL draft service (TemplateTypeRule.forExisting), so this
        // one test wires it instead of the mocked `drafts` the other update tests use.
        val controllerWithRealDrafts =
            TemplatesController(
                repository,
                validator,
                engines,
                TemplateImportService(repository, validator),
                TemplateDraftService(repository, co.datapipelines.pipeline.AuthoringGuard(true)),
                releases,
                co.datapipelines.pipeline.AuthoringGuard(true),
                audit,
            )

        val thrown =
            shouldThrow<DatapipelinesException> {
                controllerWithRealDrafts.update(
                    ifMatch = "hash-v1",
                    body =
                        """{"id":"test/fetch_orders.sql","type":"html","display_name":"F","description":"d","body":"SELECT 1"}""",
                )
            }
        thrown.code shouldBe PipelineErrorCodes.Template.TYPE_IMMUTABLE
    }

    @Test
    fun `render returns the engine's SQL as the data payload`() {
        authenticate()
        every { repository.lookupVersion(any(), "test/fetch_orders.sql", 1) } returns
            TemplateVersion(
                id = "test/fetch_orders.sql",
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = "SELECT \${x}",
                createdAt = Instant.EPOCH,
                createdBy = userId,
            )
        every { engine.render(TemplateRef("test/fetch_orders.sql", 1), mapOf("x" to 42)) } returns "SELECT 42"

        val rendered =
            controller.render("""{"name":"test/fetch_orders.sql","version":1,"context":{"x":42}}""").data
        rendered shouldBe "SELECT 42"

        every { repository.lookupVersion(any(), "nope.sql", 1) } returns null
        every { repository.existsId(any(), "nope.sql") } returns false
        shouldThrow<ApiException> { controller.render("""{"name":"nope.sql","version":1,"context":{}}""") }
            .code shouldBe "template.not_found"
    }

    @Test
    fun `render requires name, version and context in the body`() {
        authenticate()
        shouldThrow<ApiException> { controller.render("""{"version":1,"context":{}}""") }
            .details["reason"] shouldBe "name_missing"
        shouldThrow<ApiException> { controller.render("""{"name":"t.sql","context":{}}""") }
            .details["reason"] shouldBe "version_missing"
        shouldThrow<ApiException> { controller.render("""{"name":"t.sql","version":1}""") }
            .details["reason"] shouldBe "context_missing"
        shouldThrow<ApiException> { controller.render("""not json""") }
            .details["reason"] shouldBe "malformed_json"
    }

    @Test
    fun `import creates new ids and versions existing ones`() {
        authenticate()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { repository.existsId(any(), "test/fetch_orders.sql") } returns true
        every { repository.findLatest(any(), "test/fetch_orders.sql") } returns template(2)
        // The import's type inheritance and the draft service both read the WORKING version (D55);
        // the released read stays for the paths that mean "what is released".
        every { repository.findWorking(any(), "test/fetch_orders.sql") } returns template(2)
        every { repository.appendReleasedVersion(any(), "test/fetch_orders.sql", any(), userId) } returns template(2)
        every { repository.existsId(any(), "new.sql") } returns false
        // D55: the IMPORT path is not authoring — it lands RELEASED, and the stub says so, so a
        // regression that routed an import through the authoring create would not match here.
        every { repository.create(any(), any(), userId, co.datapipelines.pipeline.CreateLifecycle.RELEASED, WriteSurface.SESSION) } returns
            template().copy(id = "new.sql")

        val body =
            """{"templates":[
                {"id":"test/fetch_orders.sql","dialect":"POSTGRES","display_name":"F","description":"d","body":"SELECT 1"},
                {"id":"new.sql","dialect":"POSTGRES","display_name":"N","description":"d","body":"SELECT 2"}
            ]}"""
        val data = controller.import(body).data
        data["imported"] shouldBe 2
    }
}
