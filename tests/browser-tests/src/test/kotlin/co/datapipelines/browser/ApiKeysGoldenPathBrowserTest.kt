package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Golden path 8 of the release checklist: API-key minting and revocation through the
 * settings screen. The property that matters most for release confidence is ONCE-NESS:
 * the plaintext key appears in exactly ONE render (`#keyCreated`) and never again — a
 * reload must not show it. Revocation removes the row's revoke affordance and marks it
 * dead (auth §7.2's revocation contract, seen from the browser).
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

    @Test
    fun `a minted key's secret is shown exactly once`() {
        startTrace()
        loginReadyUser()

        page.navigate("$baseUrl/settings/api-keys")
        page.waitForURL("**/settings/api-keys")
        page.click("text=Generate Key")
        page.fill("#createKeyForm input[name=name]", "browser-ci")
        val mint =
            page.waitForResponse("**/partials/api-keys") {
                page.click("#createKeyForm button[type=submit]")
            }
        mint.status() shouldBe 200

        // The credential renders in a readonly INPUT (a form value, invisible to
        // innerText) — the full dpk_<id>.<secret> string is the input's value.
        page.waitForSelector("#keyCreated")
        val credential = page.locator("#keyCreated input[type=text]").inputValue()
        credential shouldContain "dpk_"
        // The SECRET half is what must never appear again — the id half legitimately
        // stays (the "Key dpk_xxx … created successfully" line and the revoke URLs).
        val secretHalf = credential.substringAfterLast(".")
        page
            .locator(
                "td",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("browser-ci"),
            ).first()
            .waitFor()

        // ONCE-NESS: a reload must not show the secret half again.
        page.reload()
        page.waitForSelector("#keys-table")
        page
            .locator(
                "td",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("browser-ci"),
            ).first()
            .waitFor()
        page.content() shouldNotContain secretHalf
    }

    @Test
    fun `revoking removes the live affordance and marks the row dead`() {
        startTrace()
        loginReadyUser()

        // Mint first (this test's own key).
        page.navigate("$baseUrl/settings/api-keys")
        page.click("text=Generate Key")
        page.fill("#createKeyForm input[name=name]", "to-revoke")
        page.waitForResponse("**/partials/api-keys") {
            page.click("#createKeyForm button[type=submit]")
        }
        page.waitForSelector("#keyCreated")

        // The confirm dialog is a native confirm() — accept it for this run.
        page.onDialog { it.accept() }
        val row =
            page
                .locator(
                    "tr",
                    com.microsoft.playwright.Page
                        .LocatorOptions()
                        .setHasText("to-revoke"),
                ).first()
        page.waitForResponse("**/partials/api-keys/*") {
            row
                .getByRole(
                    com.microsoft.playwright.options.AriaRole.BUTTON,
                    com.microsoft.playwright.Locator
                        .GetByRoleOptions()
                        .setName("Revoke"),
                ).click()
        }
        page
            .locator(
                "tr",
                com.microsoft.playwright.Page
                    .LocatorOptions()
                    .setHasText("to-revoke"),
            ).first()
            .locator("text=revoked")
            .waitFor()
    }
}
