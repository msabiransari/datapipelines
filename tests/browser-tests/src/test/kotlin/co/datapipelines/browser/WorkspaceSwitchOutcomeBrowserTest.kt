package co.datapipelines.browser

import com.microsoft.playwright.Route
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** A failed switch must identify its transport outcome; restoring the real route must enter it. */
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
            route.fulfill(Route.FulfillOptions().setStatus(status).setHeaders(headers).setBody("Switch refused"))
        }
        try {
            val failure = shouldThrow<AssertionError> { enterWorkspace(page, workspace) }
            val message = requireNotNull(failure.message)
            message shouldContain "request=POST $baseUrl/workspace/switch"
            message shouldContain "status=$status"
            message shouldContain "Location=$location"
            message shouldContain "finalUrl="
            println("481 refused switch: $message")
        } finally {
            page.unroute("$baseUrl/workspace/switch")
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
}
