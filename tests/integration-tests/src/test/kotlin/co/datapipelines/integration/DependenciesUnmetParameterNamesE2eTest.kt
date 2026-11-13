package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/**
 * **`unmet_names` through the real routes** (#892; record §11.1 `parameter_names` row and §12, Draft 10 erratum (11);
 * acceptance A34's read-side display). Every object is authored through the product's own REST routes over real
 * PostgreSQL; a dashboard can only be SAVED with every binding met (`DashboardValidator`), so an unmet name is reached the
 * way it is reached in the product: the dashboard pins an OPEN DRAFT of its set, and that draft is then overwritten in
 * place without the bound name (`ParameterSetService.write` — no dashboard-consumer check on a draft write). SQL stands
 * in only for case 6's removal of a pinned set (a pinned set's delete is refused, `parameter.in_use`).
 *
 * The `unmet` answer of case 1 is the enveloped route golden `constraint-parameter-names-unmet.json` (normalised exactly
 * as `DependenciesGoldenE2eTest` normalises its route cases), and its `data` is byte-equal to the contract golden of the
 * same name.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DependenciesUnmetParameterNamesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `case 1 and 2 - one name removed from the pinned open draft is unmet, the satisfied control has no key`() {
        val case = answers().getValue("one")
        val control = case.before.data()
        withClue("control: no edge carries unmet_names") { unmetKeys(control).shouldBeEmpty() }
        val after = case.after.data()
        val edge = setEdge(after)
        edge.constraint().path("names").texts() shouldBe listOf("region", "since")
        edge.constraint().path("unmet_names").texts() shouldBe listOf("since")
        after.path("complete").asBoolean() shouldBe true
        after.path("diagnostics") shouldBe control.path("diagnostics")
        unmetKeys(after) shouldBe listOf(edge.path("edge_id").asText())
    }

    @Test
    fun `case 3 - two names removed are both unmet in the order names uses, on one edge, with no diagnostic`() {
        val case = answers().getValue("two")
        val after = case.after.data()
        val edge = setEdge(after)
        edge.constraint().path("unmet_names").texts() shouldBe listOf("region", "since")
        after.path("diagnostics").toList().shouldBeEmpty()
        after.path("edges").count { it.path("relationship").asText() == "PARAMETER_SET" } shouldBe 1
        unmetKeys(case.before.data()).shouldBeEmpty()
    }

    @Test
    fun `case 4 - A34 - a literal binding adds nothing and a repeated binding appears once in names and in unmet_names`() {
        val case = answers().getValue("a34")
        setEdge(case.before.data()).constraint().path("names").texts() shouldBe listOf("region")
        unmetKeys(case.before.data()).shouldBeEmpty()
        val constraint = setEdge(case.after.data()).constraint()
        constraint.path("names").texts() shouldBe listOf("region")
        constraint.path("unmet_names").texts() shouldBe listOf("region")
    }

    @Test
    fun `case 6 - a pinned set absent and one only in another workspace answer alike - MISSING, no unmet_names`() {
        val case = answers().getValue("missing")
        val absent = MAPPER.readTree(case.before.body) as ObjectNode
        val foreign = MAPPER.readTree(case.after.body) as ObjectNode
        listOf(case.before, case.after).forEach { withClue(it.body.take(EXCERPT)) { it.status shouldBe HTTP_OK } }
        listOf("absent" to absent, "foreign" to foreign).forEach { (label, answer) ->
            withClue(label) {
                val data = answer.path("data")
                val set = data.path("nodes").single { it.path("family").asText() == "parameter_set" }
                set.path("resolution").asText() shouldBe "missing"
                data.path("diagnostics").map { it.path("code").asText() to it.path("node_id").asText() } shouldContain
                    ("MISSING" to set.path("node_id").asText())
                unmetKeys(data).shouldBeEmpty()
            }
        }
        // The two per-request fields of #875's envelope, and nothing else: every semantic field is compared raw.
        listOf(absent, foreign).forEach {
            it.remove("correlation_id")
            (it.path("data") as ObjectNode).remove("observed_at")
        }
        MAPPER.writeValueAsString(foreign) shouldBe MAPPER.writeValueAsString(absent)
        println("event=dependencies.unmet_missing_twins data=${MAPPER.writeValueAsString(foreign)}")
    }

    @Test
    fun `case 7 - the pin decides, not CURRENT - the pinned draft v2 lacks the name the released v1 declares`() {
        val case = answers().getValue("pin")
        unmetKeys(case.before.data()).shouldBeEmpty()
        val edge = setEdge(case.after.data())
        edge.path("resolved_version").asInt() shouldBe 2
        edge.constraint().path("unmet_names").texts() shouldBe listOf("since")
        val released = case.facts.getValue("v1")
        released.path("data").path("status").asText() shouldBe "RELEASED"
        released.path("data").path("current_version").asInt() shouldBe 1
        released.path("data").path("parameters").map { it.path("name").asText() } shouldContain "since"
    }

    @Test
    fun `the unmet answer equals its committed route golden, whose data is byte-equal to the contract golden`() {
        val text = normalised(answers().getValue("one").after.body)
        val written = Files.createDirectories(repoRoot().resolve("tests/integration-tests/build/dependencies-goldens"))
        Files.writeString(written.resolve("$GOLDEN.json"), text)
        withClue("the normalised answer is under $written") { text shouldBe Files.readString(ROUTE_GOLDENS.resolve("$GOLDEN.json")) }
        val data = MAPPER.readTree(text).path("data")
        val contract = Files.readString(CONTRACT_GOLDENS.resolve("$GOLDEN.json"))
        MAPPER.readTree(contract) shouldBe data
        MAPPER.writer(PRINTER).writeValueAsString(data) + "\n" shouldBe contract
    }

    // ------------------------------------------------------------------------------- the world and its answers

    /** One case: the tree before and after the set's change, and any fact the case reads beside it. */
    private class Case(
        val before: Answer,
        val after: Answer,
        val facts: Map<String, JsonNode> = emptyMap(),
    )

    private data class Answer(
        val status: Int,
        val body: String,
    ) {
        fun data(): JsonNode {
            withClue(body.take(EXCERPT)) { status shouldBe HTTP_OK }
            return MAPPER.readTree(body).path("data")
        }
    }

    private fun answers(): Map<String, Case> = collected ?: World(port).collect().also { collected = it }

    private class World(
        private val port: Int,
    ) {
        private val admin = session(WORKSPACE)
        private val fx = FamilyRootsFixture(port, admin, WORKSPACE_ID, DATASOURCE)
        private val foreign = FamilyRootsFixture(port, session(FOREIGN), FOREIGN_ID, DATASOURCE)

        fun collect(): Map<String, Case> {
            seedRows()
            FamilyRootsFixture(port, superAdmin(), WORKSPACE_ID, DATASOURCE)
                .send("POST", "/api/v1/datasources", DATASOURCE_BODY)
                .expect("datasource", HTTP_CREATED)
            val query = fx.sqlTemplate("$PREFIX/query.sql")
            val source = fx.pipeline("$PREFIX/source", query, declared("region", "since"))
            val chart = fx.visualization("$PREFIX/chart", fx.jsonataTemplate("$PREFIX/shape.jsonata"))
            return mapOf(
                "one" to removed("one", source, chart, keep = listOf("region")),
                "two" to removed("two", source, chart, keep = listOf("other"), declares = listOf("region", "since", "other")),
                "a34" to a34(chart, query),
                "missing" to missing(source, chart),
                "pin" to pin(source, chart),
            )
        }

        /** A set declaring [declares], a dashboard binding region and since, then the set's open draft keeping only [keep]. */
        private fun removed(
            case: String,
            source: FamilyRootsFixture.Node,
            chart: FamilyRootsFixture.Node,
            keep: List<String>,
            declares: List<String> = listOf("region", "since"),
        ): Case {
            val set = createSet(fx, "$PREFIX/$case/filters", declares)
            val board = board("$PREFIX/$case/board", set.name, 1, listOf(binding(source, "region", "since")), chart)
            val before = tree(board)
            writeSet(set, keep, expectedVersion = 1)
            return Case(before, tree(board))
        }

        /** A34: one source binding region and a literal, a second binding region again — then region removed. */
        private fun a34(
            chart: FamilyRootsFixture.Node,
            query: FamilyRootsFixture.Node,
        ): Case {
            val source = fx.pipeline("$PREFIX/a34/source", query, declared("region", "country"))
            val set = createSet(fx, "$PREFIX/a34/filters", listOf("region", "since"))
            val sources =
                listOf(
                    """{"name": "revenue_source", "pipeline": {"name": "${source.name}", "version": 1},
                        "parameters": {"region": {"parameter": "region"}, "country": {"value": "US"}}}""",
                    """{"name": "second_source", "pipeline": {"name": "${source.name}", "version": 1},
                        "parameters": {"region": {"parameter": "region"}}}""",
                )
            val board = board("$PREFIX/a34/board", set.name, 1, sources, chart)
            val before = tree(board)
            writeSet(set, listOf("since"), expectedVersion = 1)
            return Case(before, tree(board))
        }

        /**
         * Case 6: ONE saved dashboard and ONE pin. The named plant renames the pinned set in the caller's workspace (the
         * dashboard row is untouched): request A finds that name and version nowhere. Then the same name and version is
         * authored in the foreign workspace, lacking the bound names: request B.
         */
        private fun missing(
            source: FamilyRootsFixture.Node,
            chart: FamilyRootsFixture.Node,
        ): Case {
            val name = "$PREFIX/missing/filters"
            createSet(fx, name, listOf("region", "since"))
            val board = board("$PREFIX/missing/board", name, 1, listOf(binding(source, "region", "since")), chart)
            sql("UPDATE parameter_sets SET name = ? WHERE workspace_id = ?::uuid AND name = ?", "$name-gone", WORKSPACE_ID, name)
            val absent = tree(board)
            createSet(foreign, name, listOf("other"))
            return Case(absent, tree(board))
        }

        /** Case 7: v1 RELEASED declaring since; the dashboard pins the open draft v2, which then loses since. */
        private fun pin(
            source: FamilyRootsFixture.Node,
            chart: FamilyRootsFixture.Node,
        ): Case {
            val set = createSet(fx, "$PREFIX/pin/filters", listOf("region", "since"))
            fx.send("POST", "/api/v1/parameter-sets/${set.id}/release", "", set.hash()).expect("release v1", HTTP_OK)
            writeSet(set, listOf("region", "since"), expectedVersion = 2, description = "v2 draft")
            val board = board("$PREFIX/pin/board", set.name, 2, listOf(binding(source, "region", "since")), chart)
            val before = tree(board)
            writeSet(set, listOf("region"), expectedVersion = 2, description = "v2 draft")
            val after = tree(board)
            val v1 = MAPPER.readTree(fx.send("GET", "/api/v1/parameter-sets/${set.id}/versions/1", "").expect("v1", HTTP_OK).asString())
            return Case(before, after, mapOf("v1" to v1))
        }

        private inner class SetRef(
            val name: String,
            val id: String,
        ) {
            fun hash(): String = working().path("body_hash").asText()

            fun working(): JsonNode =
                MAPPER.readTree(fx.send("GET", "/api/v1/parameter-sets/$id", "").expect("set $name", HTTP_OK).asString()).path("data")
        }

        private fun createSet(
            on: FamilyRootsFixture,
            name: String,
            parameters: List<String>,
        ): SetRef {
            val created = on.send("POST", "/api/v1/parameter-sets", setDocument(name, parameters)).expect("set $name", HTTP_CREATED)
            return SetRef(name, created.jsonPath().getString("data.id"))
        }

        /** The draft write; the version it lands on is asserted, so an in-place overwrite is proven, not assumed. */
        private fun writeSet(
            set: SetRef,
            parameters: List<String>,
            expectedVersion: Int,
            description: String = "#892",
        ) {
            val written =
                fx
                    .send("PUT", "/api/v1/parameter-sets/${set.id}", setDocument(set.name, parameters, description), set.hash())
                    .expect("write ${set.name}", HTTP_OK)
            withClue("the write of ${set.name} lands on version $expectedVersion") {
                written.jsonPath().getInt("data.version") shouldBe expectedVersion
                written.jsonPath().getString("data.status") shouldBe "DRAFT"
            }
        }

        private fun binding(
            source: FamilyRootsFixture.Node,
            vararg bound: String,
        ): String =
            """{"name": "revenue_source", "pipeline": {"name": "${source.name}", "version": 1},
                "parameters": {${bound.joinToString(", ") { "\"$it\": {\"parameter\": \"$it\"}" }}}}"""

        private fun board(
            name: String,
            set: String,
            setVersion: Int,
            sources: List<String>,
            chart: FamilyRootsFixture.Node,
        ): String {
            val document =
                """
                {"name": "$name", "display_name": "Unmet names", "description": "#892",
                 "parameter_set": {"name": "$set", "version": $setVersion},
                 "sources": [${sources.joinToString(", ")}],
                 "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                     "visualization": {"name": "${chart.name}", "version": 1},
                                     "inputs": {"revenue": {"source": "revenue_source"}}}],
                 "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
                """.trimIndent()
            fx.send("POST", "/api/v1/dashboards", document).expect("dashboard $name", HTTP_CREATED)
            return name
        }

        private fun tree(board: String): Answer {
            val response =
                given()
                    .port(port)
                    .asSession(admin)
                    .accept(JSON)
                    .get("/api/v1/dependencies/dashboard?name=$board&version=1")
            return Answer(response.statusCode, response.body().asString())
        }

        private fun sql(
            statement: String,
            vararg values: String,
        ) {
            DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
                c.prepareStatement(statement).use { s ->
                    values.forEachIndexed { i, v -> s.setString(i + 1, v) }
                    withClue(statement) { s.executeUpdate() shouldBe 1 }
                }
            }
        }

        /** Our workspace and a foreign one; the admin is workspace admin of both; a super admin registers the datasource. */
        private fun seedRows() {
            E2eClean.beforeSeeding()
            DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
                c.createStatement().use { s ->
                    s.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Unmet names'), " +
                            "('$FOREIGN_ID', '$FOREIGN', 'Foreign')",
                    )
                    listOf(ADMIN to false, SUPER_ADMIN to true).forEach { (id, superAdmin) ->
                        s.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$id', '$id@unmet892.test', 'unmet', 'test', 'unmet892-$id', TRUE, $superAdmin)",
                        )
                    }
                    s.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                            "('$WORKSPACE_ID', '$ADMIN', 'workspace_admin'), ('$FOREIGN_ID', '$ADMIN', 'workspace_admin')",
                    )
                }
            }
        }

        private fun session(workspace: String): String = E2eSession.jwt(JWT_SECRET, ADMIN, "admin@unmet892.test", workspace)

        private fun superAdmin(): String = E2eSession.jwt(JWT_SECRET, SUPER_ADMIN, "super_admin@unmet892.test", WORKSPACE)

        private fun Response.expect(
            what: String,
            status: Int,
        ): Response = also { withClue("$what: ${asString().take(EXCERPT)}") { statusCode shouldBe status } }
    }

    private companion object {
        const val JSON = "application/json"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val EXCERPT = 600
        const val OBSERVED_AT = "2026-01-01T00:00:00Z"
        const val GOLDEN = "constraint-parameter-names-unmet"
        val MAPPER = ObjectMapper()

        const val WORKSPACE = "unmet892-e2e"
        const val FOREIGN = "unmet892-foreign"
        val WORKSPACE_ID = UUID.randomUUID().toString()
        val FOREIGN_ID = UUID.randomUUID().toString()
        val ADMIN = UUID.randomUUID().toString()
        val SUPER_ADMIN = UUID.randomUUID().toString()
        const val PREFIX = "test/tree892/unmet"
        const val DATASOURCE = "tree892_unmet"
        const val DATASOURCE_BODY =
            """{"name": "$DATASOURCE", "display_name": "Unmet names", "dialect": "H2", "readonly": false,
               "jdbc_url": "jdbc:h2:mem:$DATASOURCE;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}"""
        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        var collected: Map<String, Case>? = null

        /** A pipeline's declared parameters: each optional STRING. */
        fun declared(vararg names: String): Map<String, Any> = names.associateWith { mapOf("type" to "STRING", "required" to false) }

        /** A set of optional STRING `INPUT` parameters, named [parameters] in that order. */
        fun setDocument(
            name: String,
            parameters: List<String>,
            description: String = "#892",
        ): String =
            MAPPER.writeValueAsString(
                mapOf(
                    "name" to name,
                    "display_name" to "Unmet names",
                    "description" to description,
                    "parameters" to
                        parameters.map {
                            mapOf(
                                "name" to it,
                                "label" to it.replaceFirstChar(Char::uppercase),
                                "type" to "STRING",
                                "kind" to "INPUT",
                                "required" to false,
                            )
                        },
                ),
            )

        /** The dashboard → parameter set edge of [data] (one per dashboard). */
        fun setEdge(data: JsonNode): JsonNode = data.path("edges").single { it.path("relationship").asText() == "PARAMETER_SET" }

        /** The edge's one `parameter_names` constraint element. */
        fun JsonNode.constraint(): JsonNode = path("constraints").single { it.path("kind").asText() == "parameter_names" }

        /** The edge ids carrying an `unmet_names` key anywhere in their constraints. */
        fun unmetKeys(data: JsonNode): List<String> =
            data.path("edges").filter { e -> e.path("constraints").any { it.has("unmet_names") } }.map { it.path("edge_id").asText() }

        fun JsonNode.texts(): List<String> = map { it.asText() }

        fun repoRoot(): Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { it.resolve("settings.gradle.kts").toFile().exists() }

        val ROUTE_GOLDENS: Path get() = repoRoot().resolve("modules/web/src/test/resources/dependencies/goldens")
        val CONTRACT_GOLDENS: Path
            get() = repoRoot().resolve("modules/dependency-contract/src/main/resources/co/datapipelines/dependencies/wire/goldens")

        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_PATTERN = Regex("\"[0-9a-f]{64}\"")

        /** The printer G1's goldens are written in: two-space indent, arrays one element per line, `"key": value`. */
        val PRINTER: DefaultPrettyPrinter =
            DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .apply { indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE) }

        /** `DependenciesGoldenE2eTest.normalised`, the same rules: one golden directory, one normal form. */
        fun normalised(body: String): String {
            val tree = MAPPER.readTree(body)
            (tree.path("data") as? ObjectNode)?.takeIf { it.has("observed_at") }?.put("observed_at", OBSERVED_AT)
            val printed = MAPPER.writer(PRINTER).writeValueAsString(tree)
            val uuids = LinkedHashMap<String, String>()
            val hashes = LinkedHashMap<String, String>()
            val ids = UUID_PATTERN.replace(printed) { m -> uuids.getOrPut(m.value) { "00000000-0000-4000-8000-%012d".format(uuids.size) } }
            return HASH_PATTERN.replace(ids) { m -> hashes.getOrPut(m.value) { "\"%064d\"".format(hashes.size + 1) } } + "\n"
        }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

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
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
