package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.parameters.ParameterEvaluationRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * #374 — the Parameter Sets controller: the workspace page's model attributes and its two JSON blocks, per role.
 * The blocks are the page's wire to its script, so they are asserted AS TEXT: a hostile display name must not be
 * able to close the block it rides in, a promoter's block must not carry a hidden current, and the role flag the
 * client reads is the SERVER's (a promoter's page issues no evaluate).
 */
class ParameterSetsUiControllerTest {
    private val sets = mockk<ParameterSetService>()
    private val themeResolver = mockk<ThemeResolver>()
    private val lens = mockk<PromoterLens>()
    private val evaluations = mockk<ParameterEvaluationRepository>()
    private val controller =
        ParameterSetsUiController(
            ParameterSetsBrowseModel(sets),
            ParameterSetsWorkspaceModel(sets),
            ParameterSetEvaluationsBrowseModel(sets, evaluations),
            themeResolver,
            lens,
        )
    private val ws = ParameterSetsUiFixtures.workspaceId
    private val id = UUID.randomUUID()
    private val request: HttpServletRequest = MockHttpServletRequest()

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun authenticate(
        role: WorkspaceRole,
        view: LensedView,
    ) {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@b.c",
                "U",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(ws, "acme", role = role),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        every { lens.viewFor(any()) } returns view
        every { themeResolver.resolve(any()) } returns "saas"
    }

    private val everything = LensedView(ReadLens.Everything, ReadLens.Everything)
    private val record = ParameterSetsUiFixtures.record(id, currentVersion = 1)

    private fun seed(
        lens: ReadLens,
        displayName: String = "Geo filters",
        withDraft: Boolean,
    ) {
        val one = ParameterSetsUiFixtures.version(record, 1, PipelineVersionStatus.RELEASED, displayName)
        val draft = ParameterSetsUiFixtures.version(record, 2, PipelineVersionStatus.DRAFT, displayName)
        every { sets.findWorking(ws, lens, id) } returns if (withDraft) draft else one
        every { sets.listVersions(ws, lens, id) } returns (if (withDraft) listOf(draft, one) else listOf(one)).map { it.detail }
        every { sets.findVersion(ws, lens, id, 1) } returns one
        every { sets.findVersion(ws, lens, id, 2) } returns if (withDraft) draft else null
    }

    private fun open(
        version: String? = null,
        tab: String? = null,
    ): ExtendedModelMap {
        val m = ExtendedModelMap()
        controller.workspace(id, version, tab, m, request) shouldBe "parameter-sets/workspace"
        return m
    }

    @Test
    fun `the history tab paints the first page of records and mounts no body - the page issues no evaluate`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        seed(ReadLens.Everything, withDraft = false)
        every { evaluations.page(ws, id, null, ParameterSetEvaluationsBrowseModel.PAGE_SIZE + 1, 0) } returns emptyList()

        val m = open(tab = "history")

