package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.mail.Folder
import jakarta.mail.Session
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * **Scheduler notification mail OFF** (#442b, F4): mail is configured and reachable, the scheduler
 * dispatches, a schedule names recipients for every event — and
 * `datapipelines.scheduler.notifications.enabled` is false (the default). A full run writes no
 * notification event, no delivery and no notification trail row, and the box stays empty.
 *
 * Its own class because the flag is boot configuration ([ScheduleNotificationDeliveryE2eTest] runs
 * with it on). The events are written in the transition's own transaction, so a run that reached
 * its final state with zero event rows has provably enqueued nothing; the empty box agrees.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleNotificationMailOffE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `with the flag off a full run enqueues nothing and the box stays empty`() {
        seed()
        val schedule =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to "test/notify_off",
                            "payload" to mapOf("pipeline" to PIPELINE, "version" to "current"),
                            "parameters" to emptyMap<String, Any>(),
                            "cron" to YEARLY,
                            "timezone" to "UTC",
                            "notifications" to
                                mapOf("recipients" to listOf(OPS), "events" to listOf("start", "success", "failure", "unknown", "blocked")),
                        ),
                    ),
                ).post("/api/v1/schedules")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        // Non-vacuity: the schedule holds the recipient, and the deployment reports delivery off.
        schedule.getInt("data.notifications.recipient_count") shouldBe 1
        schedule.getString("data.notifications.delivery.state") shouldBe "off"
        schedule.getString("data.notifications.delivery.reason") shouldBe "disabled"
        val scheduleId = schedule.getString("data.id")

        val run =
            session()
                .post("/api/v1/schedules/$scheduleId/run")
                .then()
                .statusCode(202)
                .extract()
                .jsonPath()
                .getString("data.id")
        val detail =
            poll("run $run to reach a final state") {
                runDetail(scheduleId, run).takeIf { it["state"].asText() !in setOf("queued", "starting", "running") }
            }
        detail["state"].asText() shouldBe "succeeded"
        detail["trail"].map { it["kind"].asText() } shouldContainExactly listOf("recorded", "claimed", "execution_started", "finished")
        detail["deliveries"].size() shouldBe 0

        scalar("SELECT count(*) FROM schedule_notification_events WHERE schedule_id = '$scheduleId'") shouldBe "0"
        scalar("SELECT count(*) FROM schedule_notification_deliveries") shouldBe "0"
        inbox(OPS) shouldBe 0
    }

    private fun session() = given().port(port).asSession(ADMIN_SESSION)

    private fun runDetail(
        schedule: String,
        run: String,
    ): JsonNode = mapper.readTree(session().get("/api/v1/schedules/$schedule/runs/$run").asString())["data"]

    private fun <T : Any> poll(
        what: String,
        probe: () -> T?,
    ): T =
        assertTimeoutPreemptively(Duration.ofSeconds(POLL_BUDGET_SECONDS), { "timed out waiting for $what" }) {
            var found: T? = null
            while (found == null) {
                found = probe()
                if (found == null) Thread.sleep(POLL_MILLIS)
            }
            found
        }

    /** How many messages [address]'s box holds, read over IMAP. */
    private fun inbox(address: String): Int {
        val props =
            Properties().apply {
                setProperty("mail.store.protocol", "imap")
                setProperty("mail.imap.host", greenmail.host)
                setProperty("mail.imap.port", greenmail.getMappedPort(IMAP_PORT).toString())
            }
        val store = Session.getInstance(props).getStore("imap")
        store.connect(greenmail.host, greenmail.getMappedPort(IMAP_PORT), address, "any-password")
        return try {
            store.getFolder("INBOX").let { folder ->
                folder.open(Folder.READ_ONLY)
                folder.messageCount
            }
        } finally {
            store.close()
        }
    }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Notification off E2E')")
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                "('$ADMIN_ID', 'notify-off-admin@e2e.test', 'Notify Off Admin', 'test', 'notify-off-admin-sub', TRUE, TRUE)",
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')")
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE notify_off_rows (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)")
                statement.execute("INSERT INTO notify_off_rows VALUES (1, 'row-1')")
            }
        }
        registerCatalog()
    }

    /** The H2 datasource and its released template. */
    private fun registerCatalog() {
        session()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Notification off E2E H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
        val template =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to TEMPLATE,
                            "dialect" to "H2",
                            "display_name" to "Notification off rows",
                            "description" to "Rows for the flag-off run",
                            "imports" to emptyList<Any>(),
                            "body" to "SELECT id, label FROM notify_off_rows",
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        session()
            .contentType(ContentType.JSON)
            .header(IF_MATCH, template.jsonPath().getString("data.body_hash"))
            .body("""{"name": "$TEMPLATE"}""")
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
        releasePipeline()
    }

    private fun releasePipeline() {
        val pipeline =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "schema_version" to 1,
                            "name" to PIPELINE,
                            "display_name" to "Notification off",
                            "description" to "The flag-off run",
                            "nodes" to
                                listOf(
                                    mapOf(
                                        "id" to "rows",
                                        "type" to "DQL",
                                        "source" to H2_DATASOURCE,
                                        "description" to "Reads the seeded row",
                                        "template" to mapOf("id" to TEMPLATE, "version" to 1),
                                        "depends_on" to emptyList<String>(),
                                    ),
                                ),
                        ),
                    ),
                ).post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        session()
            .contentType(ContentType.JSON)
            .header(IF_MATCH, pipeline.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/${pipeline.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
    }

    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery(query).use { row -> if (row.next()) row.getString(1) else null }
        }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    companion object {
        private const val SMTP_PORT = 3025
        private const val IMAP_PORT = 3143
        private const val IF_MATCH = "If-Match"
        private const val POLL_BUDGET_SECONDS = 90L
        private const val POLL_MILLIS = 250L

        /** Never due during the suite: the run here is a Run now. */
        private const val YEARLY = "0 3 1 1 *"
        private const val OPS = "ops@deliver.test"
        private const val H2_DATASOURCE = "h2-notify-off-e2e"
        private const val H2_JDBC_URL = "jdbc:h2:mem:notifyoffe2e;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val TEMPLATE = "test/notify_off_rows.sql"
        private const val PIPELINE = "test/notify_off"
        private const val WORKSPACE = "notify-off-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "notify-off-admin@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /** The same relay and provisioning as the delivery suite (#317: boxes exist before the run). */
        @JvmStatic
        val greenmail: GenericContainer<*> =
            GenericContainer("greenmail/standalone:2.1.13")
                .withExposedPorts(SMTP_PORT, IMAP_PORT)
                .withEnv("GREENMAIL_ADDITIONAL_OPTS", "-Dgreenmail.users.login=email -Dgreenmail.users=dp:dp-any,ops:$OPS")
                .waitingFor(Wait.forListeningPorts(SMTP_PORT, IMAP_PORT).withStartupTimeout(Duration.ofMinutes(2)))

        init {
            greenmail.start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "https://scheduler-mail-off.e2e.test" }

            registry.add("datapipelines.mail.host") { greenmail.host }
            registry.add("datapipelines.mail.port") { greenmail.getMappedPort(SMTP_PORT) }
            registry.add("datapipelines.mail.username") { "dp" }
            registry.add("datapipelines.mail.password") { "dp-any" }
            registry.add("datapipelines.mail.starttls") { false }
            registry.add("datapipelines.mail.from") { "Data Pipelines <noreply@datapipelines.test>" }

            // Dispatching, with an allowlist that admits the recipient — only the flag is off.
            registry.add("datapipelines.scheduler.enabled") { "true" }
            registry.add("datapipelines.scheduler.tick-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.polling-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.notifications.enabled") { "false" }
            registry.add("datapipelines.scheduler.notifications.recipient-domains") { "deliver.test" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            greenmail.stop()
        }
    }
}
