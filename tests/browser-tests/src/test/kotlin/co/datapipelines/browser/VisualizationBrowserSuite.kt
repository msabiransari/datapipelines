package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/**
 * #399 — the Visualizations pages' shared fixtures: visualizations are created through the REAL REST route (the
 * page's own CSRF pair), so every version the pages read went through the service's strict reader; members of the
 * test's workspace are granted by one SQL row before their first login (the dashboard workspace suite's shape).
 */
abstract class VisualizationBrowserSuite : DashboardBrowserSuite() {
    /** One REST call from the signed-in page (cookie + the CSRF double-submit), the JSON body back; non-2xx fails. */
    protected fun api(
        method: String,
        path: String,
        body: String?,
        ifMatch: String? = null,
    ): Map<*, *> {
        @Suppress("UNCHECKED_CAST")
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const init = { method: args.method, credentials: 'same-origin', headers };
                  if (args.body !== null) init.body = args.body;
                  const response = await fetch(args.path, init);
                  const text = await response.text();
                  let json = null;
                  try { json = text ? JSON.parse(text) : null; } catch (e) { json = null; }
                  return { status: response.status, text: text, json: json };
                }""",
                mapOf("method" to method, "path" to path, "body" to body, "ifMatch" to ifMatch),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        val text = result["text"] as String
        withClue("$method $path -> $status ${text.take(EXCERPT)}") { (status in 200..299) shouldBe true }
        return result["json"] as Map<*, *>
    }

    /** A nested read of a JSON object the page parsed. */
    protected fun Map<*, *>.at(vararg keys: String): Any? = keys.fold<String, Any?>(this) { node, key -> (node as Map<*, *>)[key] }

    /** Creates a visualization at [name] (version 1, a DRAFT — D55); returns its id and body hash. */
    protected fun createVisualization(
        name: String,
        trace3d: Boolean = false,
    ): Pair<String, String> {
        val created = api("POST", "/api/v1/visualizations", document(name, trace3d))
        return (created.at("data", "id") as String) to (created.at("data", "body_hash") as String)
    }

    /** The ACTIVE workspace's name — `ready()`'s root is an artifact-NAME prefix, never the workspace's. */
    protected fun activeWorkspaceName(): String =
        page.evaluate("() => document.getElementById('workspace-switcher').selectedOptions[0].text") as String

    /** A member of the active workspace holding [role], granted before their first login; returns a signed-in page. */
    protected fun memberSession(
        slug: String,
        role: String,
    ): Session {
        val workspace = activeWorkspaceName()
        val email = uniqueEmail("$slug-" + generatedPassword("u").take(8))
        val member = seedLocalUser(email, generatedPassword("pw"), mustChange = false, isAdmin = false, role = role)
        sql(
            "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                "SELECT w.id, u.id, '$role' FROM workspaces w, users u WHERE w.name = '$workspace' AND u.email = '$email'",
        )
        val session = newSession()
        val memberPage = session.page
        memberPage.navigate("$baseUrl/login")
        memberPage.fill("#login-email", member.email)
        memberPage.fill("#login-password", member.oneTimePassword)
        memberPage.click("form button[type=submit]")
        memberPage.waitForURL("**/dashboard")
        memberPage.waitForSelector("#workspace-switcher")
        memberPage.selectOption("#workspace-switcher", arrayOf(workspace), Page.SelectOptionOptions().setForce(true))
        memberPage.waitForFunction("() => document.querySelector('.app-ws b')?.textContent?.trim() === '$workspace'")
        return session
    }

    /**
     * True when the page scrolls sideways — a page must never, at any width. `<main>` is the shell's scroll container
     * (`overflow-x: auto`), so a too-wide card scrolls MAIN while the document stays put: both are read.
     */
    protected fun documentOverflowsX(target: Page = page): Boolean =
        target.evaluate(
            "() => { const m = document.querySelector('.app-main');" +
                " return document.documentElement.scrollWidth > window.innerWidth || document.body.scrollWidth > window.innerWidth" +
                " || (m !== null && m.scrollWidth > m.clientWidth); }",
        ) as Boolean

    /** Two test cases over one input: a three-month two-series case and an empty one (`no_data`). */
    private fun document(
        name: String,
        trace3d: Boolean,
    ): String {
        val config =
            if (trace3d) {
                """{"data": [{"type": "surface", "x": [], "y": [], "z": []}], "layout": {}}"""
            } else {
                """{"data": [{"type": "bar", "x": [], "y": []}, {"type": "scatter", "mode": "lines", "x": [], "y": []}],
                    "layout": {"title": {"text": "Units against target"}}}"""
            }
        val bindings =
            if (trace3d) {
                """{"data[0].x": "units", "data[0].y": "target", "data[0].z": "units"}"""
            } else {
                """{"data[0].x": "month", "data[0].y": "units", "data[1].x": "month", "data[1].y": "target"}"""
            }
        val cases =
            if (trace3d) {
                """{"name": "hill", "fixtures": {"sales": [{"month": "Jan", "units": 3, "target": 4},
                                                          {"month": "Feb", "units": 5, "target": 2}]},
                    "assertions": [{"kind": "rendered"}]}"""
            } else {
                """{"name": "two series", "fixtures": {"sales": [{"month": "Jan", "units": 3, "target": 4},
                                                                {"month": "Feb", "units": 5, "target": 4},
                                                                {"month": "Mar", "units": 2, "target": 4}]},
                    "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 2}]},
                   {"name": "empty", "fixtures": {"sales": []}, "assertions": [{"kind": "no_data"}]}"""
            }
        return """
            {"name": "$name", "display_name": "Units against target", "description": "the #399 browser fixture",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"sales": {"columns": [{"name": "month", "type": "STRING", "nullable": false},
                                              {"name": "units", "type": "INTEGER", "nullable": false},
                                              {"name": "target", "type": "INTEGER", "nullable": false}]}},
             "config": $config,
             "bindings": $bindings,
             "presentation": {"title": "Units against target"},
             "tests": {"cases": [$cases]}}
            """.trimIndent()
    }

    protected companion object {
        const val EXCERPT = 600
    }
}
