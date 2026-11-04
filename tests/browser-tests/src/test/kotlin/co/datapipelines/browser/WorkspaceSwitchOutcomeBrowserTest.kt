package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * A failed switch must name the phase that did not finish and that phase's transport outcome
 * (#481, #761); restoring the real route must enter it.
 */
class WorkspaceSwitchOutcomeBrowserTest : BrowserSuite() {
    @Test
    fun `an aborted switch names the outcome and the restored switch enters the workspace`() {
        startTrace()
        val workspace = prepareWorkspace()

        var blocked = false
        page.route("$baseUrl/workspace/switch") { route ->
            route.request().method() shouldBe "POST"
            blocked = true
            route.abort()
        }
        try {
            val failure = shouldThrow<AssertionError> { enterWorkspace(page, workspace) }
            blocked shouldBe true
            val message = requireNotNull(failure.message)
            message shouldContain "Workspace switch to '$workspace' did not reach /dashboard"
            message shouldContain "phase=switch-result"
            message shouldNotContain "shell-init"
            message shouldContain "request=POST $baseUrl/workspace/switch"
            message shouldContain "status=none"
            message shouldContain "Location=none"
            message shouldContain "failure=net::ERR_FAILED"
            message shouldContain "finalUrl="
            println("481 aborted switch: $message")
        } finally {
            page.unroute("$baseUrl/workspace/switch")
        }

        // An aborted document POST can leave Chromium's error page; restore the real page
        // before exercising exactly the same forced select with the route unblocked.
        page.navigate("$baseUrl/workspaces")
        enterWorkspace(page, workspace)
        page.url() shouldBe "$baseUrl/dashboard"
        page.locator(".app-ws b").innerText().trim() shouldBe workspace
        println("481 restored switch: finalUrl=${page.url()}, workspace=$workspace")
    }

    @ParameterizedTest
    @CsvSource("403, none", "302, /workspaces?error=switch_refused")
    fun `a refused switch reports its actual status and Location`(
        status: Int,
        location: String,
    ) {
        startTrace()
        val workspace = prepareWorkspace()
        page.route("$baseUrl/workspace/switch") { route ->
            val headers = if (location == "none") emptyMap() else mapOf("Location" to location)
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(status)
                    .setHeaders(headers)
                    .setBody("Switch refused"),
            )
        }
        try {
            val failure = shouldThrow<AssertionError> { enterWorkspace(page, workspace) }
            val message = requireNotNull(failure.message)
            message shouldContain "phase=switch-result"
            message shouldNotContain "shell-init"
            message shouldContain "request=POST $baseUrl/workspace/switch"
            message shouldContain "status=$status"
            message shouldContain "Location=$location"
            val destination = if (location == "none") "$baseUrl/workspace/switch" else "$baseUrl$location"
            message shouldContain "finalUrl=$destination"
            println("481 refused switch: $message")
        } finally {
            page.unroute("$baseUrl/workspace/switch")
        }
    }

    /**
     * #761: a shell whose init never ran — its script aborted on the reload, as CI's
     * ERR_NETWORK_CHANGED did — is an init-phase red with no request, the failed load named, and
     * no claim of a switch request the page never sent.
     */
    @Test
    fun `an uninitialized shell names the init phase and claims no switch request`() {
        startTrace()
        val workspace = prepareWorkspace()
        page.route(SHELL_SCRIPT) { route -> route.abort() }
        try {
            // The planted failure can never initialize, so it gets a short init bound rather than
            // the first-render bound every other caller keeps.
            val failure =
                shouldThrow<AssertionError> { enterWorkspace(page, workspace, initTimeoutMs = PLANTED_INIT_BOUND_MS) }
            val message = requireNotNull(failure.message)
            message shouldContain "Workspace switch to '$workspace' did not reach /dashboard"
            message shouldContain "phase=shell-init (the window.__dpShellInit wait did not finish)"
            message shouldContain "request=none"
            message shouldContain "__dpShellInit=undefined"
            message shouldContain "script $baseUrl/js/shell.js net::ERR_FAILED"
            message shouldNotContain "POST"
            message shouldNotContain "pageState"
            println("761 uninitialized shell: $message")
        } finally {
            page.unroute(SHELL_SCRIPT)
        }
    }

    private fun prepareWorkspace(): String {
        val user =
            seedLocalUser(
                uniqueEmail("switch-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        val workspace = "switch-" + generatedPassword("w").take(8).lowercase()
        createWorkspaceWithoutEntering(workspace)
        return workspace
    }

    private companion object {
        const val SHELL_SCRIPT = "**/js/shell.js"

        /** The planted init failure's bound only: its shell can never initialize (#761 A.3). */
        const val PLANTED_INIT_BOUND_MS = 5_000.0
    }
}
