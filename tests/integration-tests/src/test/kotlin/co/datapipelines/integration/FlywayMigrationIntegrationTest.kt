package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
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
            )
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
    fun `creates exactly the twelve tables of metadata-db §4`() {
        val tables =
            query(
                """
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                ORDER BY tablename
                """.trimIndent(),
            ) { it.getString(1) }

        tables shouldContainExactly
            listOf(
                "api_keys",
                "audit_log",
                "datasources",
                "execution_events",
                "pipeline_executions",
                "pipeline_versions",
                "pipelines",
                "template_versions",
                "templates",
                "users",
                "workspace_members",
                "workspaces",
            )
    }

    @Test
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

        indexes shouldContainExactly
            listOf(
                "api_keys.api_keys_pkey",
                "api_keys.idx_api_keys_expires",
                "api_keys.idx_api_keys_user",
                "audit_log.audit_log_pkey",
                "audit_log.idx_audit_event",
                "audit_log.idx_audit_timestamp",
                "audit_log.idx_audit_user",
                "datasources.datasources_pkey",
                "datasources.idx_datasources_active",
                "execution_events.execution_events_pkey",
                "execution_events.uq_events_execution_event",
                "pipeline_executions.idx_executions_correlation",
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
                "template_versions.idx_template_versions_dialect",
                "template_versions.template_versions_pkey",
                "template_versions.uq_template_versions_one_draft",
                "templates.idx_templates_active",
                "templates.templates_pkey",
                "templates.uq_templates_workspace_name",
                "users.uq_users_provider_subject",
                "users.users_email_key",
                "users.users_pkey",
                "workspace_members.workspace_members_pkey",
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
                "chk_datasource_dialect",
                "chk_datasource_name",
                "chk_datasource_query_timeout",
                "chk_dialect",
                "chk_pipeline_versions_status",
                "chk_status",
                "chk_template_type",
                "chk_template_versions_status",
                "chk_triggered_via",
                "chk_type_dialect",
                "chk_workspace_member_role",
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
    fun `V4 adds the datasource scoping columns - nullable workspace, readonly defaulting false`() {
        // metadata-db §4.10: NULL workspace_id = global (existing rows backfill NULL, D9);
        // columns only — the datasources module does not change in this slice.
        val columns =
            query(
                """
                SELECT column_name || '|' || is_nullable || '|' || COALESCE(column_default, 'NONE')
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'datasources'
                   AND column_name IN ('workspace_id', 'is_readonly')
                 ORDER BY 1
                """.trimIndent(),
            ) { it.getString(1) }

        columns shouldContainExactly
            listOf(
                "is_readonly|NO|false",
                "workspace_id|YES|NONE",
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

            // The soft-delete variant: a deleted row keeps its name taken within its workspace.
            insertPipeline("retired_name", DEFAULT_WORKSPACE_UUID)
            connection.createStatement().use {
                it.executeUpdate("UPDATE pipelines SET is_deleted = TRUE WHERE name = 'retired_name'")
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
