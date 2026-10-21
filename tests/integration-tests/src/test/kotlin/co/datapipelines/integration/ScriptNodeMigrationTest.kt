package co.datapipelines.integration

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * V50 (`script_node_type`) applied to a **populated** pre-V50 database, asserted at the row
 * level — the [KeysV2MigrationTest] shape. The shipped scripts are driven directly, V1
 * through V49, then pre-V50 rows in the old shape (a RELEASED and a DRAFT pipeline version
 * carrying a `TRANSFORM` node, hashed by V6's expression; a RELEASED control with no
 * transform node), then V50 — and every row the migration produces is named here.
 *
 * What the rename (owner ruling 2026-10-20, no alias, no deprecation window) promises, and
 * what each test holds it to:
 *
 * - the typed field moves: every stored node with `"type": "TRANSFORM"` reads `"SCRIPT"`
 *   afterwards, in RELEASED and DRAFT versions alike;
 * - prose survives: the node's `id` and the description that merely mentions the word
 *   TRANSFORM are byte-identical — the migration rewrites the typed field, never text;
 * - the untouched control row is byte-identical in body and hash — the rewrite is scoped to
 *   the rows that carry a transform node;
 * - the hash follows the body: the re-stamped `body_hash` equals sha256 over the NEW
 *   `body_json::text` (V6's expression) and differs from the old one — a rewrite without a
 *   re-stamp would fail every release precondition that pins a hash;
 * - node order is preserved (the rebuild is ordered, not aggregated from a set);
 * - V33's blocks constraint now answers to `chk_script_blocks` — `chk_transform_blocks` is
 *   gone from `template_versions` (the V33 file itself stays byte-identical; migrations are
 *   immutable). The violation NAME under the new label is asserted where the template
 *   fixtures live, in `ScriptBlocksMigrationTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScriptNodeMigrationTest {
    private val actor: UUID = UUID.randomUUID()
    private val ws: UUID = UUID.randomUUID()
    private val ordersPipeline: UUID = UUID.randomUUID()
    private val plainPipeline: UUID = UUID.randomUUID()

    private val transformedBody: String =
        """
        {
          "schema_version": 1,
          "name": "test/v50_orders",
          "display_name": "V50 Orders",
          "description": "seed",
          "parameters": {},
          "nodes": [
            {
              "id": "stage_orders",
              "description": "stage the orders",
              "type": "DQL",
              "datasource": "orders_db",
              "body": "SELECT id FROM orders",
              "output": { "target": "caller" },
              "depends_on": []
            },
            {
              "id": "shape_orders",
              "description": "TRANSFORM the staged orders into shapes",
              "type": "TRANSFORM",
              "template": { "id": "test/shape", "version": 1 },
              "inputs": { "orders": "stage_orders" },
              "output": { "target": "caller" },
              "depends_on": ["stage_orders"]
            }
          ]
        }
        """.trimIndent()

    private val draftBody: String =
        """
        {
          "schema_version": 1,
          "name": "test/v50_orders",
          "display_name": "V50 Orders",
          "description": "seed draft",
          "parameters": {},
          "nodes": [
            {
              "id": "draft_shape",
              "description": "a draft that also transforms",
              "type": "TRANSFORM",
              "template": { "id": "test/shape", "version": 2 },
              "inputs": { "orders": "stage_orders" },
              "output": { "target": "caller" },
              "depends_on": []
            }
          ]
        }
        """.trimIndent()

    private val controlBody: String =
        """
        {
          "schema_version": 1,
          "name": "test/v50_plain",
          "display_name": "V50 Plain",
          "description": "seed control",
          "parameters": {},
          "nodes": [
            {
              "id": "stage_plain",
              "description": "no transform node here",
              "type": "DQL",
              "datasource": "orders_db",
              "body": "SELECT 1",
              "output": { "target": "caller" },
              "depends_on": []
            }
          ]
        }
        """.trimIndent()

    // Captured between the seed and the migration, asserted after it.
    private var preHashTransformed: String = ""
    private var preHashDraft: String = ""
    private var preHashControl: String = ""
    private var preControlBody: String = ""

    private val migrated by lazy {
        MIGRATION_PATHS_PRE_V50.forEach { execute(repoFile(it).readText()) }
        seedPreV50Rows()
        capturePreV50State()
        execute(repoFile(V50_PATH).readText())
        true
    }

    // ------------------------------------------------------------------ the rewrite

    @Test
    @Order(1)
    fun `the typed field moves to SCRIPT, the prose stays`() {
        migrated shouldBe true
        // The typed field, in a RELEASED version — the description that merely mentions the
        // word TRANSFORM and the node id are byte-identical.
        nodeRow(ordersPipeline, 1, "shape_orders") shouldBe
            listOf("SCRIPT", "TRANSFORM the staged orders into shapes", "test/shape")
        // The typed field, in a DRAFT version — drafts are rewritten too.
        nodeRow(ordersPipeline, 2, "draft_shape")[0] shouldBe "SCRIPT"
        // The DQL sibling is untouched.
        nodeRow(ordersPipeline, 1, "stage_orders")[0] shouldBe "DQL"
    }

    @Test
    @Order(2)
    fun `the control version is byte-identical in body and hash`() {
        migrated shouldBe true
        val row = versionRow(plainPipeline, 3)
        row[0] shouldBe preControlBody
        row[1] shouldBe preHashControl
    }

    @Test
    @Order(3)
    fun `the hash follows the rewritten body`() {
        migrated shouldBe true
        // Re-stamped to sha256 over the NEW body text, on every row in the table.
        query(
            "SELECT COUNT(*) FROM pipeline_versions" +
                " WHERE body_hash IS DISTINCT FROM encode(sha256(convert_to(body_json::text, 'UTF8')), 'hex')",
        ) { it.getLong(1) }.single() shouldBe 0L
        // And it CHANGED — the rewrite really moved the text the hash pins.
        versionHash(ordersPipeline, 1) shouldNotBe preHashTransformed
        versionHash(ordersPipeline, 2) shouldNotBe preHashDraft
    }

    @Test
    @Order(4)
    fun `node order is preserved through the rebuild`() {
        migrated shouldBe true
        query(
            "SELECT t.n->>'id' FROM pipeline_versions v," +
                " jsonb_array_elements(v.body_json->'nodes') WITH ORDINALITY AS t(n, ord)" +
                " WHERE v.pipeline_id = '$ordersPipeline' AND v.version = 1 ORDER BY t.ord",
        ) { it.getString(1) } shouldBe listOf("stage_orders", "shape_orders")
    }

    @Test
    @Order(5)
    fun `the blocks constraint answers to chk_script_blocks`() {
        migrated shouldBe true
        query(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'template_versions'::regclass AND contype = 'c'",
        ) { it.getString(1) }.let { names ->
            names shouldContain "chk_script_blocks"
            names.any { it == "chk_transform_blocks" } shouldBe false
        }
    }

    // ------------------------------------------------------------------ the harness

    private fun nodeRow(
        pipeline: UUID,
        version: Int,
        nodeId: String,
    ): List<String> =
        query(
            "SELECT n->>'type', n->>'description', n->'template'->>'id' FROM pipeline_versions v," +
                " jsonb_array_elements(v.body_json->'nodes') n" +
                " WHERE v.pipeline_id = '$pipeline' AND v.version = $version AND n->>'id' = '$nodeId'",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }.single()

    private fun versionRow(
        pipeline: UUID,
        version: Int,
    ): List<String> =
        query(
            "SELECT body_json::text, body_hash FROM pipeline_versions" +
                " WHERE pipeline_id = '$pipeline' AND version = $version",
        ) { listOf(it.getString(1), it.getString(2)) }.single()

    private fun versionHash(
        pipeline: UUID,
        version: Int,
    ): String = versionRow(pipeline, version)[1]

    private fun seedPreV50Rows() {
        execute(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES ('$actor', 'v50@acme.test', 'V50', 'test', 'v50-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$ws', 'acme', 'Acme')",
        )
        execute(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, current_version, workspace_id)
            VALUES ('$ordersPipeline', 'test/v50_orders', 'V50 Orders', 'seed', '$actor', 2, '$ws'),
                   ('$plainPipeline', 'test/v50_plain', 'V50 Plain', 'seed', '$actor', 3, '$ws')
            """.trimIndent(),
        )
        seedVersion(ordersPipeline, 1, transformedBody, "RELEASED")
        seedVersion(ordersPipeline, 2, draftBody, "DRAFT")
        seedVersion(plainPipeline, 3, controlBody, "RELEASED")
    }

    private fun seedVersion(
        pipeline: UUID,
        version: Int,
        body: String,
        status: String,
    ) {
        // The V6 backfill expression, applied to the inserted row: hash over the canonical
        // jsonb text of exactly what lands in the column.
        execute(
            """
            INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by, status, body_hash, released_at, released_by)
            SELECT '$pipeline', $version, '${body.replace("'", "''")}'::jsonb, '$actor', '$status',
                   encode(sha256(convert_to(('${body.replace("'", "''")}'::jsonb)::text, 'UTF8')), 'hex'), NOW(), '$actor'
              FROM pipelines p WHERE p.id = '$pipeline'
            """.trimIndent(),
        )
    }

    private fun capturePreV50State() {
        preHashTransformed = versionHash(ordersPipeline, 1)
        preHashDraft = versionHash(ordersPipeline, 2)
        preHashControl = versionHash(plainPipeline, 3)
        preControlBody = versionRow(plainPipeline, 3)[0]
    }

    private fun connection(): Connection = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    private fun execute(sql: String) {
        connection().use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun <T> query(
        sql: String,
        row: (ResultSet) -> T,
    ): List<T> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(row(rs)) }
                }
            }
        }

    private companion object {
        const val MIGRATION_DIR = "modules/app/src/main/resources/db/migration"
        const val V50_PATH = "$MIGRATION_DIR/V50__script_node_type.sql"

        /** V1 through V49, in Flyway's own order — spelled out, never globbed (the V37 test's rule). */
        val MIGRATION_PATHS_PRE_V50 =
            listOf(
                "V1__initial_schema.sql",
                "V2__datasource_introspection_include_schemas.sql",
                "V3__execution_lineage.sql",
                "V4__workspaces_rekey.sql",
                "V5__local_password_auth.sql",
                "V6__version_lifecycle.sql",
                "V7__hierarchical_template_names.sql",
                "V8__typed_templates.sql",
                "V9__datasource_last_test_outcome.sql",
                "V10__datasource_credential_key_version.sql",
                "V11__published_endpoints.sql",
                "V12__folder_required.sql",
                "V13__datasource_credential_kind.sql",
                "V14__lake_dialect.sql",
                "V15__lake_tables.sql",
                "V16__lake_template_dialect.sql",
                "V17__api_key_kind_server.sql",
                "V18__draft_first_create.sql",
                "V19__version_lifecycle.sql",
                "V20__version_write_surface.sql",
                "V21__execution_heartbeat.sql",
                "V22__lake_table_view_errors.sql",
                "V23__rbac_core.sql",
                "V24__workspace_invitations.sql",
                "V25__learned_facts.sql",
                "V26__learned_facts_workspace_rule_refs.sql",
                "V27__mail_sends.sql",
                "V28__pipeline_check_runs.sql",
                "V29__workspace_roles.sql",
                "V30__executions_executed_by.sql",
                "V31__login_minted_mcp_keys.sql",
                "V32__mcp_key_sealed_copy_cleared.sql",
                "V33__transform_template_blocks.sql",
                "V34__key_identities_and_roles.sql",
                "V36__template_implements.sql",
                "V37__keys_v2_robot_members.sql",
                "V38__scheduler_core.sql",
                "V39__parameter_sets.sql",
                "V40__schedule_run_execution_timing.sql",
                "V41__retire_pre_rep5_endpoint_rows.sql",
                "V42__visualizations_and_dashboards.sql",
                "V43__dashboard_refreshes.sql",
                "V44__visualization_test_capabilities.sql",
                "V45__dashboard_key_kind.sql",
                "V46__dashboard_pins_gin.sql",
                "V47__release_result_columns.sql",
                "V48__parameter_evaluations.sql",
                "V49__schedule_notification_settings.sql",
            ).map { "$MIGRATION_DIR/$it" }

        /** Walks up from the working directory — the same locator the module fixtures use. */
        fun repoFile(relativePath: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relativePath)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error("$relativePath not found walking up from ${File("").absolutePath}")
        }

        /**
         * A scratch database: this suite builds the schema PART-WAY on purpose (V1–V49, pre-V50
         * rows, then V50) — the V50 boundary is the subject.
         */
        val db = SharedE2e.scratchDatabase("pre_v50_script_node")
    }
}
