package co.datapipelines.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #1036 R1 — the offline pre-upgrade compatibility report, run as an operator runs it: the PACKAGED
 * command (`scripts/local-write-compatibility-report.sh` over the `bootJar`, no application started) on
 * the actual pre-upgrade metadata schema, and then on the current schema of the SAME store.
 *
 * The store starts at V64 — the last schema the previous release shipped, built from the shipped
 * migration scripts up to that version, so `published_endpoints.method` (V65, #1037) does not exist —
 * and holds the two synthetic §9 fixtures and a legacy `GET` API pin. A `SELECT`-only role runs the
 * report. Then V65 and V66 are applied to that store, a `POST`, a `PUT` and a `DELETE` pin are added,
 * and the report runs again.
 *
 * Each run asserts, from the process's own output and a second JDBC connection: the exit code, every
 * version line exactly (version, source, verdict, reason), the SUMMARY, that stdout carries no SQL,
 * URL, host, user or password, **zero customer connections** (every registration's host cannot resolve,
 * so an attempt would end the run in exit 1; the output also lists none) and **no metadata writes**
 * (a content fingerprint of every table before and after).
 *
 * The fixture's rows are written with SQL against the shipped DDL, not through the repositories: the
 * repositories write the CURRENT schema, and the point of this class is a schema that predates it.
 */
class LocalWriteCompatibilityPackagedIntegrationTest {
    private val mapper = ObjectMapper()

    @Test
    fun `the packaged report runs on the pre-upgrade V64 schema and reports a legacy GET API pin, then on the current schema`() {
        val store = SharedE2e.scratchDatabase("cr1036")
        val role = "report_ro_${UUID.randomUUID().toString().replace("-", "").take(ROLE_SUFFIX)}"
        val password = randomPassword()
        try {
            DriverManager.getConnection(store.jdbcUrl, store.username, store.password).use { admin ->
                applyMigrations(admin) { it <= PRE_UPGRADE_VERSION }
                withClue("the fixture store must be the pre-V65 schema") { hasMethodColumn(admin) shouldBe false }
                seedFixtures(admin)
                seedLegacyApi(admin)
                createReadOnlyRole(admin, role, password)

                // ---- the pre-upgrade schema ------------------------------------------------
                val before = fingerprint(admin)
                val pre = runPackagedReport(store.jdbcUrl, role, password)
                withClue("pre-upgrade stderr: ${pre.stderr}") { pre.exit shouldBe 0 }
                pre.versionLines() shouldContainExactly PRE_UPGRADE_LINES
                pre.lines.last() shouldBe "SUMMARY versions=5 unchanged=2 enrolled=1 refused=2 unreadable=0"
                pre.assertNothingSensitive()
                withClue("the report wrote to the metadata store") { fingerprint(admin) shouldBe before }

                // ---- the same store, upgraded to the current schema --------------------------
                applyMigrations(admin) { it > PRE_UPGRADE_VERSION }
                withClue("V65 adds the method column") { hasMethodColumn(admin) shouldBe true }
                seedMutationApis(admin)
                grantSelect(admin, role)
                val upgradedBefore = fingerprint(admin)
                val current = runPackagedReport(store.jdbcUrl, role, password)
                withClue("current-schema stderr: ${current.stderr}") { current.exit shouldBe 0 }
                current.versionLines() shouldContainExactly CURRENT_LINES
                current.lines.last() shouldBe "SUMMARY versions=6 unchanged=2 enrolled=1 refused=3 unreadable=0"
                current.assertNothingSensitive()
                withClue("the report wrote to the metadata store") { fingerprint(admin) shouldBe upgradedBefore }
            }
        } finally {
            DriverManager.getConnection(store.jdbcUrl, store.username, store.password).use { admin ->
                admin.createStatement().use {
                    it.execute("""DROP OWNED BY "$role"""")
                    it.execute("""DROP ROLE IF EXISTS "$role"""")
                }
            }
        }
    }

    // ------------------------------------------------------------------ the packaged command

    private data class Report(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val lines: List<String> get() = stdout.lines().filter { it.isNotBlank() }

        /** Each version line up to its `details` — the part the operator reads as version, source, verdict and reason. */
        fun versionLines(): List<String> = lines.filter { it.startsWith("workspace=") }.map { it.substringBefore(" details=") }

        fun assertNothingSensitive() {
            FORBIDDEN.forEach { word ->
                withClue("stdout names '$word'") { stdout shouldNotContain word }
                withClue("stderr names '$word'") { stderr shouldNotContain word }
            }
        }
    }

    private fun runPackagedReport(
        jdbcUrl: String,
        user: String,
        password: String,
    ): Report {
        val script = File(repoRoot(), "scripts/local-write-compatibility-report.sh")
        check(script.isFile) { "the report wrapper is missing at ${script.absolutePath}" }
        // The wrapper's JVM keeps its temp files below this test JVM's own (DEVELOPMENT.md 9.7): a fixed child name.
        val childTmp = File(System.getProperty("java.io.tmpdir"), "cr1036-report")
        check(childTmp.isDirectory || childTmp.mkdirs()) { "cannot create the report JVM's temp directory ${childTmp.absolutePath}" }
        val process =
            ProcessBuilder("bash", script.absolutePath, jar().absolutePath)
                .directory(repoRoot())
                .apply {
                    environment()["DATAPIPELINES_REPORT_JAVA_OPTS"] = "-Djava.io.tmpdir=${childTmp.absolutePath}"
                    environment()["SPRING_DATASOURCE_URL"] = jdbcUrl
                    environment()["SPRING_DATASOURCE_USERNAME"] = user
                    environment()["SPRING_DATASOURCE_PASSWORD"] = password
                    // The report must not inherit a shell's executor setting or an app profile.
                    environment().remove("DATAPIPELINES_PIPELINES_MAX_COMPOSITION_DEPTH")
                }.start()
        // Both streams are read to the end on their own threads: a full pipe must not stall the child.
        var stdout = ""
        var stderr = ""
        val outReader = Thread { stdout = process.inputStream.bufferedReader().readText() }.apply { start() }
        val errReader = Thread { stderr = process.errorStream.bufferedReader().readText() }.apply { start() }
        check(process.waitFor(REPORT_BUDGET_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "the packaged report did not finish within $REPORT_BUDGET_SECONDS s"
        }
        outReader.join()
        errReader.join()
        return Report(process.exitValue(), stdout, stderr)
    }

    private fun jar(): File {
        val override = System.getenv("DATAPIPELINES_APP_JAR")
        val jar = if (override != null) File(override) else File(repoRoot(), "modules/app/build/libs/datapipelines-app.jar")
        check(jar.isFile) {
            "bootJar output not found at ${jar.absolutePath} — the integration test task depends on :modules:app:bootJar"
        }
        return jar
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    // ------------------------------------------------------------------ the store

    /** Applies the shipped versioned migrations [admit] admits, in version order; the file grammar is enforced, not filtered. */
    private fun applyMigrations(
        connection: Connection,
        admit: (Int) -> Boolean,
    ) {
        val dir = File(repoRoot(), MIGRATIONS)
        val scripts =
            requireNotNull(dir.listFiles { f: File -> f.isFile && f.name.endsWith(".sql") }) { "no migration directory at $MIGRATIONS" }
                .map { file ->
                    val version =
                        requireNotNull(VERSION.matchEntire(file.name)) { "Migration '${file.name}' does not match V<version>__<name>.sql" }
                            .groupValues[1]
                            .toInt()
                    version to file
                }.filter { (version, _) -> admit(version) }
                .sortedBy { it.first }
        check(scripts.isNotEmpty()) { "no migration admitted" }
        scripts.forEach { (_, file) -> connection.createStatement().use { it.execute(file.readText()) } }
    }

    private fun hasMethodColumn(connection: Connection): Boolean =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_name = 'published_endpoints' AND column_name = 'method'",
                ).use { rs ->
                    rs.next()
                    rs.getInt(1) == 1
                }
        }

    private fun createReadOnlyRole(
        connection: Connection,
        role: String,
        password: String,
    ) {
        connection.createStatement().use { it.execute("""CREATE ROLE "$role" LOGIN PASSWORD '$password'""") }
        grantSelect(connection, role)
    }

    /** `SELECT` on every table and nothing else: a write attempt by the report would be refused by the server. */
    private fun grantSelect(
        connection: Connection,
        role: String,
    ) {
        connection.createStatement().use {
            it.execute("""GRANT USAGE ON SCHEMA public TO "$role"""")
            it.execute("""GRANT SELECT ON ALL TABLES IN SCHEMA public TO "$role"""")
        }
    }

    /** A content fingerprint of every table of the store: equal before and after means nothing was written. */
    private fun fingerprint(connection: Connection): Map<String, String> {
        val tables =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        check(tables.size >= MIN_TABLES) { "fingerprint saw only ${tables.size} tables" }
        return tables.associateWith { table ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("""SELECT md5(COALESCE(string_agg(t::text, E'\n' ORDER BY t::text), '')) FROM "$table" t""")
                    .use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            }
        }
    }

    // ------------------------------------------------------------------ fixtures (SQL against the shipped DDL)

    private fun seedFixtures(connection: Connection) {
        sql(
            connection,
            "INSERT INTO users (id, email, display_name, provider, provider_subject) " +
                "VALUES ('$OWNER', 'owner@example.test', 'Owner', 'google', 'sub-$OWNER')",
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$WORKSPACE', '$WORKSPACE_NAME', 'W', '$OWNER')",
        )
        listOf("wh_a", "wh_b").forEach { name ->
            sql(
                connection,
                "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                    "VALUES ('$name', '$name', 'POSTGRES', 'jdbc:postgresql://$name.invalid:5432/warehouse', 'none', '$OWNER')",
                "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES ('$name', '$WORKSPACE', '$OWNER')",
            )
        }
        // Fixture (a): version 3 is current and runs external DDL before a DQL write-back to the same registration.
        pipeline(connection, "acme/ddl_then_writeback", listOf(harmless(), harmless(), ddlThenWriteBack()), current = 3)
        // Fixture (b): version 5 writes to two registrations, version 6 is current — only the schedule reaches 5.
        pipeline(
            connection,
            "acme/two_destinations",
            List(4) { harmless() } + listOf(twoDestinations(), harmless()),
            current = 6,
        )
        schedule(connection, "nightly", "acme/two_destinations", 5)
        // The pipeline the published APIs pin: v1 one writer (enrolled), v2 two destinations (refused), v3 harmless (current).
        pipeline(connection, "acme/order_api", listOf(oneWriter(), twoDestinations(), harmless()), current = 3)
    }

    /** The pre-V65 route: no `method` column, so every route is a GET route. */
    private fun seedLegacyApi(connection: Connection) {
        api(connection, "/orders/v1/legacy", method = null, pipeline = "acme/order_api", version = 1)
    }

    /** After V65: the three mutation methods, each pinning a different version of the same pipeline. */
    private fun seedMutationApis(connection: Connection) {
        api(connection, "/orders/v1/place", method = "POST", pipeline = "acme/order_api", version = 2)
        api(connection, "/orders/v1/replace", method = "PUT", pipeline = "acme/order_api", version = 1)
        api(connection, "/orders/v1/cancel", method = "DELETE", pipeline = "acme/order_api", version = 3)
    }

    private fun pipeline(
        connection: Connection,
        name: String,
        bodies: List<List<Map<String, Any?>>>,
        current: Int,
    ) {
        val id = UUID.randomUUID()
        sql(
            connection,
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES ('$id', '$name', 'Fixture', 'd', '$OWNER', '$WORKSPACE', $current)",
        )
        bodies.forEachIndexed { index, nodes ->
            val body =
                mapper.writeValueAsString(
                    mapOf(
                        "schema_version" to 1,
                        "name" to name,
                        "display_name" to "Fixture",
                        "description" to "v${index + 1}",
                        "settings" to emptyMap<String, Any>(),
                        "parameters" to emptyMap<String, Any>(),
                        "nodes" to nodes,
                    ),
                )
            connection
                .prepareStatement(
                    "INSERT INTO pipeline_versions " +
                        "(pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                        "VALUES (?, ?, CAST(? AS jsonb), encode(sha256(convert_to(CAST(? AS jsonb)::text, 'UTF8')), 'hex'), " +
                        "'RELEASED', ?, ?, NOW())",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setInt(2, index + 1)
                    statement.setString(3, body)
                    statement.setString(4, body)
                    statement.setObject(5, UUID.fromString(OWNER))
                    statement.setObject(6, UUID.fromString(OWNER))
                    statement.executeUpdate()
                }
        }
    }

    private fun schedule(
        connection: Connection,
        name: String,
        pipeline: String,
        version: Int,
    ) {
        val payload = mapper.writeValueAsString(mapOf("pipeline" to pipeline, "version" to version))
        connection
            .prepareStatement(
                "INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json, target_ref, cron, " +
                    "timezone, created_by, updated_by, next_due_at) " +
                    "VALUES (?, ?, ?, 'pipeline', 2, CAST(? AS jsonb), ?, '0 * * * *', 'UTC', ?, ?, NOW() + INTERVAL '1 day')",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, UUID.fromString(WORKSPACE))
                statement.setString(3, name)
                statement.setString(4, payload)
                statement.setString(5, "pipeline:$pipeline")
                statement.setObject(6, UUID.fromString(OWNER))
                statement.setObject(7, UUID.fromString(OWNER))
                statement.executeUpdate()
            }
    }

    /** A released route; [method] null writes the pre-V65 shape (no `method` column at all). */
    private fun api(
        connection: Connection,
        path: String,
        method: String?,
        pipeline: String,
        version: Int,
    ) {
        val id = UUID.randomUUID()
        val pipelineId =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM pipelines WHERE workspace_id = '$WORKSPACE' AND name = '$pipeline'").use { rs ->
                    check(rs.next()) { "fixture pipeline $pipeline missing" }
                    rs.getObject(1, UUID::class.java)
                }
            }
        val columns = if (method == null) "" else ", method"
        val values = if (method == null) "" else ", '$method'"
        connection
            .prepareStatement(
                "INSERT INTO published_endpoints (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, created_by$columns) " +
                    "VALUES (?, ?, ?, ?, 30, ?$values)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, UUID.fromString(WORKSPACE))
                statement.setString(3, path)
                statement.setObject(4, pipelineId)
                statement.setObject(5, UUID.fromString(OWNER))
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO published_endpoint_versions (endpoint_id, version, status, pipeline_name, pipeline_version, " +
                    "timeout_seconds, body_hash, released_at, released_by, created_by) " +
                    "VALUES (?, 1, 'RELEASED', ?, ?, 30, 'h1', NOW(), ?, ?)",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, pipeline)
                statement.setInt(3, version)
                statement.setObject(4, UUID.fromString(OWNER))
                statement.setObject(5, UUID.fromString(OWNER))
                statement.executeUpdate()
            }
        sql(connection, "UPDATE published_endpoints SET current_version = 1 WHERE id = '$id'")
    }

    private fun sql(
        connection: Connection,
        vararg statements: String,
    ) = connection.createStatement().use { statement -> statements.forEach(statement::execute) }

    // ------------------------------------------------------------------ pipeline bodies

    private fun node(
        id: String,
        type: String,
        source: String,
        output: Map<String, Any?>? = null,
        operation: String? = null,
        dependsOn: List<String> = emptyList(),
    ): Map<String, Any?> =
        buildMap {
            put("id", id)
            put("description", "node $id")
            put("type", type)
            put("source", source)
            put("template", mapOf("id" to "test/$id.sql", "version" to 1))
            if (operation != null) put("operation", operation)
            if (output != null) put("output", output)
            put("depends_on", dependsOn)
        }

    private fun harmless() = listOf(node("read", "DQL", "wh_a", mapOf("target" to "caller")))

    private fun oneWriter() = listOf(node("touch", "DML", "wh_a", mapOf("target" to "discard"), operation = "update"))

    private fun ddlThenWriteBack() =
        listOf(
            node("make_table", "DDL", "wh_a"),
            node("load", "DQL", "tempdb", append("wh_a"), dependsOn = listOf("make_table")),
        )

    private fun twoDestinations() =
        listOf(
            node("touch", "DML", "wh_a", mapOf("target" to "discard"), operation = "update"),
            node("copy", "DQL", "tempdb", append("wh_b"), dependsOn = listOf("touch")),
        )

    private fun append(datasource: String): Map<String, Any?> {
        val destination = mapOf("target" to "datasource", "datasource" to datasource)
        return destination + mapOf("table" to "t", "mode" to "append")
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val MIGRATIONS = "modules/app/src/main/resources/db/migration"
        val VERSION = Regex("""^V(\d+)__.*\.sql$""")

        /** The last schema the previous release shipped: V65 (`published_endpoints.method`) is the first this branch's line adds. */
        const val PRE_UPGRADE_VERSION = 64
        const val MIN_TABLES = 20
        const val ROLE_SUFFIX = 12
        const val PASSWORD_BYTES = 18
        const val REPORT_BUDGET_SECONDS = 120L

        const val OWNER = "0f1e2d3c-4b5a-4968-8777-665544332211"
        const val WORKSPACE = "1a2b3c4d-5e6f-4071-8293-a4b5c6d7e8f9"
        const val WORKSPACE_NAME = "w_cr1036"
        const val PREFIX = "workspace=$WORKSPACE_NAME pipeline="

        /** Nothing a report may print: SQL, a URL, a host, the role or its password. */
        val FORBIDDEN = listOf("jdbc:", ".invalid", "warehouse", "password", "report_ro", "SELECT", "INSERT", "CREATE TABLE")

        val PRE_UPGRADE_LINES =
            listOf(
                "${PREFIX}acme/ddl_then_writeback version=3 source=current verdict=refused reason=external_ddl",
                "${PREFIX}acme/order_api version=1 source=endpoint:GET:/orders/v1/legacy verdict=enrolled",
                "${PREFIX}acme/order_api version=3 source=current verdict=unchanged",
                "${PREFIX}acme/two_destinations version=5 source=schedule:nightly verdict=refused reason=second_durable_destination",
                "${PREFIX}acme/two_destinations version=6 source=current verdict=unchanged",
            )

        val CURRENT_LINES =
            listOf(
                "${PREFIX}acme/ddl_then_writeback version=3 source=current verdict=refused reason=external_ddl",
                "${PREFIX}acme/order_api version=1 source=endpoint:GET:/orders/v1/legacy,endpoint:PUT:/orders/v1/replace verdict=enrolled",
                "${PREFIX}acme/order_api version=2 source=endpoint:POST:/orders/v1/place verdict=refused reason=second_durable_destination",
                "${PREFIX}acme/order_api version=3 source=current,endpoint:DELETE:/orders/v1/cancel verdict=unchanged",
                "${PREFIX}acme/two_destinations version=5 source=schedule:nightly verdict=refused reason=second_durable_destination",
                "${PREFIX}acme/two_destinations version=6 source=current verdict=unchanged",
            )
    }
}