        m["activeTab"] shouldBe "history"
        m["evaluations"] shouldBe emptyList<Any>()
        m["historyOffset"] shouldBe 0
        val state = m["workspaceJson"] as String
        state shouldContain "\"tab\":\"history\""
        state shouldContain "\"hasBody\":false"
        state shouldContain "\"canEvaluate\":false"
        // Non-vacuity: the same author on the workspace tab DOES mount the body and may evaluate.
        (open()["workspaceJson"] as String) shouldContain "\"canEvaluate\":true"
    }

    @Test
    fun `a promoter's history lists only the records of versions the lens admits`() {
        val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(setOf(record.name)))
        authenticate(WorkspaceRole.PROMOTER, narrowed)
        seed(ReadLens.Only(setOf(record.name)), withDraft = false)
        every { evaluations.page(ws, id, listOf(1), ParameterSetEvaluationsBrowseModel.PAGE_SIZE + 1, 0) } returns emptyList()

        open(tab = "history")["evaluations"] shouldBe emptyList<Any>()
    }

    @Test
    fun `an author's page carries the body block, the workspace block and the evaluate flag`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        seed(ReadLens.Everything, withDraft = false)
        val m = open()
        m["canEvaluate"] shouldBe true
        m["hasSelectedBody"] shouldBe true
        m["navCurrentPath"] shouldBe "acme/geo_filters"
        (m["parameterSetJson"] as String) shouldContain "\"country\""
        (m["workspaceJson"] as String) shouldContain "\"viewedVersion\":1"
        (m["workspaceJson"] as String) shouldContain "\"canEvaluate\":true"
    }

    @Test
    fun `a promoter's page carries NO evaluate flag - in the model AND in the block the script reads`() {
        val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, parameterSets = ReadLens.Only(setOf(record.name)))
        authenticate(WorkspaceRole.PROMOTER, narrowed)
        seed(ReadLens.Only(setOf(record.name)), withDraft = false)
        val m = open()
        m["canEvaluate"] shouldBe false
        (m["workspaceJson"] as String) shouldContain "\"canEvaluate\":false"
        m["hasSelectedBody"] shouldBe true
    }

    @Test
    fun `a viewer may evaluate - the row is the matrix's, not a guess from author`() {
        authenticate(WorkspaceRole.VIEWER, everything)
        seed(ReadLens.Everything, withDraft = false)
        open()["canEvaluate"] shouldBe true
    }

    @Test
    fun `a hostile display name cannot close the script block it rides in`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        seed(ReadLens.Everything, displayName = "x</script><script>alert(1)</script><!-- ", withDraft = false)
        val json = open()["parameterSetJson"] as String
        json shouldNotContain "</script"
        json shouldNotContain "<!--"
        json shouldNotContain " "
    }

    @Test
    fun `the block's current_version is the one the caller may SEE, not the stored pointer`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        seed(ReadLens.Everything, withDraft = true)
        // The stored pointer names v1; the default resolution shows v1; a viewer of the draft sees current 1 too.
        (open("2")["parameterSetJson"] as String) shouldContain "\"current_version\":1"
    }

    @Test
    fun `an explicit draft version is labelled and viewed as the draft`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        seed(ReadLens.Everything, withDraft = true)
        val m = open("2")
        m["viewedIsDraft"] shouldBe true
        m["viewedLabel"] shouldBe "v2 · draft"
    }

    @Test
    fun `a malformed version is the house 400 before any read`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        shouldThrow<ResponseStatusException> { controller.workspace(id, "abc", null, ExtendedModelMap(), request) }
            .statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `a set the caller cannot see is the 404`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        every { sets.findWorking(ws, ReadLens.Everything, id) } returns null
        shouldThrow<ResponseStatusException> { controller.workspace(id, null, null, ExtendedModelMap(), request) }
            .statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `the catalog page stamps the roles and the active theme and fills the flat list`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        every { sets.search(ws, ReadLens.Everything, null, 0, ParameterSetsBrowseModel.PAGE_SIZE) } returns emptyList()
        every { sets.countSearch(ws, ReadLens.Everything, null) } returns 0
        val m = ExtendedModelMap()
        controller.list(m, request, null, null) shouldBe "parameter-sets/list"
        m["activeTheme"] shouldBe "saas"
        m["canEvaluateParameterSets"] shouldBe true
        m["rootId"] shouldBe ParameterSetsBrowseModel.CATALOG_ROOT_ID
    }

    @Test
    fun `the catalog page carries the caller's q - a deep link searches like the box does (#415)`() {
        authenticate(WorkspaceRole.AUTHOR, everything)
        every { sets.search(ws, ReadLens.Everything, "geo", 0, ParameterSetsBrowseModel.PAGE_SIZE) } returns emptyList()
        every { sets.countSearch(ws, ReadLens.Everything, "geo") } returns 0
        val m = ExtendedModelMap()
        controller.list(m, request, "  geo  ", null) shouldBe "parameter-sets/list"
        m["q"] shouldBe "geo"
        m["searching"] shouldBe true
    }
}
