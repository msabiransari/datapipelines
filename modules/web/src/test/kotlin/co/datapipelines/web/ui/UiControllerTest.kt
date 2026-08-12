package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap

class UiControllerTest {
    private val themeResolver = mockk<ThemeResolver>()
    private val oidcRegistrations = mockk<OidcRegistrations>()
    private val controller = UiController(themeResolver, oidcRegistrations)

    private fun mockRequest() = mockk<HttpServletRequest>()

    @Test
    fun `login page returns login view and iterates over mock providers`() {
        val providers =
            listOf(
                Provider("google", "Sign in with Google"),
                Provider("github", "Sign in with GitHub"),
            )
        every { oidcRegistrations.providers() } returns providers
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns null

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.login(model, request)

        viewName shouldBe "login"
        @Suppress("UNCHECKED_CAST")
        val modelProviders = model["providers"] as List<Provider>
        modelProviders shouldHaveSize 2
        modelProviders.map { it.registrationId } shouldContain "google"
        modelProviders.map { it.registrationId } shouldContain "github"
        model["activeTheme"] shouldBe "saas"
    }

    @Test
    fun `login page passes error param to model`() {
        every { oidcRegistrations.providers() } returns emptyList()
        every { themeResolver.resolve(any()) } returns "saas"
        val request = mockRequest()
        every { request.getParameter("error") } returns "oidc_error"

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.login(model, request)

        model["error"] shouldBe "oidc_error"
    }

    @Test
    fun `dashboard returns dashboard view with theme`() {
        every { themeResolver.resolve(any()) } returns "dark"
        val request = mockRequest()

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.dashboard(model, request)

        viewName shouldBe "dashboard"
        model["activeTheme"] shouldBe "dark"
    }
}
