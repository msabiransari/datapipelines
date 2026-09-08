package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Golden path 8 of the release checklist: API-key minting and revocation — on the API screen
 * since 091, where the endpoints and the MCP connection those keys are used with also live.
 *
 * The property that matters most for release confidence is ONCE-NESS: the plaintext key appears
 * in exactly ONE render (`#keyCreated`) and never again — a reload must not show it. Around it,
 * 091 adds the three KINDS: each mints from the same form, each shows a different REACH in the
 * table, and the form's conditional fields have to follow the kind the user picked (a scope
 * posted with an endpoint key is refused by the server, so the screen must not send one).
 */
class ApiKeysGoldenPathBrowserTest : BrowserSuite() {
    private fun loginReadyUser(): LocalUser {
        val user =
            seedLocalUser(
                uniqueEmail("keys-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("keysws-" + generatedPassword("w").take(8).lowercase())
        return user
    }

    /** Opens the modal, fills the form for [kind], submits, and waits for the secret panel. */
    private fun mint(
        kind: String,
        name: String,
        bind: String? = null,
    ): String {
        page.click("text=New key")
        page.check("#createKeyForm input[name=kind][value=$kind]")
        page.fill("#key-name", name)
        if (bind != null) page.check("#createKeyForm input[name=bindings][value=\"$bind\"]")
        val mint =
            page.waitForResponse("**/partials/api-keys") {
                page.click("#createKeyForm button[type=submit]")
            }
        mint.status() shouldBe 200
        page.waitForSelector("#keyCreated")
        // The credential renders in a readonly INPUT (a form value, invisible to innerText) —
        // the full dpk_<id>.<secret> string is the input's value.
        return page.locator("#keyCreated input[type=text]").inputValue()
    }

    private fun rowFor(name: String): Locator = page.locator("tr", Page.LocatorOptions().setHasText(name)).first()

    @Test
    fun `a minted key's secret is shown exactly once`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/api-console")
        page.waitForURL("**/api-console")
        val credential = mint(kind = "user", name = "browser-ci")

        credential shouldContain "dpk_"
        // The SECRET half is what must never appear again — the id half legitimately stays
        // (the create panel's prefix chip and the revoke URLs).
        val secretHalf = credential.substringAfterLast(".")
        rowFor("browser-ci").waitFor()

        // ONCE-NESS: a reload must not show the secret half again.
        page.reload()
        page.waitForSelector("#keys-table")
        rowFor("browser-ci").waitFor()
        page.content() shouldNotContain secretHalf
    }

    @Test
    fun `all three kinds mint from the one form, and each row states its own reach`() {
        startTrace()
        loginReadyUser()
        page.navigate("$baseUrl/api-console")
        page.waitForURL("**/api-console")

        mint(kind = "user", name = "kind-user")
        // The binding picker always offers the root, whether or not this workspace has
        // published anything — binding there authorises the whole tree.
        mint(kind = "endpoint", name = "kind-endpoint", bind = "/")
        // The seeded browser user is an admin, which is what makes the server card visible.
        mint(kind = "server", name = "kind-server")

        page.reload()
        page.waitForSelector("#keys-table")

        // Each kind is TAGGED, and its reach is the thing that decides what it may do: a scope
        // for a user key, a bound path for an endpoint key, the route family for a server key.
        rowFor("kind-user").locator("text=read").first().waitFor()
        rowFor("kind-endpoint").locator("text=/**").first().waitFor()
        rowFor("kind-server").locator("text=promotion routes").first().waitFor()
    }

    @Test
    fun `revoking removes the live affordance and marks the row dead`() {
        startTrace()
        loginReadyUser()
        page.navigate("$baseUrl/api-console")
        page.waitForURL("**/api-console")
        mint(kind = "user", name = "to-revoke")

        // The confirm dialog is a native confirm() — accept it for this run.
        page.onDialog { it.accept() }
        page.waitForResponse("**/partials/api-keys/*") {
            rowFor("to-revoke")
                .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Revoke"))
                .click()
        }
        rowFor("to-revoke").locator("text=revoked").waitFor()
    }

    @Test
    fun `the old settings screen is a link, not a second key surface`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/settings/api-keys")
        page.waitForURL("**/settings/api-keys")

        // 091 reduced it to the link. Two key tables would be two answers to one question.
        page.content() shouldNotContain "id=\"keys-table\""
        page.click("text=Go to API")
        page.waitForURL("**/api-console")
        page.waitForSelector("#keys-table")
    }
}
