package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.io.path.writeText

/**
 * Sample data, slice A, end to end (design 2026-08-16-sample-data §6/§6.1/D9): the WHOLE
 * application boots with a bootstrap datasources file and an examples file, and the proof is the
 * **rows in the metadata database** — not a return value, not a log line.
 *
 * ## What is real here and what is simulated
 * Real: Flyway on a clean Postgres, the config keys as an operator sets them, the bootstrap
 * startup step at its actual point in the lifecycle, the shipped registration/validation/
 * encryption path, the shipped import services.
 *
 * Simulated: the OIDC callback. `OidcSuccessHandler` needs a real identity provider, so the
 * `auto-per-user` first login is driven at the seam that handler calls —
 * `UserService.findOrCreateByEmail` then `WorkspaceService.workspaceForLogin` — with everything
 * beneath it production. (`OidcLoginIntegrationTest` in `auth` covers the callback itself
 * against Keycloak.)
 *
 * ## Why reflection
 * `tests/integration-tests` may depend on `:modules:app` only (module-structure §4.2), and `app`
 * exposes `web` as `implementation` — so `UserService` and `WorkspaceService` are on the runtime
 * classpath but not the compile classpath. Beans are therefore resolved **by name**, the same
 * concession `ApplicationSmokeTest` makes for the composition beans. Every assertion below is
 * still made against SQL.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class SampleDataBootstrapE2eTest {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var applicationContext: ApplicationContext

    // ------------------------------------------------------------------ registration at boot

    @Test
    fun `startup registered both entries with their flags, global scope and the bootstrap actor`() {
        val actorId = bootstrapActorId()

        rows("SELECT name, is_readonly, workspace_id, created_by, is_deleted FROM datasources ORDER BY name")
            .map { it["name"] } shouldContainExactly listOf(BOOT_RO, BOOT_RW)

        val readonly = row("SELECT * FROM datasources WHERE name = '$BOOT_RO'")
        readonly["is_readonly"] shouldBe true
        // `global: true` = workspace_id NULL (metadata-db §4.10).
        readonly["workspace_id"].shouldBeNull()
        readonly["created_by"] shouldBe actorId
        readonly["display_name"] shouldBe "Bootstrapped read-only"

        val writable = row("SELECT * FROM datasources WHERE name = '$BOOT_RW'")
        writable["is_readonly"] shouldBe false
        writable["workspace_id"].shouldBeNull()
        writable["created_by"] shouldBe actorId

        // The `${'$'}{BOOTSTRAP_E2E_PASSWORD}` placeholder resolved against the process environment
        // and the resolved value was encrypted on the way in — the plaintext is not on disk.
        val hex = scalar<String>("SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = '$BOOT_RO'")
        hex.contains(PASSWORD.toByteArray().joinToString("") { "%02x".format(it) }) shouldBe false
    }

    @Test
    fun `the bootstrap actor row is the pre-provisioned admin, granted exactly once`() {
        val actor = row("SELECT * FROM users WHERE provider = 'bootstrap'")

        actor["email"] shouldBe ADMIN_EMAIL
        actor["provider_subject"] shouldBe ADMIN_EMAIL
        actor["display_name"] shouldBe "bootstrap-e2e-admin"
        actor["is_active"] shouldBe true
        actor["is_admin"] shouldBe true
        actor["last_login_at"].shouldBeNull()

        scalar<Long>("SELECT COUNT(*) FROM audit_log WHERE event = 'auth.user.admin_granted'") shouldBe 1L
        scalar<String>("SELECT details_json->>'actor' FROM audit_log WHERE event = 'auth.user.admin_granted'") shouldBe "bootstrap"
    }

    // ------------------------------------------------------------------ the restart

    @Test
    fun `running the startup step again changes nothing, logs the skip, and leaks no credential`() {
        val before = datasourceSnapshot()
        val users = scalar<Long>("SELECT COUNT(*) FROM users")

        val lines = capturingLogs { invokeStartupAgain() }

        datasourceSnapshot() shouldBe before
        scalar<Long>("SELECT COUNT(*) FROM users") shouldBe users
        scalar<Long>("SELECT COUNT(*) FROM audit_log WHERE event = 'auth.user.admin_granted'") shouldBe 1L

        lines.any { it.contains("datasource.bootstrap_skipped") } shouldBe true
        lines.any { it.contains("datasource.bootstrap_registered") } shouldBe false
        // The credential travelled through this path twice now; no line carries it.
        lines.none { it.contains(PASSWORD) } shouldBe true
    }

    // ------------------------------------------------------------------ D9

    @Test
    fun `an auto-per-user first login gets a personal workspace holding the imported examples`() {
        val email = "demo-${UUID.randomUUID().toString().take(8)}@example.com"

        val (userId, workspaceId) = firstLogin(email)

        // The workspace is the personal one provisioning just created...
        row("SELECT * FROM workspaces WHERE id = '$workspaceId'")["is_personal"] shouldBe true

        // ...and it holds the examples, read back through the same tables the REST API reads.
        rows("SELECT name FROM pipelines WHERE workspace_id = '$workspaceId' ORDER BY name")
            .map { it["name"] } shouldContainExactly listOf(EXAMPLE_PIPELINE)
        rows("SELECT name FROM templates WHERE workspace_id = '$workspaceId' ORDER BY name")
            .map { it["name"] } shouldContainExactly listOf(EXAMPLE_TEMPLATE)

        // Imported as the new user, into their own workspace — not as the bootstrap admin.
        scalar<UUID>("SELECT owner_id FROM pipelines WHERE workspace_id = '$workspaceId'") shouldBe userId

        // The example pipeline reads the bootstrap-registered readonly datasource: §12 validation
        // resolved that reference at import time, which is the two halves of this slice meeting.
        scalar<String>(
            "SELECT v.body_json::text FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                " WHERE p.workspace_id = '$workspaceId'",
        ).shouldContain(BOOT_RO)
    }

    @Test
    fun `an examples fixture that does not import fails the provisioning login loudly`() {
        // Structurally fine, so it passes the startup read — and semantically broken, so it fails
        // §12 validation at import. The failure must reach the login rather than hand the user a
        // personal workspace that is quietly missing its examples.
        val broken =
            writeFile(
                "broken-examples.json",
                examplesJson().replace("\"source\": \"$BOOT_RO\"", "\"source\": \"no-such-datasource\""),
            )

        bootAppAnd(mapOf("datapipelines.bootstrap.examples-file" to broken.toString())) { context ->
            val email = "broken-${UUID.randomUUID().toString().take(8)}@example.com"

            lateinit var error: Throwable
            val lines = capturingLogs { error = assertThrows<Exception> { firstLogin(email, context) } }

            // §13.2 `pipeline.import.missing_datasource` — the missing name travels in `details`,
            // so the message is what a caller of this suite can assert on.
            rootCauseMessage(error).shouldContain("has unmet dependencies in this environment")
            rootCauseMessage(error).shouldContain(EXAMPLE_PIPELINE)

            // 048/§A — and the operator can find it. The refusal is deliberate; what it lacked
            // was an event: "I can't log in" against a 500 with nothing structured behind it was
            // an unanswerable support report (reported by 042 as T63). The line names the
            // fixture that failed and the catalogued code, beside the workspace and the user.
            val failure = lines.single { it.contains("event=workspace.examples_seed_failed") }
            failure.shouldContain("fixture_kind=pipeline")
            failure.shouldContain("fixture=$EXAMPLE_PIPELINE")
            failure.shouldContain("error_code=pipeline.import.missing_datasource")
            failure.shouldContain("user_id=")
            lines.none { it.contains("event=workspace.examples_seeded") } shouldBe true
            // And the workspace it was seeding is not left behind as a usable empty one: the
            // login failed, so nothing downstream of provisioning ran.
            scalar<Long>(
                "SELECT COUNT(*) FROM pipelines p JOIN workspaces w ON w.id = p.workspace_id WHERE w.name LIKE 'broken-%'",
            ) shouldBe
                0L
        }
    }

    @Test
    fun `an unreadable examples file refuses startup rather than waiting for a first login`() {
        val error =
            assertThrows<Exception> {
                bootApp(mapOf("datapipelines.bootstrap.examples-file" to fixtures.resolve("absent-examples.json").toString())).close()
            }

        rootCauseMessage(error).shouldContain("could not be read")
    }

    // ------------------------------------------------------------------ fail-fast, whole app

    @Test
    fun `an unresolved placeholder refuses startup, naming the variable`() {
        val file = writeFile("unresolved.yml", bootstrapYaml(password = "\${BOOTSTRAP_E2E_MISSING_VARIABLE}"))

        val error = assertThrows<Exception> { bootApp(mapOf("datapipelines.bootstrap.datasources-file" to file.toString())).close() }

        rootCauseMessage(error).shouldContain("BOOTSTRAP_E2E_MISSING_VARIABLE")
        rootCauseMessage(error).shouldContain("not set in this process's environment")
    }

    @Test
    fun `an entry failing section 9 validation refuses startup`() {
        // A refused server-managed key under `properties.hikari` (§5.6) — the same failure a REST
        // create would give, arriving at boot instead.
        val file =
            writeFile(
                "invalid.yml",
                """
                datasources:
                  - name: bootstrap-e2e-invalid
                    dialect: H2
                    jdbc_url: jdbc:h2:mem:bootstrap_e2e_invalid;DB_CLOSE_DELAY=-1
                    username: sa
                    password: sa
                    properties:
                      hikari:
                        jdbcUrl: jdbc:h2:mem:elsewhere
                    readonly: true
                    global: true
                """.trimIndent(),
            )

        val error = assertThrows<Exception> { bootApp(mapOf("datapipelines.bootstrap.datasources-file" to file.toString())).close() }

        // The refusal carries the §9 rule's own message. `DatasourceValidationException` keeps the
        // catalogued code (`datasource.validation.properties_invalid`) in a field rather than in
        // `message`, and this suite may not name that type (module-structure §4.2), so the
        // assertion is on the §5.6 text — which no other rule produces.
        rootCauseMessage(error).shouldContain("'jdbcUrl' is server-managed and cannot be set under properties.hikari")
    }

    @Test
    fun `a datasources file without a bootstrap admin email refuses startup, naming both keys`() {
        val error =
            assertThrows<Exception> {
                bootApp(
                    mapOf(
                        "datapipelines.bootstrap.datasources-file" to datasourcesFile.toString(),
                        "datapipelines.auth.bootstrap-admin-email" to "",
                    ),
                ).close()
            }

        val message = rootCauseMessage(error)
        message.shouldContain("datapipelines.bootstrap.datasources-file")
        message.shouldContain("datapipelines.auth.bootstrap-admin-email")
    }

    // ------------------------------------------------------------------ helpers

    /** Re-invokes the very object the container calls at startup — a restart of that step. */
    private fun invokeStartupAgain() {
        val bean = applicationContext.getBean("bootstrapDatasourceStartup")
        bean.javaClass.getMethod("afterSingletonsInstantiated").invoke(bean)
    }

    /**
     * The `auto-per-user` first login, at the two calls `OidcSuccessHandler` makes.
     * @return the new user's id and the id of the workspace login stamped.
     */
    private fun firstLogin(
        email: String,
        context: ApplicationContext = applicationContext,
    ): Pair<UUID, UUID> {
        val userService = context.getBean("userService")
        val user =
            userService.javaClass
                .getMethod(
                    "findOrCreateByEmail",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ).invoke(userService, email, "Demo User", null, "google", "sub-${UUID.randomUUID()}")
        val userId = user.javaClass.getMethod("getId").invoke(user) as UUID

        val workspaceService = context.getBean("workspaceService")
        val context =
            workspaceService.javaClass.methods
                .first { it.name == "workspaceForLogin" }
                .invoke(workspaceService, user, email)
        val workspaceId = context.javaClass.getMethod("getId").invoke(context) as UUID
        return userId to workspaceId
    }

    /**
     * Boots the whole application with [overrides] on top of this class's infrastructure, to prove
     * a refusal is the WHOLE app refusing rather than one bean throwing in isolation.
     *
     * Values go in as command-line arguments, not `SpringApplicationBuilder.properties(...)`:
     * that method installs them as *default* properties, the lowest-precedence source, so
     * `application.yml`'s `${'$'}{SPRING_DATASOURCE_URL}` would still win and fail to resolve.
     * The context is SERVLET because auth's `SecurityFilterChain` needs `HttpSecurity`, which only
     * exists in a servlet web application; `server.port=0` never binds, because the bootstrap step
     * runs inside `refresh()` and throws before the connector starts.
     */
    private fun bootApp(overrides: Map<String, String>): ConfigurableApplicationContext {
        val args = (baseProperties() + overrides).map { (key, value) -> "--$key=$value" }.toTypedArray()
        return SpringApplicationBuilder(DatapipelinesApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .run(*args)
    }

    /** Boots, runs [block] against that context, and always closes it. */
    private fun bootAppAnd(
        overrides: Map<String, String>,
        block: (ApplicationContext) -> Unit,
    ) = bootApp(overrides).use(block)

    private fun rootCauseMessage(error: Throwable): String {
        var current: Throwable = error
        val seen = StringBuilder(current.message.orEmpty())
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
            seen.append('\n').append(current.message.orEmpty())
        }
        return seen.toString()
    }

    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage + " " + it.argumentArray?.joinToString(" ") }
    }

    /** Every column a re-registration could disturb. */
    private fun datasourceSnapshot(): List<Map<String, Any?>> =
        rows(
            "SELECT name, display_name, dialect, jdbc_url, username, encode(password_encrypted, 'hex') AS pw," +
                " properties_json::text AS props, is_readonly, is_deleted, workspace_id, created_by, created_at, updated_at" +
                " FROM datasources ORDER BY name",
        )

    private fun bootstrapActorId(): UUID = scalar("SELECT id FROM users WHERE provider = 'bootstrap'")

    private fun rows(sql: String): List<Map<String, Any?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    generateSequence { if (rs.next()) columns.associateWith { rs.getObject(it) } else null }.toList()
                }
            }
        }

    private fun row(sql: String): Map<String, Any?> = rows(sql).single()

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(sql: String): T = rows(sql).single().values.first() as T

    companion object {
        private const val SECRET_BYTES = 32

        private const val ADMIN_EMAIL = "bootstrap-e2e-admin@example.com"
        private const val BOOT_RO = "bootstrap-e2e-readonly"
        private const val BOOT_RW = "bootstrap-e2e-writable"
        private const val EXAMPLE_TEMPLATE = "bootstrap_e2e_example.sql"
        private const val EXAMPLE_PIPELINE = "bootstrap_e2e_example"

        /**
         * The file's literal credential. Placeholder RESOLUTION is proved against an injected
         * environment in the module suites (`BootstrapDatasourceFileReaderTest`,
         * `BootstrapDatasourceRegistrarIntegrationTest`); what this suite adds is the negative
         * through the real `System.getenv` — see the unresolved-placeholder test, which needs no
         * variable set and would therefore be unaffected by any test-JVM environment.
         */
        private const val PASSWORD = "bootstrap-e2e-file-secret"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()

        private val fixtures: Path = Files.createTempDirectory("bootstrap-e2e")
        private val datasourcesFile: Path = writeFile("bootstrap-datasources.yml", bootstrapYaml())
        private val examplesFile: Path = writeFile("examples.json", examplesJson())

        private fun writeFile(
            name: String,
            content: String,
        ): Path = fixtures.resolve(name).also { it.writeText(content) }

        /** Two entries: one readonly, one not — so the flag is proven in both directions. */
        private fun bootstrapYaml(password: String = PASSWORD): String =
            """
            datasources:
              - name: $BOOT_RO
                display_name: Bootstrapped read-only
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_e2e_ro;DB_CLOSE_DELAY=-1
                username: sa
                password: $password
                readonly: true
                global: true
              - name: $BOOT_RW
                dialect: H2
                jdbc_url: jdbc:h2:mem:bootstrap_e2e_rw;DB_CLOSE_DELAY=-1
                username: sa
                password: $password
                readonly: false
                global: true
            """.trimIndent()

        /**
         * A template and a pipeline that reads the bootstrap-registered readonly datasource — the
         * demo's own shape, and the reason §12 validation at import time is a real check here.
         */
        private fun examplesJson(): String =
            """
            {
              "templates": [
                {
                  "id": "$EXAMPLE_TEMPLATE",
                  "dialect": "H2",
                  "display_name": "Bootstrap example",
                  "description": "Seeded into every personal workspace",
                  "imports": [],
                  "body": "SELECT 1 AS n"
                }
              ],
              "pipelines": [
                {
                  "schema_version": 1,
                  "name": "$EXAMPLE_PIPELINE",
                  "display_name": "Bootstrap example",
                  "description": "Reads the seeded read-only datasource",
                  "parameters": {},
                  "nodes": [
                    {
                      "id": "read_sample",
                      "description": "DQL read from the seeded read-only datasource",
                      "type": "DQL",
                      "source": "$BOOT_RO",
                      "template": {"id": "$EXAMPLE_TEMPLATE", "version": 1},
                      "output": {"target": "caller"},
                      "depends_on": []
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        // OWN Postgres, deliberately not the shared one: the bootstrap this suite verifies
        // runs AT CONTEXT BOOT — before any test method could clean — and the suite's
        // assertions count whole tables (users, admin_granted audit rows). A database that
        // starts empty and stays owned by one suite is the honest fixture for both.
        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        private val redis get() = SharedE2e.redis

        private val oidc = OidcDiscoveryStub()

        /** The same infrastructure the annotated context uses, as a plain map for [bootApp]. */
        @JvmStatic
        fun baseProperties(): Map<String, String> =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to SharedE2e.redisPort.toString(),
                "spring.data.redis.password" to "",
                "datapipelines.redis.host" to redis.host,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to jwtSecret,
                "datapipelines.db.encryption-key" to encryptionKey,
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test Google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                "datapipelines.auth.bootstrap-admin-email" to ADMIN_EMAIL,
                "datapipelines.workspaces.provisioning-mode" to "auto-per-user",
            )

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            baseProperties().forEach { (key, value) -> registry.add(key) { value } }
            registry.add("datapipelines.bootstrap.datasources-file") { datasourcesFile.toString() }
            registry.add("datapipelines.bootstrap.examples-file") { examplesFile.toString() }
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
