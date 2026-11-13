package co.datapipelines.browser

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

    /**
     * Creates a visualization at [name] (version 1, a DRAFT — D55); returns its id and body hash. The document is the
     * bar chart of [ChartBrowserFixtures.workspaceDocument] (#860): three months and an empty case.
     */
    protected fun createVisualization(name: String): Pair<String, String> {
        val created = api("POST", "/api/v1/visualizations", ChartBrowserFixtures.workspaceDocument(name))
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
        // #831: the suite's own login, landing and switch (#761's checked phases), not copies.
        login(session.page, member.email, member.oneTimePassword)
        landOnDashboard(session.page)
        enterWorkspace(session.page, workspace)
        return session
    }

    protected companion object {
        const val EXCERPT = 600
    }
}
