package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * The migration test required by module-structure.md §5.10: Flyway runs against a
 * clean Postgres container and the resulting schema is asserted against
 * metadata-db.md §4/§5.
 *
 * It boots the application rather than driving the Flyway API directly, for two
 * reasons. First, that is the path production actually takes — Flyway is applied by
 * Spring Boot autoconfiguration on startup (metadata-db.md §7.2), so testing the API
 * in isolation would verify a code path nothing runs. Second, §3.1 rule 2 confines
 * the Flyway dependency to `app`; a compile-time Flyway import here would put it in
 * a second module.
 *
 * Assertions are plain JDBC over `information_schema` / `pg_catalog` deliberately:
 * they describe the schema the way the spec does, so a reviewer can diff this file
 * against metadata-db.md §4 line by line.
 */
@SpringBootTest(classes = [DatapipelinesApplication::class])
@Suppress("LargeClass") // one class per shipped migration family, asserted schema-line-by-schema-line (§5.10)
class FlywayMigrationIntegrationTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `every shipped migration is recorded as applied and successful`() {
        val rows =
            query(
                "SELECT version || '|' || description || '|' || success FROM flyway_schema_history ORDER BY installed_rank",
            ) { it.getString(1) }

        rows shouldContainExactly
            listOf(
                "1|initial schema|true",
                "2|datasource introspection include schemas|true",
                "3|execution lineage|true",
                "4|workspaces rekey|true",
                "5|local password auth|true",
                "6|version lifecycle|true",
                "7|hierarchical template names|true",
                "8|typed templates|true",
                "9|datasource last test outcome|true",
                // 068 (renumbered V11→V10 at merge: it landed before 066, which now takes V11).
                "10|datasource credential key version|true",
                // 074 — published endpoints (066 is shelved, so this takes V11).
                "11|published endpoints|true",
                // 077 — the §4.1 folder requirement's deploy gate. No DDL: a DO-block
                // pre-check only, which is why no other assertion in this file moves.
                "12|folder required|true",
                // 087 §A — the credential-kind rename + columns + three CHECKs.
                "13|datasource credential kind|true",
                // 087 §C — LAKE joins chk_datasource_dialect. No data change.
                "14|lake dialect|true",
                // 089 §A — the dp-lake catalog: one table, one named UNIQUE, one CHECK.
                "15|lake tables|true",
                // 089 §F — LAKE joins template_versions.chk_dialect (the 088 showcase's
                // LAKE template insert, and every LAKE DQL template, violated the V8 set).
                "16|lake template dialect|true",
                // 091 §A — `server` joins chk_api_keys_kind. No data change: no existing row
                // can hold a value the CHECK did not admit until now.
                "17|api key kind server|true",
                // 099 — D55: `pipelines.current_version` / `templates.current_version` become
                // nullable and lose their DEFAULT 0 (NULL = never released). The two UPDATEs are
                // expected to touch zero rows — no writer ever left the sentinel behind.
                "18|draft first create|true",
                // 101 — the version lifecycle: discard stamps + the is_deleted retirement.
                "19|version lifecycle|true",
                // 102 — the write-surface stamps on both version tables (owner ruling
                // 2026-09-09): created_via / updated_via, CHECK'd, defaulting 'session'.
                "20|version write surface|true",
                // 108 §D — the instance heartbeat the crash sweep reads (V20 was taken by 102 at merge).
                "21|execution heartbeat|true",
                // 109 §A — the per-table lake view-creation outcome columns (both NULL = healthy).
                "22|lake table view errors|true",
                // 112 — RBAC round 1: capability onto the membership, workspace deactivation,
                // the `demo` seed, and the datasource grant table that replaced "global".
                "23|rbac core|true",
                // 113 — workspace invitations: the email-keyed membership-waiting-for-its-user
                // (auth.md §4.6); the login path materialises it.
                "24|workspace invitations|true",
            )
    }

    @Test
    fun `V23 puts capability on the membership and visibility in the grant table`() {
        // The column inventory, read from the SHIPPED database: the three flags exist, the
        // `role` column is gone, and the datasource binding split into ownership + grants.
        columnsOf("workspace_members") shouldContainExactly
            listOf("admin", "author", "joined_at", "promoter", "user_id", "workspace_id")

        columnsOf("datasource_workspaces") shouldContainExactly
            listOf("datasource_name", "granted_at", "granted_by", "workspace_id")

        columnsOf("datasources").contains("workspace_id") shouldBe false
        columnsOf("datasources").contains("owner_workspace_id") shouldBe true

        columnsOf("workspaces").contains("deactivated_at") shouldBe true
        columnsOf("workspaces").contains("deactivated_by") shouldBe true
    }

    @Test
    fun `V23's two indexes exist - the last-admin count and the selectable-workspace lookup`() {
        // Named, because both back a rule rather than a query someone happened to write: the
        // partial admin index is what makes the last-admin count cheap, and the active-workspace
        // index is what every selection reads.
        query(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'" +
                " AND indexname IN ('idx_workspace_members_admins', 'idx_workspaces_active'," +
                " 'idx_datasource_workspaces_workspace') ORDER BY 1",
        ) { it.getString(1) } shouldContainExactly
            listOf("idx_datasource_workspaces_workspace", "idx_workspace_members_admins", "idx_workspaces_active")
    }

    /** Every column of [table] in the shipped database, name order. */
    private fun columnsOf(table: String): List<String> =
        query(
            "SELECT column_name FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$table' ORDER BY 1",
        ) { it.getString(1) }

    @Test
    fun `V24 creates the invitation table keyed by workspace and normalized email`() {
        // The column inventory, read from the SHIPPED database (113, auth.md §4.6): an
        // invitation is a membership waiting for its user — the member's flags, the inviter,
        // and the email in the one canonical form, keyed so one email holds one invitation
        // per workspace and a re-invite REPLACES the flags (the latest admin decision wins).
        columnsOf("workspace_invitations") shouldContainExactly
            listOf("admin", "author", "email", "invited_at", "invited_by", "promoter", "workspace_id")

        // The database keeps the two invariants the service normalises for: admin implies
        // author (the membership's own CHECK, mirrored), and no row stores a mixed-case email.
        query(
            "SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace" +
                " AND conrelid = 'workspace_invitations'::regclass AND contype = 'c' ORDER BY 1",
        ) { it.getString(1) } shouldContainExactly
            listOf("chk_workspace_invitation_admin_authors", "chk_workspace_invitation_email_lower")

        // The materialise lookup walks by email; the listing walks the PK prefix.
        query(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'" +
                " AND tablename = 'workspace_invitations' ORDER BY 1",
        ) { it.getString(1) } shouldContainExactly
            listOf("idx_workspace_invitations_email", "workspace_invitations_pkey")
    }

    @Test
    fun `V25 creates the learned facts table with its closed kind list, checked and cascading`() {
        // 118 (learned-semantic-layer §3): the column inventory, read from the SHIPPED database.
        columnsOf("learned_facts") shouldContainExactly
            listOf(
                "datasource_name", "evidence_sql", "evidence_summary", "fact", "id", "kind", "recorded_at", "recorded_by",
                "recorded_in", "recorded_via", "refs_json", "retired_at", "retired_reason", "schema_fingerprint", "scope",
                "source_pipeline_id", "source_version", "supersedes", "trust", "verified_at", "verified_by", "workspace_id",
            )

        // The kind CHECK is the §4 list — proven by its definition text, because enums.md §19
        // and the Kotlin enum are drift-tested against this exact list.
        query(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_learned_facts_kind'",
        ) { it.getString(1) }.single() shouldContain
            "'unit'::text, 'time_zone'::text, 'sampling'::text, 'grain'::text, 'window'::text, 'enum_meaning'::text, " +
            "'join'::text, 'caveat'::text, 'format'::text, 'definition'::text, 'exclusion'::text, 'preference'::text"

        // D-S11: a datasource delete cascades its facts; a purged source pipeline only detaches.
        query(
            "SELECT confdeltype FROM pg_constraint WHERE conrelid = 'learned_facts'::regclass AND contype = 'f'" +
                " AND confrelid IN ('datasources'::regclass, 'pipelines'::regclass) ORDER BY confrelid::regclass::text",
        ) { it.getString(1) } shouldContainExactly listOf("c", "n")
    }

    @Test
    fun `V20 stamps the write surface on both version tables, checked and defaulted`() {
        // The ruling's schema half, read from the SHIPPED database: the columns exist on BOTH
        // tables, carry the default (existing rows "backfill" by it), and the CHECK admits
        // exactly the three surfaces — an INSERT proves it, not the constraint's text (the
        // V17 rule: a CHECK that parses but does not bind is invisible to a text assertion).
        val columns =
            query(
                """
                SELECT table_name || '|' || column_name || '|' || is_nullable || '|' || column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND column_name IN ('created_via', 'updated_via')
                   AND table_name IN ('pipeline_versions', 'template_versions')
                  ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "pipeline_versions|created_via|NO|'session'::text",
                "pipeline_versions|updated_via|NO|'session'::text",
                "template_versions|created_via|NO|'session'::text",
                "template_versions|updated_via|NO|'session'::text",
            )

        // The CHECK: the three surfaces land, a fourth is refused — on BOTH tables.
        assertAll(
            { viaAccepted("pipeline_versions", "mcp") shouldBe true },
            { viaAccepted("pipeline_versions", "api_key") shouldBe true },
            { viaAccepted("pipeline_versions", "cli") shouldBe false },
            { viaAccepted("template_versions", "mcp") shouldBe true },
            { viaAccepted("template_versions", "session") shouldBe true },
            { viaAccepted("template_versions", "agent") shouldBe false },
        )
    }

    /**
     * V20's insert-probe: does the via CHECK admit [value] on [table]? The probe's FK targets
     * are seeded REAL rows first (a bogus UUID would fail the FK, not the CHECK — the exact
     * confusion the V17 shape guards against by naming the constraint). Rolled back either way.
     */
    @Suppress("NestedBlockDepth") // the probe is one transaction's shape: seed, insert, classify, roll back
    private fun viaAccepted(
        table: String,
        value: String,
    ): Boolean {
        val probeUser = UUID.randomUUID()
        val pipelineId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active)" +
                            " VALUES ('$probeUser', 'v20-probe@datapipelines.test', 'V20 probe', 'test', 'v20-probe', TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)" +
                            " VALUES ('$pipelineId', 'v20/probe', 'V20 probe', '', '$probeUser'," +
                            " (SELECT id FROM workspaces LIMIT 1), NULL)",
                    )
                    statement.execute(
                        "INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by)" +
                            " VALUES ('${UUID.randomUUID()}', 'v20-probe-tpl', 'V20 probe', '', NULL," +
                            " (SELECT id FROM workspaces LIMIT 1), '$probeUser')",
                    )
                }
                when (table) {
                    "pipeline_versions" -> {
                        conn
                            .prepareStatement(
                                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, created_by, created_via)" +
                                    " VALUES ('$pipelineId', 99, '{}', 'probe-hash', '$probeUser', ?)",
                            ).use {
                                it.setString(1, value)
                                it.executeUpdate()
                            }
                    }

                    else -> {
                        conn
                            .prepareStatement(
                                // template_versions is keyed by the templates SURROGATE (V4); the
                                // probe seeds the parent row in the same transaction.
                                "INSERT INTO template_versions" +
                                    " (template_id, version, body, body_hash, engine, dialect, created_by, created_via)" +
                                    " VALUES ((SELECT id FROM templates WHERE name = 'v20-probe-tpl'), 99, 'x'," +
                                    " 'probe-hash', 'freemarker', 'POSTGRES', '$probeUser', ?)",
                            ).use {
                                it.setString(1, value)
                                it.executeUpdate()
                            }
                    }
                }
                return true
            } catch (e: SQLException) {
                // Only the CHECK's own refusal is the assertion — anything else (an FK, a
                // syntax slip) is an unexpected failure and must not read as "refused".
                check(e.message.orEmpty().contains("chk_") || e.message.orEmpty().contains("via")) {
                    "unexpected SQL failure: ${e.message}"
                }
                return false
            } finally {
                conn.rollback()
                conn.autoCommit = true
            }
        }
    }

    @Test
    fun `V17 lets api_keys hold the server kind, and still refuses an unknown one`() {
        // The CHECK is the database's half of §7.7 (metadata-db §4.10: a bad kind reaching this
        // table would break every credential decision made from it). Asserted by INSERTING, not
        // by reading the constraint's text — a constraint that parses and does not admit the
        // value is exactly the failure a text assertion cannot see. Both rows are rolled back.
        assertAll(
            { kindAccepted("server") shouldBe true },
            { kindAccepted("endpoint") shouldBe true },
            { kindAccepted("wizard") shouldBe false },
        )
    }

    @Test
    fun `V18 makes both current_version pointers nullable with no default`() {
        // D55's schema half, read from the SHIPPED database rather than from the migration text:
        // NULL is how "never released" is stored, and the DEFAULT is GONE — a column whose absence
        // means "not released" is one an accidental INSERT could get right by luck.
        val columns =
            query(
                """
                SELECT table_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND column_name = 'current_version'
                   AND table_name IN ('pipelines', 'templates')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "pipelines|YES|NONE",
                "templates|YES|NONE",
            )
    }

    /** True when `api_keys.kind` accepts [kind]; the probe row is always rolled back. */
    private fun kindAccepted(kind: String): Boolean =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active)" +
                            " VALUES (\'$KIND_PROBE_USER\', \'v17-probe@datapipelines.test\', \'V17 probe\'," +
                            " \'test\', \'v17-probe\', TRUE) ON CONFLICT (id) DO NOTHING",
                    )
                    statement.execute(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id, kind)" +
                            " VALUES (\'dpk_V17PROBE01\', \'$KIND_PROBE_USER\', \'probe\', \'h\', \'{}\'::text[]," +
                            " (SELECT id FROM workspaces LIMIT 1), \'$kind\')",
                    )
                }
                true
            } catch (e: java.sql.SQLException) {
                // The CHECK's own refusal IS the assertion — not an unexpected failure.
                check(e.message.orEmpty().contains("chk_api_keys_kind")) { "unexpected SQL failure: ${e.message}" }
                false
            } finally {
                connection.rollback()
            }
        }

    @Test
    fun `V9 adds the last-test-outcome columns to datasources`() {
        // datasources §8.1B (061/T84): three additive, NULLABLE columns with no default —
        // NULL across all three is "never tested", which is the truthful state of every
        // pre-V9 row. A default would have to invent an outcome nobody observed.
        //
        // They are the ONE documented exception to metadata-db §2's "every UPDATE sets
        // updated_at": recordTestOutcome writes these three and nothing else, because a test
        // outcome is an observation ABOUT the datasource, not a change TO it — which is what
        // keeps §8A.3 rule 1's byte-untouched guarantee mechanically checkable.
        val columns =
            query(
                """
                SELECT column_name || '|' || data_type || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'datasources'
                   AND column_name IN ('last_test_at', 'last_test_ok', 'last_test_message')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "last_test_at|timestamp with time zone|YES|NONE",
                "last_test_message|text|YES|NONE",
                "last_test_ok|boolean|YES|NONE",
            )
    }

    @Test
    fun `V5 adds the local password auth columns to users`() {
        // metadata-db §4.1 (auth.md §5A): password_hash NULL = OIDC-only account;
        // must_change_password forces the first-login change; failed_login_count and
        // locked_until back the per-account lockout. All additive — existing rows keep
        // password_hash NULL and behave exactly as before.
        val columns =
            query(
                """
                SELECT column_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'users'
                   AND column_name IN ('password_hash', 'password_changed_at', 'must_change_password',
                                       'failed_login_count', 'locked_until')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "failed_login_count|NO|0",
                "locked_until|YES|NONE",
                "must_change_password|NO|false",
                "password_changed_at|YES|NONE",
                "password_hash|YES|NONE",
            )
    }

    @Test
    fun `V8 adds the typed-template column and relaxes dialect to nullable`() {
        // template-hierarchy-design §5.1 (046): `type` is NOT NULL backfilled 'sql' — every
        // pre-V8 template is SQL by construction, so the default IS the truthful value — and
        // `dialect` becomes nullable, because an `html` template declares none. The pairing
        // (sql ⇔ dialect present, html ⇔ dialect null) is enforced by chk_type_dialect,
        // asserted by name in the CHECK-constraint set above and by shape in the templates
        // module's TypedTemplatesMigrationTest.
        val columns =
            query(
                """
                SELECT column_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'template_versions'
                   AND column_name IN ('type', 'dialect')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "dialect|YES|NONE",
                "type|NO|'sql'::text",
            )
    }

    @Test
    fun `creates exactly the seventeen tables of metadata-db §4`() {
        val tables =
            query(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY tablename
                """.trimIndent(),
            ) { it.getString(1) }

        // In-any-order for the same collation reason as the index assertion below.
        tables shouldContainExactlyInAnyOrder
            listOf(
                "api_keys",
                "audit_log",
                "datasource_workspaces",
                "datasources",
                // 074 (V11) — the published-endpoint registry and its key bindings.
                "endpoint_key_bindings",
                "execution_events",
                // 089 §A (V15) — the dp-lake catalog.
                "lake_tables",
                // 118 (V25) — the learned semantic layer.
                "learned_facts",
                "pipeline_executions",
                "pipeline_versions",
                "pipelines",
                "published_endpoints",
                "template_versions",
                "templates",
                "users",
                "workspace_invitations",
                "workspace_members",
                "workspaces",
            )
    }

    @Test
    @Suppress("LongMethod") // the inventory IS the assertion; splitting it hides what is asserted
    fun `creates exactly the indexes of metadata-db §5 and no others`() {
        // The negative half matters most: §5 deliberately does NOT create
        // idx_events_execution (duplicate of the uq_events_execution_event
        // constraint index on the highest-volume table) and does NOT create
        // uq_users_email (a UNIQUE constraint, whose index Postgres names itself).
        // An extra CREATE INDEX here is a silent write-amplification regression,
        // which is why this asserts the exact set. V4 replaced the implicit
        // pipelines_name_key with the explicitly named uq_pipelines_workspace_name
        // (per-workspace uniqueness) and re-keyed the templates PK onto the surrogate.
        val indexes =
            query(
                """
                SELECT tablename || '.' || indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        // In-any-order, deliberately. The CONTENT is the contract — "exactly these indexes and
        // no others" — and the ORDER is Postgres's `en_US.UTF-8` collation, which does not
        // sort `_` where codepoint order would: `datasource_workspaces` versus `datasources`
        // lands differently in the database than in a Kotlin list literal. Asserting the order
        // too would make this guard fail for a reason that has nothing to do with indexes,
        // which is the collation trap this project has already paid for once.
        indexes shouldContainExactlyInAnyOrder
            listOf(
                "api_keys.api_keys_pkey",
                "api_keys.idx_api_keys_endpoint_kind",
                "api_keys.idx_api_keys_expires",
                "api_keys.idx_api_keys_user",
                "audit_log.audit_log_pkey",
                "audit_log.idx_audit_event",
                "audit_log.idx_audit_timestamp",
                "audit_log.idx_audit_user",
                "datasource_workspaces.datasource_workspaces_pkey",
                "datasource_workspaces.idx_datasource_workspaces_workspace",
                "datasources.datasources_pkey",
                "datasources.idx_datasources_active",
                "endpoint_key_bindings.endpoint_key_bindings_pkey",
                "endpoint_key_bindings.idx_endpoint_key_bindings_key",
                "execution_events.execution_events_pkey",
                "execution_events.uq_events_execution_event",
                "lake_tables.lake_tables_pkey",
                "lake_tables.uq_lake_tables_datasource_namespace_name",
                // 118 (V25) — every read is per datasource; the workspace half is partial.
                "learned_facts.idx_learned_facts_datasource",
                "learned_facts.idx_learned_facts_workspace",
                "learned_facts.learned_facts_pkey",
                "pipeline_executions.idx_executions_correlation",
                "pipeline_executions.idx_executions_heartbeat",
                "pipeline_executions.idx_executions_pipeline",
                "pipeline_executions.idx_executions_root",
                "pipeline_executions.idx_executions_status_running",
                "pipeline_executions.idx_executions_user",
                "pipeline_executions.pipeline_executions_pkey",
                "pipeline_versions.pipeline_versions_pkey",
                "pipeline_versions.uq_pipeline_versions_one_draft",
                "pipelines.idx_pipelines_owner",
                "pipelines.pipelines_pkey",
                "pipelines.uq_pipelines_workspace_name",
                "published_endpoints.idx_published_endpoints_pipeline",
                "published_endpoints.idx_published_endpoints_workspace",
                "published_endpoints.published_endpoints_path_pattern_key",
                "published_endpoints.published_endpoints_pkey",
                "template_versions.idx_template_versions_dialect",
                "template_versions.template_versions_pkey",
                "template_versions.uq_template_versions_one_draft",
                "templates.idx_templates_active",
                "templates.templates_pkey",
                "templates.uq_templates_workspace_name",
                "users.uq_users_provider_subject",
                "users.users_email_key",
                "users.users_pkey",
                "workspace_invitations.idx_workspace_invitations_email",
                "workspace_invitations.workspace_invitations_pkey",
                "workspace_members.idx_workspace_members_admins",
                "workspace_members.workspace_members_pkey",
                "workspaces.idx_workspaces_active",
                "workspaces.workspaces_name_key",
                "workspaces.workspaces_pkey",
            )
    }

    @Test
    fun `the composite FK to pipeline_versions exists and targets its primary key`() {
        // metadata-db.md §4.6: "the point of this table's integrity" — without it,
        // pipeline_version is a free-floating integer and "which JSON actually ran?"
        // has no reliable answer.
        val definition =
            query(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fk_executions_pipeline_version'",
            ) { it.getString(1) }

        definition shouldContainExactly
            listOf(
                "FOREIGN KEY (pipeline_id, pipeline_version) REFERENCES pipeline_versions(pipeline_id, version)",
            )
    }

    @Test
    fun `creates exactly the named CHECK constraints of metadata-db §4`() {
        val checks =
            query(
                """
                SELECT conname FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace AND contype = 'c' AND conname LIKE 'chk_%'
                ORDER BY conname
                """.trimIndent(),
            ) { it.getString(1) }

        checks shouldContainExactly
            listOf(
                // 074 (V11) — the api_keys.kind enum column. Sorted first, like every other row:
                // the query is ORDER BY'd and the assertion is order-sensitive.
                "chk_api_keys_kind",
                // 087 §A (V13) — the three credential CHECKs of metadata-db §4.10: the kind is
                // in the enums.md §5A set, `kind = 'none'` iff no ciphertext (which is what makes
                // `password_set` derivable), and `username` is present exactly when the kind
                // allows it.
                "chk_datasource_credential_kind",
                "chk_datasource_credential_present",
                "chk_datasource_credential_username",
                "chk_datasource_dialect",
                "chk_datasource_name",
                "chk_datasource_query_timeout",
                "chk_dialect",
                // 089 §A (V15) — lake_tables.format is parquet|iceberg; a third value would
                // generate bad view SQL later, and the database is the last place to catch it.
                "chk_lake_table_format",
                // 118 (V25) — learned_facts: the closed kind list, the kind↔scope rule, the
                // fact/summary length windows, refs ≥ 1, the trust set, the write surface, the
                // scope↔workspace rule and the retired stamp (learned-semantic-layer §3/§4/§5).
                "chk_learned_facts_fact_length",
                "chk_learned_facts_kind",
                "chk_learned_facts_kind_scope",
                "chk_learned_facts_refs",
                "chk_learned_facts_retired",
                "chk_learned_facts_scope",
                "chk_learned_facts_scope_workspace",
                "chk_learned_facts_summary_length",
                "chk_learned_facts_trust",
                "chk_learned_facts_via",
                // 101 (V19): discard stamps — both NULL unless DISCARDED, a stamp when it is.
                "chk_pipeline_versions_discard_stamps",
                "chk_pipeline_versions_status",
                // 102 (V20): the write-surface stamps — 'session' | 'api_key' | 'mcp', both
                // columns, the ruling's CHECK so a key id can never drift back in as a value.
                "chk_pipeline_versions_via",
                "chk_status",
                "chk_template_type",
                "chk_template_versions_discard_stamps",
                "chk_template_versions_status",
                "chk_template_versions_via",
                "chk_triggered_via",
                "chk_type_dialect",
                // V24 (113) — the invitation row carries the same invariant as the membership
                // it becomes, and stores the email in the one canonical form §4.2 mandates.
                // Sorted BEFORE the members' CHECK: pg_constraint's ORDER BY conname puts
                // "workspace_invitation_*" ahead of "workspace_member_*".
                "chk_workspace_invitation_admin_authors",
                "chk_workspace_invitation_email_lower",
                // V23 replaced the role CHECK with the invariant that outlived it: a workspace
                // admin can author, stated once in the database (RBAC design §1).
                "chk_workspace_member_admin_authors",
            )
    }

    @Test
    fun `V6 adds the version lifecycle columns to both version tables`() {
        // metadata-db §4.5/§4.9 (versioning.md §11): status backfills RELEASED via the
        // column default; body_hash is backfilled by the SAME sha256 expression the
        // repositories use and then forced NOT NULL; released_at is DB-generated at
        // release and NULL until then; updated_by/updated_at carry the last DRAFT write.
        val columns =
            query(
                """
                SELECT table_name || '|' || column_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('pipeline_versions', 'template_versions')
                   AND column_name IN ('status', 'body_hash', 'released_at', 'released_by', 'updated_by', 'updated_at')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "pipeline_versions|body_hash|NO|NONE",
                "pipeline_versions|released_at|YES|NONE",
                "pipeline_versions|released_by|YES|NONE",
                "pipeline_versions|status|NO|'RELEASED'::text",
                "pipeline_versions|updated_at|YES|NONE",
                "pipeline_versions|updated_by|YES|NONE",
                "template_versions|body_hash|NO|NONE",
                "template_versions|released_at|YES|NONE",
                "template_versions|released_by|YES|NONE",
                "template_versions|status|NO|'RELEASED'::text",
                "template_versions|updated_at|YES|NONE",
                "template_versions|updated_by|YES|NONE",
            )
    }

    /**
     * The D17 promotion classification registry (metadata-db §5A, 035/G): every live table
     * must be classified, and every classified table must exist.
     *
     * The twelve-name literal in `creates exactly the twelve tables…` asks "is this table
     * EXPECTED?"; this asks the second question — "is it CLASSIFIED?" — by parsing §5A's
     * table and comparing both directions, the `verifyModuleDependencies` shape: a
     * mechanical enumeration compared against a declared list that mirrors a doc section.
     * A new table fails here until its §5A row lands, and a §5A row for a table that does
     * not exist fails here too — a one-directional guard would let the doc rot.
     */
    @Test
    fun `every live table is classified in metadata-db 5A and every classified table exists`() {
        val classified = promotionClassification()

        val live =
            query(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """.trimIndent(),
            ) { it.getString(1) }

        val unclassified = live - classified.keys
        val nonexistent = classified.keys - live.toSet()

        if (unclassified.isNotEmpty()) {
            io.kotest.assertions.fail(
                "Table(s) ${unclassified.sorted()} exist in the schema but have no row in metadata-db.md §5A " +
                    "Promotion Classification. Add a row there (| table | verdict | resource | version series | " +
                    "export key | why |, verdict one of promotable / environment-local / derived) AND to this " +
                    "test's expected-table list — in the SAME commit. The classification is what keeps the schema " +
                    "and the promotion design in step (D17).",
            )
        }
        if (nonexistent.isNotEmpty()) {
            io.kotest.assertions.fail(
                "metadata-db.md §5A classifies ${nonexistent.sorted()}, which do not exist in the schema — the " +
                    "registry has drifted from the migrations. Ship the migration the row describes, or remove " +
                    "the row; a classified-but-absent table is a row that will mislead the next migration author.",
            )
        }
        classified.size shouldBe live.size
    }

    /** §5A's rows as `{table → verdict}`; a malformed or absent section fails the parse rather than being skipped. */
    private fun promotionClassification(): Map<String, String> {
        val doc = repoFile("docs/metadata-db.md").readText()
        val match =
            Regex(
                "^## 5A\\. Promotion Classification$(.*?)^## ",
                setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
            ).find(doc) ?: error(
                "metadata-db.md has no '## 5A. Promotion Classification' section — the classification registry " +
                    "FlywayMigrationIntegrationTest parses is missing. Add the section (one row per §4 table) or " +
                    "update this test's parser if the section moved.",
            )
        val rows =
            Regex("""^\|\s*`([a-z_]+)`\s*\|\s*(promotable|environment-local|derived)\s*\|""", RegexOption.MULTILINE)
                .findAll(match.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
        if (rows.isEmpty()) {
            error(
                "metadata-db.md §5A parsed zero classification rows — the table's columns must be " +
                    "`| table | verdict | resource | version series | export key | why |` with the verdict one of " +
                    "promotable / environment-local / derived.",
            )
        }
        return rows
    }

    /** Walks up from the working directory to locate a repo file (the shared test pattern). */
    private fun repoFile(relativePath: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${java.io.File("").absolutePath}")
    }

    @Test
    fun `emits no triggers`() {
        // metadata-db.md §2/§7.2: updated_at is application-maintained. A generator
        // that "helpfully" adds a BEFORE UPDATE trigger contradicts the spec.
        val triggers =
            query(
                """
                SELECT c.relname || '.' || t.tgname
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND NOT t.tgisinternal
                """.trimIndent(),
            ) { it.getString(1) }

        triggers shouldBe emptyList()
    }

    @Test
    fun `every timestamp column is TIMESTAMPTZ`() {
        // metadata-db.md §2: never TIMESTAMP without time zone. flyway_schema_history
        // is Flyway's own table and is not ours to constrain.
        val naive =
            query(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name <> 'flyway_schema_history'
                  AND data_type = 'timestamp without time zone'
                ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        naive shouldBe emptyList()
    }

    @Test
    fun `the _json suffix and the JSONB type agree in both directions`() {
        // metadata-db.md §2: "The suffix is a naming rule, not a hint: a column
        // without it is not JSONB." Both directions are asserted, because either
        // half alone lets a mismatch through.
        val jsonbWithoutSuffix =
            query(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'jsonb' AND column_name NOT LIKE '%\_json'
                """.trimIndent(),
            ) { it.getString(1) }

        val suffixWithoutJsonb =
            query(
                """
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name LIKE '%\_json' AND data_type <> 'jsonb'
                """.trimIndent(),
            ) { it.getString(1) }

        jsonbWithoutSuffix shouldBe emptyList()
        suffixWithoutJsonb shouldBe emptyList()
    }

    @Test
    fun `V3 creates the root_execution_id lineage index`() {
        // metadata-db §4.6/§5: one indexed query returns the whole execution family;
        // cancellation keys off root_execution_id. The exact-set test above pins the full
        // index list — this names the one V3 adds.
        val indexes =
            query(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'pipeline_executions'
                """.trimIndent(),
            ) { it.getString(1) }

        indexes shouldContain "idx_executions_root"
    }

    @Test
    fun `V3 refuses an execution row without root_execution_id`() {
        // metadata-db §4.6: backfilled to execution_id, NOT NULL from V3 on — family queries
        // and cancellation never special-case NULL. ExecutionRepository.create always binds it
        // (record.rootExecutionId ?: record.executionId); this pins the database floor under that.
        val userId = UUID.randomUUID()
        val pipelineId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'T', 'google', ?)",
                ).use {
                    it.setObject(1, userId)
                    it.setString(2, "u$userId@example.com")
                    it.setString(3, "sub-$userId")
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
                    VALUES (?, ?, 'T', ?, 1, 'defa0000-0000-0000-0000-000000000001')
                    """.trimIndent(),
                ).use {
                    it.setObject(1, pipelineId)
                    it.setString(2, "p_" + pipelineId.toString().replace("-", ""))
                    it.setObject(3, userId)
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash," +
                        " status, created_by, released_by, released_at) " +
                        "VALUES (?, 1, CAST('{}' AS jsonb), 'seed-hash', 'RELEASED', ?, ?, NOW())",
                ).use {
                    it.setObject(1, pipelineId)
                    it.setObject(2, userId)
                    it.setObject(3, userId)
                    it.executeUpdate()
                }

            val violation =
                shouldThrow<SQLException> {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO pipeline_executions (
                                execution_id, pipeline_id, pipeline_version, status, triggered_by, triggered_via
                            ) VALUES (?, ?, 1, 'RUNNING', ?, 'REST')
                            """.trimIndent(),
                        ).use {
                            it.setObject(1, UUID.randomUUID())
                            it.setObject(2, pipelineId)
                            it.setObject(3, userId)
                            it.executeUpdate()
                        }
                }
            violation.message shouldContain "root_execution_id"
        }
    }

    @Test
    fun `V4 seeds the default workspace with the constant UUID and no creator`() {
        // metadata-db §4.11 (R1/R2): the seed carries the well-known constant — slice-1
        // repositories pin it, slice 2 finds every pin by grepping it — and created_by NULL
        // (system-provisioned; a fresh install has no user to reference).
        val rows =
            query(
                "SELECT id::TEXT || '|' || name || '|' || is_personal || '|' || COALESCE(created_by::TEXT, 'NULL')" +
                    " FROM workspaces WHERE name = 'default'",
            ) { it.getString(1) }

        rows shouldContainExactly listOf("defa0000-0000-0000-0000-000000000001|default|false|NULL")
    }

    @Test
    fun `V4 re-keys templates onto a surrogate UUID PK and template_versions follows`() {
        // metadata-db §4.8/§4.9: today's TEXT id is the `name` column; the PK and the
        // template_versions reference are the surrogate UUID. Pipeline-JSON and imports_json
        // {id, version} refs keep meaning `name` — stored payloads are not rewritten.
        val columns =
            query(
                """
                SELECT table_name || '.' || column_name || '.' || data_type
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND ((table_name = 'templates' AND column_name IN ('id', 'name'))
                    OR (table_name = 'template_versions' AND column_name = 'template_id'))
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "template_versions.template_id.uuid",
                "templates.id.uuid",
                "templates.name.text",
            )
    }

    @Test
    fun `V23 leaves the datasource OWNER column and the readonly flag, workspace_id gone`() {
        // metadata-db §4.10: V4's `workspace_id` (NULL = global) was replaced in V23 by
        // `owner_workspace_id` (NULL = an instance datasource) plus the `datasource_workspaces`
        // grant table (§4.16). Ownership and visibility were one column and are now two
        // concepts, so the column this test used to assert must be ABSENT — its presence would
        // mean the drop did not run.
        val columns =
            query(
                """
                SELECT column_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'datasources'
                   AND column_name IN ('workspace_id', 'owner_workspace_id', 'is_readonly')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "is_readonly|NO|false",
                "owner_workspace_id|YES|NONE",
            )
    }

    @Test
    fun `V4 scopes pipeline name uniqueness per workspace, soft-deleted rows included`() {
        // metadata-db §4.4: the mechanism is unchanged — a plain UNIQUE constraint, no
        // partial index — re-keyed (workspace_id, name). The same name is legal in two
        // workspaces and violated in one, and a soft-deleted row still holds its name
        // within its workspace. The constraint name is pinned: PipelineRepository maps
        // its violation to pipeline.validation.duplicate_name.
        val userId = UUID.randomUUID()
        val secondWorkspace = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'T', 'google', ?)",
                ).use {
                    it.setObject(1, userId)
                    it.setString(2, "u$userId@example.com")
                    it.setString(3, "sub-$userId")
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (?, 'second', 'Second', ?)",
                ).use {
                    it.setObject(1, secondWorkspace)
                    it.setObject(2, userId)
                    it.executeUpdate()
                }

            fun insertPipeline(
                name: String,
                workspaceId: UUID,
            ) {
                connection
                    .prepareStatement(
                        """
                        INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)
                        VALUES (?, ?, 'T', ?, 1, ?)
                        """.trimIndent(),
                    ).use {
                        it.setObject(1, UUID.randomUUID())
                        it.setString(2, name)
                        it.setObject(3, userId)
                        it.setObject(4, workspaceId)
                        it.executeUpdate()
                    }
            }

            insertPipeline("shared_name", DEFAULT_WORKSPACE_UUID)
            insertPipeline("shared_name", secondWorkspace) // legal across workspaces
            shouldThrow<SQLException> { insertPipeline("shared_name", DEFAULT_WORKSPACE_UUID) }
                .message shouldContain "uq_pipelines_workspace_name"

            // 101/D59: names are unique FOREVER — every version DISCARDED (the derived
            // retired state since V19) keeps the name taken exactly like a live row; there
            // is no is_deleted column to set any more.
            insertPipeline("retired_name", DEFAULT_WORKSPACE_UUID)
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    UPDATE pipeline_versions SET status = 'DISCARDED', discarded_at = NOW()
                     WHERE pipeline_id = (SELECT id FROM pipelines WHERE name = 'retired_name')
                    """.trimIndent(),
                )
            }
            insertPipeline("retired_name", secondWorkspace) // legal in the other workspace
            shouldThrow<SQLException> { insertPipeline("retired_name", DEFAULT_WORKSPACE_UUID) }
                .message shouldContain "uq_pipelines_workspace_name"
        }
    }

    private fun <T> query(
        sql: String,
        row: (ResultSet) -> T,
    ): List<T> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(row(rs)) }
                }
            }
        }

    companion object {
        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        private const val SECRET_BYTES = 32

        /** The V15 probe's own user row — rolled back with the probe, never committed. */
        private const val KIND_PROBE_USER = "f15b0000-0000-0000-0000-000000000015"

        /** The seeded `default` workspace (metadata-db §4.11, R2) the V4 assertions pin against. */
        private val DEFAULT_WORKSPACE_UUID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /**
         * In-process OIDC discovery (auth.md §5.2) for the configured providers — the
         * real `OidcConfig` fetches this at startup; nothing here authenticates anyone.
         */
        private val oidc = OidcDiscoveryStub()

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        /**
         * The OIDC property block every full-context test in this module needs (P7):
         * the provider list is re-declared in full — Spring takes a bound list wholesale
         * from the highest-precedence source containing any element of it, and BOTH
         * indices must be covered because a lookup for an index absent here falls
         * through to application.yml's `${GOOGLE_CLIENT_ID}` placeholder, which
         * resolves to nothing in tests. (application.yml ships google only; the
         * second entry here merely exercises multi-provider discovery against the stub.)
         */
        internal fun oidcProperties(registry: DynamicPropertyRegistry) {
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            // Random management port — see observability.md §4.2.
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            // Generated per run — no literal secret in any test fixture (HIGH-2).
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            oidcProperties(registry)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
