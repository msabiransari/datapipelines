package co.datapipelines.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * The #186 registration matrix, over the REAL wire: in-process engines (H2 `mem:`/`file:`,
 * DuckDB, SQLite) and file-backed URLs are registered by a **super admin only** — a workspace
 * admin is refused even with `member-datasources-enabled` ON — and a file-backed URL must
 * resolve under a declared `datapipelines.datasources.file-roots` root for ANYONE.
 *
 * The non-vacuity floor is the point of the sweep: a rule that refused nothing, or accepted
 * nothing, would be indistinguishable from a wiring mistake, so the counts are asserted
 * (≥ 12 refusals, ≥ 3 acceptances), not just the per-cell outcomes.
 *
 * Falsified at birth: with the gate line in `DatasourceWorkspaceRules` reverted, the seven
 * in-process ws-admin cells go 201 and the floor fails. The update leg is the same rule one
 * route over: re-pointing an existing datasource's URL at an in-process form is a registration.
 *
 * 186b adds the case-sensitivity cells: the H2 driver reads the four prefixes case-SENSITIVELY
 * (`ConnectionInfo.parseName`, h2-2.3.232 ll. 197-219) and opens `jdbc:h2:TCP://h/x` as a
 * literal FILE under the process's working directory — so every mixed-case prefix is an
 * Unknown form, refused by the VALIDATOR for BOTH roles (the refusal is not the workspace
 * gate's; it binds super admins too), and the sweep proves no `.mv.db` file appears under the
 * process's working directory while it runs. Non-vacuity for that walk: the lower-case `mem:`
 * arm still registers (the acceptance floor below).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceInProcessRegistrationE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private var refusals = 0
    private var acceptances = 0

    @Test
    fun `the registration matrix - every in-process and file-backed form, both roles`() {
        val underRoot = FILE_ROOT.resolve("under-${UUID.randomUUID()}")
        Files.createDirectories(underRoot)

        // 186b: no H2 file database may materialize under the process's working directory
        // while the sweep runs — a mixed-case URL that slipped the classifier would create one.
        val mvDbBefore = mvDbUnderCwd()

        val matrix = StringBuilder("registration matrix (186):\n")
        for (case in cases(underRoot)) {
            for ((role, key) in listOf("ws_admin" to WSADMIN_KEY.plaintext, "super_admin" to ADMIN_KEY.plaintext)) {
                val expected = if (role == "ws_admin") case.wsAdmin else case.superAdmin
                val response = register(key, "${case.label}-$role", case.dialect, case.jdbcUrl)
                val (status, body) = response
                val outcome =
                    when {
                        status == 201 -> {
                            RoleOutcome.ACCEPT
                        }

                        expected == RoleOutcome.REFUSE_FORM && body.contains("not supported for dialect") -> {
                            RoleOutcome.REFUSE_FORM
                        }

                        expected == RoleOutcome.REFUSE_ROOTS && body.contains("datasource.validation.jdbc_url_malformed") -> {
                            RoleOutcome.REFUSE_ROOTS
                        }

                        body.contains("datasource.validation.workspace_forbidden") -> {
                            RoleOutcome.REFUSE
                        }

                        else -> {
                            RoleOutcome.UNEXPECTED
                        }
                    }
                if (outcome == RoleOutcome.ACCEPT) acceptances++ else refusals++
                matrix.append("  %-18s %-12s -> %d %s\n".format(case.label, role, status, outcome))
                withClue("${case.label} as $role: expected $expected, got HTTP $status — $body") {
                    outcome shouldBe expected
                }
            }
        }
        println(matrix)

        withClue("non-vacuity: the sweep refused AND accepted (matrix below)\n$matrix") {
            refusals shouldBeGreaterThanOrEqualTo 12
            acceptances shouldBeGreaterThanOrEqualTo 3
        }

        // 186b: the mixed-case cells were all refusals at the VALIDATOR, so nothing ever
        // connected — prove it: no H2 database file appeared under the process's working
        // directory (the driver's answer to a prefix it does not match case-sensitively).
        withClue("no .mv.db created under the working directory by any sweep cell") {
            (mvDbUnderCwd() - mvDbBefore) shouldBe emptySet()
        }
    }

    @Test
    fun `the update leg - re-pointing a server H2 at an in-process URL is refused for a workspace admin`() {
        // A server-form H2 registered by a workspace admin (member gate ON) is legal; moving its
        // URL in-process is a registration, and the workspace admin may not do it.
        register(WSADMIN_KEY.plaintext, "sweep186-update-target", "H2", "jdbc:h2:tcp://127.0.0.1:9/sweep186u").first shouldBe 201

        val refused = update(WSADMIN_KEY.plaintext, "sweep186-update-target", "jdbc:h2:mem:sweep186moved")
        withClue("ws_admin re-point to mem: must refuse: $refused") {
            refused.first shouldBe 400
            refused.second.contains("datasource.validation.workspace_forbidden") shouldBe true
        }
        refusals++

        // The super admin may — and the row then reads back with the new URL.
        update(ADMIN_KEY.plaintext, "sweep186-update-target", "jdbc:h2:mem:sweep186moved").first shouldBe 200
        acceptances++
        val readBack =
            given()
                .port(port)
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/sweep186-update-target")
                .then()
                .statusCode(200)
                .extract()
                .path<String>("data.jdbc_url")
        readBack shouldBe "jdbc:h2:mem:sweep186moved"
    }

    /** POST /api/v1/datasources → (status, body). */
    private fun register(
        key: String,
        name: String,
        dialect: String,
        jdbcUrl: String,
    ): Pair<Int, String> {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header("DP-API-Key", key)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to name,
                            "display_name" to "Sweep 186 $name",
                            "dialect" to dialect,
                            "jdbc_url" to jdbcUrl,
                            "username" to "sa",
                            "password" to "sa",
                        ),
                    ),
                ).`when`()
                .post("/api/v1/datasources")
                .thenReturn()
        return response.statusCode() to response.body().asString()
    }

    /** PUT /api/v1/datasources/{name} with a new jdbc_url → (status, body). */
    private fun update(
        key: String,
        name: String,
        jdbcUrl: String,
    ): Pair<Int, String> {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header("DP-API-Key", key)
                .body(
                    mapper.writeValueAsString(
                        mapOf("name" to name, "dialect" to "H2", "jdbc_url" to jdbcUrl, "username" to "sa"),
                    ),
                ).`when`()
                .put("/api/v1/datasources/$name")
                .thenReturn()
        return response.statusCode() to response.body().asString()
    }

    private enum class RoleOutcome { ACCEPT, REFUSE, REFUSE_ROOTS, REFUSE_FORM, UNEXPECTED }

    /** (label, dialect, jdbc_url) — one row per cell of the brief's sweep, 186b's mixed-case rows included. */
    private fun cases(underRoot: java.nio.file.Path): List<Case> =
        listOf(
            Case("h2-mem", "H2", "jdbc:h2:mem:sweep186mem", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("h2-file-in", "H2", "jdbc:h2:file:$underRoot/app", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("h2-file-out", "H2", "jdbc:h2:file:/etc/sweep186", RoleOutcome.REFUSE, RoleOutcome.REFUSE_ROOTS),
            Case("h2-file-dotdot", "H2", "jdbc:h2:file:$underRoot/../escape", RoleOutcome.REFUSE, RoleOutcome.REFUSE_ROOTS),
            Case("h2-tcp", "H2", "jdbc:h2:tcp://127.0.0.1:9/sweep186", RoleOutcome.ACCEPT, RoleOutcome.ACCEPT),
            // 186b: mixed-case prefixes are Unknown forms — refused for BOTH roles by the
            // validator (jdbc_url_malformed), never re-read as the lower-case form.
            Case("h2-mem-mixed", "H2", "jdbc:h2:MEM:sweep186mixed", RoleOutcome.REFUSE_FORM, RoleOutcome.REFUSE_FORM),
            Case("h2-tcp-mixed", "H2", "jdbc:h2:TCP://127.0.0.1:9/sweep186x", RoleOutcome.REFUSE_FORM, RoleOutcome.REFUSE_FORM),
            Case("h2-ssl-mixed", "H2", "jdbc:h2:SSL://127.0.0.1/sweep186x", RoleOutcome.REFUSE_FORM, RoleOutcome.REFUSE_FORM),
            Case("h2-file-mixed", "H2", "jdbc:h2:FILE:$underRoot/app", RoleOutcome.REFUSE_FORM, RoleOutcome.REFUSE_FORM),
            Case("duckdb-mem", "DUCKDB", "jdbc:duckdb::memory:", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("duckdb-file", "DUCKDB", "jdbc:duckdb:$underRoot/app.duckdb", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("duckdb-file-out", "DUCKDB", "jdbc:duckdb:/etc/sweep186.duckdb", RoleOutcome.REFUSE, RoleOutcome.REFUSE_ROOTS),
            // The driver matches :memory: case-insensitively (probed, 186b) — and md: is
            // refused in both cases (it reaches the network on connect).
            Case("duckdb-mem-mixed", "DUCKDB", "jdbc:duckdb::MEMORY:", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("duckdb-md-mixed", "DUCKDB", "jdbc:duckdb:MD:sweep186", RoleOutcome.REFUSE_FORM, RoleOutcome.REFUSE_FORM),
            Case("sqlite-mem", "SQLITE", "jdbc:sqlite::memory:", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("sqlite-file", "SQLITE", "jdbc:sqlite:$underRoot/app.db", RoleOutcome.REFUSE, RoleOutcome.ACCEPT),
            Case("sqlite-file-out", "SQLITE", "jdbc:sqlite:/etc/sweep186.db", RoleOutcome.REFUSE, RoleOutcome.REFUSE_ROOTS),
            // The xerial driver is case-SENSITIVE (probed, 186b): :MEMORY: is a literal
            // FILE named ":MEMORY:", so the form is InProcessFile — gated for the workspace
            // admin, roots-refused for the super admin (a relative path names no root).
            Case("sqlite-mem-mixed", "SQLITE", "jdbc:sqlite::MEMORY:", RoleOutcome.REFUSE, RoleOutcome.REFUSE_ROOTS),
            Case("pg-server", "POSTGRES", "jdbc:postgresql://db:5432/app", RoleOutcome.ACCEPT, RoleOutcome.ACCEPT),
        )

    /** The H2 database files under the process's working directory — 186b's "the driver created a file" witness. */
    private fun mvDbUnderCwd(): Set<String> =
        java.io
            .File(".")
            .walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name.endsWith(".mv.db") }
            .map { it.path }
            .toSet()

    private data class Case(
        val label: String,
        val dialect: String,
        val jdbcUrl: String,
        val wsAdmin: RoleOutcome,
        val superAdmin: RoleOutcome,
    )

    companion object {
        private val FILE_ROOT = Files.createTempDirectory("sweep186-roots")

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val WSADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val WORKSPACE_ID: String = UUID.randomUUID().toString()

        private val ADMIN_KEY = E2eAuth.generateKey("e2e-186-admin", arrayOf("read", "execute", "author"))
        private val WSADMIN_KEY = E2eAuth.generateKey("e2e-186-wsadmin", arrayOf("read", "execute", "author"))

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val random = SecureRandom()
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }

            // The sweep's two knobs: the member gate ON (the in-process rule must refuse
            // anyway), and the declared root the file-backed cells resolve against.
            registry.add("datapipelines.workspaces.member-datasources-enabled") { "true" }
            registry.add("datapipelines.datasources.file-roots") { FILE_ROOT.toString() }

            listOf("google").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-186-admin@datapipelines.test', 'E2E 186 Admin', 'test',
                             'e2e-186-admin-sub', TRUE, TRUE),
                            ('$WSADMIN_USER_ID', 'e2e-186-wsadmin@datapipelines.test', 'E2E 186 WsAdmin', 'test',
                             'e2e-186-wsadmin-sub', TRUE, FALSE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name)" +
                            " VALUES ('$WORKSPACE_ID', 'lane186-sweep', 'Lane 186 sweep')",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_USER_ID', 'workspace_admin')," +
                            " ('$WORKSPACE_ID', '$WSADMIN_USER_ID', 'workspace_admin')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        for ((key, owner) in listOf(ADMIN_KEY to ADMIN_USER_ID, WSADMIN_KEY to WSADMIN_USER_ID)) {
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(owner))
                            ps.setString(3, key.name)
                            ps.setString(4, key.hash)
                            ps.setArray(5, connection.createArrayOf("text", key.scopes))
                            ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
            }
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
