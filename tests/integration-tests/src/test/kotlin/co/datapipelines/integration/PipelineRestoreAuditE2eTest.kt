package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * #379 on the real wire: the `pipeline.version.restored` audit row carries the pointer
 * PAIR — `current_version_before` and `current_version_after` — from the restore
 * statement's own snapshot (enums.md §15), on BOTH audit writers. The REST verb
 * (`POST /pipelines/{id}/versions/{v}/restore`) and the workspace dialog verb
 * (`POST /partials/pipelines/{id}/lifecycle/restore`) each persist one row whose
 * `details` are asserted WHOLE, key by key, including the explicit null before —
 * reading the persisted `audit_log` rows, never a mock call.
 *
 * The pointer's three shapes are each exercised: lower → higher restored (the move,
 * 1 → 2), higher → unchanged higher (a restore cannot lower the pointer, 2 → 2), and
 * null → restored (the last live version discarded leaves the pointer NULL). A failed
 * restore (a RELEASED target) and a cross-workspace attempt write NO success audit.
 * Release stamps survive the restore round-trip and the discard stamps clear.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PipelineRestoreAuditE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `a REST restore that moves the pointer up persists ONE row with before 1 and after 2`() {
        val id = seedReleasedPipeline("pra/pipelines/restore_move_${UUID.randomUUID()}", currentVersion = 2, versions = 1 to 2)

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("$API/pipelines/$id/versions/2/discard")
            .then()
            .statusCode(200)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("$API/pipelines/$id/versions/2/restore")
            .then()
            .statusCode(200)

        assertOneAuditRow(
            event = EVENT,
            needle = id,
            details =
                detailsNode(
                    "pipeline_id" to id,
                    "version" to 2,
                    "current_version_before" to 1,
                    "current_version_after" to 2,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
        withClue("the release stamps survive the restore round-trip; the discard stamps clear") {
            stampsOf(id, 2).let { stamps ->
                stamps.releasedAt shouldBe Instant.parse("2026-10-01T00:00:00Z")
                stamps.releasedBy shouldBe ADMIN_ID
                stamps.discardedAt shouldBe null
                stamps.discardedBy shouldBe null
            }
        }
    }

    @Test
    @Order(2)
    fun `the workspace dialog's restore persists the pair too - before 2 and after 2, a restore cannot lower the pointer`() {
        val id = seedReleasedPipeline("pra/pipelines/restore_hold_${UUID.randomUUID()}", currentVersion = 2, versions = 1 to 2)

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("$API/pipelines/$id/versions/1/discard")
            .then()
            .statusCode(200)

        val dialog =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("HX-Request", "true")
                .param("version", "1")
                .post("/partials/pipelines/$id/lifecycle/restore")
        dialog.then().statusCode(200).header("HX-Redirect", "/pipelines/$id?tab=versions&ok=restored")

        assertOneAuditRow(
            event = EVENT,
            needle = id,
            details =
                detailsNode(
                    "pipeline_id" to id,
                    "version" to 1,
                    "current_version_before" to 2,
                    "current_version_after" to 2,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
    }

    @Test
    @Order(3)
    fun `restoring the pipeline's last live version answers an explicit NULL before`() {
        val id = seedReleasedPipeline("pra/pipelines/restore_null_${UUID.randomUUID()}", currentVersion = 1, versions = 1 to 1)

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("$API/pipelines/$id/versions/1/discard")
            .then()
            .statusCode(200)
        withClue("discarding the only live version leaves the pointer NULL") {
            pointerOf(id) shouldBe null
        }

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("$API/pipelines/$id/versions/1/restore")
            .then()
            .statusCode(200)

        assertOneAuditRow(
            event = EVENT,
            needle = id,
            details =
                detailsNode(
                    "pipeline_id" to id,
                    "version" to 1,
                    "current_version_before" to null,
                    "current_version_after" to 1,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
    }

    @Test
    @Order(4)
    fun `a failed restore - a RELEASED target - writes no success audit`() {
        val id = pipelineOf("pra/pipelines/restore_null_")
        val before = auditRows(EVENT, id).size

        val refused = given().port(port).asSession(ADMIN_SESSION).post("$API/pipelines/$id/versions/1/restore")

        refused.then().statusCode(409)
        withClue("the refusal records nothing: the row count stands at the earlier success") {
            auditRows(EVENT, id).size shouldBe before
        }
    }

    @Test
    @Order(5)
    fun `another workspace's session cannot restore this workspace's version and records nothing`() {
        val restTarget = pipelineOf("pra/pipelines/restore_move_")
        val dialogTarget = pipelineOf("pra/pipelines/restore_hold_")
        val restBefore = auditRows(EVENT, restTarget).size
        val dialogBefore = auditRows(EVENT, dialogTarget).size

        given()
            .port(port)
            .asSession(OTHER_SESSION)
            .post("$API/pipelines/$restTarget/versions/2/restore")
            .then()
            .statusCode(404)
        given()
            .port(port)
            .asSession(OTHER_SESSION)
            .header("HX-Request", "true")
            .param("version", "1")
            .post("/partials/pipelines/$dialogTarget/lifecycle/restore")
            .then()
            .statusCode(404)

        withClue("the cross-workspace refusals record nothing on either writer") {
            auditRows(EVENT, restTarget).size shouldBe restBefore
            auditRows(EVENT, dialogTarget).size shouldBe dialogBefore
        }
    }

    // ---- the audit rows --------------------------------------------------------------------------------

    /** The expected row's `details` — the whole JSON, key by key; a null pair value is an explicit JSON null. */
    private fun detailsNode(vararg pairs: Pair<String, Any?>): ObjectNode {
        val node = mapper.createObjectNode()
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> node.put(key, value)
                is String -> node.put(key, value)
                null -> node.putNull(key)
            }
        }
        return node
    }

    /** Exactly ONE [EVENT] row naming [needle], by the workspace admin, whose `details` equal [details] ENTIRELY. */
    private fun assertOneAuditRow(
        event: String,
        needle: String,
        details: ObjectNode,
    ) {
        val rows = auditRows(event, needle)
        withClue("expected exactly one $event row naming $needle") { rows.size shouldBe 1 }
        val row = rows.single()
        row["user_id"].asText() shouldBe ADMIN_ID
        withClue("the row's details must be exactly the pair and its ids — nothing else rides it") {
            row["details"] shouldBe details
        }
    }

    private fun auditRows(
        event: String,
        needle: String,
    ): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            collectRows(connection, event, needle, out)
        }
        return out
    }

    private fun collectRows(
        connection: java.sql.Connection,
        event: String,
        needle: String,
        out: MutableList<JsonNode>,
    ) {
        connection
            .prepareStatement(
                "SELECT user_id, details_json FROM audit_log WHERE event = ? AND details_json::text LIKE ? ORDER BY timestamp ASC",
            ).use { ps ->
                ps.setString(1, event)
                ps.setString(2, "%$needle%")
                ps.executeQuery().use { rows ->
                    while (rows.next()) {
                        out.add(
                            mapper
                                .createObjectNode()
                                .put("user_id", rows.getString("user_id"))
                                .set("details", mapper.readTree(rows.getString("details_json"))),
                        )
                    }
                }
            }
    }

    // ---- the fixtures ----------------------------------------------------------------------------------

    /**
     * A pipeline with [versions] RELEASED rows, seeded SQL-first like the shared lifecycle
     * audit suite: the verbs under test are discard/restore, not authoring. Returns the id.
     */
    private fun seedReleasedPipeline(
        name: String,
        currentVersion: Int,
        versions: Pair<Int, Int>,
    ): String {
        val id = UUID.randomUUID().toString()
        sql(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                ('$id', '$name', 'Restore audit', '', '$ADMIN_ID', '$WORKSPACE_ID', $currentVersion)
            """.trimIndent(),
        )
        ((versions.first)..(versions.second)).forEach { v ->
            sql(
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                    ('$id', $v, '${pipelineBody(name, v)}'::jsonb, 'hash-pra-$v', 'RELEASED',
                     '$ADMIN_ID', '$ADMIN_ID', '$RELEASED_STAMP')
                """.trimIndent(),
            )
        }
        return id
    }

    /** The one row the earlier orders seeded, found by the name's stable prefix. */
    private fun pipelineOf(prefix: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT id FROM pipelines WHERE workspace_id = ?::uuid AND name LIKE ?",
                ).use { ps ->
                    ps.setString(1, WORKSPACE_ID)
                    ps.setString(2, "$prefix%")
                    ps.executeQuery().use { rows ->
                        rows.next()
                        return rows.getString("id")
                    }
                }
        }
    }

    private fun pointerOf(id: String): Int? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            readPointer(connection, id)
        }

    private fun readPointer(
        connection: java.sql.Connection,
        id: String,
    ): Int? =
        connection
            .prepareStatement("SELECT current_version FROM pipelines WHERE id = ?::uuid")
            .use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rows ->
                    rows.next()
                    val value = rows.getInt("current_version")
                    if (rows.wasNull()) null else value
                }
            }

    /**
     * (released_at, released_by, discarded_at, discarded_by) of one version row, read straight
     * off the table. The instant is read as an [OffsetDateTime] and normalized — a timestamptz's
     * string form follows the SESSION timezone, its instant does not.
     */
    private fun stampsOf(
        id: String,
        version: Int,
    ): VersionStamps {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT released_at, released_by, discarded_at, discarded_by FROM pipeline_versions" +
                        " WHERE pipeline_id = ?::uuid AND version = ?",
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setInt(2, version)
                    ps.executeQuery().use { rows ->
                        rows.next()
                        return VersionStamps(
                            releasedAt = rows.getObject("released_at", OffsetDateTime::class.java).toInstant(),
                            releasedBy = rows.getString("released_by"),
                            discardedAt = rows.getObject("discarded_at", OffsetDateTime::class.java)?.toInstant(),
                            discardedBy = rows.getString("discarded_by"),
                        )
                    }
                }
        }
    }

    /** The four stamp columns; `discarded_*` must read back SQL NULL after a restore. */
    private data class VersionStamps(
        val releasedAt: Instant?,
        val releasedBy: String?,
        val discardedAt: Instant?,
        val discardedBy: String?,
    )

    private fun pipelineBody(
        name: String,
        version: Int,
    ): String =
        """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"pra/templates/read-$version.sql","version":1}}]}"""

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        const val EVENT = "pipeline.version.restored"
        const val API = "/api/v1"
        const val WORKSPACE = "pra-restore"
        const val OTHER_WORKSPACE = "pra-restore-other"

        /** A FIXED release stamp, so the round-trip equality is exact, not "some time before". */
        const val RELEASED_STAMP = "2026-10-01 00:00:00+00"

        val WORKSPACE_ID = UUID.randomUUID().toString()
        val ADMIN_ID = UUID.randomUUID().toString()
        val OTHER_WORKSPACE_ID = UUID.randomUUID().toString()
        val OTHER_ADMIN_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "pra-admin@e2e.test", WORKSPACE)
        private val OTHER_SESSION get() = E2eSession.jwt(JWT_SECRET, OTHER_ADMIN_ID, "pra-other@e2e.test", OTHER_WORKSPACE)

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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES " +
                            "('$WORKSPACE_ID', '$WORKSPACE', 'Pipeline restore audit E2E') ON CONFLICT (id) DO NOTHING",
                    )
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES " +
                            "('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Pipeline restore audit E2E other') ON CONFLICT (id) DO NOTHING",
                    )
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active) VALUES
                            ('$ADMIN_ID', 'pra-admin@e2e.test', 'PRA Admin', 'test', 'pra-admin-sub', TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active) VALUES
                            ('$OTHER_ADMIN_ID', 'pra-other@e2e.test', 'PRA Other', 'test', 'pra-other-sub', TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin') ON CONFLICT DO NOTHING",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_ADMIN_ID', 'workspace_admin') ON CONFLICT DO NOTHING",
                    )
                }
            }
        }
    }
}
