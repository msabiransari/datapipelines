package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Golden path 9 of the release checklist: the workspace switcher. Two workspaces, a
 * switch between them, and the ACTIVE badge following the choice — the whole point of
 * the switcher is that every surface below it re-scopes (design §5.1).
 */
class WorkspaceSwitcherBrowserTest : BrowserSuite() {
    @Test
    fun `a fresh workspace appears in the switcher and switching re-scopes`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("wsw-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        val first = "ws-a-" + generatedPassword("w").take(6).lowercase()
        val second = "ws-b-" + generatedPassword("w").take(6).lowercase()
        createWorkspace(first)
        createWorkspace(second)

        // Both are options in the header's switcher; the ACTIVE default is the first
        // membership (workspaceForLogin's fallback), not the latest creation.
        page.navigate("$baseUrl/workspaces")
        page.waitForSelector("form[action*='/workspace/switch'] select")
        val switcher = page.locator("form[action*='/workspace/switch'] select")
        switcher.inputValue() shouldBe first
        // The form's source carries no CSRF field: Spring Security's request-data
        // processor injects the hidden `_csrf` input into every `th:action` form at
        // render time (CookieCsrfTokenRepository is active — SecurityConfig). Assert the
        // RENDERED form, so a future template or config change that drops the token
        // fails here instead of surfacing as a 403 in production.
        page.locator(".app-workspace-form input[name='_csrf']").inputValue().isNotBlank() shouldBe true

        // Switch to the second: the form submits on change and re-renders with the
        // new choice selected.
        switcher.selectOption(second)
        // The switch POST redirects to /dashboard — wait for that navigation to settle
        // BEFORE the selector wait, which otherwise binds to the outgoing document.
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD)
        // <option> elements are never "visible" — the wait must ask for ATTACHED.
        page.waitForSelector(
            "form[action*='/workspace/switch'] select option[selected][value='" + second + "']",
            com.microsoft.playwright.Page
                .WaitForSelectorOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )

        // And back again — the switcher follows, both directions.
        page.locator("form[action*='/workspace/switch'] select").selectOption(first)
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD)
        page.waitForSelector(
            "form[action*='/workspace/switch'] select option[selected][value='" + first + "']",
            com.microsoft.playwright.Page
                .WaitForSelectorOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
        )
    }
}
