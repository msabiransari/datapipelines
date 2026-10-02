package co.datapipelines.browser

import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * #374 — the shared fixtures of the Parameter Sets workspace's browser classes: the REST calls
 * a set's versions are made through (the real routes, never a SQL shortcut for what a route
 * can do), the second session a promoter logs in on, and the ISOLATED promotion target that lets
 * the production lens ADMIT released sets (the [PipelineWorkspacePromoterAdmittedBrowserTest]
 * precedent — without a reachable target the lens admits nothing, roles design §3.1).
 *
 * The target is process-wide and its inventory names no parameter set, so every set authored in
 * a test is "newer than the target" and a promoter is admitted its RELEASED versions only.
 */
abstract class ParameterSetBrowserSuite : DashboardBrowserSuite() {
    /** One REST call in-page, with the session's CSRF pair (every non-GET carries it). */
    protected fun api(
        method: String,
        path: String,
        body: String? = null,
        ifMatch: String? = null,
        on: com.microsoft.playwright.Page = page,
    ): Pair<Int, String> {
        val answer =
            on.evaluate(
                """async ([method, path, body, ifMatch]) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json' };
                  if (ifMatch) headers['If-Match'] = ifMatch;
                  if (method !== 'GET') headers['DP-CSRF-Token'] = csrf ? decodeURIComponent(csrf[1]) : '';
                  const res = await fetch(path, { method, credentials: 'same-origin', headers, body });
                  let text = '';
                  try { text = await res.text(); } catch (e) {}
                  return [res.status, text];
                }""",
                arrayOf(method, path, body, ifMatch),
            ) as List<*>
        return (answer[0] as Number).toInt() to (answer[1] as String)
    }

    /** A call that MUST succeed - the status is the assertion, the body the clue. */
    protected fun must(
        method: String,
        path: String,
        body: String? = null,
        ifMatch: String? = null,
    ): String {
        val (status, text) = api(method, path, body, ifMatch)
        check(status == 200 || status == 201) { "$method $path -> $status: ${text.take(400)}" }
        return text
    }

    protected fun hashOf(text: String): String = Regex(""""body_hash"\s*:\s*"([^"]+)"""").find(text)!!.groupValues[1]

    protected fun idOf(text: String): String =
        Regex(""""id"\s*:\s*"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"""").find(text)!!.groupValues[1]

    /** A set body in the wire's shape: [name] and the raw JSON array of [parameters]. */
    protected fun setBody(
        name: String,
        parameters: String,
        displayName: String = "Fixture set",
    ): String = """{"name":"$name","display_name":"$displayName","description":"#374 browser fixture","parameters":$parameters}"""

    /** A constants SELECT parameter: [values] are `value` strings, the first is the default. */
    protected fun constants(
        name: String,
        values: List<String>,
    ): String =
        """{"name":"$name","label":"${name.replaceFirstChar { it.uppercase() }}","type":"STRING","kind":"SELECT",""" +
            """"cardinality":"SINGLE","required":true,"source":{"constants":[""" +
            values.mapIndexed { i, v -> """{"value":"$v","display_value":"${v.uppercase()}","is_default":${i == 0}}""" }.joinToString(",") +
            """]},"presentation":{"control":"dropdown"}}"""

    /** Creates a set (a draft v1) and returns its id and body hash. */
    protected fun createSet(body: String): Pair<String, String> {
        val created = must("POST", "/api/v1/parameter-sets", body)
        return idOf(created) to hashOf(created)
    }

    /** Releases the draft [hash] names - with its pinned templates when [withTemplates]; returns the new body hash. */
    protected fun release(
        id: String,
        hash: String,
        withTemplates: Boolean = false,
    ): String {
        val query = if (withTemplates) "?release_pinned_templates=true" else ""
        return hashOf(must("POST", "/api/v1/parameter-sets/$id/release$query", ifMatch = hash))
    }

    /** A new draft on top of the current head; returns the new body hash. */
    protected fun newDraft(
        id: String,
        hash: String,
        body: String,
    ): String = hashOf(must("PUT", "/api/v1/parameter-sets/$id", body, ifMatch = hash))

    /** Adds [email] to [workspaceName] as [role] - the membership exists BEFORE the user's first login. */
    protected fun seedMembership(
        workspaceName: String,
        email: String,
        role: String,
    ) {
        java.sql.DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role)
                        SELECT w.id, u.id, '$role'
                          FROM workspaces w, users u
                         WHERE w.name = '$workspaceName' AND u.email = '$email'
                        ON CONFLICT (workspace_id, user_id) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }
    }

    /** The active workspace's name, read off the switcher. */
    protected fun activeWorkspace(): String =
        page.evaluate(
            "() => { const s = document.getElementById('workspace-switcher'); return s ? s.selectedOptions[0].text : null; }",
        ) as String

    /** A second session logged in as a promoter of [workspaceName] (granted before first login, switched in after). */
    protected fun openPromoterSession(workspaceName: String): Session {
        val user =
            seedLocalUser(
                uniqueEmail("p374-pro" + generatedPassword("p").take(6)),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "promoter",
            )
        seedMembership(workspaceName, user.email, role = "promoter")
        val promoter = newSession()
        promoter.page.navigate("$baseUrl/login")
        promoter.page.fill("#login-email", user.email)
        promoter.page.fill("#login-password", user.oneTimePassword)
        promoter.page.click("form button[type=submit]")
        promoter.page.waitForURL("**/dashboard")
        promoter.page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              await fetch('/workspace/switch?name=' + encodeURIComponent(name), {
                method: 'POST', credentials: 'same-origin',
                headers: { 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
              });
            }""",
            workspaceName,
        )
        return promoter
    }

    /** Saves the PNG of the current page under build/reports (the handback's screens). */
    protected fun shot(name: String) {
        page.waitForTimeout(300.0)
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setFullPage(true)
                .setPath(
                    java.nio.file.Paths
                        .get("build", "reports", "$name.png"),
                ),
        )
    }

    companion object {
        private const val SERVER_KEY = "p374-browser-server-key"

        /** The stub's probe counter - the fixture's own non-vacuity witness. */
        val probeHits = AtomicInteger(0)

        private val target: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/api/v1/promotion/inventory") { exchange ->
                    val presented = exchange.requestHeaders.getFirst("DP-Promotion-Key")
                    if (presented != SERVER_KEY) {
                        val refused =
                            """{"schema_version":1,"correlation_id":"00000000-0000-0000-0000-000000000000",""" +
                                """"error":{"code":"promotion.key_invalid","message":"bad key"}}"""
                        val bytes = refused.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(401, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                        return@createContext
                    }
                    probeHits.incrementAndGet()
                    val workspace =
                        exchange.requestURI.query
                            ?.split('&')
                            ?.firstOrNull { it.startsWith("workspace=") }
                            ?.substringAfter("workspace=")
                            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                            ?: ""
                    val body =
                        """{"schema_version":1,"correlation_id":"00000000-0000-0000-0000-000000000000","data":{""" +
                            """"deployment":"p374-lower","authoring_enabled":true,"workspace":"$workspace",""" +
                            """"pipelines":[],"templates":[],"datasources":[],"parameter_sets":[]}}"""
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun promotionTarget(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${target.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { SERVER_KEY }
        }
    }
}
