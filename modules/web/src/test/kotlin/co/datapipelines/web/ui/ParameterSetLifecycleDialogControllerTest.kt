package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetBody
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetReleased
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersion
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import java.time.Instant
import java.util.UUID

/**
 * #876 §F.3 — the parameter set's release dialog, the family's first human release shell, at the unit seam its pipeline
 * twin's controller test established: the GET renders the SHARED dialog over the plan [ReleasePreviews] builds (never
 * for a set with no draft), the POST hands the posted plan to the SAME [ParameterSetService.release] the REST route
 * calls — with a plan, never the bare cascade alias — and answers `HX-Redirect` onto the released version. A POST
 * without the plan's `tree_hash` is `parameter.release.plan_required` (428) and the service never runs. Session-only:
 * an API key is refused `auth.session.required` before anything is read. Both routes declare PARAMETER_SET_RELEASE.
 */
class ParameterSetLifecycleDialogControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val writer = UUID.randomUUID()

    private val sets = mockk<ParameterSetService>()
    private val actors = mockk<ActorNames>()
    private val previews = mockk<ReleasePreviews>()
    private val dialogs = ParameterSetLifecycleDialogModel(sets, actors)
    private val controller = ParameterSetLifecycleDialogController(sets, dialogs, previews)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `both routes declare PARAMETER_SET_RELEASE - the existing row, no new permission`() {
        val get = ParameterSetLifecycleDialogController::class.java.methods.single { it.isAnnotationPresent(GetMapping::class.java) }
        val postRoute = ParameterSetLifecycleDialogController::class.java.methods.single { it.isAnnotationPresent(PostMapping::class.java) }

        get.getAnnotation(RequiredScope::class.java).value shouldBe Permission.PARAMETER_SET_RELEASE
        postRoute.getAnnotation(RequiredScope::class.java).value shouldBe Permission.PARAMETER_SET_RELEASE
    }

    @Test
    fun `the GET renders the shared dialog over the draft's plan, with the route and the verb`() {
        val principal = authenticate()
        every { sets.findWorking(workspaceId, ReadLens.Everything, setId) } returns working()
        every { actors.lookup(listOf(writer)) } returns mapOf(writer to "Muhammad")
        val rd = ReleaseDialogFixtures.plan(ReleaseDialogFixtures.root("parameter_set", "sales/filters", 2))
        every { previews.dialog(ReleaseFamily.PARAMETER_SET, workspaceId, "sales/filters", 2, principal.userId, any()) } returns rd
        val model = ExtendedModelMap()

        val view = controller.releaseDialog(model, setId)

        view shouldBe "partials/release-dialog :: dialog"
        val dlg = model["dlg"] as ParameterSetLifecycleDialogModel.ReleaseDialog
        dlg.version shouldBe 2
        dlg.bodyHash shouldBe "hash-2"
        dlg.updatedBy shouldBe "Muhammad"
        dlg.refusal.shouldBeNull()
        model["rd"] shouldBe rd
        model["releaseAction"] shouldBe "/partials/parameter-sets/$setId/lifecycle/release"
        model["releaseVerb"] shouldBe "parameter-set-release"
    }

    @Test
    fun `a set with no draft opens the dialog with the not_draft refusal and plans nothing`() {
        authenticate()
        every { sets.findWorking(workspaceId, ReadLens.Everything, setId) } returns working(status = PipelineVersionStatus.RELEASED)
        val model = ExtendedModelMap()

        controller.releaseDialog(model, setId)

        (model["dlg"] as ParameterSetLifecycleDialogModel.ReleaseDialog).refusal?.code shouldBe ParameterErrorCodes.VERSION_NOT_DRAFT
        model.containsAttribute("rd") shouldBe false
        verify(exactly = 0) { previews.dialog(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown set is parameter_not_found`() {
        authenticate()
        every { sets.findWorking(workspaceId, ReadLens.Everything, setId) } returns null

        assertThrows<DatapipelinesException> { controller.releaseDialog(ExtendedModelMap(), setId) }.code shouldBe
            ParameterErrorCodes.NOT_FOUND
    }

    @Test
    fun `the POST hands the posted plan to the SAME service release and redirects onto the released version`() {
        val principal = authenticate()
        every {
            sets.release(workspaceId, setId, "hash-2", principal.userId, releasePinnedTemplates = false, plan = PLAN)
        } returns ParameterSetReleased(working(status = PipelineVersionStatus.RELEASED), emptyList())

        val response = controller.release(setId, bodyHash = "hash-2", treeHash = TREE_HASH, includeDependencies = INCLUDE)

        response.headers.getFirst("HX-Redirect") shouldBe "/parameter-sets/$setId?version=2&ok=released"
        verify(exactly = 1) { sets.release(workspaceId, setId, "hash-2", principal.userId, releasePinnedTemplates = false, plan = PLAN) }
    }

    @Test
    fun `a POST without the plan's tree_hash is plan_required and the service never runs`() {
        authenticate()

        val thrown =
            assertThrows<DatapipelinesException> {
                controller.release(setId, bodyHash = "hash-2", treeHash = null, includeDependencies = INCLUDE)
            }

        thrown.code shouldBe ParameterErrorCodes.RELEASE_PLAN_REQUIRED
        verify(exactly = 0) { sets.release(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `over HTTP the form binds - a bare POST is the 428 Shape C refusal, a planned one reaches the service`() {
        val principal = authenticate()
        every {
            sets.release(workspaceId, setId, "hash-2", principal.userId, releasePinnedTemplates = false, plan = PLAN)
        } returns ParameterSetReleased(working(status = PipelineVersionStatus.RELEASED), emptyList())
        val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(UiExceptionHandler()).build()

        val bare =
            mvc
                .perform(
                    post("/partials/parameter-sets/$setId/lifecycle/release")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("bodyHash", "hash-2")
                        .header("HX-Request", "true"),
                ).andExpect(status().`is`(428))
                .andReturn()
        bare.response.contentAsString shouldContain ParameterErrorCodes.RELEASE_PLAN_REQUIRED

        mvc
            .perform(
                post("/partials/parameter-sets/$setId/lifecycle/release")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("bodyHash", "hash-2")
                    .param("tree_hash", TREE_HASH)
                    .param("include_dependencies", INCLUDE[0])
                    .param("include_dependencies", INCLUDE[1])
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
        verify(exactly = 1) { sets.release(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an API key is refused before anything is read - the release is a human verb`() {
        authenticate(method = AuthMethod.API_KEY)

        assertThrows<DatapipelinesException> { controller.releaseDialog(ExtendedModelMap(), setId) }.code shouldBe
            "auth.session.required"
        assertThrows<DatapipelinesException> {
            controller.release(setId, bodyHash = "hash-2", treeHash = TREE_HASH, includeDependencies = INCLUDE)
        }.code shouldBe "auth.session.required"
        verify(exactly = 0) { sets.findWorking(any(), any(), any()) }
        verify(exactly = 0) { sets.release(any(), any(), any(), any(), any(), any()) }
    }

    // ------------------------------------------------------------------ fixtures

    private fun authenticate(method: AuthMethod = AuthMethod.OIDC): AuthenticatedPrincipal {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@d.p",
                "User",
                method,
                workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.AUTHOR),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        return principal
    }

    private fun working(status: PipelineVersionStatus = PipelineVersionStatus.DRAFT): ParameterSetVersion {
        val at = Instant.parse("2026-11-12T00:00:00Z")
        return ParameterSetVersion(
            ParameterSetRecord(setId, workspaceId, "sales/filters", "Filters", "", 1, at, at, writer),
            ParameterSetVersionDetail(setId, 2, status, "hash-2", at, writer),
            ParameterSetJson.mapper.treeToValue(
                ParameterSetJson.mapper.readTree("""{ "display_name": "Filters", "parameters": [] }"""),
                ParameterSetBody::class.java,
            ),
        )
    }

    private companion object {
        const val TREE_HASH = "v1:0123456789abcdef"
        val INCLUDE = listOf("n1", "n2")
        val PLAN = ReleasePlanRequest(TREE_HASH, INCLUDE)
    }
}
