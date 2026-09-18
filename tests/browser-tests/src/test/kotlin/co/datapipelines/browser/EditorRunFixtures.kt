package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe

/**
 * 151 — the REST-seeded fixtures the arrow-clarity and Start-marker suites run against, on
 * the suite's own Postgres as the source (the same in-page fetch shape
 * [PipelineEditorBoundariesBrowserTest] and [NodeProgressBrowserTest] use: the CSRF cookie and
 * the session are the browser's, so nothing here bypasses the product's auth).
 *
 * Generic shapes only — no dataset facts (the skill-is-generic rule holds for fixtures too):
 * `generate_series` sources, `test/` names, one producer staged into tempdb and read by two
 * consumers, with a DDL node whose only meaning is ORDERING.
 */
internal object EditorRunFixtures {
    /** Registers the suite's Postgres as a source datasource; empty list on success, else the refusal. */
    @Suppress("UNCHECKED_CAST")
    fun registerSourceDatasource(
        page: Page,
        baseUrl: String,
        name: String,
    ): List<String> {
        page.navigate("$baseUrl/datasources")
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        return page.evaluate(
            """async (args) => {
              const headers = JSON.parse(document.body.getAttribute('hx-headers') || '{}');
              const body = new URLSearchParams({
                name: args.name, displayName: 'Arrow clarity source', dialect: 'POSTGRES',
                jdbcUrl: args.url, credentialKind: 'password', username: args.user, password: args.password,
                description: 'generate_series source for the 151 browser proof',
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
    }

    @Suppress("UNCHECKED_CAST")
    fun createTemplate(
        page: Page,
        id: String,
        body: String,
        dialect: String = "POSTGRES",
    ) {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const r = await fetch('/api/v1/templates', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, dialect: args.dialect, display_name: args.id, description: '151 browser fixture', imports: [], body: args.body }) });
                  return { status: r.status, body: r.status === 201 ? '' : await r.text() };
                }""",
                mapOf("id" to id, "dialect" to dialect, "body" to body),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        check(status == 201 || status == 409) { "template $id $status ${result["body"]}" }
    }

    @Suppress("UNCHECKED_CAST")
    fun postPipeline(
        page: Page,
        name: String,
        nodes: String,
    ): String {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  const res = await fetch('/api/v1/pipelines', { method: 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ name: args.name, display_name: args.name, nodes: JSON.parse(args.nodes) }) });
                  const text = await res.text();
                  let id = null;
                  try { const data = JSON.parse(text); id = data.id || (data.data && data.data.id) || null; } catch (e) {}
                  return { status: res.status, id: id, body: text.slice(0, 300) };
                }""",
                mapOf("name" to name, "nodes" to nodes),
            ) as Map<String, Any?>
        (result["status"] as Number).toInt() shouldBe 201
        val id = result["id"] as String?
        check(id != null && Regex("[0-9a-f]{8}-[0-9a-f-]{27}").matches(id)) {
            "the create response did not name the pipeline id: ${result["body"]}"
        }
        return id
    }

    /**
     * 159 addendum (#151) — the owner's shape: THREE DQL cards, two of them tempdb → tempdb
     * (a CTAS, so their output port carries `one statement` + the committed count — the lines
     * that grow a card mid-run), one a staged fetch from the source:
     *
     * ```
     *   stage_calendar ──▶ pairs_a
     *                 ╲──▶ pairs_b
     * ```
     */
    fun createStageChainPipeline(
        page: Page,
        name: String,
        datasource: String,
        rows: Int,
    ): String {
        val slug = name.split("/")[1]
        createTemplate(page, "test/${slug}_cal.sql", "SELECT g AS day_id, g % 7 AS dow FROM generate_series(1, $rows) g")
        createTemplate(page, "test/${slug}_pa.sql", "SELECT dow, COUNT(*) AS c FROM stage_calendar GROUP BY dow", dialect = "H2")
        createTemplate(page, "test/${slug}_pb.sql", "SELECT day_id FROM stage_calendar WHERE dow = 3", dialect = "H2")
        return postPipeline(
            page,
            name,
            """[
              { "id": "stage_calendar", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_cal.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stage_calendar" }, "depends_on": [] },
              { "id": "pairs_a", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_pa.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "pairs_a" }, "depends_on": ["stage_calendar"] },
              { "id": "pairs_b", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_pb.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "pairs_b" }, "depends_on": ["stage_calendar"] }
            ]""",
        )
    }

    /**
     * ONE producer, TWO consumers, ONE ordering-only DDL:
     *
     * ```
     *   stg ──▶ mk_index ──▶ by_a     by_a depends on stg AND mk_index
     *    │ ╲       ╲──────▶ by_b     by_b depends on stg AND mk_index
     *    ╲  ╲────────────▶ by_a
     *     ╲──────────────▶ by_b
     *   mk_index: a DDL on tempdb — no rows, only "after stg"
     * ```
     * `stg` stages [rows] rows from Postgres into `tempdb.stg`; `by_a` stages a summary into
     * `tempdb.by_a`; `by_b` is the caller node. So the run has one producer with THREE
     * dependents (the fan-out the issue names), a DDL ordering into both consumers, a caller
     * output and two consumers writing after the DDL — every shape the brief asks the graph
     * to keep honest. Both consumers wait for the DDL on purpose: a DQL reading `stg` while
     * `CREATE INDEX … ON stg` runs hits H2's table-lock timeout
     * (`Timeout trying to lock table "stg"`, measured 2026-09-17 on this suite) and fails
     * the node — real engine behaviour, recorded as a product follow-up, not a fixture to
     * race against.
     */
    fun createFanOutPipeline(
        page: Page,
        name: String,
        datasource: String,
        rows: Int,
    ): String {
        val slug = name.split("/")[1]
        createTemplate(page, "test/${slug}_stg.sql", "SELECT g AS n, g % 7 AS bucket FROM generate_series(1, $rows) g")
        // A fixed identifier: the slug carries dashes, and tempdb is per execution, so it cannot collide.
        createTemplate(page, "test/${slug}_idx.sql", "CREATE INDEX idx_stg_bucket ON stg(bucket)", dialect = "H2")
        createTemplate(page, "test/${slug}_a.sql", "SELECT bucket, COUNT(*) AS c FROM stg GROUP BY bucket", dialect = "H2")
        createTemplate(page, "test/${slug}_b.sql", "SELECT n FROM stg WHERE bucket = 3", dialect = "H2")
        return postPipeline(
            page,
            name,
            """[
              { "id": "stg", "type": "DQL", "source": "$datasource", "template": { "id": "test/${slug}_stg.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "stg" }, "depends_on": [] },
              { "id": "mk_index", "type": "DDL", "source": "tempdb", "template": { "id": "test/${slug}_idx.sql", "version": 1 },
                "depends_on": ["stg"] },
              { "id": "by_a", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_a.sql", "version": 1 },
                "output": { "target": "tempdb", "table": "by_a" }, "depends_on": ["stg", "mk_index"] },
              { "id": "by_b", "type": "DQL", "source": "tempdb", "template": { "id": "test/${slug}_b.sql", "version": 1 },
                "output": { "target": "caller" }, "depends_on": ["stg", "mk_index"] }
            ]""",
        )
    }
}
