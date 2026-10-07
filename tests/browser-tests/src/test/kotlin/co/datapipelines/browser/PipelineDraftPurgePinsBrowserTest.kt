package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.DriverManager

/** #462: real API saves, production pin consumers, browser evidence and unchanged persisted state. */
@Suppress("LongMethod") // one composed save/refuse/repoint/purge scenario, with state asserted after each action
class PipelineDraftPurgePinsBrowserTest : DashboardBrowserSuite() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `draft references refuse both purge APIs and the dialog until repointed`(sole: Boolean) {
        startTrace()
        val root = ready("purge462")
        val datasource = registerSourceDatasource()
        val template = "test/${root}_purge.sql"
        createTemplate(template, "SELECT 1 AS n")
        val childName = "$root/child"
        val childBody = pipelineBody(childName, datasource, template)
        val child = create("pipelines", childBody)
        if (!sole) {
            // The released survivor is fixture state; every inbound draft reference below is SAVED through the API.
            sql(
                "UPDATE pipeline_versions SET status = 'RELEASED', released_at = NOW(), released_by = created_by " +
                    "WHERE pipeline_id = '$child'",
            )
            sql("UPDATE pipelines SET current_version = 1 WHERE id = '$child'")
            val changed = childBody.replace("Draft purge fixture", "Draft purge fixture two")
            request("PUT", "/api/v1/pipelines/$child", changed, hash(child))["status"] shouldBe 200
        }
        val version = if (sole) 1 else 2
        val replacementName = "$root/replacement"
        create("pipelines", pipelineBody(replacementName, datasource, template))
        seedExecution(child, version)
        val before = snapshot(child)

        // Open unpinned first: the POST must recheck when references appear AFTER this GET.
        openPurgeDialog(child, version)
        page.locator("[data-typed-confirm]").count() shouldBe 1
        val parentName = "$root/parent"
        val parentBody = parentBody(parentName, childName, version)
        val parent = create("pipelines", parentBody)
        val boardName = "$root/board"
        val boardBody = boardBody(boardName, childName, version)
        val board = create("dashboards", boardBody)
        seedForeignPins(root, parent, board, parentBody, boardBody)

        val direct = request("POST", "/partials/pipelines/$child/lifecycle/purge?version=$version&confirm=v$version")
        direct["status"] shouldBe 409
        direct["body"].toString() shouldContain "pipeline.version.pinned"
        snapshot(child) shouldBe before
        assertApiRefusals(child, version, before, parentName, boardName)
        assertDialog(child, version, parentName, boardName)
        java.nio.file.Files
            .createDirectories(
                java.nio.file.Path
                    .of("build/browser-evidence"),
            )
        page.screenshot(
            com.microsoft.playwright.Page
                .ScreenshotOptions()
                .setPath(
                    java.nio.file.Path
                        .of("build/browser-evidence/purge462-${if (sole) "sole" else "nonsole"}.png"),
                ),
        )

        // Repoint the parent via the same save path that created the edge.
        request(
            "PUT",
            "/api/v1/pipelines/$parent",
            parentBody
                .replace(childName, replacementName)
                .replace("\"version\":$version", "\"version\":1"),
            hash(parent),
        )["status"] shouldBe 200
        val dashboardOnly = request("DELETE", "/api/v1/pipelines/$child/versions/$version")
        dashboardOnly["status"] shouldBe 409
        dashboardOnly["body"].toString() shouldContain boardName
        snapshot(child) shouldBe before

