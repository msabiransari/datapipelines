package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * The A.5 acceptance (the record's §8.3, #299): a REAL export envelope travels — REST export,
 * REST import, the C29 refusal, and a promotion batch received by a REAL receiver deployment —
 * and lands whole every time. Before #299 the envelope never bound: the `parameter_set` node's
 * lifecycle keys refused the strict `ParameterSetBody` mapper, so every import answered
 * `body_invalid` and every batch carrying a set rolled back whole; the bundled `templates`
 * were read off the wrong node and silently skipped.
 *
 * ## Three deployments, because ids are global (P24)
 * `parameter_sets.id` is the PRIMARY KEY of the whole server, so a kept id can land only on a
 * database that does not hold it: the source (which authors and exports), the import target
 * (authoring on — import is not an authoring write), and the promotion receiver (authoring off,
 * the pre-shared key). One scratch database each — a receiver refuses to boot beside ANY draft,
 * so none of them can share the shared E2E database. What landed in the promotion target is
 * asserted against its DATABASE, not against the receiver's own report.
 */
@ExtendWith(SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ParameterSetTransferE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    /** REST on the SOURCE deployment. */
    private fun rest(
        method: String,
        path: String,
        session: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> {
        var request = given().port(port).contentType(ContentType.JSON).asSession(session)
        headers.forEach { (name, value) -> request = request.header(name, value) }
        if (body != null) request = request.body(body)
        val sent = request.`when`()
        val response =
            when (method) {
                "POST" -> sent.post(path)
                else -> sent.get(path)
            }.then().extract()
        return response.statusCode() to mapper.readTree(response.asString())
    }

    /** REST on one of the two hand-booted deployments. */
    private fun restOn(
        target: Int,
        method: String,
        path: String,
        session: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> {
        var request = given().port(target).contentType(ContentType.JSON).asSession(session)
        headers.forEach { (name, value) -> request = request.header(name, value) }
        if (body != null) request = request.body(body)
        val sent = request.`when`()
        val response =
            when (method) {
                "POST" -> sent.post(path)
                else -> sent.get(path)
            }.then().extract()
        return response.statusCode() to mapper.readTree(response.asString())
    }

    private fun errorCode(envelope: JsonNode): String = envelope.path("error").path("code").asText()

    @Test
    @Order(1)
    fun `fixture - tables, the released template and set in the source, targets provisioned`() {
        // The source context is up (its Flyway ran) — its auth rows can be seeded now.
        seedWorkspaces(sourceJdbc, WS_SOURCE)
        seedTables()
        registerDatasourceOn(port, sessionFor(WS_SOURCE))
        createReleasedTemplate(TEMPLATE)
        createReleasedSet()
        createReleasedTemplate(TEMPLATE_2)
        createReleasedSet(SET_NAME_2)
        // The import target: its own deployment, its own datasource, the grant to both workspaces.
        registerDatasourceOn(restTargetPort, sessionFor(WS_REST))
        grantOn(checkNotNull(restTarget), DATASOURCE, WS_C29)
        // The promotion receiver: authoring off, but a datasource registration is not an authoring
        // write (the two-deployment E2E registers on its receiver the same way). The second
        // receiver workspace exists for the C29-at-landing case: the id PK is global (P24).
        registerDatasourceOn(receiverPort, sessionFor(WS_PROMO))
        grantOn(checkNotNull(receiver), DATASOURCE, WS_PROMO2)
    }

    @Test
    @Order(2)
    fun `the export envelope imports into a second deployment whole - id kept, released, hash equal, template landed`() {
        val envelope = export()
        val parameterSet = envelope.path("parameter_set")
        val exportedHash = parameterSet.path("body_hash").asText()

        val (status, imported) =
            restOn(restTargetPort, "POST", "/api/v1/parameter-sets/import", sessionFor(WS_REST), envelopeText(envelope))

        withClue("import must land the envelope: $imported") { status shouldBe 200 }
        val data = imported.path("data")
        data.path("id").asText() shouldBe parameterSet.path("id").asText()
        data.path("import_created").asBoolean() shouldBe true
        data.path("status").asText() shouldBe "RELEASED"
        data.path("version").asInt() shouldBe parameterSet.path("version").asInt()
        data.path("body_hash").asText() shouldBe exportedHash
        withClue("the pinned template must exist in the target workspace") {
            templatePresentOn(restTargetPort, WS_REST)
        }
    }

    @Test
    @Order(3)
    fun `a second identical import is the idempotent no-op`() {
        val (status, imported) =
            restOn(restTargetPort, "POST", "/api/v1/parameter-sets/import", sessionFor(WS_REST), envelopeText(export()))

        withClue("the no-op re-import must answer 200: $imported") { status shouldBe 200 }
        imported.path("data").path("import_created").asBoolean() shouldBe false
        imported.path("data").path("import_unchanged").asBoolean() shouldBe true
    }

    @Test
    @Order(4)
    fun `the same envelope into another workspace refuses id_taken - and the bundled templates it landed stay`() {
        val (probeStatus, probe) = restOn(restTargetPort, "GET", "/api/v1/templates?name=$TEMPLATE", sessionFor(WS_C29))

        val (status, refused) =
            restOn(restTargetPort, "POST", "/api/v1/parameter-sets/import", sessionFor(WS_C29), envelopeText(export()))

        withClue("C29: the refusal, not a re-issue: $refused") { status shouldBe 409 }
        errorCode(refused) shouldBe "parameter.version.conflict"
        val details = refused.path("error").path("details")
        details.path("reason").asText() shouldBe "id_taken"
        // The templates landed BEFORE the set was judged (the §8.3 order) and a set refusal
        // leaves them (§18 C35 — the import is not atomic across templates and set).
        withClue("the bundled template must have landed in $WS_C29 before the refusal (probe was $probeStatus)") {
            templatePresentOn(restTargetPort, WS_C29)
        }
    }

    @Test
    @Order(5)
    fun `a promotion batch carrying a set is received whole - the set live in the target workspace`() {
        // A CONSTANTS-only set: the receive's binding (#299) is what this case proves — with the
        // bind reverted the receiver refuses the whole batch. Its sibling case below carries the
        // TEMPLATE-BACKED set (#302 — the C36 wall, since the receive's validation runs outside
        // the one transaction).
        createReleasedConstantsSet()
        val envelope = exportOf(CONSTANTS_SET_NAME)
        val (status, applied) = pushBatch(batchOf(WS_PROMO, envelope))

        withClue("the receiver must accept the batch: $applied") { status shouldBe 200 }
        applied.path("data").path("parameter_sets").asInt() shouldBe 1

        // What the TARGET holds — read from the receiver's database, not from its report.
        val exportedHash = envelope.path("parameter_set").path("body_hash").asText()
        val exportedId = envelope.path("parameter_set").path("id").asText()
        val receiverDb = checkNotNull(receiver).jdbc
        val row =
            receiverDb.row(
                "SELECT s.workspace_id::text AS ws, v.status AS status, v.body_hash AS hash" +
                    " FROM parameter_sets s JOIN parameter_set_versions v ON v.parameter_set_id = s.id AND v.version = 1" +
                    " WHERE s.id = '$exportedId'",
            )
        withClue("the set must be live in the promotion target") {
            row["ws"] shouldBe receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
            row["status"] shouldBe "RELEASED"
            row["hash"] shouldBe exportedHash
        }
    }

    @Test
    @Order(6)
    fun `a promotion batch carrying a TEMPLATE-BACKED set is received whole - the C36 wall`() {
        // #302: the receiver's §4 validation (the selector probe — a CUSTOMER connection) runs
        // BEFORE the one transaction, against the receiver's own datasource, with the batch's
        // templates judged from their payloads; the landing stays inside the transaction. Before
        // the fix this batch answered 400 selector_query_failed with
        // details.datasource_code = datasource.lease_in_transaction and rolled back whole.
        val envelope = exportOf(SET_NAME)
        val (status, applied) = pushBatch(batchOf(WS_PROMO, envelope))

        withClue("the receiver must accept the template-backed batch: $applied") { status shouldBe 200 }
        applied.path("data").path("parameter_sets").asInt() shouldBe 1

        // The set, by NAME and VERSION and HASH — read from the receiver's database.
        val exportedHash = envelope.path("parameter_set").path("body_hash").asText()
        val exportedId = envelope.path("parameter_set").path("id").asText()
        val receiverDb = checkNotNull(receiver).jdbc
        val row =
            receiverDb.row(
                "SELECT s.workspace_id::text AS ws, s.name AS name, v.status AS status, v.body_hash AS hash" +
                    " FROM parameter_sets s JOIN parameter_set_versions v ON v.parameter_set_id = s.id AND v.version = 1" +
                    " WHERE s.id = '$exportedId'",
            )
        withClue("the template-backed set must be live in the promotion target") {
            row["ws"] shouldBe receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
            row["name"] shouldBe SET_NAME
            row["status"] shouldBe "RELEASED"
            row["hash"] shouldBe exportedHash
        }
        templatePresentOn(receiverPort, WS_PROMO)
    }

    @Test
    @Order(7)
    fun `a set whose landing refuses id_taken rolls the whole batch back - the templates included (C36)`() {
        // The SAME set id into the receiver's SECOND workspace: the id PK is global (P24), so the
        // landing refuses C29's id_taken — AFTER the batch's templates were pushed inside the one
        // transaction. C36's property: the rollback is whole — the template is gone too.
        val envelope = exportOf(SET_NAME)
        val (status, refused) = pushBatch(batchOf(WS_PROMO2, envelope))

        withClue("C29 at the landing: $refused") { status shouldBe 409 }
        errorCode(refused) shouldBe "parameter.version.conflict"
        refused.path("error").path("details").path("reason").asText() shouldBe "id_taken"

        val receiverDb = checkNotNull(receiver).jdbc
        val ws2 = receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO2'")
        withClue("the batch's template must NOT have survived the rollback") {
            receiverDb
                .scalar(
                    "SELECT count(*) FROM templates WHERE workspace_id::text = '$ws2' AND name = '$TEMPLATE'",
                ) shouldBe "0"
        }
        withClue("the refusing set must NOT be in the target workspace") {
            receiverDb
                .scalar(
                    "SELECT count(*) FROM parameter_sets WHERE workspace_id::text = '$ws2' AND id = '${envelope.path("parameter_set").path("id").asText()}'",
                ) shouldBe "0"
        }
    }

    @Test
    @Order(8)
    fun `a batch whose second set refuses validation lands nothing - the refusal is the entry's own code`() {
        // Two sets: the first (template-backed, pin riding the batch) validates against the
        // receiver's own datasource; the SECOND pins a template the batch does not bring and the
        // receiver does not hold — the §8.3 refusal is the entry's own
        // `parameter.import.missing_template`, raised BEFORE the transaction opens, so nothing
        // lands. With the validation moved back inside the transaction, the first set's probe
        // would lease-refuse first (datasource.lease_in_transaction) — this case is the pin.
        val good = exportOf(SET_NAME_2)
        val badEntry = ghostPinnedEntry(good)
        val batch = batchOf(WS_PROMO, good)
        batch.set<JsonNode>("parameter_sets", mapper.createArrayNode().add(good.path("parameter_set")).add(badEntry))
        val (status, refused) = pushBatch(batch)

        withClue("the second set's own refusal: $refused") { status shouldBe 400 }
        errorCode(refused) shouldBe "parameter.import.missing_template"

        val receiverDb = checkNotNull(receiver).jdbc
        val ws = receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
        withClue("nothing of the batch may land") {
            receiverDb.scalar("SELECT count(*) FROM templates WHERE workspace_id::text = '$ws' AND name = '$TEMPLATE_2'") shouldBe "0"
            receiverDb.scalar("SELECT count(*) FROM parameter_sets WHERE workspace_id::text = '$ws' AND name = '$SET_NAME_2'") shouldBe "0"
            receiverDb.scalar(
                "SELECT count(*) FROM parameter_sets WHERE workspace_id::text = '$ws' AND name = '${badEntry.path("name").asText()}'",
            ) shouldBe "0"
        }
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    private fun seedTables() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS dim_state (country_code VARCHAR(2), state_code VARCHAR(2), state_name VARCHAR(30))",
                )
                statement.execute("DELETE FROM dim_state")
                statement.execute("INSERT INTO dim_state VALUES ('US','NY','New York'),('US','CA','California')")
            }
        }
    }

    private fun registerDatasourceOn(
        target: Int,
        session: String,
    ) {
        val (status, body) =
            restOn(
                target,
                "POST",
                "/api/v1/datasources",
                session,
                """{"name": "$DATASOURCE", "display_name": "Transfer E2E", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            )
        withClue("datasource must register: $body") { status shouldBe 201 }
    }

    private fun grantOn(
        deployment: Deployment,
        datasource: String,
        workspace: String,
    ) {
        val workspaceId = deployment.jdbc.scalar("SELECT id::text FROM workspaces WHERE name = '$workspace'")
        deployment.jdbc.execute(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)" +
                " VALUES ('$datasource', '$workspaceId', '$ADMIN_USER_ID')" +
                " ON CONFLICT DO NOTHING",
        )
    }

    private fun createReleasedTemplate(template: String = TEMPLATE) {
        val (status, created) =
            rest(
                "POST",
                "/api/v1/templates",
                sessionFor(WS_SOURCE),
                """{"id": "$template", "dialect": "H2", "display_name": "Transfer E2E states",
                   "description": "the pinned selector (a bind, never interpolation)",
                   "body": ${mapper.writeValueAsString(SELECT_BODY)}}""",
            )
        withClue("template must create: $created") { status shouldBe 201 }
        val hash = created.path("data").path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/templates/release", sessionFor(WS_SOURCE), """{"name": "$template"}""", mapOf("If-Match" to hash))
        withClue("template must release: $released") { releaseStatus shouldBe 200 }
    }

    private fun createReleasedSet(name: String = SET_NAME) {
        val document = if (name == SET_NAME) SET_DOCUMENT else SET_DOCUMENT_2
        val (status, created) = rest("POST", "/api/v1/parameter-sets", sessionFor(WS_SOURCE), document)
        withClue("set must create: $created") { status shouldBe 201 }
        releaseSet(created)
    }

    /** A second set with NO template pins and NO datasource — the promotion receive's walk. */
    private fun createReleasedConstantsSet() {
        val (status, created) = rest("POST", "/api/v1/parameter-sets", sessionFor(WS_SOURCE), CONSTANTS_SET_DOCUMENT)
        withClue("constants set must create: $created") { status shouldBe 201 }
        releaseSet(created)
    }

    private fun releaseSet(created: JsonNode) {
        val data = created.path("data")
        val hash = data.path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/parameter-sets/${data.path("id").asText()}/release", sessionFor(WS_SOURCE), "", mapOf("If-Match" to hash))
        withClue("set must release: $released") { releaseStatus shouldBe 200 }
        released.path("data").path("status").asText() shouldBe "RELEASED"
    }

    private fun export(): JsonNode = exportOf(SET_NAME)

    private fun exportOf(name: String): JsonNode {
        val id = sourceJdbc.scalar("SELECT id::text FROM parameter_sets WHERE name = '$name'")
        val (status, body) = rest("GET", "/api/v1/parameter-sets/$id/export", sessionFor(WS_SOURCE))
        withClue("export must answer the envelope: $body") { status shouldBe 200 }
        val envelope = body.path("data")
        envelope.path("parameter_set").path("id").asText() shouldBe id
        return envelope
    }

    private fun envelopeText(envelope: JsonNode): String = mapper.writeValueAsString(envelope)

    /** The §10.4 batch shape over one set envelope: its templates bundle plus its `parameter_set` node. */
    private fun batchOf(
        workspace: String,
        envelope: JsonNode,
    ): com.fasterxml.jackson.databind.node.ObjectNode =
        mapper
            .createObjectNode()
            .put("source_env", "pt-src")
            .put("key_fingerprint", "e2e-fingerprint")
            .put("workspace", workspace)
            .also { batch ->
                batch.set<JsonNode>("templates", envelope.path("templates"))
                batch.set<JsonNode>("parameter_sets", mapper.createArrayNode().add(envelope.path("parameter_set")))
                batch.set<JsonNode>("pipelines", mapper.createArrayNode())
                batch.set<JsonNode>("endpoints", mapper.createArrayNode())
            }

    /** Pushes [batch] to the receiver with the pre-shared key — the promotion channel's one route. */
    private fun pushBatch(batch: JsonNode): Pair<Int, JsonNode> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$receiverPort/api/v1/promotion/push"))
                .header("Content-Type", "application/json")
                .header(PROMOTION_HEADER, SERVER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(batch)))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body())
    }

    /**
     * The bad entry of the validation-refusal case: [good]'s `parameter_set` node renamed, its
     * pin moved to a template neither the batch brings nor the receiver holds — the entry's own
     * `parameter.import.missing_template` is the refusal, before anything lands.
     */
    private fun ghostPinnedEntry(good: JsonNode): JsonNode {
        val entry = good.path("parameter_set").deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        entry.put("id", UUID.randomUUID().toString())
        entry.put("name", "$SET_NAME_2/ghost_pinned")
        val parameters = entry.path("parameters")
        parameters.forEach { parameter ->
            val source = parameter.path("source")
            if (source.has("template")) {
                (source as com.fasterxml.jackson.databind.node.ObjectNode).set<JsonNode>(
                    "template",
                    mapper.createObjectNode().put("id", "pt$RUN/does_not_exist.sql").put("version", 1),
                )
            }
        }
        return entry
    }

    private fun templatePresentOn(
        target: Int,
        workspace: String,
    ) {
        val (status, body) = restOn(target, "GET", "/api/v1/templates?name=$TEMPLATE", sessionFor(workspace))
        withClue("template must resolve in $workspace: $body") { status shouldBe 200 }
        body.path("data").path("id").asText() shouldBe TEMPLATE
    }

    companion object {
        const val H2_JDBC_URL = "jdbc:h2:mem:paramtransfer194e;DB_CLOSE_DELAY=-1"
        const val DATASOURCE = "transfer-e2e"
        const val PROMOTION_HEADER = "DP-Promotion-Key"

        /** Names unique forever — every run takes a fresh set (versioning §3.2). */
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        val WS_SOURCE = "pt_src_$RUN"
        val WS_REST = "pt_rest_$RUN"
        val WS_C29 = "pt_c29_$RUN"
        val WS_PROMO = "pt_promo_$RUN"
        val WS_PROMO2 = "pt_promo2_$RUN"
        val TEMPLATE = "pt$RUN/states_of_country.sql"
        val TEMPLATE_2 = "pt$RUN/states_of_country_2.sql"
        val SET_NAME = "pt$RUN/acme/region_filters"
        val SET_NAME_2 = "pt$RUN/acme/region_filters_2"

        /** The pinned selector — the cascade E2E's shape: value, display_value, is_default, a bind. */
        const val SELECT_BODY =
            "SELECT state_code AS \"value\", state_name AS \"display_value\", FALSE AS \"is_default\"" +
                " FROM dim_state WHERE country_code IN (:country) ORDER BY state_name"
        val SET_DOCUMENT: String =
            """
            {
              "name": "$SET_NAME",
              "display_name": "Region filters",
              "description": "transfer round trip",
              "parameters": [
                {
                  "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT",
                  "cardinality": "MULTI", "required": true,
                  "source": {"constants": [{"value": "US", "display_value": "United States"}]}
                },
                {
                  "name": "state", "label": "State", "type": "STRING", "kind": "SELECT", "required": true,
                  "source": {"template": {"id": "$TEMPLATE", "version": 1}, "datasource": "$DATASOURCE"},
                  "depends_on": ["country"]
                }
              ]
            }
            """.trimIndent()

        /** No pins, no datasource: what the promotion receive can validate inside its own transaction. */
        val CONSTANTS_SET_NAME = "pt$RUN/acme/constant_filters"
        val CONSTANTS_SET_DOCUMENT: String =
            """
            {
              "name": "$CONSTANTS_SET_NAME",
              "display_name": "Constant filters",
              "description": "promotion receive walk",
              "parameters": [
                {
                  "name": "region", "label": "Region", "type": "STRING", "kind": "SELECT",
                  "cardinality": "SINGLE", "required": true,
                  "source": {"constants": [{"value": "EMEA", "display_value": "EMEA", "is_default": true}]}
                }
              ]
            }
            """.trimIndent()

        /** A second template-backed pair — the validation-refusal case's first (valid) entry. */
        val SET_DOCUMENT_2: String =
            """
            {
              "name": "$SET_NAME_2",
              "display_name": "Region filters two",
              "description": "promotion receive walk, template-backed",
              "parameters": [
                {
                  "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT",
                  "cardinality": "MULTI", "required": true,
                  "source": {"constants": [{"value": "US", "display_value": "United States"}]}
                },
                {
                  "name": "state", "label": "State", "type": "STRING", "kind": "SELECT", "required": true,
                  "source": {"template": {"id": "$TEMPLATE_2", "version": 1}, "datasource": "$DATASOURCE"},
                  "depends_on": ["country"]
                }
              ]
            }
            """.trimIndent()

        /** A fresh empty database per deployment — ids are global, so a kept id lands only once per database. */
        private val sourceDb = SharedE2e.scratchDatabase("param_transfer_194e_src")
        private val restDb = SharedE2e.scratchDatabase("param_transfer_194e_rest")
        private val promoDb = SharedE2e.scratchDatabase("param_transfer_194e_promo")

        private val sourceJdbc = ScratchJdbc(sourceDb.jdbcUrl, sourceDb.username, sourceDb.password)

        private val JWT_SECRET: String = E2eSession.newSecret()
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-194e@datapipelines.test"

        /** The deprecated pre-shared promotion credential — the receiver acts as the System actor. */
        private val SERVER_KEY: String = "dpk-transfer-e2e-server-${UUID.randomUUID()}"

        private const val SECRET_BYTES = 32
        private val random = SecureRandom()

        private var restTarget: Deployment? = null
        private var restTargetPort: Int = 0
        private var receiver: Deployment? = null
        private var receiverPort: Int = 0

        private fun sessionFor(workspace: String): String = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL, workspace = workspace)

        /** The SOURCE deployment (this class's own @SpringBootTest context) on its scratch database. */
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { sourceDb.jdbcUrl }
            registry.add("spring.datasource.username") { sourceDb.username }
            registry.add("spring.datasource.password") { sourceDb.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        /**
         * The two hand-booted deployments, started BEFORE the authoring @SpringBootTest context:
         * a receiver refuses to boot beside ANY draft, and the source deployment this class
         * boots next is ABOUT to make some. Each boots its own fresh scratch database, so each
         * Flyway applies the schema itself; the auth rows are seeded right after.
         */
        @BeforeAll
        @JvmStatic
        fun bootTargetsAndSeed() {
            val encryptionKey = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
            val common =
                arrayOf(
                    "--management.server.port=0",
                    "--spring.data.redis.host=${SharedE2e.redisHost}",
                    "--spring.data.redis.port=${SharedE2e.redisPort}",
                    "--spring.data.redis.password=",
                    "--datapipelines.redis.host=${SharedE2e.redisHost}",
                    "--datapipelines.redis.port=${SharedE2e.redisPort}",
                    "--datapipelines.jwt.secret=$JWT_SECRET",
                    "--datapipelines.db.encryption-key=$encryptionKey",
                    "--datapipelines.auth.local.enabled=true",
                    "--datapipelines.auth.base-url=http://localhost:8080",
                    "--datapipelines.posture=development",
                )
            val restContext =
                boot(
                    common,
                    restDb,
                    arrayOf(
                        "--datapipelines.env=pt-rest-target",
                        "--datapipelines.deployment.authoring-enabled=true",
                    ),
                )
            restTarget =
                Deployment(
                    restContext,
                    ScratchJdbc(restDb.jdbcUrl, restDb.username, restDb.password),
                )
            restTargetPort = portOf(restContext)
            val receiverContext =
                boot(
                    common,
                    promoDb,
                    arrayOf(
                        "--datapipelines.env=pt-receiver",
                        "--datapipelines.deployment.authoring-enabled=false",
                        "--datapipelines.deployment.promotion.server-key=$SERVER_KEY",
                    ),
                )
            receiver =
                Deployment(
                    receiverContext,
                    ScratchJdbc(promoDb.jdbcUrl, promoDb.username, promoDb.password),
                )
            receiverPort = portOf(receiverContext)

            seedWorkspaces(checkNotNull(restTarget).jdbc, WS_REST, WS_C29)
            seedWorkspaces(checkNotNull(receiver).jdbc, WS_PROMO, WS_PROMO2)
        }

        /** One deployment boot — command-line args, which win over the yml's `${...}` defaults. */
        private fun boot(
            common: Array<String>,
            db: SharedE2e.SharedPostgresRef,
            deploymentArgs: Array<String>,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .run(
                    *(
                        common +
                            arrayOf(
                                "--server.port=0",
                                "--spring.datasource.url=${db.jdbcUrl}",
                                "--spring.datasource.username=${db.username}",
                                "--spring.datasource.password=${db.password}",
                            ) +
                            deploymentArgs
                    ),
                )

        @AfterAll
        @JvmStatic
        fun closeTargets() {
            restTarget?.context?.close()
            receiver?.context?.close()
        }

        private fun portOf(context: ConfigurableApplicationContext): Int =
            Integer.parseInt(checkNotNull(context.environment.getProperty("local.server.port")))

        private fun seedWorkspaces(
            jdbc: ScratchJdbc,
            vararg names: String,
        ) {
            jdbc.execute(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', 'E2E 194e', 'test', 'e2e-194e-sub', TRUE, TRUE)
                """.trimIndent(),
            )
            names.forEach { name ->
                val id = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
                jdbc.execute(
                    "INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$name', 'Transfer $name')",
                )
                jdbc.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$id', '$ADMIN_USER_ID', 'workspace_admin')",
                )
            }
        }

        /** One hand-booted deployment: its context (the port) and its database (the truth). */
        private class Deployment(
            val context: ConfigurableApplicationContext,
            val jdbc: ScratchJdbc,
        )
    }

    /**
     * A tiny JDBC reader — the suites in this module read rows directly rather than through
     * Spring, and what landed in a target is a DATABASE fact, not a service's own report.
     */
    private class ScratchJdbc(
        private val url: String,
        private val user: String,
        private val password: String,
    ) {
        fun scalar(sql: String): String = row(sql).values.first()

        fun row(sql: String): Map<String, String> =
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        check(rs.next()) { "no row for: $sql" }
                        (1..rs.metaData.columnCount).associate { i ->
                            rs.metaData.getColumnLabel(i) to (rs.getString(i) ?: "")
                        }
                    }
                }
            }

        fun execute(sql: String) {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }
    }
}
