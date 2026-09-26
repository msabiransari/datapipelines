package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * #232 on the WIRE: the per-user limiter (rest-api §12) answers with its OWN sentence — the
 * catalog's request-volume message — on BOTH metered surfaces, and never with the login
 * damper's "Too many sign-in attempts". The report that opened the issue was a signed-in
 * caller throttled on `/mcp` by a filter that copied the shared exception's login default;
 * this suite holds the live answers to the sentence the catalog owns
 * (`ApiErrorCatalog.userMessageFor(rate_limit.exceeded)`), pinned as the LITERAL the caller
 * receives, the way `ApplicationSmokeTest` reads the CSP.
 *
 * The two arms also prove §12.1's shared budget across credentials: one seeded person's
 * SESSION spends the budget over REST, and their OWN MCP key — the same owner, so the same
 * budget — is the one refused on `/mcp`.
 *
 * Driving past the bound: the suite boots with `requests-per-minute = 1`, so the second
 * request of the same minute is the refusal. The two calls run back to back, and the minute
 * window is EPOCH-aligned (RedisRateLimiter), so a call pair that straddles a boundary would
 * leave the second call allowed — the walk re-requests in that case (at most a few times;
 * each new minute's budget is spent by its first allowed call), which is what makes this
 * deterministic rather than a race with a 1-in-a-few-hundred red.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PerUserRateLimitE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the per-user limiter answers with the API sentence on rest and on mcp - never the login one`() {
        ensureSeeded()

        // Arm A — the session's own budget on REST: the second request of the minute is the
        // refusal, and it carries the request-volume sentence.
        val restThrottled =
            drivePastBound {
                given()
                    .port(port)
                    .asSession(jwt(viewerA, "viewer-a@ratelimit.test"))
                    .`when`()
                    .get("/api/v1/pipelines")
                    .then()
                    .extract()
            }
        restThrottled.statusCode() shouldBe 429
        restThrottled.body().asString() shouldContain """"code":"rate_limit.exceeded""""
        restThrottled.body().asString() shouldContain API_SENTENCE
        restThrottled.body().asString() shouldNotContain LOGIN_SENTENCE

        // Arm B — one person, two credentials, ONE budget (§12.1): the session spends it over
        // REST (a fresh user's first request of a minute is deterministically allowed), and
        // the same person's MCP key is the one refused on /mcp.
        val spend =
            given()
                .port(port)
                .asSession(jwt(viewerB, "viewer-b@ratelimit.test"))
                .`when`()
                .get("/api/v1/pipelines")
                .then()
                .extract()
        spend.statusCode() shouldBe 200

        val mcpAnswer =
            drivePastBound {
                given()
                    .port(port)
                    .header("DP-API-Key", viewerBKey.plaintext)
                    .contentType(ContentType.JSON)
                    .accept("application/json, text/event-stream")
                    .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                    .`when`()
                    .post("/mcp")
                    .then()
                    .extract()
            }
        mcpAnswer.statusCode() shouldBe 429
        mcpAnswer.body().asString() shouldContain """"code":"rate_limit.exceeded""""
        mcpAnswer.body().asString() shouldContain API_SENTENCE
        mcpAnswer.body().asString() shouldNotContain LOGIN_SENTENCE
    }

    /**
     * Sends the request pair until one of them is the throttled answer: [request] returns the
     * next response each call, and the first 429 proves the bound was crossed. Each allowed
     * call spends the CURRENT minute bucket, so the following call inside that minute is
     * refused — the loop exists only for the epoch-boundary straddle, never for waiting.
     */
    private fun drivePastBound(request: () -> ExtractableResponse<Response>): ExtractableResponse<Response> {
        repeat(5) {
            val answer = request()
            if (answer.statusCode() == 429) return answer
        }
        error("the limiter never throttled the request in 5 attempts — the bound was not crossed")
    }

    private fun jwt(
        userId: UUID,
        email: String,
    ): String = E2eSession.jwt(jwtSecret, userId.toString(), email, workspace = WS_NAME)

    companion object {
        /** The catalog's sentence for `rate_limit.exceeded` — the LITERAL the caller receives (#232). */
        const val API_SENTENCE = "You're sending requests faster than we allow. Wait a moment and try again."

        /** The login damper's sentence — never an API throttle's answer. */
        const val LOGIN_SENTENCE = "Too many sign-in attempts"

        const val WS_NAME = "ratelimit232"

        private val wsId = UUID.randomUUID()
        private val viewerA = UUID.randomUUID()
        private val viewerB = UUID.randomUUID()

        private val viewerBKey = E2eAuth.generateKey("ratelimit232-mcp", ownerId = viewerB.toString())

        private val jwtSecret = E2eSession.newSecret()

        private var seeded = false

        private fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager
                .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO workspaces (id, name, display_name) VALUES ('$wsId', '$WS_NAME', 'Rate Limit 232')",
                        )
                        listOf(
                            "viewer-a" to viewerA,
                            "viewer-b" to viewerB,
                        ).forEach { (label, id) ->
                            statement.execute(
                                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                    "('$id', '$label@ratelimit.test', '$label', 'test', '$label-sub', TRUE, FALSE)",
                            )
                            statement.execute(
                                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$wsId', '$id', 'viewer')",
                            )
                        }
                        statement.execute(
                            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                                "('${viewerBKey.id}', '$viewerB', '$viewerB', '${viewerBKey.name}', " +
                                "'${viewerBKey.hash}', '$wsId', 'user', NULL)",
                        )
                    }
                }
        }

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            // The limiter under test: one request per user per minute makes the SECOND request
            // of any minute the refusal (the per-second default stays at its shipped 100).
            registry.add("datapipelines.rate-limit.requests-per-minute") { "1" }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
