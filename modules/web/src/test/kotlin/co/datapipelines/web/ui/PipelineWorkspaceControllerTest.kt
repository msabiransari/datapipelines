package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionRecord
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * #348 — the canonical read page's version resolution (workspace spec §3.1, A1/A2): explicit
 * admitted version wins; the default is the ACTUAL current pointer, then the accessible draft,
 * then choose-a-version — never a clamp, never a fallback, never a hidden-metadata leak. The
 * fixtures carry DIFFERENT node ids per version, so a wrong body cannot pass as the right
 * label (the design record's second migration trap).
 */
class PipelineWorkspaceControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val lens = mockk<PromoterLens>()
    private val controller =
        PipelineWorkspaceController(
            PipelineWorkspaceModel(co.datapipelines.web.pipelineServiceOver(repository)),
            themeResolver,
            lens,
        )

    private val pipelineId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val everything = LensedView(ReadLens.Everything, ReadLens.Everything)

    /** The promoter lens, aimed at this fixture's one pipeline name: it admits the record and nothing else. */
    private val narrowed = LensedView(ReadLens.Only(setOf("sample_pipeline")), ReadLens.NOTHING)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { lens.viewFor(any()) } returns everything
        every { themeResolver.resolve(any()) } returns "saas"
    }

    /** Re-aims the lens AND the role at the promoter for one test's later opens: the lens narrows the reads, the role gates the verbs. */
    private fun becomePromoter() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "p@b.c",
                "P",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme", role = WorkspaceRole.PROMOTER),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { lens.viewFor(any()) } returns narrowed
    }

    private val record =
        PipelineRecord(
            id = pipelineId,
            name = "sample_pipeline",
            displayName = "Sample Pipeline",
            description = "A sample pipeline for testing",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    /** Each version's body names a DIFFERENT node id — the body, not a label, is the assertion. */
    private fun bodyJson(nodeId: String): String =
        """
        {
          "schema_version": 1,
          "name": "sample_pipeline",
          "display_name": "Sample Pipeline",
          "settings": {"tempdb": {"engine": "H2"}},
          "parameters": {},
          "nodes": [
            {"id": "$nodeId", "type": "DQL", "source": "prod_db",
             "template": {"id": "test/select_all", "version": 1}, "depends_on": []}
          ]
        }
        """.trimIndent()

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ): PipelineVersionDetail =
        PipelineVersionDetail(
            pipelineId = pipelineId,
            version = version,
            status = status,
            bodyHash = "hash-$version",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    private fun row(
        version: Int,
        status: PipelineVersionStatus,
    ): PipelineVersionRecord =
        PipelineVersionRecord(
            pipelineId = pipelineId,
            version = version,
            status = status,
            bodyHash = "hash-$version",
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    /** v1 current release, v2 newer release (NOT current), v3 draft. */
    private fun seedThreeVersions() {
        every { repository.findById(any(), pipelineId) } returns record.copy(currentVersion = 1)
        every { repository.findVersionBody(any(), pipelineId, 1) } returns bodyJson("extract_v1")
        every { repository.findVersionBody(any(), pipelineId, 2) } returns bodyJson("extract_v2")
        every { repository.findVersionBody(any(), pipelineId, 3) } returns bodyJson("extract_v3")
        every { repository.findVersionDetail(any(), pipelineId, 1) } returns detail(1, PipelineVersionStatus.RELEASED)
        every { repository.findVersionDetail(any(), pipelineId, 2) } returns detail(2, PipelineVersionStatus.RELEASED)
        every { repository.findVersionDetail(any(), pipelineId, 3) } returns detail(3, PipelineVersionStatus.DRAFT)
        every { repository.findDraftDetail(any(), pipelineId) } returns detail(3, PipelineVersionStatus.DRAFT)
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns detail(1, PipelineVersionStatus.RELEASED)
        every { repository.listVersions(any(), pipelineId) } returns
            listOf(row(3, PipelineVersionStatus.DRAFT), row(2, PipelineVersionStatus.RELEASED), row(1, PipelineVersionStatus.RELEASED))
    }

    private fun open(
        version: String? = null,
        tab: String? = null,
    ): ExtendedModelMap {
        val m = ExtendedModelMap()
        controller.workspace(pipelineId, version, tab, m, mockk())
        return m
    }

    @Test
    fun `the default view is the ACTUAL current pointer - v1 - even with a newer release and a draft`() {
        authenticate()
        seedThreeVersions()
        val m = open()
        m["viewedVersion"] shouldBe 1
        m["hasSelectedBody"] shouldBe true
        (m["pipelineJson"] as String) shouldContain "extract_v1"
        m["viewedIsCurrent"] shouldBe true
        m["viewedStatusLabel"] shouldBe "released"
        (m["viewedLabel"] as String) shouldBe "v1 · released · current"
    }

    @Test
    fun `an explicit version shows that version's body - v2 - and never falls back`() {
        authenticate()
        seedThreeVersions()
        val m = open(version = "2")
        m["viewedVersion"] shouldBe 2
        (m["pipelineJson"] as String) shouldContain "extract_v2"
        m["viewedIsCurrent"] shouldBe false
        m["viewedStatusLabel"] shouldBe "released"
    }

    @Test
    fun `an explicit draft version shows the draft body with its draft label`() {
        authenticate()
        seedThreeVersions()
        val m = open(version = "3")
        m["viewedVersion"] shouldBe 3
        (m["pipelineJson"] as String) shouldContain "extract_v3"
        m["viewedIsDraft"] shouldBe true
        m["viewedStatusLabel"] shouldBe "draft"
        // The draft pointer stays available for the Release affordance beside the viewed body.
        m["hasDraft"] shouldBe true
        m["draftVersion"] shouldBe 3
    }

    @Test
    fun `an absent explicit version is the house 404 - never the current body`() {
        authenticate()
        seedThreeVersions()
        every { repository.findVersionBody(any(), pipelineId, 9) } returns null
        every { repository.findVersionDetail(any(), pipelineId, 9) } returns null
        val e = shouldThrow<DatapipelinesException> { open(version = "9") }
        e.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `an invalid version syntax is the house 400 - zero, negative and non-numeric alike`() {
        authenticate()
        seedThreeVersions()
        shouldThrow<ResponseStatusException> { open(version = "abc") }.statusCode shouldBe HttpStatus.BAD_REQUEST
        shouldThrow<ResponseStatusException> { open(version = "0") }.statusCode shouldBe HttpStatus.BAD_REQUEST
        shouldThrow<ResponseStatusException> { open(version = "-1") }.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `a promoter reading an admitted release resolves the same body through the lens`() {
        authenticate()
        seedThreeVersions()
        becomePromoter()
        val m = open(version = "2")
        m["viewedVersion"] shouldBe 2
        (m["pipelineJson"] as String) shouldContain "extract_v2"
    }

    @Test
    fun `a promoter asking for the draft version gets the same 404 an absent number is`() {
        authenticate()
        seedThreeVersions()
        becomePromoter()
        val e = shouldThrow<DatapipelinesException> { open(version = "3") }
        e.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `a draft-only pipeline shows the draft by default, labelled - and the promoter gets the empty state`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record.copy(currentVersion = null)
        every { repository.findVersionBody(any(), pipelineId, 1) } returns bodyJson("extract_v1")
        every { repository.findVersionDetail(any(), pipelineId, 1) } returns detail(1, PipelineVersionStatus.DRAFT)
        every { repository.findDraftDetail(any(), pipelineId) } returns detail(1, PipelineVersionStatus.DRAFT)
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns null
        every { repository.listVersions(any(), pipelineId) } returns listOf(row(1, PipelineVersionStatus.DRAFT))

        val m = open()
        m["viewedVersion"] shouldBe 1
        m["viewedIsDraft"] shouldBe true
        m["viewedStatusLabel"] shouldBe "draft"
        m["viewedIsCurrent"] shouldBe false

        // Under the lens the draft does not exist: no current, no admitted versions — the
        // corresponding empty state, and no body, no execute, no hidden number.
        becomePromoter()
        val promoter = open()
        promoter["hasSelectedBody"] shouldBe false
        promoter["viewedVersion"] shouldBe null
        promoter["canExecute"] shouldBe false
        (promoter["versions"] as List<*>).isEmpty() shouldBe true
    }

    @Test
    fun `no current with an admitted history is the choose-a-version state with that history`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record.copy(currentVersion = null)
        every { repository.findVersionBody(any(), pipelineId, 1) } returns bodyJson("extract_v1")
        every { repository.findVersionBody(any(), pipelineId, 2) } returns bodyJson("extract_v2")
        every { repository.findVersionDetail(any(), pipelineId, 1) } returns detail(1, PipelineVersionStatus.RELEASED)
        every { repository.findVersionDetail(any(), pipelineId, 2) } returns detail(2, PipelineVersionStatus.RELEASED)
        every { repository.findDraftDetail(any(), pipelineId) } returns null
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns null
        every { repository.listVersions(any(), pipelineId) } returns
            listOf(row(2, PipelineVersionStatus.RELEASED), row(1, PipelineVersionStatus.RELEASED))

        val m = open()
        m["hasSelectedBody"] shouldBe false
        m["viewedVersion"] shouldBe null
        m["canExecute"] shouldBe false
        (m["versions"] as List<*>).size shouldBe 2
    }

    @Test
    fun `an unknown pipeline id is the house 404`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns null
        shouldThrow<DatapipelinesException> { open() }.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `a pipeline with no viewable body is the empty state - the pipeline exists, nothing hides`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record
        every { repository.findDraftDetail(any(), pipelineId) } returns null
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns null
        every { repository.findVersionBody(any(), pipelineId, 1) } returns null
        every { repository.findVersionDetail(any(), pipelineId, 1) } returns null
        every { repository.listVersions(any(), pipelineId) } returns emptyList()
        val m = open()
        m["hasSelectedBody"] shouldBe false
        m["viewedVersion"] shouldBe null
        m["canExecute"] shouldBe false
    }

    @Test
    fun `a present body with an absent detail row still renders - the narrow-read tolerance`() {
        authenticate()
        every { repository.findById(any(), pipelineId) } returns record.copy(currentVersion = 4)
        every { repository.findVersionBody(any(), pipelineId, 4) } returns bodyJson("extract_v4")
        every { repository.findVersionDetail(any(), pipelineId, 4) } returns null
        every { repository.findDraftDetail(any(), pipelineId) } returns null
        every { repository.findCurrentVersionDetail(any(), pipelineId) } returns null
        every { repository.listVersions(any(), pipelineId) } returns emptyList()

        val m = open()
        m["hasSelectedBody"] shouldBe true
        m["viewedVersion"] shouldBe 4
        // No detail row: no invented lifecycle badge, and no fabricated current marker.
        m["viewedStatusLabel"] shouldBe null
        m["viewedIsDraft"] shouldBe false
    }

    @Test
    fun `the tab set is closed - unknown resolves to flow and runs needs the execution read`() {
        authenticate()
        seedThreeVersions()
        open(tab = "nonsense")["activeTab"] shouldBe "flow"
        open(tab = null)["activeTab"] shouldBe "flow"
        // A member holds the execution read: runs stands. A promoter does not: flow, without
        // any runs read attempted (the read floor stays the reads', not the page's).
        open(tab = "runs")["activeTab"] shouldBe "runs"
        becomePromoter()
        val promoter = open(tab = "runs")
        promoter["activeTab"] shouldBe "flow"
        promoter["canReadExecutions"] shouldBe false
    }

    @Test
    fun `the workspace json names the viewed version - one source for displayed and submitted`() {
        authenticate()
        seedThreeVersions()
        val json = open(version = "2")["workspaceJson"] as String
        json shouldContain "\"viewedVersion\":2"
        json shouldContain "\"hasBody\":true"
        json shouldContain pipelineId.toString()
    }
}
