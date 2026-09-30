package co.datapipelines.browser

import java.sql.DriverManager

/**
 * #10 L3a — the dashboard CLIENT runtime's browser suites share this: the real application, the
 * runtime's vendored files served by it, and a plain-JavaScript HOST page the suite itself fulfils
 * through Playwright's route interception (the `ShellBusyBrowserTest` mould) under the app's LIVE
 * `Content-Security-Policy` — copied off a real response at runtime, never imported
 * (`browser-tests` may not read `modules/auth`; build.gradle.kts:7), so the measurement cannot pass
 * on a policy the app no longer sends.
 *
 * The host page is where the conformance cases run: `DatapipelinesDashboard.init` with the
 * first-party composite adapter, the three vendored renderers, and the bundle the request names.
 * Everything else — the vendor scripts, the stylesheets, the runtime routes, the parameter engine,
 * the refresh stream — is the REAL application over the browser's own session; nothing here
 * bypasses the product's auth.
 *
 * Fixtures follow the L2 E2E's seeding shape (`DashboardRuntimeE2eTest`): the pipelines, sets and
 * templates are created through the API, then stamped RELEASED by SQL — nothing is releasable
 * until L4 (`ReleaseEvidence.NOT_INSTALLED`), so SQL seeding is the only way a runtime read can
 * serve a board. Each test seeds its OWN user and workspace (the suite's order-independence rule).
 */
abstract class DashboardBrowserSuite : BrowserSuite() {
    private lateinit var seededEmail: String

    protected fun suffix(): String = generatedPassword("d").takeLast(8).lowercase()

    /** A fresh admin in a workspace of their own; returns the root folder this test's names live under. */
    protected fun ready(slug: String): String {
        val user = seedLocalUser(uniqueEmail("$slug-" + suffix()), generatedPassword("pw"), mustChange = false)
        seededEmail = user.email
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(slug + suffix())
        return slug + suffix()
    }

    // ------------------------------------------------------------------ the host page

    /**
     * The glue the fulfilled page loads as a FILE (the CSP allows no inline script): it boots the
     * runtime with the first-party composite adapter and exposes what the assertions read. The
     * renderers are wrapped at registration time so a case can COUNT renders — the mechanical
     * count, never an impression.
     */
    protected val hostGlueJs =
        """
        window.__dp = { ready: false, error: null, code: null, notifications: [], renders: [], statuses: [], instance: null };
        (function () {
          var runtime = window.DatapipelinesDashboard;
          var params = new URLSearchParams(location.search);
          var container = document.getElementById('board');
          var wrapped = {};
          var real = runtime._internal.renderers();
          Object.keys(real).forEach(function (kind) {
            var implementation = real[kind];
            wrapped[kind] = {
              kind: implementation.kind,
              version: implementation.version,
              create: function (context) {
                var handle = implementation.create(context);
                var wrappedHandle = {
                  renderData: function (occurrence, rows, bindings) {
                    window.__dp.renders.push({ name: occurrence.name, rows: rows });
                    return handle.renderData(occurrence, rows, bindings);
                  },
                };
                if (handle.resize) wrappedHandle.resize = function () { return handle.resize(); };
                if (handle.dispose) wrappedHandle.dispose = function () { return handle.dispose(); };
                return wrappedHandle;
              },
            };
          });
          Object.keys(wrapped).forEach(function (kind) { runtime._internal.replaceRenderer(kind, wrapped[kind]); });
          try {
            var instance = runtime.init({
              server: { baseUrl: '', credentials: 'session' },
              dashboard: { id: params.get('id'), version: 'released' },
              container: container,
              adapter: runtime.adapters(container),
              options: {
                onNotification: function (n) { window.__dp.notifications.push(n); },
              },
            });
            window.__dp.instance = instance;
            var statuses = window.__dp.statuses;
            instance.on('commit', function () {});
            instance.ready.then(function () { window.__dp.ready = true; }, function (error) {
              window.__dp.code = error && error.code ? error.code : String(error);
              window.__dp.error = error;
            });
          } catch (e) {
            window.__dp.code = e && e.code ? e.code : e.name;
            window.__dp.error = String(e);
          }
        })();
        """

