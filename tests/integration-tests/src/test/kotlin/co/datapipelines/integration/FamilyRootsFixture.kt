package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.security.MessageDigest
import java.sql.DriverManager

/**
 * The authoring and reading half of [FamilyRootsReleaseCascadeE2eTest] (#873 §C): every artifact is created through the
 * product's own REST routes (nothing seeded), and every fact a release plan needs is read straight from SQL — the
 * closure's tree hash is recomputed HERE, from the committed rows and the record's §2.1 `v1` format, never asked of
 * the product. One [Node] per closure member; [edges] in each parent's pin order, so the node ids follow the walk's
 * depth-first discovery order (`n0` the root).
 */
internal class FamilyRootsFixture(
    private val port: Int,
    private val session: String,
    private val workspaceId: String,
    private val datasource: String,
) {
    private val mapper = ObjectMapper()

    /** A closure member: its family wire word and key (the artifact's name). */
    data class Node(
        val family: String,
        val name: String,
    )

    data class Edge(
        val from: Node,
        val to: Node,
        val path: String,
    )

    // ---- authoring, through REST -----------------------------------------------------------------------------

    fun sqlTemplate(
        name: String,
        body: String = "SELECT DATE '2026-01-01' AS \"month\", 10.5 AS \"amount\"",
        library: Boolean = false,
        imports: List<String> = emptyList(),
    ): Node {
        val document =
            mapOf(
                "id" to name,
                "dialect" to "H2",
                "display_name" to "Family roots",
                "description" to "#873",
                "is_library" to library,
                "imports" to imports.mapIndexed { i, import -> mapOf("id" to import, "version" to 1, "alias" to "lib$i") },
                "body" to body,
            )
        created(send("POST", "/api/v1/templates", mapper.writeValueAsString(document)))
        return Node("template", name)
    }

    fun jsonataTemplate(name: String): Node {
        val document =
            """
            {"id":"$name","type":"jsonata","display_name":"Shape","description":"#873",
             "body":"[ rows.{\"month_labels\": month, \"amounts\": amount} ]",
             "contract":{"mode":"row","inputs":{"rows":{"kind":"table","columns":[
                            {"name":"month","type":"DATE"},{"name":"amount","type":"DECIMAL","precision":12,"scale":2}]}},
                         "output":{"kind":"table","columns":[{"name":"month_labels","type":"STRING"},
                            {"name":"amounts","type":"DECIMAL","precision":12,"scale":2}]}},
             "invariants":[],"tests":[{"name":"empty input","input":{"rows":[],"inputs":{}},"expect":{"output":[]}}]}
            """.trimIndent()
        created(send("POST", "/api/v1/templates", document))
        return Node("template", name)
    }

    fun pipeline(
        name: String,
        template: Node,
        parameters: Map<String, Any> = emptyMap(),
    ): Node {
        val node =
            mapOf(
                "id" to "n0",
                "type" to "DQL",
                "source" to datasource,
                "description" to "One row",
                "template" to mapOf("id" to template.name, "version" to 1),
                "depends_on" to emptyList<String>(),
            )
        val document =
            mapOf("name" to name, "display_name" to "Family roots", "description" to "#873", "nodes" to listOf(node)) +
                (if (parameters.isEmpty()) emptyMap() else mapOf("parameters" to parameters))
        created(send("POST", "/api/v1/pipelines", mapper.writeValueAsString(document)))
        return Node("pipeline", name)
    }

    fun parameterSet(
        name: String,
        template: Node,
    ): Node {
        val parameter =
            mapOf(
                "name" to "region",
                "label" to "Region",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "required" to false,
                "source" to mapOf("template" to mapOf("id" to template.name, "version" to 1), "datasource" to datasource),
            )
        val document = mapOf("name" to name, "display_name" to "Family roots", "description" to "#873", "parameters" to listOf(parameter))
        created(send("POST", "/api/v1/parameter-sets", mapper.writeValueAsString(document)))
        return Node("parameter_set", name)
    }

    /** A DRAFT visualization over [template] whose one test case is recorded GREEN for its current hash. */
    fun visualization(
        name: String,
        template: Node,
    ): Node {
        val created = send("POST", "/api/v1/visualizations", visualizationDocument(name, template.name))
        created(created)
        val id = created.jsonPath().getString("data.id")
        val started = send("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        withClue(started.asString()) { started.statusCode shouldBe CREATED }
        val sid = started.jsonPath().getString("data.session_id")
        val completed = send("POST", "/api/v1/visualizations/$id/tests/sessions/$sid/results", RESULTS)
        withClue(completed.asString()) { completed.jsonPath().getString("data.status") shouldBe "GREEN" }
        return Node("visualization", name)
    }

    /** A dashboard over [set], one [source] (binding each of [bound] to the set parameter of that name) and [visualization]. */
    fun dashboard(
        name: String,
        set: Node,
        source: Node,
        visualization: Node,
        bound: List<String> = emptyList(),
    ): Node {
        val parameters = bound.joinToString(", ") { "\"$it\": {\"parameter\": \"$it\"}" }
        val document =
            """
            {"name": "$name", "display_name": "Family roots", "description": "#873",
             "parameter_set": {"name": "${set.name}", "version": 1},
             "sources": [{"name": "revenue_source", "pipeline": {"name": "${source.name}", "version": 1},
                          "parameters": {$parameters}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "${visualization.name}", "version": 1},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()
        created(send("POST", "/api/v1/dashboards", document))
        return Node("dashboard", name)
    }

    /** The visualization closure under [prefix]: a jsonata shape and the chart over it, every node a DRAFT. */
    fun visualizationClosure(prefix: String): Pair<Node, List<Edge>> {
        val template = jsonataTemplate("$prefix/shape.jsonata")
        val chart = visualization("$prefix/chart", template)
        return chart to listOf(Edge(chart, template, "transform.template"))
    }

    /**
     * The dashboard closure under [prefix] — its set, source pipeline and chart with their templates — every node a DRAFT.
     * With [bindRegion] the source pipeline declares `region` and the dashboard binds it to the set's `region` (A34).
     */
    fun dashboardClosure(
        prefix: String,
        bindRegion: Boolean = false,
    ): Pair<Node, List<Edge>> {
        val selectorTemplate = sqlTemplate("$prefix/selector.sql", SELECTOR_BODY)
        val set = parameterSet("$prefix/set", selectorTemplate)
        val query = sqlTemplate("$prefix/query.sql")
        val declared = if (bindRegion) mapOf("region" to mapOf("type" to "STRING", "required" to false)) else emptyMap()
        val source = pipeline("$prefix/source", query, declared)
        val shape = jsonataTemplate("$prefix/shape.jsonata")
        val chart = visualization("$prefix/chart", shape)
        val board = dashboard("$prefix/board", set, source, chart, if (bindRegion) listOf("region") else emptyList())
        return board to
            listOf(
                Edge(board, set, "parameter_set"),
                Edge(set, selectorTemplate, "parameters[0].source.template"),
                Edge(board, source, "sources[0].pipeline"),
                Edge(source, query, "nodes[0].template"),
                Edge(board, chart, "visualizations[0].visualization"),
                Edge(chart, shape, "transform.template"),
            )
    }

    /** [root] and its edges in pin order, for the closure of [family] under [prefix]; every node a DRAFT. */
    fun closure(
        family: String,
        prefix: String,
    ): Pair<Node, List<Edge>> =
        when (family) {
            "template" -> {
                val lib = sqlTemplate("$prefix/lib.sql", "<#macro one>SELECT 1 AS \"n\"</#macro>", library = true)
                val root = sqlTemplate("$prefix/root.sql", "<@lib0.one/>", imports = listOf(lib.name))
                root to listOf(Edge(root, lib, "imports[0]"))
            }

            "parameter_set" -> {
                val template = sqlTemplate("$prefix/selector.sql", SELECTOR_BODY)
                val set = parameterSet("$prefix/set", template)
                set to listOf(Edge(set, template, "parameters[0].source.template"))
            }

            "visualization" -> {
                visualizationClosure(prefix)
            }

            else -> {
                dashboardClosure(prefix)
            }
        }

    /**
     * The compatibility entry of [root] (#873 §C.3): the family's release DIALOG POST (template, visualization, dashboard —
     * a session form with the CSRF double-submit) or, for a parameter set (no dialog until G6 U4), its REST route without
     * a plan; each with its legacy consent flag set, at the root's stored hash.
     */
    fun releaseCompatible(root: Node): Response {
        val row = row(root)
        return when (root.family) {
            "template" -> {
                form("/partials/templates/lifecycle/release", mapOf("name" to root.name, "bodyHash" to row.hash))
            }

            "parameter_set" -> {
                send("POST", "/api/v1/parameter-sets/${row.uuid}/release?release_pinned_templates=true", "", row.hash)
            }

            "visualization" -> {
                form(
                    "/partials/visualizations/${row.uuid}/lifecycle/release",
                    mapOf("body_hash" to row.hash, "release_pinned_templates" to "true"),
                )
            }

            else -> {
                form(
                    "/partials/dashboards/${row.uuid}/lifecycle/release",
                    mapOf("bodyHash" to row.hash, "releasePinnedVisualizations" to "true"),
                )
            }
        }
    }

    private fun form(
        path: String,
        fields: Map<String, String>,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.URLENC)
            .apply { fields.forEach { (key, value) -> formParam(key, value) } }
            .post(path)

    /** Releases [root] with a plan over [edges] selecting every node but the root, at the root's stored hash. */
    fun releaseWithPlan(
        root: Node,
        edges: List<Edge>,
        treeHash: String = treeHash(root, edges),
    ): Response {
        val ids = nodeIds(root, edges)
        val plan = mapOf("tree_hash" to treeHash, "include_dependencies" to ids.filterKeys { it != root }.values.toList())
        val row = row(root)
        return if (root.family == "template") {
            send("POST", "/api/v1/templates/release", mapper.writeValueAsString(plan + ("name" to root.name)), row.hash)
        } else {
            send("POST", "/api/v1/${ROUTES.getValue(root.family)}/${row.uuid}/release", mapper.writeValueAsString(plan), row.hash)
        }
    }

    fun send(
        method: String,
        path: String,
        body: String,
        ifMatch: String? = null,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .request(method, path)

    private fun created(response: Response) {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe CREATED }
    }

    // ---- reading, through SQL ---------------------------------------------------------------------------------

    /** One version row: the entity's uuid and current pointer, the version's status and hash. */
    data class Row(
        val uuid: String,
        val current: String?,
        val status: String,
        val hash: String,
    )

    fun row(node: Node): Row {
        val (table, versions, fk) = TABLES.getValue(node.family)
        return DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c
                .prepareStatement(
                    "SELECT a.id::text, a.current_version::text, v.status, v.body_hash FROM $table a JOIN $versions v ON v.$fk = a.id" +
                        " WHERE a.workspace_id = ?::uuid AND a.name = ? AND v.version = 1",
                ).use { s ->
                    s.setString(1, workspaceId)
                    s.setString(2, node.name)
                    s.executeQuery().use { r ->
                        check(r.next()) { "no version 1 of ${node.family} ${node.name}" }
                        Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4))
                    }
                }
        }
    }

    /** The node ids the walk assigns: depth-first discovery from [root] over [edges] in pin order. */
    fun nodeIds(
        root: Node,
        edges: List<Edge>,
    ): Map<Node, String> {
        val ids = LinkedHashMap<Node, String>()

        fun visit(node: Node) {
            if (node in ids) return
            ids[node] = "n${ids.size}"
            edges.filter { it.from == node }.forEach { visit(it.to) }
        }
        visit(root)
        return ids
    }

    /** The §2.1 `v1` tree hash of [root]'s closure, from the committed rows: byte-sorted nodes and edges, SHA-256. */
    fun treeHash(
        root: Node,
        edges: List<Edge>,
    ): String {
        fun id(node: Node) = "[\"${node.family}\",\"${node.name}\",1]"
        val nodes =
            nodeIds(root, edges).keys.map { node ->
                val row = row(node)
                val current = row.current ?: "null"
                "{\"id\":${id(
                    node,
                )},\"entity_uuid\":\"${row.uuid}\",\"status\":\"${row.status}\",\"body_hash\":\"${row.hash}\",\"current\":$current}"
            }
        val edgeJson = edges.map { "{\"from\":${id(it.from)},\"to\":${id(it.to)},\"path\":\"${it.path}\"}" }
        val canonical =
            "{\"format\":\"v1\",\"workspace\":\"$workspaceId\",\"root\":${id(root)}," +
                "\"nodes\":${nodes.sorted().joinToString(",", "[", "]")},\"edges\":${edgeJson.sorted().joinToString(",", "[", "]")}}"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun visualizationDocument(
        name: String,
        template: String,
    ): String =
        """
        {"name":"$name","display_name":"Family roots","description":"#873",
         "renderer":{"kind":"chart","version":"1"},
         "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                            {"name":"amount","type":"DECIMAL","nullable":false}]}},
         "transform":{"template":{"name":"$template","version":1},"inputs":{"rows":"revenue"}},
         "config":{"mark":{"type":"bar"},
                   "encoding":{"x":{"field":"month_labels","type":"nominal","sort":"ascending"},
                               "y":{"field":"amounts","type":"quantitative","aggregate":"sum"}}},
         "bindings":{"amounts":"amounts","month_labels":"month_labels"},
         "tests":{"cases":[{"name":"one month","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},
                              "assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":1}]}]}}
        """.trimIndent()

    companion object {
        private const val CREATED = 201
        private const val EXCERPT = 600

        /** A selector template's body: the value, label and default columns a SELECT parameter reads. */
        const val SELECTOR_BODY = "SELECT 'a' AS \"value\", 'A' AS \"display_value\", FALSE AS \"is_default\" ORDER BY 1"
        private const val RESULTS = """{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"e2e","theme":"dark"}}"""

        /** Family wire word → (entity table, version table, version FK). Constants only; no request value reaches SQL text. */
        val TABLES =
            mapOf(
                "template" to Triple("templates", "template_versions", "template_id"),
                "pipeline" to Triple("pipelines", "pipeline_versions", "pipeline_id"),
                "parameter_set" to Triple("parameter_sets", "parameter_set_versions", "parameter_set_id"),
                "visualization" to Triple("visualizations", "visualization_versions", "visualization_id"),
                "dashboard" to Triple("dashboards", "dashboard_versions", "dashboard_id"),
            )

        private val ROUTES =
            mapOf(
                "pipeline" to "pipelines",
                "parameter_set" to "parameter-sets",
                "visualization" to "visualizations",
                "dashboard" to "dashboards",
            )
    }
}
