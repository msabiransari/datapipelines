package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.util.Base64

/**
 * The promotion page's parameter-set rows in a REAL browser (#313).
 *
 * The page is driven as a person drives it: a seeded local workspace admin signs in, opens
 * `/promotion`, and the plan must offer the workspace's released parameter sets in their own
 * table — `name="parameter_set"` checkboxes inside the same form as the pipelines' — with the
 * read-only arm for a non-promoter carrying the same rows without any input.
 *
 * The higher environment is a STUB on a loopback port: the browser harness boots ONE app per
 * spec class ([BrowserSuite]'s one-context convention — it cannot boot two deployments), so
 * the two-deployment E2E's shape is not available here. The stub answers the inventory with
 * empty lists, which makes every released object "absent on target" — the visible-candidate
 * state the screens photograph. No push is attempted against the stub: the promotion act
 * itself is `PromotionTwoDeploymentE2eTest`'s, and this suite's subject is the PAGE.
 */
class PromotionSetsBrowserTest : BrowserSuite() {
    @Test
    fun `the page offers parameter sets to a promoter and reads them out to a non-promoter, light and dark`() {
        startTrace()
        val admin = seedLocalUser(uniqueEmail("promo-sets-admin"), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")

        val setSuffix = suffix()
        createAndReleaseSet(sessionAuth(), setSuffix)

        // The promoter sees the sets table inside the form, with the same Send column.
        page.navigate("$baseUrl/promotion")
        page.waitForSelector("h2:has-text('Parameter sets')")
        val box = page.locator("form input[name=parameter_set][value=\"$SET_PREFIX/$setSuffix\"]")
        box.count() shouldBe 1
        page.locator("form table[data-promotion-sets=send] th:has-text('Send')").count() shouldBe 1

        // One form, one submit — the set checkboxes ride the same form as the pipelines'.
        page.locator("form button[data-verb=promote]").count() shouldBe 1

        // Light and dark, per the screens contract.
        ensureTheme("light")
        screenshot("promotion-parameter-sets-light")
        ensureTheme("dark")
        screenshot("promotion-parameter-sets-dark")
    }

    @Test
    fun `a non-promoter reads the same set rows with no input anywhere`() {
        val author =
            seedLocalUser(uniqueEmail("promo-sets-author"), generatedPassword("pw"), mustChange = false, isAdmin = false, role = "author")
        login(author.email, author.oneTimePassword)
        page.waitForURL("**/dashboard")

        val setSuffix = suffix()
        createAndReleaseSet(sessionAuth(), setSuffix)

        page.navigate("$baseUrl/promotion")
        page.waitForSelector("table[data-promotion-plan-sets=read-only]")
        page.content() shouldContain "$SET_PREFIX/$setSuffix"
        // The reader arm: no form, no checkbox — the same content, no control the server would refuse.
        val main = page.locator("main").innerHTML()
        main.contains("<form") shouldBe false
        main.contains("name=\"parameter_set\"") shouldBe false
    }

    // ------------------------------------------------------------------ fixture

    private fun suffix(): String = generatedPassword("s").take(8).lowercase()

    private fun createAndReleaseSet(
        auth: RestAuth,
        setSuffix: String,
    ) {
        val created =
            postForBody(
                auth,
                "/api/v1/parameter-sets",
                """
                {"name": "$SET_PREFIX/$setSuffix", "display_name": "Region filters", "description": "the promotion page's set rows (#313)",
                 "parameters": [
                   {"name": "region", "label": "Region", "type": "STRING", "kind": "SELECT",
                    "cardinality": "SINGLE", "required": true,
                    "source": {"constants": [{"value": "EMEA", "display_value": "EMEA", "is_default": true}]}}
                 ]}
                """.trimIndent(),
                expected = 201,
            )
        val id = created.substringAfter("\"id\":\"").substringBefore('"')
        val hash = created.substringAfter("\"body_hash\":\"").substringBefore('"')
        post(
            auth,
            "/api/v1/parameter-sets/$id/release",
            "",
            headers = mapOf("If-Match" to hash),
            expected = 200,
        )
    }

    private fun sessionAuth(): RestAuth {
        val cookies = page.context().cookies().associate { it.name to it.value }
        val session = checkNotNull(cookies["dp_session"]) { "no dp_session cookie in the browser context" }
        val csrf = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in the browser context" }
        return RestAuth("dp_session=$session; dp_csrf=$csrf", csrf, "default")
    }

    /** A session's REST credentials: the cookie pair, the CSRF double-submit value, the workspace. */
    private class RestAuth(
        val cookie: String,
        val csrf: String,
        val workspace: String,
    )

    private fun screenshot(name: String) {
        val dir = Paths.get("build", "reports", "browser-screenshots").also { it.toFile().mkdirs() }
        page.screenshot(Page.ScreenshotOptions().setPath(dir.resolve("$name.png")))
    }

    private fun post(
        auth: RestAuth,
        path: String,
        body: String,
        expected: Int,
        headers: Map<String, String> = emptyMap(),
    ) {
        postForBody(auth, path, body, expected, headers)
    }

    private fun postForBody(
        auth: RestAuth,
        path: String,
        body: String,
        expected: Int,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("Cookie", auth.cookie)
                .header("DP-CSRF-Token", auth.csrf)
                .header("DP-Workspace", auth.workspace)
                .header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != expected) {
            throw AssertionError("POST $path failed (status=${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }

    private companion object {
        private const val SET_PREFIX = "browser"

        /**
         * The stub higher environment: the inventory holds NOTHING, so every released object
         * here is a promotable candidate ("absent" on target). Answered per request; the plan
         * cache is per workspace with a short TTL — the walk reads the page, never promotes.
         */
        private val stub: HttpServer =
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"default",""" +
                                """"pipelines":[],"templates":[],"datasources":[],"parameter_sets":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }

        @DynamicPropertySource
        @JvmStatic
        fun stubTarget(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "browser-promo-stub-key" }
        }
    }
}
