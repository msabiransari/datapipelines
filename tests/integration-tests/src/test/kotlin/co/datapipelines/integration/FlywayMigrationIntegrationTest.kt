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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
@Testcontainers
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
            )
    }

    @Test
    fun `creates exactly the ten tables of metadata-db §4`() {
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
            )
    }

    @Test
    fun `creates exactly the indexes of metadata-db §5 and no others`() {
        // The negative half matters most: §5 deliberately does NOT create
        // idx_events_execution (duplicate of the uq_events_execution_event
        // constraint index on the highest-volume table) and does NOT create
        // uq_users_email / uq_pipelines_name (those are UNIQUE constraints, whose
        // indexes Postgres names itself). An extra CREATE INDEX here is a silent
        // write-amplification regression, which is why this asserts the exact set.
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
                "pipelines.idx_pipelines_owner",
                "pipelines.pipelines_name_key",
                "pipelines.pipelines_pkey",
                "template_versions.idx_template_versions_dialect",
                "template_versions.template_versions_pkey",
                "templates.idx_templates_active",
                "templates.templates_pkey",
                "users.uq_users_provider_subject",
                "users.users_email_key",
                "users.users_pkey",
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
                "chk_status",
                "chk_triggered_via",
            )
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
                    "INSERT INTO pipelines (id, name, display_name, owner_id, current_version) VALUES (?, ?, 'T', ?, 1)",
                ).use {
                    it.setObject(1, pipelineId)
                    it.setString(2, "p_" + pipelineId.toString().replace("-", ""))
                    it.setObject(3, userId)
                    it.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by) VALUES (?, 1, CAST('{}' AS jsonb), ?)",
                ).use {
                    it.setObject(1, pipelineId)
                    it.setObject(2, userId)
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
        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32

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
         * through to application.yml's `${GOOGLE_CLIENT_ID}`/`${MICROSOFT_CLIENT_ID}`
         * placeholders, which resolve to nothing in tests.
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
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

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
