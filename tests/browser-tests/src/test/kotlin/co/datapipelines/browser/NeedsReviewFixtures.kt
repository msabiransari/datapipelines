package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.matchers.shouldBe
import java.sql.DriverManager
import java.util.UUID

/**
 * 7e's `needs_review` seeding (#533, transform-nodes design §8.2), shared by the suites that read
 * the marker on the real page: [ScriptImplementsBrowserTest] (the walk) and
 * [BrowserResourceRowBrowserTest] (#701, the three-badge row at the browser's minimum width).
 *
 * The rule is written by SQL: `semantics_record` is an MCP tool and the MCP key reaches `/mcp`
 * only (owner ruling B2); the recording path is `ScriptImplementsE2eTest`'s. The rows are what
 * `LearnedFactRecorder` writes for a WORKSPACE definition (no refs, the empty fingerprint), and
 * the supersession is its two writes: the successor carrying `supersedes`, the predecessor
 * retired with reason `superseded`. The script is written over REST in the session (no key
 * reaches /api/v1), so the §2.3 citation check runs on every write.
 */
internal object NeedsReviewFixtures {
    private const val ORDER_ID = """{"name":"order_id","type":"INTEGER"}"""

    /**
     * A WORKSPACE definition in [workspace] (a datasource named [datasource] must exist), as the
     * recorder writes it; with [supersedes], ALSO the recorder's second write — the predecessor
     * retired `superseded` — in the same transaction. Returns the new rule's id.
     */
    fun recordRule(
        email: String,
        datasource: String,
        text: String,
        supersedes: UUID?,
        workspace: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection.autoCommit = false
            connection
                .prepareStatement(
                    """
                    INSERT INTO learned_facts (id, scope, workspace_id, datasource_name, kind, fact, refs_json, trust,
                                               schema_fingerprint, recorded_by, recorded_via, recorded_in, supersedes)
                    SELECT CAST(? AS uuid), 'WORKSPACE', CAST(? AS uuid), ?, 'definition', ?, '[]'::jsonb, 'asserted', '', u.id, 'session',
                           CAST(? AS uuid), CAST(? AS uuid)
                      FROM users u WHERE u.email = ?
                    """.trimIndent(),
                ).use { insert ->
                    insert.setObject(1, id)
                    insert.setObject(2, workspace)
                    insert.setString(3, datasource)
                    insert.setString(4, text)
                    insert.setObject(5, workspace)
                    insert.setObject(6, supersedes)
                    insert.setString(7, email)
                    insert.executeUpdate() shouldBe 1
                }
            if (supersedes != null) {
                connection
                    .prepareStatement(
                        "UPDATE learned_facts SET trust = 'retired', retired_at = NOW(), retired_reason = 'superseded' WHERE id = ?",
                    ).use { retire ->
                        retire.setObject(1, supersedes)
                        retire.executeUpdate() shouldBe 1
                    }
            }
            connection.commit()
        }
        return id
    }

    /**
     * A jsonata template citing [rule]: created (version 1, released by the server) when [hash]
     * is null, else its draft written over `If-Match: hash`. [revision] above 1 renames the one
     * test case, so each write is a changed version. Returns the response's `data` JSON text.
     */
    @Suppress("UNCHECKED_CAST")
    fun writeCitingScript(
        page: Page,
        id: String,
        rule: UUID,
        revision: Int = 1,
        hash: String? = null,
    ): String {
        val caseName = if (revision > 1) "empty input $revision" else "empty input"
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' };
                  if (args.hash) headers['If-Match'] = args.hash;
                  const r = await fetch('/api/v1/templates', { method: args.hash ? 'PUT' : 'POST', credentials: 'same-origin', headers,
                    body: JSON.stringify({ id: args.id, type: 'jsonata', display_name: args.id, description: '7e browser fixture',
                      body: args.body, contract: JSON.parse(args.contract), invariants: [], tests: JSON.parse(args.tests),
                      implements: [args.rule] }) });
                  const text = await r.text();
                  return { status: r.status, body: text };
                }""",
                mapOf(
                    "id" to id,
                    "hash" to hash,
                    "body" to """[ inputs.orders[customer_id != null].{ "order_id": order_id } ]""",
                    "contract" to
                        """{"mode":"table","inputs":{"orders":{"kind":"table","columns":[$ORDER_ID,{"name":"customer_id","type":"STRING","nullable":true}]}},
                            "output":{"kind":"table","columns":[$ORDER_ID]}}""",
                    "tests" to """[{"name":"$caseName","input":{"inputs":{"orders":[]}},"expect":{"output":[]}}]""",
                    "rule" to rule.toString(),
                ),
            ) as Map<String, Any?>
        val status = (result["status"] as Number).toInt()
        check(status == if (hash == null) 201 else 200) {
            "script template $id revision $revision (If-Match $hash): $status ${result["body"]}"
        }
        return result["body"] as String
    }
}
