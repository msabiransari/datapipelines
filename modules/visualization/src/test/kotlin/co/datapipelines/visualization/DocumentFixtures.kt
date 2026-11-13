package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The worked documents: the dashboard implementation spec's §3.2 dashboard VERBATIM, and its §3.1 visualization as
 * the worked `chart` document v1 (design record 2026-11-06 §5.1, the 860 brief: the `plotly` kind retired) — the
 * 854 example `bar-vertical.json`'s encoding over the spec's own input, transform and test case, so every dependent
 * (the dashboard's `revenue` input, the validator fakes' `revenue_bars@2` contract) still reads it unchanged. The
 * `bindings` are the DERIVED map (each encoded field to itself); `presentation` is gone (refused on a chart, O-5).
 * Only the `name` (outside the body) is added, since a document carries it and the spec's JSON shows the body.
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
          "renderer": { "kind": "chart", "version": "1" },
          "inputs": {
            "revenue": { "columns": [ { "name": "month", "type": "DATE", "nullable": false },
                                      { "name": "amount", "type": "DECIMAL", "nullable": false } ] }
          },
          "transform": { "template": { "name": "finance/scripts/revenue_bars", "version": 2 },
                         "inputs": { "rows": "revenue" } },
          "config": { "mark": { "type": "bar" },
                      "encoding": { "x": { "field": "month_labels", "type": "nominal", "sort": null, "title": "Month" },
                                    "y": { "field": "amounts", "type": "quantitative", "aggregate": "sum", "title": "Revenue",
                                           "format": "compact" } },
                      "title": { "text": "Revenue" } },
          "bindings": { "amounts": "amounts", "month_labels": "month_labels" },
          "tests": { "cases": [ { "name": "twelve months", "fixtures": { "revenue": [ { "month": "2026-01-01", "amount": "10.5" } ] },
                                  "assertions": [ { "kind": "rendered" }, { "kind": "mark_count", "equals": 1 } ] } ] }
        }
        """.trimIndent()

    /** A `table` document over the same input and transform — for the tests about rules a chart does not have. */
    val SPEC_TABLE: String =
        """
        {
          "name": "finance/visualizations/monthly_revenue_table",
          "display_name": "Monthly revenue",
          "description": "",
          "renderer": { "kind": "table", "version": "1" },
          "inputs": {
            "revenue": { "columns": [ { "name": "month", "type": "DATE", "nullable": false },
                                      { "name": "amount", "type": "DECIMAL", "nullable": false } ] }
          },
          "transform": { "template": { "name": "finance/scripts/revenue_bars", "version": 2 },
                         "inputs": { "rows": "revenue" } },
          "config": { "columns": [ { "label": "Month", "values": [] }, { "label": "Revenue", "values": [] } ] },
          "bindings": { "columns[0].values": "month_labels", "columns[1].values": "amounts" },
          "presentation": { "title": "Monthly revenue", "tokens": { "series": "categorical" } },
          "tests": { "cases": [ { "name": "twelve months", "fixtures": { "revenue": [ { "month": "2026-01-01", "amount": "10.5" } ] },
                                  "assertions": [ { "kind": "rendered" }, { "kind": "text_visible", "text": "Month" } ] } ] }
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

    fun table(): ObjectNode = tree(SPEC_TABLE)

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
