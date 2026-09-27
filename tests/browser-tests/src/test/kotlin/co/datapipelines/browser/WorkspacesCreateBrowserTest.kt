package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * #170 — a created workspace appears WITHOUT a manual refresh, in a real browser.
 *
 * The owner (super admin, live site, 2026-09-18): after Create on /workspaces the new
 * workspace was not shown until a manual refresh. Reproduced on the base: the create
 * submit rode the shell's hx-boost, and a boosted swap replaces only #app-main — the
 * table (inside the swap) came back fresh, but the rail's switcher (outside it, filled
 * by UiWorkspaceAdvice's `workspaceOptions` on every FULL render) kept the options
 * rendered before the workspace existed. `BrowserSuite.enterWorkspace` has worked around
 * exactly this with a `page.reload()` since the fixture was written; its comment names
 * the mechanism. The fix is the house idiom for a page-route mutation whose success
 * changes the shell (097 §2.1, the switch form's own precedent): `hx-boost="false"` on
 * the create form, so the whole shell re-renders with the new workspace in both places.
 *
 * The guard is the issue's: create, then assert the row is in BOTH the table and the
 * switcher, with no reload the test performs. (The submit's own full navigation IS the
 * fix; the point is that the user does nothing else.) The creator here is a super admin,
 * whose switcher is `workspaceRepository.findAll()` — the owner's exact role.
 *
 * The data was never stale server-side: `WorkspaceService.create` invalidates the auth
 * cache, and a super admin's `listOwn` is uncached SQL — the defect was purely which part
 * of the shell the boosted response could reach.
 *
 * #256 extends the same assertion to the three lifecycle verbs on the same page:
 * deactivate, reactivate and delete change the SHELL too (097 §2.1 — the switcher's
 * options come from `UiWorkspaceAdvice.workspaceOptions`, filled on every FULL render),
 * so each verb navigates in full and its before/after option list is asserted with no
 * reload the test performs: deactivate → absent, reactivate → present, delete → absent.
 */
class WorkspacesCreateBrowserTest : BrowserSuite() {
    @Test
    fun `creating a workspace puts it in the table AND the switcher without a manual reload`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("wsnew-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        val name = "wsnew" + generatedPassword("w").take(8).lowercase()
        page.navigate("$baseUrl/workspaces")
        page.waitForURL("**/workspaces")
        // The switcher does not offer it before the create (the fixture is honest about
        // what changed).
        optionAttached(name).shouldBeFalse()

        page.fill("form[action*='/workspaces/create'] input[name=name]", name)
        page.click("form[action*='/workspaces/create'] button[type=submit]")

        // The table carries the row (this much the boosted swap already did on the base).
        page.locator("td", Page.LocatorOptions().setHasText(name)).first().waitFor()
        // THE DEFECT: the switcher offers it too — no reload, no manual anything.
        page
            .locator("#workspace-switcher option[value='$name']")
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED))
        optionAttached(name).shouldBeTrue()
    }

    /** The switcher's `<option>` for [name]: ATTACHED, not visible — the select is the
     *  switcher card's invisible overlay (the label is the hit target), so an option is
     *  never "visible" in Playwright's terms. */
    private fun optionAttached(name: String): Boolean =
        page
            .locator("#workspace-switcher option[value='$name']")
            .count() > 0

    /** A lifecycle verb's button on [name]'s row (the template's stable `data-verb` hooks). */
    private fun rowButton(
        name: String,
        verb: String,
    ): Locator = page.locator("tr", Page.LocatorOptions().setHasText(name)).first().locator("[data-verb='$verb']")

    /**
     * #256 — the three lifecycle verbs keep the switcher honest: deactivate → the workspace
     * is ABSENT from the switcher without a reload, reactivate → present again, delete →
     * absent. Red on the base: the verb rode the shell's hx-boost, the swap replaced only
     * #app-main, and the rail kept every option rendered before the mutation.
     */
    @Test
    fun `deactivate, reactivate and delete update the switcher without a manual reload`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("wslife-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")

        // A workspace of its own, never entered (createWorkspaceWithoutEntering's shape):
        // the fixture's active workspace stays `default` throughout.
        val name = "wslife" + generatedPassword("w").take(8).lowercase()
        page.navigate("$baseUrl/workspaces")
        page.waitForURL("**/workspaces")
        page.fill("form[action*='/workspaces/create'] input[name=name]", name)
        page.click("form[action*='/workspaces/create'] button[type=submit]")
        page.locator("td", Page.LocatorOptions().setHasText(name)).first().waitFor()
        optionAttached(name).shouldBeTrue()

        // DEACTIVATE → the option leaves the switcher (the server filters inactive workspaces).
        rowButton(name, "workspace-deactivate").click()
        rowButton(name, "workspace-reactivate").waitFor() // the table came back fresh already
        optionAttached(name).shouldBeFalse()

        // REACTIVATE → the option returns.
        rowButton(name, "workspace-reactivate").click()
        rowButton(name, "workspace-deactivate").waitFor()
        optionAttached(name).shouldBeTrue()

        // DELETE → the option leaves again, with the row.
        rowButton(name, "workspace-delete").click()
        page
            .locator("tr", Page.LocatorOptions().setHasText(name))
            .first()
            .waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED))
        optionAttached(name).shouldBeFalse()
    }
}
