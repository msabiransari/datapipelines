package co.datapipelines.integration

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The fixture documents and names of [G0NativeLifecycleWritersE2eTest] (#841 G0 R3), in their own file so the test
 * file declares exactly one class (the pregate's 2b selection derives a file's class from its declarations).
 */
internal object G0Lifecycle {
    const val TEMPLATE = "test/g0l/shared.sql"
    const val LIFECYCLE_TEMPLATE = "test/g0l/lifecycle.sql"
    const val SOLE_TEMPLATE = "test/g0l/sole.sql"
    const val SOURCE = "test/g0l/source"
    const val SOURCE_CHART = "test/g0l/source_chart"
    const val DATASOURCE = "h2-g0l-841"

    fun templateArguments(id: String): Map<String, Any?> =
        mapOf(
            "id" to id,
            "dialect" to "H2",
            "display_name" to "G0 lifecycle",
            "description" to "Expects: nothing (plain SQL).",
            "body" to "SELECT 1 AS n",
        )

    /** The family's full document for [name], [revision] in its `description` (a body change, so a real edit). */
    fun document(
        mapper: ObjectMapper,
        family: G0NativeLifecycleWritersE2eTest.Family,
        name: String,
        revision: String,
    ): String =
        when (family) {
            G0NativeLifecycleWritersE2eTest.Family.PIPELINE -> {
                pipeline(mapper, name, revision)
            }

            G0NativeLifecycleWritersE2eTest.Family.VISUALIZATION -> {
                """
                {"name": "$name", "display_name": "G0 chart", "description": "$revision",
                 "renderer": {"kind": "chart", "version": "1"},
                 "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                    {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
                 "config": {"title": {"text": "G0 chart"}, "mark": {"type": "bar"},
                            "encoding": {"x": {"field": "month", "type": "temporal", "sort": "ascending"},
                                         "y": {"field": "amount", "type": "quantitative", "aggregate": "sum"}}},
                 "bindings": {"amount": "amount", "month": "month"},
                 "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                      "assertions": [{"kind": "rendered"}]}]}}
                """.trimIndent()
            }

            G0NativeLifecycleWritersE2eTest.Family.DASHBOARD -> {
                """
                {"name": "$name", "display_name": "G0 board", "description": "$revision",
                 "sources": [{"name": "rows_source", "pipeline": {"name": "$SOURCE", "version": 1}}],
                 "visualizations": [{"name": "rows_chart", "type": "visualization",
                                     "visualization": {"name": "$SOURCE_CHART", "version": 1},
                                     "inputs": {"revenue": {"source": "rows_source"}}}],
                 "layout": {"grid": [{"name": "rows_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
                """.trimIndent()
            }

            G0NativeLifecycleWritersE2eTest.Family.PARAMETER_SET -> {
                parameterSet(mapper, name, revision)
            }
        }

    private fun pipeline(
        mapper: ObjectMapper,
        name: String,
        revision: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "G0 lifecycle",
                "description" to revision,
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "n0",
                            "type" to "DQL",
                            "source" to DATASOURCE,
                            "description" to "One row",
                            "template" to mapOf("id" to TEMPLATE, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            ),
        )

    private fun parameterSet(
        mapper: ObjectMapper,
        name: String,
        revision: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "name" to name,
                "display_name" to "G0 filters",
                "description" to revision,
                "parameters" to
                    listOf(
                        mapOf(
                            "name" to "min_amount",
                            "label" to "Minimum amount",
                            "type" to "DECIMAL",
                            "precision" to 12,
                            "scale" to 2,
                            "kind" to "INPUT",
                            "required" to false,
                            "default_value" to 0,
                        ),
                    ),
            ),
        )
}
