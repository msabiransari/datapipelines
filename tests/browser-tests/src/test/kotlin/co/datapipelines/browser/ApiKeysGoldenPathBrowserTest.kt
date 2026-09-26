package co.datapipelines.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Golden path 8 of the release checklist: API keys — since 179 (D16/D17) the workspace's keys on
 * `/api-keys`. Keys v2 (233, A15) retired the login-minted MCP key and its top-bar chip: every
 * key, `mcp` included, is created on this page, so the chip's leg of the path (prefix, copy,
 * delete-to-rotate) left with it — the `user` kind the form once refused is `mcp` now, offered
 * with the role the creator may give.
 *
 * The property that matters most for release confidence is ONCE-NESS: the plaintext of an
 * admin-minted key appears in exactly ONE render (`#keyCreated`) and never again — a reload
 * must not show it. Around it, the KINDS contract: `endpoint` and `server` mint from the
 * form, and `user` is REFUSED (the login hook mints those) — with the refusal surfacing as
 * the toast, not a dead form.
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

    /** Opens the modal on /api-keys, fills the form for [kind], submits, waits for the secret panel. */
    private fun mint(
        kind: String,
        name: String,
        bind: String? = null,
    ): String {
        page.click("text=New API key")
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
    fun `a minted API key's secret is shown exactly once`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/api-keys")
        page.waitForURL("**/api-keys")
        val credential = mint(kind = "endpoint", name = "browser-ci")

        credential shouldContain "dpk_"
        // The SECRET half is what must never appear again — the id half legitimately stays
        // (the create panel's prefix chip and the row's prefix).
        val secretHalf = credential.substringAfterLast(".")
        rowFor("browser-ci").waitFor()

        // ONCE-NESS: a reload must not show the secret half again.
        page.reload()
        page.waitForSelector("#keys-table")
        rowFor("browser-ci").waitFor()
        page.content() shouldNotContain secretHalf
    }

    @Test
    fun `endpoint and server mint from the one form, and user is not offered at all`() {
        startTrace()
        loginReadyUser()
        page.navigate("$baseUrl/api-keys")
        page.waitForURL("**/api-keys")

        // The binding picker always offers the root, whether or not this workspace has
        // published anything — associating there authorises the whole tree.
        mint(kind = "endpoint", name = "kind-endpoint", bind = "/")
        // The seeded browser user is an admin, which is what makes the server card visible.
        mint(kind = "server", name = "kind-server")

        page.reload()
        page.waitForSelector("#keys-table")

        rowFor("kind-endpoint").locator("text=/**").first().waitFor()
        // A server key has no bindings to show: never an empty-looking cell.
        rowFor("kind-server").waitFor()

        // D16: `user` is not offered at all — the login hook mints those, and a form that
        // offered it would be offering the service's `auth.key_kind_not_mintable` refusal
        // (the refusal of a hand-crafted POST is ApiKeyMintingTest's, over the wire).
        page.click("text=New API key")
        page.locator("#key-modal").waitFor()
        page.locator("#createKeyForm input[name=kind][value=user]").count() shouldBe 0
    }

    @Test
    fun `deleting removes the live affordance and marks the row dead`() {
        startTrace()
        loginReadyUser()
        page.navigate("$baseUrl/api-keys")
        page.waitForURL("**/api-keys")
        mint(kind = "endpoint", name = "to-delete")

        // The confirm dialog is a native confirm() — accept it for this run.
        page.onDialog { it.accept() }
        page.waitForResponse("**/partials/api-keys/*") {
            rowFor("to-delete")
                .getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Delete"))
                .click()
        }
        rowFor("to-delete").locator("text=deleted").waitFor()
    }

    @Test
    fun `the old settings screen points at the Keys page`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/settings/api-keys")
        page.waitForURL("**/settings/api-keys")

        // 091 reduced it to a link; 179 repointed it at the chip; keys v2 at the Keys page. Two
        // key tables would be two answers to one question.
        page.content() shouldNotContain "id=\"keys-table\""
        page.content() shouldContain "Keys page"
        // The old pointer's sentence, not the words "top bar" — the layout's own comments name the bar.
        page.content() shouldNotContain "MCP key is in the top bar"
        page.click("text=Manage API keys")
        page.waitForURL("**/api-keys")
        page.waitForSelector("#keys-table")
    }
}
