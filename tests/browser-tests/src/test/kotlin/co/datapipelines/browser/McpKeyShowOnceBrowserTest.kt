package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #213 (D16, amended 2026-09-23) — the login-minted MCP key is copyable ONCE: the first
 * Copy destroys the server's sealed copy in the same act that serves it, so the chip must
 * stop offering Copy WITHOUT a reload (the copy handler swaps in the re-rendered chip), a
 * reload must not bring the button back (the key is hash-only now — this is server state,
 * not a page cache), and rotation — delete, sign in again — is the only way back to a
 * copyable key.
 *
 * Non-vacuity: the assertion that matters is the CLIPBOARD'S VALUE (a `dpk_…` full key),
 * not that a click happened — a handler that toasts without copying passes every
 * button-count check.
 */
class McpKeyShowOnceBrowserTest : BrowserSuite() {
    @Test
    fun `the MCP key copies once - the chip loses Copy without a reload, and rotation is the way back`() {
        startTrace()
        val user =
            seedLocalUser(
                uniqueEmail("once-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("oncews-" + generatedPassword("w").take(8).lowercase())
        page.context().grantPermissions(listOf("clipboard-read", "clipboard-write"))

        // The sign-in (and the switch inside createWorkspace) minted a copyable key.
        page.waitForSelector("#app-mcpkey [data-mcp-copy]")

        // The click's fetch serves the key AND destroys its copyable copy; the handler then
        // swaps in the re-rendered chip (GET /partials/mcp-key/chip). Wait for that response
        // so the button-count below reads the swapped DOM, never the pre-click one.
        page.waitForResponse("**/partials/mcp-key/chip") {
            page.locator("[data-mcp-copy]").click()
        }
        val copied = page.evaluate("navigator.clipboard.readText()") as String
        copied shouldStartWith "dpk_"
        copied.length shouldBe 65

        // No reload: the swapped chip has no Copy, and says why.
        page.locator("#app-mcpkey [data-mcp-copy]").count() shouldBe 0
        page.content() shouldContain "Copied already"

        // A reload changes nothing — the clear is server-side, in the same statement that
        // served the key.
        page.reload()
        page.waitForSelector("#app-mcpkey")
        page.locator("#app-mcpkey [data-mcp-copy]").count() shouldBe 0

        // Rotation is the way back: delete-to-rotate, a REAL sign-in (the mint is the login
        // hook's), and the fresh key offers Copy again.
        page.onDialog { it.accept() }
        page.waitForResponse("**/partials/mcp-key") {
            page.locator("[data-verb=mcp-key-delete]").click()
        }
        page.waitForSelector("text=sign in")
        page.context().clearCookies()
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        page.waitForSelector("#app-mcpkey [data-mcp-copy]")
    }
}
