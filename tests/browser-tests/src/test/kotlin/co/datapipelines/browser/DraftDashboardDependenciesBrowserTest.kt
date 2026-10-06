package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** #459: a browser builds and runs an entirely draft graph through the real authoring API. */
class DraftDashboardDependenciesBrowserTest : DashboardBrowserSuite() {
    @Test
    @Suppress("LongMethod") // creation, initial render, parameter change, edit and discard are one ordered API/browser experiment
    fun `all draft dependencies render through nested composition selectors imports and transforms`() {
        startTrace()
        val root = ready("draftdeps")
        val datasource = registerSourceDatasource()
        val inner = "$root/templates/inner.sql"
        val outer = "$root/templates/outer.sql"
        val query = "$root/templates/query.sql"
        val selector = "$root/templates/options.sql"
        val transform = "$root/templates/shape"
        val innerBody = sqlTemplate(inner, "<#function suffix><#return '-lib'></#function>", library = true)
        val createdInner = request("POST", "/api/v1/templates", innerBody, 201)
        request(
            "POST",
            "/api/v1/templates",
            sqlTemplate(
                outer,
                "<#function suffix><#return inner.suffix()></#function>",
                library = true,
                imports = """[{"id":"$inner","version":1,"alias":"inner"}]""",
            ),
            201,
        )
        request(
            "POST",
            "/api/v1/templates",
            sqlTemplate(
                query,
                "SELECT CAST(:country AS TEXT) || '${'$'}{lib.suffix()}' AS c, 42 AS n, 10 AS previous",
                imports = """[{"id":"$outer","version":1,"alias":"lib"}]""",
            ),
            201,
        )
        request(
            "POST",
            "/api/v1/templates",
            sqlTemplate(
                selector,
                "SELECT CAST(:kind AS TEXT) || '-1' AS value, " +
                    "CAST(:kind AS TEXT) || '-1' AS display_value, true AS is_default ORDER BY value",
            ),
            201,
        )
        request(
            "POST",
            "/api/v1/templates",
            """
            {"id":"$transform","type":"jsonata","display_name":"Shape","description":"Draft transform",
             "body":"[ rows.{\"c\": c & \"-transform\"} ]",
             "contract":{"mode":"row","inputs":{"rows":{"kind":"table","columns":[{"name":"c","type":"STRING"},{"name":"n","type":"INTEGER"},{"name":"previous","type":"INTEGER"}]}},
                         "output":{"kind":"table","columns":[{"name":"c","type":"STRING"}]}},
             "invariants":[],"tests":[{"name":"empty input","input":{"rows":[],"inputs":{}},"expect":{"output":[]}}]}
            """.trimIndent(),
            201,
        )
        val leaf = "$root/pipelines/leaf"
        val middle = "$root/pipelines/middle"
        val parent = "$root/pipelines/parent"
        request(
            "POST",
            "/api/v1/pipelines",
            pipeline(
                leaf,
                """
                [{"id":"read","description":"Draft leaf","type":"DQL","source":"$datasource",
                  "template":{"id":"$query","version":1},"output":{"target":"caller"},"depends_on":[]}]
                """.trimIndent(),
            ),
            201,
        )
        listOf(middle to leaf, parent to middle).forEach { (name, child) ->
            request(
                "POST",
                "/api/v1/pipelines",
                pipeline(
                    name,
                    """
                    [{"id":"child","description":"Draft child","type":"PIPELINE","pipeline":{"name":"$child","version":1},
                      "parameters":{"country":"${'$'}{country}"},"output":{"target":"caller"},"depends_on":[]}]
                    """.trimIndent(),
                ),
                201,
            )
        }
        request(
            "POST",
            "/api/v1/parameter-sets",
            """
            {"name":"$root/sets/options","display_name":"Options","parameters":[
              {"name":"kind","label":"Kind","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,
               "source":{"constants":[{"value":"EU","display_value":"EU","is_default":true},
                                           {"value":"US","display_value":"US","is_default":false}]}},
              {"name":"country","label":"Country","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,
               "depends_on":["kind"],"source":{"template":{"id":"$selector","version":1},"datasource":"$datasource"}}]}
            """.trimIndent(),
            201,
        )
        request(
            "POST",
            "/api/v1/visualizations",
            """
            {"name":"$root/visualizations/table","display_name":"Draft table","renderer":{"kind":"table","version":"1"},
             "inputs":{"main":{"columns":[{"name":"c","type":"STRING","nullable":false},{"name":"n","type":"INTEGER","nullable":false},{"name":"previous","type":"INTEGER","nullable":false}]}},
             "transform":{"template":{"name":"$transform","version":1},"inputs":{"rows":"main"}},
             "config":{"columns":[{"label":"Country","values":null}]},"bindings":{"columns[0].values":"c"}}
            """.trimIndent(),
            201,
        )
        request(
            "POST",
            "/api/v1/visualizations",
            """
            {"name":"$root/visualizations/kpi","display_name":"Draft KPI","renderer":{"kind":"kpi","version":"1"},
             "inputs":{"main":{"columns":[{"name":"n","type":"INTEGER","nullable":false},
                                               {"name":"previous","type":"INTEGER","nullable":false}]}},
             "config":{"label":"Total","value":null,"format":"integer","comparison":{"label":"Previous","value":null}},
             "bindings":{"value":"n","comparison.value":"previous"}}
            """.trimIndent(),
            201,
        )
        request(
            "POST",
            "/api/v1/visualizations",
            """
            {"name":"$root/visualizations/chart","display_name":"Draft chart","renderer":{"kind":"plotly","version":"4"},
             "inputs":{"main":{"columns":[{"name":"c","type":"STRING","nullable":false},
                                               {"name":"n","type":"INTEGER","nullable":false}]}},
             "config":{"data":[{"type":"bar","x":[],"y":[]}]},"bindings":{"data[0].x":"c","data[0].y":"n"}}
            """.trimIndent(),
            201,
        )
        val board =
            request(
                "POST",
                "/api/v1/dashboards",
                """
                   {"name":"$root/boards/draft","display_name":"Draft dependencies","parameter_set":{"name":"$root/sets/options","version":1},
                    "sources":[{"name":"source","pipeline":{"name":"$parent","version":1},"parameters":{"country":{"parameter":"country"}}}],
                    "visualizations":[{"name":"table","type":"visualization","visualization":{"name":"$root/visualizations/table","version":1},
                      "inputs":{"main":{"source":"source"}}},
                {"name":"kpi","type":"visualization","visualization":{"name":"$root/visualizations/kpi","version":1},"inputs":{"main":{"source":"source"}}},
                {"name":"chart","type":"visualization","visualization":{"name":"$root/visualizations/chart","version":1},"inputs":{"main":{"source":"source"}}}],
                "layout":{"columns":12,"grid":[{"name":"table","x":0,"y":0,"w":8,"h":4},{"name":"kpi","x":8,"y":0,"w":4,"h":4},
                                                 {"name":"chart","x":0,"y":4,"w":12,"h":4}]},
                    "actions":[{"name":"refresh_all","type":"refresh","scope":"all","initial":true}]}
                """.trimIndent(),
                201,
            )["id"] as String
        request("GET", "/api/v1/dashboards/$board/runtime/config", null, 404)
        val config = request("GET", "/api/v1/dashboards/$board/runtime/config?version=1", null, 200)["configuration_id"]
        page.navigate("$baseUrl/dashboards/$board?version=1&tab=board")
        awaitCell("EU-1-lib-transform")
        page.locator("#dp-board .plotly .main-svg").first().waitFor()
        page.locator("#dp-board .dp-dashboard-kpi-number").innerText() shouldBe "42"
        page.locator("#dp-board .dp-dashboard-kpi-comparison-value").innerText() shouldBe "10"
        page.locator("[data-dp-parameter='kind'] select").selectOption("1")
        page.waitForCondition { page.locator("[data-dp-parameter='country'] select").innerText().contains("US-1") }
        page.evaluate("() => window.__dpPage.instance.refresh({scope:'all'})")
        awaitCell("US-1-lib-transform")
        // A real draft write at the same version must invalidate the graph, through two import edges.
        val edited = sqlTemplate(inner, "<#function suffix><#return '-edited'></#function>", library = true)
        request("PUT", "/api/v1/templates", edited, 200, createdInner["body_hash"] as String)
        request("GET", "/api/v1/dashboards/$board/runtime/config?version=1", null, 200)["configuration_id"] shouldNotBe config
        page.reload()
        awaitCell("EU-1-edited-transform")
        page.screenshot(
            com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                java.nio.file.Paths
                    .get("build", "reports", "draft-dashboard-dependencies.png"),
            ),
        )
        // A discarded nested import is refused in the page before a source can run.
        sql(
            "UPDATE template_versions SET status = 'DISCARDED', discarded_at = NOW() " +
                "WHERE template_id = (SELECT id FROM templates WHERE name = '$inner')",
        )
        page.reload()
        page.waitForSelector("#dp-board-refusal:not([hidden])")
        page.locator("#dp-board-refusal").innerText().contains("dashboard.runtime.dependency_missing") shouldBe true
    }

    private fun awaitCell(text: String) {
        page.waitForCondition { page.locator("#dp-board").innerText().contains(text) }
        page
            .locator("[data-dp-viewed-label]")
            .first()
            .innerText()
            .contains("draft") shouldBe true
    }

    private fun pipeline(
        name: String,
        nodes: String,
    ): String =
        """{"name":"$name","display_name":"Draft source","description":"Nested draft",
           "parameters":{"country":{"type":"STRING","required":true}},"nodes":$nodes}"""

    private fun sqlTemplate(
        id: String,
        body: String,
        library: Boolean = false,
        imports: String = "[]",
    ): String =
        """{"id":"$id","dialect":"POSTGRES","display_name":"Draft SQL","description":"Draft dependency",
           "is_library":$library,"imports":$imports,"body":"$body"}"""

    @Suppress("UNCHECKED_CAST")
    private fun request(
        method: String,
        url: String,
        body: String?,
        expected: Int,
        hash: String? = null,
    ): Map<String, Any?> {
        val response =
            page.evaluate(
                """async a => {
          const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
          const headers = {'Content-Type':'application/json','DP-CSRF-Token':csrf ? decodeURIComponent(csrf[1]) : ''};
          if (a.hash) headers['If-Match'] = a.hash;
          const r = await fetch(a.url, {method:a.method,credentials:'same-origin',headers,body:a.body || undefined});
          return {status:r.status,text:await r.text()};
        }""",
                mapOf("method" to method, "url" to url, "body" to body, "hash" to hash),
            ) as Map<String, Any?>
        check((response["status"] as Number).toInt() == expected) { "$method $url: $response" }
        return page.evaluate("s => JSON.parse(s).data || {}", response["text"]) as Map<String, Any?>
    }
}
