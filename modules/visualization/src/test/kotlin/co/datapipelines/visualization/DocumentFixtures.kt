package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The spec's two worked documents (the dashboard implementation spec §3.1 and §3.2), VERBATIM — the brief's
 * rule: they read and validate unchanged. Only the `name` (outside the body) is added, since a document
 * carries it and the spec's JSON shows the body.
 */
internal object DocumentFixtures {
    const val VISUALIZATION_NAME = "finance/visualizations/monthly_revenue"
    const val DASHBOARD_NAME = "finance/dashboards/revenue_overview"

    val SPEC_VISUALIZATION: String =
        """
        {
          "name": "$VISUALIZATION_NAME",
          "display_name": "Monthly revenue",
          "description": "",
          "renderer": { "kind": "plotly", "version": "4" },
          "inputs": {
            "revenue": { "columns": [ { "name": "month", "type": "DATE", "nullable": false },
                                      { "name": "amount", "type": "DECIMAL", "nullable": false } ] }
          },
          "transform": { "template": { "name": "finance/transforms/revenue_bars", "version": 2 },
                         "inputs": { "rows": "revenue" } },
          "config": { "data": [ { "type": "bar", "x": "${'$'}.x", "y": "${'$'}.y" } ], "layout": { "title": { "text": "Revenue" } } },
          "bindings": { "data[0].x": "month_labels", "data[0].y": "amounts" },
          "presentation": { "title": "Monthly revenue", "tokens": { "series": "categorical" } },
          "tests": { "cases": [ { "name": "twelve months", "fixtures": { "revenue": [ { "month": "2026-01-01", "amount": "10.5" } ] },
                                  "assertions": [ { "kind": "rendered" }, { "kind": "trace_count", "equals": 1 } ] } ] }
        }
        """.trimIndent()

    val SPEC_DASHBOARD: String =
        """
        {
          "name": "$DASHBOARD_NAME",
          "display_name": "Revenue overview",
          "description": "",
          "parameter_set": { "name": "finance/parameters/reporting_period", "version": 1 },
          "sources": [
            { "name": "revenue_source", "pipeline": { "name": "finance/pipelines/monthly_revenue", "version": 7 },
              "parameters": { "year": { "parameter": "year" }, "currency": { "value": "USD" } } }
          ],
          "visualizations": [
            { "name": "revenue_chart", "type": "visualization",
              "visualization": { "name": "finance/visualizations/monthly_revenue", "version": 3 },
              "inputs": { "revenue": { "source": "revenue_source" } }, "timeout_seconds": 120 }
          ],
          "groups": [ { "name": "overview_group", "type": "group", "members": [ "year", "revenue_chart", "refresh_button" ] } ],
          "actions": [ { "name": "refresh_overview", "type": "refresh", "scope": "targets", "targets": [ "revenue_chart" ], "initial": true } ],
          "action_controls": [ { "name": "refresh_button", "type": "action_control", "action": "refresh_overview", "label": "Apply" } ],
          "parameter_scopes": { "year": [ "overview_group" ] },
          "parameter_state": { "dashboard": { "visible": "inherit", "enabled": "inherit" },
                               "parameters": { "currency": { "visible": "force_false" } } },
          "outgoing_overrides": { "revenue_source": { "currency": { "value": "USD" } } },
          "layout": { "parameter_set": { "position": "left" }, "parameter_placements": { "year": { "group": "overview_group" } },
                      "grid": [ { "name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4 } ], "columns": 12 },
          "timeouts": { "refresh_seconds": 300 }
        }
        """.trimIndent()

    fun tree(json: String): ObjectNode = ArtifactJson.mapper.readTree(json) as ObjectNode

    fun visualization(): ObjectNode = tree(SPEC_VISUALIZATION)

    fun dashboard(): ObjectNode = tree(SPEC_DASHBOARD)

    /** The node at a dotted path with `[n]` indexes, as an object — for surgical edits of a fixture. */
    fun ObjectNode.obj(path: String): ObjectNode {
        var node: JsonNode = this
        Regex("""([a-z_]+)|\[(\d+)]""").findAll(path).forEach { m ->
            node = if (m.groupValues[1].isNotEmpty()) node.get(m.groupValues[1]) else node.get(m.groupValues[2].toInt())
        }
        return node as ObjectNode
    }
}