    /**
     * Serves the host page (+ its glue) at `/test/dashboards/host` under the LIVE CSP of a real
     * response, with the Plotly bundle the query names. The page loads the same design-system
     * sheets the app layout loads, so the chart tokens resolve exactly as they do in production.
     */
    protected fun installHostPage(
        bundle: String = "2d",
        theme: String = "dark",
    ) {
        val cspHeader = fetchLiveCsp()
        val html =
            """
            <!doctype html>
            <html lang="en" data-theme="$theme">
            <head>
              <meta charset="utf-8">
              <title>dashboard host</title>
              <link rel="stylesheet" href="/vendor/design-system/tokens.css">
              <link rel="stylesheet" href="/vendor/design-system/themes/$theme.css">
              <link rel="stylesheet" href="/css/app.css">
              <link rel="stylesheet" href="/css/dashboards.css">
              <link rel="stylesheet" href="/vendor/plotly/plotly.css">
            </head>
            <body>
              <main id="board"></main>
              <script src="/vendor/plotly/plotly-$bundle.min.js" data-dp-plotly-bundle="$bundle"></script>
              <script src="/js/datapipelines-dashboard.js"></script>
              <script src="/js/datapipelines-dashboard-plotly.js"></script>
              <script src="/js/datapipelines-dashboard-table.js"></script>
              <script src="/js/datapipelines-dashboard-kpi.js"></script>
              <script src="/test/dashboards/host-glue.js"></script>
            </body>
            </html>
            """.trimIndent()
        page.route(
            "**/test/dashboards/host*",
        ) { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("text/html; charset=utf-8")
                    .setHeaders(mapOf("Content-Security-Policy" to cspHeader))
                    .setBody(html),
            )
        }
        page.route("**/test/dashboards/host-glue*") { route ->
            route.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/javascript; charset=utf-8")
                    .setHeaders(mapOf("Content-Security-Policy" to cspHeader))
                    .setBody(hostGlueJs),
            )
        }
    }

    /**
     * The LIVE policy, copied off a real HTML response — the one way a browser-tests module can
     * read what the app actually sends (SecurityHeaders is out of reach by design).
     */
    protected fun fetchLiveCsp(): String {
        val response = page.request().get("$baseUrl/login")
        val csp: String? = response.headers()["content-security-policy"]
        check(!csp.isNullOrBlank()) { "the live /login response carried no Content-Security-Policy" }
        return csp
    }

    protected fun openHost(dashboardId: String) {
        page.navigate("$baseUrl/test/dashboards/host?id=$dashboardId")
        try {
            page.waitForFunction("() => window.__dp && (window.__dp.ready || window.__dp.code)")
        } catch (timeout: Exception) {
            val keys =
                page.evaluate(
                    "() => Object.keys(window).filter(function (k) { return k.indexOf('Datapipelines') === 0 || k === '__dp'; })",
                )
            val scripts =
                page.evaluate(
                    "() => Array.from(document.scripts).map(function (s) {" +
                        " return s.src + ' dp=' + (s.getAttribute('data-dp-plotly-bundle') || ''); })",
                )
            val readyState = page.evaluate("() => document.readyState")
            val body = page.evaluate("() => document.body ? document.body.innerHTML.slice(0, 300) : 'no body'")
            throw AssertionError(
                "the host page never signalled — readyState=$readyState windowKeys=$keys scripts=$scripts body=$body",
                timeout,
            )
        }
        val code = page.evaluate("() => window.__dp.code") as String?
        if (code != null) {
            val error = page.evaluate("() => String(window.__dp.error)") as String
            val cause =
                page.evaluate(
                    "() => (window.__dp.error && window.__dp.error.details) ? JSON.stringify(window.__dp.error.details) : null",
                ) as String?
            throw AssertionError("the host page failed to boot: $code — $error — details: $cause")
        }
    }

    // ------------------------------------------------------------------ fixtures (L2's mould)

    /**
     * The board every case mounts: one fast bar chart, one table, one slow chart (a 3 s
     * single-row sleep — the abort and overlap cases' long-running source), one KPI, and the
     * initial `refresh_all` action. DQL caller-output only, so the boards pass the read-only rule.
     */
    protected fun seedBoard(root: String): String {
        val datasource = registerSourceDatasource()
        createTemplate("test/${root}_chart.sql", "SELECT g AS x, (g * 10)::int AS y FROM generate_series(1, 4) g")
        createTemplate("test/${root}_table.sql", "SELECT g AS n FROM generate_series(1, 5) g")
        createTemplate("test/${root}_kpi.sql", "SELECT 42 AS v")
        createTemplate("test/${root}_slow.sql", "SELECT 1 AS x, 999 AS y FROM (SELECT pg_sleep(3)) s")
        createTemplate("test/${root}_surface.sql", "SELECT 1 AS a, 2 AS b, 3 AS c UNION ALL SELECT 2, 3, 4 UNION ALL SELECT 3, 2, 1")
        createPipeline("$root/pipelines/chart", "test/${root}_chart.sql", datasource)
        createPipeline("$root/pipelines/table", "test/${root}_table.sql", datasource)
        createPipeline("$root/pipelines/kpi", "test/${root}_kpi.sql", datasource)
        createPipeline("$root/pipelines/slow", "test/${root}_slow.sql", datasource)
        createPipeline("$root/pipelines/surface", "test/${root}_surface.sql", datasource)
        releasePipelines(
            listOf(
                "$root/pipelines/chart",
                "$root/pipelines/table",
                "$root/pipelines/kpi",
                "$root/pipelines/slow",
                "$root/pipelines/surface",
            ),
        )
        val chart =
            seedVisualization("$root/visualizations/chart", plotlyBody("""{"type":"bar","x":null,"y":null}""", "x", "y", "INTEGER"))
        val slowChart =
            seedVisualization("$root/visualizations/slowchart", plotlyBody("""{"type":"bar","x":null,"y":null}""", "x", "y", "INTEGER"))
        val cells =
            seedVisualization(
                "$root/visualizations/cells",
                """{"display_name":"Cells","renderer":{"kind":"table","version":"1"},""" +
                    """"inputs":{"main":{"columns":[{"name":"n","type":"INTEGER","nullable":false}]}},""" +
                    """"config":{"columns":[{"label":"N","values":"n","format":"integer"}]},"bindings":{"n":"n"}}""",
            )
        val kpi =
            seedVisualization(
                "$root/visualizations/total",
                """{"display_name":"Total","renderer":{"kind":"kpi","version":"1"},""" +
                    """"inputs":{"main":{"columns":[{"name":"v","type":"INTEGER","nullable":false}]}},""" +
                    """"config":{"label":"Total","value":"v","format":"integer"},"bindings":{"v":"v"}}""",
            )
        return seedDashboard(
            "$root/boards/overview",
            sources =
                listOf(
                    "chart" to "$root/pipelines/chart",
                    "cells" to "$root/pipelines/table",
                    "total" to "$root/pipelines/kpi",
                    "slow" to "$root/pipelines/slow",
                ),
            occurrences =
                listOf(
                    Triple("revenue", chart, "chart"),
                    Triple("cells", cells, "cells"),
                    Triple("total", kpi, "total"),
                    Triple("slowchart", slowChart, "slow"),
                ),
            initial = true,
        )
    }

    /** A board whose pinned visualization is a SURFACE trace — the server answers renderer.bundle "3d". */
    protected fun seedSurfaceBoard(root: String): String {
        val datasource = registerSourceDatasource()
        createTemplate("test/${root}_surface_src.sql", "SELECT 1 AS a, 2 AS b, 3 AS c UNION ALL SELECT 2, 3, 4 UNION ALL SELECT 3, 2, 1")
        createPipeline("$root/pipelines/surface", "test/${root}_surface_src.sql", datasource)
        releasePipelines(listOf("$root/pipelines/surface"))
        val surface =
            seedVisualization(
                "$root/visualizations/surface",
                """{"display_name":"Surface","renderer":{"kind":"plotly","version":"4"},""" +
                    """"inputs":{"main":{"columns":[{"name":"a","type":"INTEGER","nullable":false},""" +
                    """{"name":"b","type":"INTEGER","nullable":false},""" +
                    """{"name":"c","type":"INTEGER","nullable":false}]}},""" +
                    """"config":{"data":[{"type":"surface","x":null,"y":null,"z":null}],"layout":{}},""" +
                    """"bindings":{"data[0].x":"a","data[0].y":"b","data[0].z":"c"}}""",
            )
        return seedDashboard(
            "$root/boards/surface",
            sources = listOf("surface" to "$root/pipelines/surface"),
            occurrences = listOf(Triple("hill", surface, "surface")),
            initial = true,
        )
    }

    private fun plotlyBody(
        trace: String,
        xColumn: String,
        yColumn: String,
        type: String,
    ): String =
        """{"display_name":"Chart","renderer":{"kind":"plotly","version":"4"},""" +
            """"inputs":{"main":{"columns":[{"name":"$xColumn","type":"$type","nullable":false},""" +
            """{"name":"$yColumn","type":"$type","nullable":false}]}},""" +
            """"config":{"data":[$trace],"layout":{}},"bindings":{"data[0].x":"$xColumn","data[0].y":"$yColumn"}}"""

    /** The suite's own Postgres as the source — the 151 convention; in-page fetch with the CSRF pair. */
    @Suppress("UNCHECKED_CAST")
    private fun registerSourceDatasource(): String {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        val name = "dbr-src-" + suffix()
        val failures =
            page.evaluate(
                """async (args) => {
                  const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
                  const body = new URLSearchParams({
                    name: args.name, displayName: 'Dashboard browser source', dialect: 'POSTGRES',
                    jdbcUrl: args.url, credentialKind: 'password', username: args.user, password: args.password,
                    description: 'the L3a conformance source',
                  });
                  const res = await fetch('/partials/datasources', {
                    method: 'POST', credentials: 'same-origin',
                    headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body,
                  });
                  return res.ok ? [] : [res.status + ' ' + (await res.text()).slice(0, 300)];
                }""",
                mapOf(
                    "name" to name,
                    "url" to SharedBrowserE2e.jdbcUrl.substringBefore("?"),
                    "user" to SharedBrowserE2e.username,
                    "password" to SharedBrowserE2e.password,
                ),
            ) as List<String>
        check(failures.isEmpty()) { "the datasource registration failed: $failures" }
        return name
    }

    @Suppress("UNCHECKED_CAST")
    private fun createTemplate(
        id: String,
        body: String,
    ) {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, dialect: 'POSTGRES', display_name: args.id, description: 'L3a fixture', imports: [], body: args.body }) });
                  return { status: r.status, body: (await r.text()).slice(0, 300) };
                }""",
                mapOf("id" to id, "body" to body),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        check(status == 201 || status == 409) { "template $id: $status ${result["body"]}" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createPipeline(
        name: String,
        templateId: String,
        datasource: String,
    ) {
        val nodes =
            """[{"id":"read","description":"the dashboard source","type":"DQL","source":"$datasource",""" +
                """"template":{"id":"$templateId","version":1},"output":{"target":"caller"},"depends_on":[]}]"""
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const res = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ name: args.name, display_name: args.name, description: 'L3a fixture', nodes: JSON.parse(args.nodes) }) });
                  return { status: res.status, body: (await res.text()).slice(0, 300) };
                }""",
                mapOf("name" to name, "nodes" to nodes),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        check(status == 201 || status == 409) { "pipeline $name: $status ${result["body"]}" }
    }

    private fun releasePipelines(names: List<String>) {
        val admin = currentUserId()
        sql(
            """
            UPDATE pipeline_versions v SET status = 'RELEASED', released_at = NOW(), released_by = '$admin'
              FROM pipelines p
              WHERE v.pipeline_id = p.id AND v.version = 1 AND p.name = ANY(ARRAY[{}])
            """.trimIndent().replace(
                "{}",
                names.joinToString(",") {
                    "'$it'"
                },
            ),
        )
        sql(
            """
            UPDATE pipelines p SET current_version = 1
              WHERE p.name = ANY(ARRAY[{}])
            """.trimIndent().replace(
                "{}",
                names.joinToString(",") {
                    "'$it'"
                },
            ),
        )
    }

    private fun currentUserId(): String = rows("SELECT id::text AS i FROM users WHERE email = '$seededEmail'").single()["i"] as String

    private fun rows(query: String): List<Map<String, Any?>> =
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    readRows(statement, query)
                }
            }

    private fun readRows(
        statement: java.sql.Statement,
        query: String,
    ): List<Map<String, Any?>> =
        statement.executeQuery(query).use { resultSet ->
            val metadata = resultSet.metaData
            val out = mutableListOf<Map<String, Any?>>()
            while (resultSet.next()) {
                val row = mutableMapOf<String, Any?>()
                for (c in 1..metadata.columnCount) row[metadata.getColumnLabel(c)] = resultSet.getObject(c)
                out += row
            }
            out
        }

    protected fun sql(query: String) {
        DriverManager
            .getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(query)
                }
            }
    }

    private fun seedVisualization(
        name: String,
        body: String,
    ): String {
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
        val admin = currentUserId()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', (SELECT id FROM workspaces WHERE name = '${currentWorkspace()}'), '$name', '$name', '', 1, '$admin')",
        )
        sql(
            "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, " +
                "released_at, released_by, created_by) " +
                "SELECT id, 1, " +
                "'${body.replace("'", "''")}'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$admin', '$admin' " +
                "FROM visualizations WHERE name = '$name' AND workspace_id = " +
                "(SELECT id FROM workspaces WHERE name = '${currentWorkspace()}')",
        )
        return name
    }

    private fun seedDashboard(
        name: String,
        sources: List<Pair<String, String>>,
        occurrences: List<Triple<String, String, String>>,
        initial: Boolean,
    ): String {
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
        val admin = currentUserId()
        val sourcesJson =
            sources.joinToString(",", "[", "]") { (source, pipeline) ->
                """{"name":"$source","pipeline":{"name":"$pipeline","version":1},"parameters":{}}"""
            }
        val occurrencesJson =
            occurrences.joinToString(",", "[", "]") { (occurrence, visualization, source) ->
                """{"name":"$occurrence","type":"visualization",""" +
                    """"visualization":{"name":"$visualization","version":1},""" +
                    """"inputs":{"main":{"source":"$source"}}}"""
            }
        val actionsJson =
            if (initial) """[{"name":"refresh_all","type":"refresh","scope":"all","initial":true}]""" else "[]"
        val body =
            """{"display_name":"${name.substringAfterLast('/')}","sources":$sourcesJson,"visualizations":$occurrencesJson,""" +
                """"layout":{"columns":12,"grid":[]},"actions":$actionsJson}"""
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "SELECT '$id'::uuid, w.id, '$name', '$name', '', 1, '$admin' FROM workspaces w WHERE w.name = '${currentWorkspace()}'",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, " +
                "released_at, released_by, created_by) " +
                "SELECT id, 1, " +
                "'${body.replace("'", "''")}'::jsonb, 'RELEASED', 'seeded-$name', NOW(), '$admin', '$admin' " +
                "FROM dashboards WHERE name = '$name' AND workspace_id = " +
                "(SELECT id FROM workspaces WHERE name = '${currentWorkspace()}')",
        )
        return rows(
            "SELECT id::text AS i FROM dashboards WHERE name = '$name' AND workspace_id = " +
                "(SELECT id FROM workspaces WHERE name = '${currentWorkspace()}')",
        ).single()["i"] as String
    }

    private fun currentWorkspace(): String {
        // The switcher's hidden option carries the ACTIVE workspace's name — the one this session is in.
        val name =
            page.evaluate(
                "() => { const s = document.getElementById('workspace-switcher'); return s ? s.selectedOptions[0].text : null; }",
            ) as String?
        check(!name.isNullOrBlank()) { "the session's active workspace could not be read from the switcher" }
        return name
    }
}
