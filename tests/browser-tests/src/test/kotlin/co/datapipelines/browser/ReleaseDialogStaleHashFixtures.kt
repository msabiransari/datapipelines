package co.datapipelines.browser

import java.sql.DriverManager

/** Bound-value, independent persisted-state oracle; identifiers come only from this closed family list. */
internal class ReleaseDialogStaleHashFixtures(
    val family: String,
    val name: String,
    private val workspace: String,
) : AutoCloseable {
    private val table =
        when (family) {
            "template" -> "templates"
            "dashboard" -> "dashboards"
            "visualization" -> "visualizations"
            else -> error("unknown fixture family")
        }
    private val versions = "${family}_versions"
    private val foreignKey = "${family}_id"

    fun state(): List<String?> =
        connection().use { c ->
            c
                .prepareStatement(
                    "SELECT v.status, v.body_hash, a.current_version::text, v.version::text " +
                        "FROM $table a JOIN $versions v ON v.$foreignKey = a.id WHERE a.name = ? " +
                        "AND a.workspace_id = (SELECT id FROM workspaces WHERE name = ?) ORDER BY v.version",
                ).use { s ->
                    s.setString(1, name)
                    s.setString(2, workspace)
                    s.executeQuery().use { r ->
                        check(r.next()) { "owned artifact missing" }
                        val state = (1..4).map { r.getString(it) }
                        check(!r.next()) { "unexpected extra version" }
                        state
                    }
                }
        }

    fun greenRunCount(hash: String): Int =
        connection().use { c ->
            c
                .prepareStatement(
                    "SELECT count(*) FROM visualization_test_runs r JOIN visualizations v ON v.id = r.visualization_id " +
                        "WHERE v.name = ? AND r.body_hash = ? AND r.status = 'GREEN' " +
                        "AND v.workspace_id = (SELECT id FROM workspaces WHERE name = ?)",
                ).use { s ->
                    s.setString(1, name)
                    s.setString(2, hash)
                    s.setString(3, workspace)
                    s.executeQuery().use { r ->
                        check(r.next())
                        r.getInt(1)
                    }
                }
        }

    override fun close() {
        connection().use { c ->
            c.prepareStatement("DELETE FROM $table WHERE name = ? AND workspace_id = (SELECT id FROM workspaces WHERE name = ?)").use { s ->
                s.setString(1, name)
                s.setString(2, workspace)
                s.executeUpdate()
            }
        }
    }

    companion object {
        fun document(
            family: String,
            name: String,
            title: String,
        ): String =
            when (family) {
                "template" -> {
                    """{"id":"$name","type":"sql","dialect":"POSTGRES","body":"${if (title == "B") "SELECT 2" else "SELECT 1"}",
                        "display_name":"$title","description":"$title"}"""
                }

                "dashboard" -> {
                    """{"name":"$name","display_name":"$title","description":"$title","sources":[],
                        "visualizations":[],"layout":{"columns":12,"grid":[]},"actions":[]}"""
                }

                "visualization" -> {
                    """
                    {"name":"$name","display_name":"Units","description":"453",
                     "renderer":{"kind":"plotly","version":"4"},
                     "inputs":{"sales":{"columns":[{"name":"month","type":"STRING","nullable":false},
                                                        {"name":"units","type":"INTEGER","nullable":false}]}},
                     "config":{"data":[{"type":"bar","x":[],"y":[]}]},
                     "bindings":{"data[0].x":"month","data[0].y":"units"},"presentation":{"title":"$title"},
                     "tests":{"cases":[{"name":"one month","fixtures":{"sales":[{"month":"Jan","units":3}]},
                                          "assertions":[{"kind":"rendered"},{"kind":"trace_count","equals":1}]}]}}
                    """.trimIndent()
                }

                else -> {
                    error("unknown fixture family")
                }
            }

        private fun connection() =
            DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password)
    }
}