        // Retain a historical pin on the child beside a live, repointed dashboard version.
        sql(
            "UPDATE dashboard_versions SET status = 'DISCARDED', released_at = NOW(), released_by = created_by, " +
                "discarded_at = NOW(), discarded_by = created_by WHERE dashboard_id = '$board'",
        )
        val repointed = boardBody.replace(childName, replacementName).replace("\"version\":$version", "\"version\":1")
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, body_hash, status, created_by) " +
                "SELECT dashboard_id, 2, ('$repointed'::jsonb - 'name'), 'repointed462', 'DRAFT', created_by " +
                "FROM dashboard_versions WHERE dashboard_id = '$board' AND version = 1",
        )
        if (sole) {
            // Entity purge asks stored dashboard pins; this discarded pin must still be named.
            val historical = request("POST", "/api/v1/pipelines/$child/draft/discard", hash = hash(child))
            historical["status"] shouldBe 409
            historical["body"].toString() shouldContain boardName
            historical["body"].toString() shouldContain "DISCARDED"
            snapshot(child) shouldBe before
            assertDialog(child, version, null, boardName)
            sql("DELETE FROM dashboard_versions WHERE dashboard_id = '$board' AND version = 1")
        }

        // Exact dashboard pins on another version must not block a nonsole purge.
        if (!sole) {
            val pinsSurvivor = repointed.replace(replacementName, childName)
            request("PUT", "/api/v1/dashboards/$board", pinsSurvivor, "repointed462")["status"] shouldBe 200
        }
        openPurgeDialog(child, version)
        page.locator("[data-typed-confirm]").count() shouldBe 1
        request("POST", "/api/v1/pipelines/$child/draft/discard", hash = hash(child))["status"] shouldBe 204
        scalar("SELECT count(*)::text FROM pipeline_executions WHERE pipeline_id = '$child'") shouldBe "0"
        scalar("SELECT count(*)::text FROM pipeline_versions WHERE pipeline_id = '$child' AND version = $version") shouldBe "0"
        scalar("SELECT count(*)::text FROM pipelines WHERE id = '$child'") shouldBe if (sole) "0" else "1"
    }

    private fun openPurgeDialog(
        child: String,
        version: Int,
    ) {
        page.navigate("$baseUrl/pipelines/$child?tab=versions")
        val row = page.locator("tr[data-version-row='$version']")
        row.locator("details.tplx-vmenu summary").click()
        row.locator("[data-verb='pipeline-purge']").click()
        page.locator("#pe-dialog [data-lifecycle-dialog='pipeline-purge']").waitFor()
    }

    private fun assertApiRefusals(
        child: String,
        version: Int,
        before: String,
        parent: String,
        board: String,
    ) {
        listOf(
            request("POST", "/api/v1/pipelines/$child/draft/discard", hash = hash(child)),
            request("DELETE", "/api/v1/pipelines/$child/versions/$version"),
        ).forEach { response ->
            response["status"] shouldBe 409
            response["body"].toString() shouldContain "pipeline.version.pinned"
            response["body"].toString() shouldContain parent
            response["body"].toString() shouldContain board
            response["body"].toString() shouldNotContain "/foreign_"
            snapshot(child) shouldBe before
        }
    }

    private fun assertDialog(
        child: String,
        version: Int,
        parent: String?,
        board: String,
    ) {
        openPurgeDialog(child, version)
        page.locator("[data-purge-pinned]").innerText() shouldContain "cannot be purged"
        parent?.let { page.locator("[data-purge-pinner-pipeline]").innerText() shouldContain it }
        page.locator("[data-purge-pinner-dashboard]").innerText() shouldContain board
        page.locator("[data-purge-pinners]").innerText() shouldNotContain "/foreign_"
        page.locator("[data-typed-confirm]").count() shouldBe 0
    }

    private fun create(
        family: String,
        body: String,
    ): String {
        val response = request("POST", "/api/v1/$family", body)
        check(response["status"] == 201) { "Create $family: $response" }
        return scalar("SELECT id::text FROM $family WHERE name = '${nameOf(body)}'")
    }

    private fun nameOf(body: String): String = requireNotNull(Regex("\"name\":\"([^\"]+)\"").find(body)).groupValues[1]

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        hash: String? = null,
    ): Map<*, *> =
        page.evaluate(
            """async (a) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
              if (a.hash) headers['If-Match'] = a.hash;
              if (a.url.startsWith('/partials/')) headers['HX-Request'] = 'true';
              const response = await fetch(a.url, { method: a.method, headers, credentials: 'same-origin', body: a.body });
              return { status: response.status, body: await response.text() };
            }""",
            mapOf("method" to method, "url" to url, "body" to body, "hash" to hash),
        ) as Map<*, *>

    private fun pipelineBody(
        name: String,
        source: String,
        template: String,
    ): String =
        """{"name":"$name","display_name":"Draft purge fixture","description":"462","parameters":{},"nodes": [""" +
            """{"id":"read","type":"DQL","source":"$source","template":{"id":"$template","version":1},""" +
            """"output":{"target":"caller"},"depends_on":[]}]}"""

    private fun parentBody(
        name: String,
        child: String,
        version: Int,
    ): String =
        """{"name":"$name","display_name":"Parent","description":"462","parameters":{},"nodes": [""" +
            """{"id":"child","type":"PIPELINE","pipeline":{"name":"$child","version":$version},"depends_on":[]}]}"""

    private fun boardBody(
        name: String,
        child: String,
        version: Int,
    ): String =
        """{"name":"$name","display_name":"Board","description":"462",""" +
            """"sources":[{"name":"s1","pipeline":{"name":"$child","version":$version},"parameters":{}}],""" +
            """"visualizations":[],"groups":[],"actions":[],"action_controls":[],"layout":{"grid":[]}}"""

    private fun seedExecution(
        child: String,
        version: Int,
    ) {
        sql(
            "INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json, " +
                "executed_by, triggered_via, root_execution_id) " +
                "SELECT execution.id, p.id, $version, 'SUCCESS', '{}', owner_id, 'REST', execution.id " +
                "FROM pipelines p CROSS JOIN (SELECT gen_random_uuid() AS id) execution WHERE p.id = '$child'",
        )
    }

    private fun seedForeignPins(
        root: String,
        parent: String,
        board: String,
        parentBody: String,
        boardBody: String,
    ) {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES (gen_random_uuid(), '${root}_foreign', 'Foreign')")
        val foreignWorkspace = scalar("SELECT id::text FROM workspaces WHERE name = '${root}_foreign'")
        val parentName = "$root/foreign_parent"
        val foreignParent = parentBody.replace("$root/parent", parentName)
        sql(
            "INSERT INTO pipelines (id, workspace_id, name, display_name, description, owner_id) " +
                "SELECT gen_random_uuid(), '$foreignWorkspace', '$parentName', 'Foreign', '', owner_id FROM pipelines WHERE id = '$parent'",
        )
        sql(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by) " +
                "SELECT p.id, 1, '$foreignParent'::jsonb, 'foreign462', 'DRAFT', p.owner_id FROM pipelines p " +
                "WHERE p.workspace_id = '$foreignWorkspace' AND p.name = '$parentName'",
        )
        val boardName = "$root/foreign_board"
        val foreignBoard = boardBody.replace("$root/board", boardName)
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, created_by) " +
                "SELECT gen_random_uuid(), '$foreignWorkspace', '$boardName', 'Foreign', '', created_by " +
                "FROM dashboards WHERE id = '$board'",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, body_hash, status, created_by) " +
                "SELECT d.id, 1, ('$foreignBoard'::jsonb - 'name'), 'foreign462', 'DRAFT', d.created_by FROM dashboards d " +
                "WHERE d.workspace_id = '$foreignWorkspace' AND d.name = '$boardName'",
        )
    }

    private fun hash(id: String): String =
        scalar("SELECT body_hash FROM pipeline_versions WHERE pipeline_id = '$id' ORDER BY version DESC LIMIT 1")

    private fun snapshot(id: String): String =
        scalar(
            "SELECT jsonb_build_object('pipeline', to_jsonb(p), 'versions', " +
                "(SELECT jsonb_agg(to_jsonb(v) ORDER BY version) FROM pipeline_versions v WHERE pipeline_id = p.id), " +
                "'executions', (SELECT jsonb_agg(to_jsonb(e) ORDER BY execution_id) " +
                "FROM pipeline_executions e WHERE pipeline_id = p.id))::text " +
                "FROM pipelines p WHERE id = '$id'",
        )

    private fun scalar(query: String): String =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rows ->
                    check(rows.next()) { "Missing fixture row" }
                    rows.getString(1)
                }
            }
        }
}
