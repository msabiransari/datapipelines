package co.datapipelines.mcp

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * `templates_update` (117, mcp-server.md §6.2.36) — the draft write REST §8.4 makes, asserted
 * through the REAL [TemplateDraftService] (no service double: the copy-on-write, no-op and
 * type-immutability rules are the service's, and a mock would let the tool re-decide them).
 * The repository and validator are the SAME doubles [TemplateToolsTest] uses for
 * `templates_create` — this module's tool tests are wire-and-wiring tests, not database tests.
 */
class TemplatesUpdateToolTest {
    private val templates = mockk<TemplateRepository>()
    private val validator = mockk<TemplateValidator>()
    private val authoring = AuthoringGuard(true)
    private val ctx = McpFixtures.ctx()

    /** The REAL service REST §8.4 goes through — the tool must not re-decide any of its rules. */
    private fun updateTool() = TemplatesUpdateTool(templates, TemplateDraftService(templates, authoring), validator)

    private val args =
        McpArguments(
            mapOf(
                "id" to "test/revenue.sql",
                "expected_hash" to "hash-v1",
                "dialect" to "POSTGRES",
                "display_name" to "Revenue",
                "description" to "Expects: month (STRING).",
                "body" to "SELECT 2",
            ),
        )

    /** The stored row the tool reads back after the service write — the §8.4 projection's source. */
    private fun stored(
        version: Int = 2,
        body: String = "SELECT 2",
    ): Template =
        McpFixtures
            .template(version = version)
            .copy(
                body = body,
                status = if (version == 1) PipelineVersionStatus.RELEASED else PipelineVersionStatus.DRAFT,
                bodyHash = "hash-v$version",
            )

    private fun draftDetail(
        version: Int,
        status: PipelineVersionStatus,
        hash: String = "hash-v$version",
    ): TemplateVersionDetail =
        TemplateVersionDetail(
            templateId = "test/revenue.sql",
            version = version,
            status = status,
            bodyHash = hash,
            createdAt = java.time.Instant.EPOCH,
            createdBy = McpFixtures.USER,
            updatedBy = McpFixtures.USER,
            updatedAt = java.time.Instant.EPOCH,
        )

    @Test
    fun `update validates, writes the draft through the service, and answers with the stored projection`() {
        val validated = slot<TemplateDraft>()
        val writtenHash = slot<String>()
        every { validator.validateOrThrow(capture(validated), McpFixtures.WORKSPACE_ID) } answers { firstArg() }
        // First write after a release: the released v1 is the working version, no draft exists,
        // and createDraft copies it to v2. WriteSurface.MCP and the actor are part of the
        // expectation — a write that lost either would be stamping the wrong surface.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns null
        every {
            templates.createDraft(
                McpFixtures.WORKSPACE_ID,
                "test/revenue.sql",
                any(),
                capture(writtenHash),
                McpFixtures.USER,
                WriteSurface.MCP,
            )
        } returns draftDetail(2, PipelineVersionStatus.DRAFT)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 2) } returns stored()

        val payload = updateTool().call(args, ctx) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            { payload["id"].asText() shouldBe "test/revenue.sql" },
            { payload["version"].asInt() shouldBe 2 },
            { payload["status"].asText() shouldBe "DRAFT" },
            { payload["body_hash"].asText() shouldBe "hash-v2" },
            { payload["dialect"].asText() shouldBe "POSTGRES" },
            { payload["type"].asText() shouldBe "sql" },
            { payload["body"].asText() shouldBe "SELECT 2" },
            { payload["draft"]["version"].asInt() shouldBe 2 },
            { payload["draft"]["body_hash"].asText() shouldBe "hash-v2" },
            // The hash the caller read is the precondition the service received — verbatim.
            { writtenHash.captured shouldBe "hash-v1" },
            { validated.captured.body shouldBe "SELECT 2" },
        )
    }

    @Test
    fun `a second write overwrites the same draft in place and carries its pointer`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        // §5.2: the draft already exists, so writeDraft overwrites it in place — same version
        // number, new content hash. No createDraft may fire: a second copy would be the pile
        // the draft rule exists to prevent.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored()
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns
            draftDetail(2, PipelineVersionStatus.DRAFT, hash = "hash-v2-old")
        every {
            templates.writeDraft(McpFixtures.WORKSPACE_ID, "test/revenue.sql", any(), "hash-v2-old", McpFixtures.USER, WriteSurface.MCP)
        } returns draftDetail(2, PipelineVersionStatus.DRAFT)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 2) } returns stored()

        val payload =
            updateTool().call(args + mapOf("expected_hash" to "hash-v2-old"), ctx) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            { payload["version"].asInt() shouldBe 2 },
            { payload["status"].asText() shouldBe "DRAFT" },
            { payload["draft"]["version"].asInt() shouldBe 2 },
        )
        verify(exactly = 0) { templates.createDraft(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a no-op body answers RELEASED and carries no draft pointer`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        // §5.1, the createDraft arm: the incoming content hash equals the released content's,
        // so the draft insert is suppressed and the repository answers with the RELEASED state.
        // The tool must NOT dress that up as a draft — the agent reading status DRAFT here
        // would believe its no-op opened one.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns null
        every {
            templates.createDraft(McpFixtures.WORKSPACE_ID, "test/revenue.sql", any(), "hash-v1", McpFixtures.USER, WriteSurface.MCP)
        } returns draftDetail(1, PipelineVersionStatus.RELEASED)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 1) } returns stored(version = 1)

        val payload = updateTool().call(args + mapOf("body" to "SELECT 1"), ctx) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            { payload["version"].asInt() shouldBe 1 },
            { payload["status"].asText() shouldBe "RELEASED" },
            { payload.has("draft") shouldBe false },
        )
    }

    @Test
    fun `update without expected_hash is a protocol error before anything is read`() {
        shouldThrow<McpError> {
            updateTool().call(args - "expected_hash", ctx)
        }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS

        // A malformed precondition must not reach the working-version read at all.
        verify(exactly = 0) { templates.findWorking(any(), any()) }
    }

    @Test
    fun `update of an unknown template is the catalogued not-found`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "nope/x.sql") } returns null

        val error =
            shouldThrow<DatapipelinesException> {
                updateTool().call(args + mapOf("id" to "nope/x.sql"), ctx)
            }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Template.NOT_FOUND },
            { error.details["template_id"] shouldBe "nope/x.sql" },
        )
    }

    @Test
    fun `a stale hash is the catalogued conflict with the current state in details`() {
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        // Both writes miss (§4.2: the guard is the statement's own predicate) — the service
        // re-reads and raises the conflict carrying what IS current, which is what the agent
        // rebases on.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns null
        every {
            templates.createDraft(any(), any(), any<TemplateDraft>(), any(), any(), any())
        } returns null
        every { templates.findLatest(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findVersionDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 1) } returns
            draftDetail(1, PipelineVersionStatus.RELEASED)

        val error =
            shouldThrow<DatapipelinesException> {
                updateTool().call(args + mapOf("expected_hash" to "hash-someone-else-wrote"), ctx)
            }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Template.VERSION_CONFLICT },
            { error.details["current_body_hash"] shouldBe "hash-v1" },
            { error.details["current_status"] shouldBe "RELEASED" },
        )
    }

    @Test
    fun `a type change is refused with the validator's own immutable-type code`() {
        // 046 §5.3: the type is chosen at creation; the refusal comes from
        // TemplateTypeRule.forExisting INSIDE the real service, so the tool cannot answer a
        // different rule than the REST surface does.
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)

        val error =
            shouldThrow<DatapipelinesException> {
                updateTool().call(args + mapOf("type" to "html", "dialect" to null), ctx)
            }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Template.TYPE_IMMUTABLE },
            { error.details["established_type"] shouldBe "sql" },
            { error.details["type"] shouldBe "html" },
        )
    }

    /**
     * 135 §C (T276) — three sightings in one day: an update sent without `dialect` was refused
     * `template.validation.dialect_invalid` and the agent resent with the dialect the draft
     * already had. The dialect is a property of the template the id names, so an absent one
     * is INHERITED from the working version; the validator then sees a complete draft.
     */
    @Test
    fun `an update without dialect inherits the working version's dialect`() {
        val validated = slot<TemplateDraft>()
        every { validator.validateOrThrow(capture(validated), McpFixtures.WORKSPACE_ID) } answers { firstArg() }
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns null
        every {
            templates.createDraft(McpFixtures.WORKSPACE_ID, "test/revenue.sql", any(), "hash-v1", McpFixtures.USER, WriteSurface.MCP)
        } returns draftDetail(2, PipelineVersionStatus.DRAFT)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 2) } returns stored()

        val payload = updateTool().call(args - "dialect", ctx) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            // The draft the validator and the write received carries the STORED dialect —
            // the validator's "dialect is required unless html" rule sees a complete draft.
            { validated.captured.dialect shouldBe Dialect.POSTGRES },
            { payload["status"].asText() shouldBe "DRAFT" },
            { payload["dialect"].asText() shouldBe "POSTGRES" },
        )
    }

    /**
     * 136 §D (T289b) — `type` used to DEFAULT to `sql`, so an html template updated without
     * `type` was refused `type_immutable` for a field the agent never changed. It is inherited
     * from the working version the way 135 inherits the dialect; a stated one still reaches the
     * service's own immutability check (the arm above).
     */
    @Test
    fun `an update without type inherits the working version's type - an html template stays html`() {
        val validated = slot<TemplateDraft>()
        val html =
            stored(version = 1, body = "<p>\${x}</p>").copy(id = "test/card.html", type = TemplateType.HTML, dialect = null)
        every { validator.validateOrThrow(capture(validated), McpFixtures.WORKSPACE_ID) } answers { firstArg() }
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/card.html") } returns html
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/card.html") } returns null
        every {
            templates.createDraft(McpFixtures.WORKSPACE_ID, "test/card.html", any(), "hash-v1", McpFixtures.USER, WriteSurface.MCP)
        } returns draftDetail(2, PipelineVersionStatus.DRAFT)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/card.html", 2) } returns
            html.copy(version = 2, body = "<p>\${y}</p>", status = PipelineVersionStatus.DRAFT, bodyHash = "hash-v2")

        val payload =
            updateTool().call(
                McpArguments(args.rawMap() - "dialect" + mapOf("id" to "test/card.html", "body" to "<p>\${y}</p>")),
                ctx,
            ) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            { validated.captured.type shouldBe TemplateType.HTML },
            { validated.captured.dialect shouldBe null },
            { payload["type"].asText() shouldBe "html" },
            { payload["status"].asText() shouldBe "DRAFT" },
        )
    }

    @Test
    fun `an update naming the dialect the draft already has is the ordinary write`() {
        val validated = slot<TemplateDraft>()
        every { validator.validateOrThrow(capture(validated), McpFixtures.WORKSPACE_ID) } answers { firstArg() }
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        every { templates.findDraftDetail(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns null
        every {
            templates.createDraft(McpFixtures.WORKSPACE_ID, "test/revenue.sql", any(), "hash-v1", McpFixtures.USER, WriteSurface.MCP)
        } returns draftDetail(2, PipelineVersionStatus.DRAFT)
        every { templates.findVersion(McpFixtures.WORKSPACE_ID, "test/revenue.sql", 2) } returns stored()

        val payload = updateTool().call(args + mapOf("dialect" to "POSTGRES"), ctx) as com.fasterxml.jackson.databind.node.ObjectNode

        assertAll(
            { validated.captured.dialect shouldBe Dialect.POSTGRES },
            { payload["draft"]["version"].asInt() shouldBe 2 },
        )
    }

    @Test
    fun `an update naming a DIFFERENT dialect is refused before anything is validated or written`() {
        // A pipeline node pins this template against a source of the established dialect; a
        // template re-targeted at another engine is a new template, not an edit of this one.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)

        val error =
            shouldThrow<DatapipelinesException> {
                updateTool().call(args + mapOf("dialect" to "H2"), ctx)
            }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Template.DIALECT_INVALID },
            { error.details["dialect"] shouldBe "H2" },
            { error.details["established_dialect"] shouldBe "POSTGRES" },
            { error.details["template_id"] shouldBe "test/revenue.sql" },
        )
        verify(exactly = 0) { validator.validateOrThrow(any(), any()) }
        verify(exactly = 0) { templates.createDraft(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update refuses with the catalogued code when authoring is disabled`() {
        // versioning §5.5: the guard lives in the service both surfaces share — a promotion
        // receiver's agent gets the same refusal through the tool as through the REST PUT
        // (which also validates first, then refuses at the write).
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        // 135 §C: the tool reads the working version for the dialect before it validates; the
        // guard still fires at the write, exactly where REST's does.
        every { templates.findWorking(McpFixtures.WORKSPACE_ID, "test/revenue.sql") } returns stored(version = 1)
        val hardened = TemplatesUpdateTool(templates, TemplateDraftService(templates, AuthoringGuard(false)), validator)

        val error = shouldThrow<DatapipelinesException> { hardened.call(args, ctx) }

        assertAll(
            { error.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED },
            { error.details["config_key"] shouldBe AuthoringGuard.CONFIG_KEY },
        )
    }

    /** `args + mapOf(...)` / `args - key` sugar over the raw argument map, for legibility above. */
    private operator fun McpArguments.plus(extra: Map<String, Any?>): McpArguments = McpArguments(rawMap() + extra)

    private operator fun McpArguments.minus(key: String): McpArguments = McpArguments(rawMap() - key)
}
