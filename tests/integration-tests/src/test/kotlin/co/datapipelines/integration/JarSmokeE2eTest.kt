package co.datapipelines.integration

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * THE FAT-JAR SMOKE (025 B4 — the guard for T34's whole class): boots the REAL
 * `datapipelines-app.jar` as `java -jar` — the packaged classpath, not the exploded
 * classes every other E2E runs against — and drives the authenticated UI as a browser
 * does. T34's three defects (settings 500 `URI is not hierarchical`, dashboard partials
 * 500 `Error resolving fragment`, /error itself failing) were all invisible to the entire
 * suite precisely because nothing ever rendered a screen from inside a jar.
 *
 * Authentication is the deployment's own: a session JWT minted locally over the
 * configured HS256 secret mints an API key through the real endpoint (CSRF double-submit
 * included), and the screens are then fetched with `DP-API-Key` — a credential that
 * authenticates on every path (`ApiKeyFilter` has no path test), which is exactly the
 * honest first-visitor shape.
 *
 * ## What this does and does not cover
 *
 * Covered: every screen renders 200 from the PACKAGED classpath with real seeded data
 * behind it (dashboard + both dashboard partials, settings with the theme listing,
 * datasources with a NON-EMPTY listing, one execution detail, and 025b's two asset-heaviest
 * screens — the pipeline editor and the template editor), and the error page chain
 * (/error renders the failure page, the B3 cascade). Not covered: JavaScript execution
 * (htmx swaps, the OOB theme exchange) — this is an HTTP contract smoke; the browser
 * behaviors stay with the unit/E2E layers. The jar is rebuilt by the `bootJar` dependency
 * wired onto the integration test task, so the smoke always runs the CURRENT tree.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JarSmokeE2eTest {
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Boot state — instance vars under PER_CLASS lifecycle (one app per run). */
    private var oidcStub: HttpServer? = null
    private var app: Process? = null
    private var appLog: File? = null
    private var apiKey: String = ""

    @BeforeAll
    fun boot() {
        startOidcStub()
        startApp()
        seed()
        apiKey = mintApiKey()
    }

    @AfterAll
    fun shutdown() {
        app?.destroyForcibly()
        app?.waitFor()
        oidcStub?.stop(0)
    }

    // ------------------------------------------------------------------ the screens

    @Test
    fun `the dashboard and both partials render from the jar`() {
        get("/dashboard").second shouldBe 200
        // B2: the partials are fetched by the page's hx-gets — assert them directly.
        val stats = get("/partials/dashboard-stats")
        stats.second shouldBe 200
        stats.first shouldContain "Total Pipelines"
        val recent = get("/partials/recent-executions")
        recent.second shouldBe 200
        // The seeded execution is rendered — real content, not an error shell.
        recent.first shouldContain "SUCCESS"
        recent.first shouldContain SEEDED_EXECUTION
        noneCarriesErrorMarkers("/", "/partials/dashboard-stats", "/partials/recent-executions")
    }

    @Test
    fun `settings renders from the jar - the T21 class`() {
        val (body, status) = get("/settings")
        status shouldBe 200
        // B1 (025b): the listing must come from REAL jar-classpath enumeration, not the
        // controller's DEFAULT_THEMES fallback — the old `shouldContain "saas"` was satisfied
        // by the fallback itself (its first entry) and by the layout's themes/saas.css link,
        // so it was blind to exactly the silent-fallback failure it guarded. The vendored set
        // and the fallback carry the SAME nine names, so membership cannot observe
        // enumeration. ORDER can: the resolver returns the names SORTED (auto first, forest
        // before light), the fallback is hand-ordered (saas first, light before forest) — the
        // two inversions below are only producible by real enumeration.
        body shouldContain "value=\"auto\""
        body.indexOf("value=\"auto\"") shouldBeLessThan body.indexOf("value=\"saas\"")
        body.indexOf("value=\"forest\"") shouldBeLessThan body.indexOf("value=\"light\"")
        noneCarriesErrorMarkers("/settings")
    }

    @Test
    fun `the datasources partial renders a NON-EMPTY listing`() {
        val (body, status) = get("/partials/datasources")
        status shouldBe 200
        body shouldContain SEEDED_DATASOURCE
    }

    @Test
    fun `an execution detail renders from the jar`() {
        val (body, status) = get("/executions/$SEEDED_EXECUTION")
        status shouldBe 200
        body shouldContain "smoke"
        noneCarriesErrorMarkers("/executions/$SEEDED_EXECUTION")
    }

    @Test
    fun `the pipeline editor renders from the jar - the asset-heaviest screen`() {
        val (body, status) = get("/pipelines/$PIPELINE/editor")
        status shouldBe 200
        // The seeded pipeline's JSON is embedded for the client-side graph — real content,
        // not an editor shell over a missing record.
        body shouldContain "smoke_pipe"
        body shouldContain "pipeline-editor"
        noneCarriesErrorMarkers("/pipelines/$PIPELINE/editor")
    }

    @Test
    fun `the template editor renders from the jar`() {
        val (body, status) = get("/templates/editor?name=$SEEDED_TEMPLATE")
        status shouldBe 200
        body shouldContain "Smoke Template"
        noneCarriesErrorMarkers("/templates/editor?name=$SEEDED_TEMPLATE")
    }

    @Test
    fun `the error page itself renders - the B3 cascade stays dead`() {
        // T34(c): while B1/B2 were live, GET /error died with HttpMessageNotWritableException
        // and the failure page failed. Pinned: /error answers with the RENDERED failure page.
        val (body, status) = get("/error")
        status shouldBe 500
        body shouldContain "Server Error"
        body shouldNotContain "Whitelabel"
    }

    // ------------------------------------------------------------------ boot machinery

    private fun startOidcStub() {
        // The client-registration layer fetches real discovery at startup; a one-endpoint
        // stub satisfies it (the smoke never performs an OIDC login).
        oidcStub =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/.well-known/openid-configuration") { exchange ->
                    val issuer = "http://127.0.0.1:${exchange.localAddress.port}"
                    val doc =
                        """
                        {"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
                        "token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks",
                        "response_types_supported":["code"],
                        "subject_types_supported":["public"],
                        "id_token_signing_alg_values_supported":["RS256"]}
                        """.trimIndent().replace("\n", "")
                    val body = doc.toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
    }

    private fun startApp() {
        val jar = jarFile()
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val log = Files.createTempFile("jar-smoke", ".log").toFile()
        val pb =
            ProcessBuilder(javaBin, "-jar", jar.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(log)
        pb.environment().putAll(appEnvironment())
        app = pb.start()
        appLog = log
        val deadline = System.currentTimeMillis() + APP_BOOT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (app!!.isAlive.not()) error("Jar died during boot — log:\n${log.readText()}")
            // A refused connection is "not up yet", not a failure — java.net.http throws.
            val health =
                runCatching {
                    request("/health", apiKey = null, accept = "application/json").second
                }.getOrNull()
            if (health == 200) return
            Thread.sleep(500)
        }
        error("Jar did not become healthy in ${APP_BOOT_TIMEOUT_MS / 1000}s — log:\n${log.readText().takeLast(4000)}")
    }

    private fun appEnvironment(): Map<String, String> =
        mapOf(
            "SERVER_PORT" to appPort.toString(),
            // The jar's actuator would otherwise bind application.yml's fixed 9090. Serially that
            // was always free; under org.gradle.parallel another module's app-booting suite can hold
            // it at the same instant ("Port 9090 was already in use", 2026-09-03). 0 = ephemeral;
            // nothing here reads the management port — health goes through the app port.
            "MANAGEMENT_SERVER_PORT" to "0",
            "SPRING_DATASOURCE_URL" to postgres.jdbcUrl,
            "SPRING_DATASOURCE_USERNAME" to postgres.username,
            "SPRING_DATASOURCE_PASSWORD" to postgres.password,
            "DATAPIPELINES_REDIS_HOST" to redis.host,
            "DATAPIPELINES_REDIS_PORT" to redis.getMappedPort(REDIS_PORT).toString(),
            "DATAPIPELINES_JWT_SECRET" to jwtSecret,
            "DATAPIPELINES_DB_ENCRYPTION_KEY" to encryptionKey,
            "DATAPIPELINES_AUTH_BASE_URL" to "http://127.0.0.1:$appPort",
            "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_NAME" to "google",
            "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_ID" to "smoke",
            "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_SECRET" to "smoke",
            "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_ISSUER_URI" to "http://127.0.0.1:${oidcStub!!.address.port}",
        )

    private fun jarFile(): File {
        val override = System.getProperty("jarSmoke.jar")
        val jar =
            if (override != null) {
                File(override)
            } else {
                // The test task's working directory is this module; the jar lives at the
                // repo root (same root-walk discipline as the auth module's RepoFiles).
                File(repoRoot(), "modules/app/build/libs/datapipelines-app.jar")
            }
        check(jar.isFile) {
            "bootJar output not found at ${jar.absolutePath} — the integration test task " +
                "depends on :modules:app:bootJar; building by hand: ./gradlew :modules:app:bootJar"
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

    // ------------------------------------------------------------------ seeding + auth

    private fun seed() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, " +
                        "is_active, is_admin) VALUES ('$USER', 'smoke@test', 'Smoke', 'google', " +
                        "'smoke-sub', TRUE, TRUE)",
                )
                s.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'smoke', 'Smoke')")
                s.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$WORKSPACE', '$USER', 'owner')",
                )
                s.execute(
                    "INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, " +
                        "password_encrypted, created_by, is_readonly) VALUES ('$SEEDED_DATASOURCE', " +
                        "'Smoke DS', 'POSTGRES', '${postgres.jdbcUrl}', '${postgres.username}', " +
                        "'x'::bytea, '$USER', TRUE)",
                )
                s.execute(
                    "INSERT INTO pipelines (id, name, display_name, description, owner_id, " +
                        "workspace_id, current_version) VALUES ('$PIPELINE', 'smoke_pipe', " +
                        "'Smoke Pipe', '', '$USER', '$WORKSPACE', 1)",
                )
                s.execute(
                    "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash," +
                        " status, created_by, released_by, released_at) " +
                        "VALUES ('$PIPELINE', 1, " +
                        "'{\"name\":\"smoke_pipe\",\"nodes\":[],\"parameters\":{}}'::jsonb," +
                        " 'seed-hash', 'RELEASED', '$USER', '$USER', NOW())",
                )
                s.execute(
                    "INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, " +
                        "status, parameters_json, triggered_by, triggered_via, root_execution_id) " +
                        "VALUES ('$SEEDED_EXECUTION', '$PIPELINE', 1, 'SUCCESS', '{}'::jsonb, " +
                        "'$USER', 'REST', '$SEEDED_EXECUTION')",
                )
                s.execute(
                    "INSERT INTO templates (id, workspace_id, name, display_name, description, " +
                        "current_version, created_by) VALUES ('$TEMPLATE', '$WORKSPACE', " +
                        "'$SEEDED_TEMPLATE', 'Smoke Template', '', 1, '$USER')",
                )
                s.execute(
                    "INSERT INTO template_versions (template_id, version, engine, dialect, body," +
                        " body_hash, status, created_by, released_by, released_at)" +
                        " VALUES ('$TEMPLATE', 1, 'freemarker', 'POSTGRES', " +
                        "'SELECT 1 AS smoke', 'seed-hash', 'RELEASED', '$USER', '$USER', NOW())",
                )
            }
        }
    }

    /** A session JWT exactly as `JwtService.issue` does, over the secret this test configured. */
    private fun sessionJwt(): String {
        val now = Instant.now()
        val header = b64("""{"alg":"HS256","typ":"JWT"}""")
        val payload =
            b64(
                """{"sub":"$USER","email":"smoke@test","name":"Smoke",""" +
                    """"scopes":["read","execute","author","admin"],""" +
                    """"iss":"datapipelines","iat":${now.epochSecond},""" +
                    """"exp":${now.plusSeconds(3600).epochSecond},"active_workspace":"smoke"}""",
            )
        val signature =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
                b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
            }
        return "$header.$payload.$signature"
    }

    /** Mints the API key through the real endpoint: session cookie, CSRF double-submit. */
    private fun mintApiKey(): String {
        val csrf =
            HttpRequest
                .newBuilder(URI.create("$base/settings"))
                .header("Cookie", "dp_session=${sessionJwt()}")
                .build()
        val csrfResponse = http.send(csrf, HttpResponse.BodyHandlers.ofString())
        val token =
            csrfResponse
                .headers()
                .allValues("Set-Cookie")
                .firstOrNull { it.startsWith("dp_csrf=") }
                ?.substringAfter("dp_csrf=")
                ?.substringBefore(";")
                ?: error("no dp_csrf issued: ${csrfResponse.statusCode()}")
        val mint =
            HttpRequest
                .newBuilder(URI.create("$base/api/v1/auth/api-keys"))
                .header("Content-Type", "application/json")
                .header("Cookie", "dp_session=${sessionJwt()}; dp_csrf=$token")
                .header("DP-CSRF-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString("""{"name":"smoke-key","scopes":["read"]}"""))
                .build()
        val minted = http.send(mint, HttpResponse.BodyHandlers.ofString())
        check(minted.statusCode() == 201) { "key mint failed ${minted.statusCode()}: ${minted.body()}" }
        val match = Regex(""""key"\s*:\s*"(dpk_[^"]+)"""").find(minted.body()) ?: error("no key in ${minted.body()}")
        return match.groupValues[1]
    }

    // ------------------------------------------------------------------ http helpers

    private fun get(path: String): Pair<String, Int> = request(path, apiKey)

    private fun request(
        path: String,
        apiKey: String?,
        accept: String = "text/html",
    ): Pair<String, Int> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$base$path"))
                .header("Accept", accept)
        apiKey?.let { builder.header("DP-API-Key", it) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.body() to response.statusCode()
    }

    /** The T34 error markers — none of the screens may carry any of them. */
    private fun noneCarriesErrorMarkers(vararg paths: String) {
        paths.forEach { path ->
            val (body, _) = get(path)
            body shouldNotContain "Error resolving fragment"
            body shouldNotContain "Whitelabel"
            body shouldNotContain "URI is not hierarchical"
        }
    }

    private val base: String get() = "http://127.0.0.1:$appPort"

    private companion object {
        val USER = UUID.randomUUID().toString()
        val WORKSPACE = UUID.randomUUID().toString()
        val PIPELINE = UUID.randomUUID().toString()
        val TEMPLATE = UUID.randomUUID().toString()
        const val SEEDED_EXECUTION = "44444444-4444-4444-4444-444444444444"
        const val SEEDED_DATASOURCE = "smoke_ds"
        const val SEEDED_TEMPLATE = "smoke_tpl"
        const val REDIS_PORT = 6379
        const val APP_BOOT_TIMEOUT_MS = 120_000L

        val random = SecureRandom()

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

        // Known-to-the-test credentials: the session JWT is signed over the secret, and the
        // encryption key is 32 decoded bytes (§7). Generated per run; no literals (HIGH-2).
        private val jwtSecret: String = randomSecret()
        private val encryptionKey: String = randomSecret()
        private val appPort: Int = 5580 + random.nextInt(100)

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(32).also { random.nextBytes(it) })

        private fun b64(value: String): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
