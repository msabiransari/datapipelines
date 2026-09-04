package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceSessionRequiredException
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.pipelines.PromotionService
import co.datapipelines.web.pipelines.PromotionTargetClient
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * [PromotionUiController] — the screen's three plan states and the action's gate, beside
 * PromotionTwoDeploymentE2eTest's end-to-end coverage. Pins: no-target renders as a
 * CONFIG state, a target refusal renders its code in place (the operator's next step
 * depends on WHICH refusal), nothing-selected is a flash not a no-op, an API-key principal
 * cannot drive the promote post (the session-only rule), and a refusal's code's last
 * segment becomes the flash key.
 */
class PromotionUiControllerTest {
    private val promotionService = mockk<PromotionService>()
    private val client = mockk<PromotionTargetClient>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = PromotionUiController(promotionService, client, themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(method: AuthMethod = AuthMethod.OIDC) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    setOf(Scope.ADMIN),
                    method,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    @Test
    fun `no target configured is a config state, not an error`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { client.hasTarget } returns false
        every { client.targetBaseUrl } returns ""

        val model = ExtendedModelMap()
        controller.screen(model, MockHttpServletRequest()) shouldBe "promotion/index"

        model["hasTarget"] shouldBe false
        verify(exactly = 0) { promotionService.plan(any(), any()) }
    }

    @Test
    fun `a reachable target renders the plan`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { client.hasTarget } returns true
        every { client.targetBaseUrl } returns "https://prod.example"
        val plan = mockk<co.datapipelines.web.pipelines.PromotionService.Plan>()
        every { promotionService.plan(workspaceId, "acme") } returns plan

        val model = ExtendedModelMap()
        controller.screen(model, MockHttpServletRequest())

        model["plan"] shouldBe plan
        model["targetBaseUrl"] shouldBe "https://prod.example"
    }

    @Test
    fun `a target refusal renders its code in place - which refusal matters`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { client.hasTarget } returns true
        every { client.targetBaseUrl } returns "https://prod.example"
        every { promotionService.plan(any(), any()) } throws
            DatapipelinesException("promotion.target_unreachable", "connection refused")

        val model = ExtendedModelMap()
        controller.screen(model, MockHttpServletRequest()) shouldBe "promotion/index"

        model["planError"] shouldBe "promotion.target_unreachable"
        model["planErrorMessage"] shouldBe "connection refused"
    }

    @Test
    fun `nothing selected is the nothing_selected flash`() {
        authenticate()
        controller.promote(names = listOf("  ", "")) shouldBe "redirect:/promotion?error=nothing_selected"
    }

    @Test
    fun `an api-key principal cannot drive the promote post`() {
        authenticate(method = AuthMethod.API_KEY)

        controller.promote(names = listOf("p1")) shouldBe "redirect:/promotion?error=session_required"

        verify(exactly = 0) { promotionService.promote(any(), any(), any()) }
    }

    @Test
    fun `a session principal promotes the trimmed selection and flashes the counts`() {
        authenticate()
        val applied =
            co.datapipelines.web.pipelines.PromotionWire.Applied(
                workspace = "acme",
                sourceEnv = "staging",
                templates = 2,
                pipelines = 3,
            )
        every { promotionService.promote(workspaceId, "acme", listOf("p1", "p2")) } returns applied

        controller.promote(names = listOf(" p1 ", "p2")) shouldBe
            "redirect:/promotion?ok=promoted&pipelines=3&templates=2"
    }

    @Test
    fun `a promote refusal's code last segment becomes the flash key`() {
        authenticate()
        every { promotionService.promote(any(), any(), any()) } throws
            DatapipelinesException("promotion.receiver_conflict", "version mismatch")

        controller.promote(names = listOf("p1")) shouldBe "redirect:/promotion?error=receiver_conflict"
    }

    @Test
    fun `the session-required refusal is caught and flashed, not a 500`() {
        authenticate(method = AuthMethod.API_KEY)

        // The exact exception the gate throws, caught by the action's own handler.
        val view = controller.promote(names = listOf("p1"))
        view shouldBe "redirect:/promotion?error=session_required"
    }
}
