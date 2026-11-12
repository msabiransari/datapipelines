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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
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
 * **The enveloped route goldens** (#875 G4 §E, record A47) — one file per §11.1 case the eight routes produce from an
 * authored workspace, each the WHOLE answer (§4.1 success or §4.2 error envelope) of the real HTTP stack over real
 * PostgreSQL. Every object is authored through the product's own REST routes; SQL stands in only where no route exists
 * (a scheduler-set block, an endpoint's `is_enabled`, and moving/renaming the one template the MISSING twins need).
 *
 * The committed files live in `modules/web/src/test/resources/dependencies/goldens/` (G6 loads them for its renderer
 * tests). An answer is compared after [normalised] replaces what differs run to run — `correlation_id`, `observed_at`,
 * every UUID and every 64-hex hash, each by order of first appearance — and every normalised answer is ALSO written to
 * `build/dependencies-goldens/`, so a red run shows the file to diff (there is no write mode: a golden changes only
 * by a reviewed copy). Tokens the routes cannot produce from authored data — `acyclic: false` (cycles are refused at
 * save), the `LIMIT` cases and the two 503s — are the web module's `DependenciesGoldenTest`, in the same directory.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DependenciesGoldenE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `every answer the routes give equals its committed golden`() {
        val written = Files.createDirectories(repoRoot().resolve("tests/integration-tests/build/dependencies-goldens"))
        val mismatched =
            answers().mapNotNull { (case, answer) ->
                val text = normalised(answer.body)
                Files.writeString(written.resolve("$case.json"), text)
                val golden = GOLDENS_DIR.resolve("$case.json")
                case.takeUnless { Files.exists(golden) && Files.readString(golden) == text }
            }
        withClue("cases whose answer differs from (or has no) golden; the normalised answers are under $written") {
            mismatched.shouldBeEmpty()
        }
    }

    @Test
    fun `the goldens directory holds exactly the route cases and the web cases, at least the A47 floor`() {
        val files = Files.list(GOLDENS_DIR).use { s -> s.map { it.fileName.toString().removeSuffix(".json") }.toList().toSet() }
        files shouldBe CASES.keys + WEB_CASES
        files.size shouldBeGreaterThanOrEqual A47_FLOOR
    }

    @Test
    fun `the 200 answers cover every relationship, constraint, operational variant and lifecycle, each with nodes`() {
        val trees = answers().filterValues { it.status == HTTP_OK }.mapValues { (_, a) -> MAPPER.readTree(a.body).path("data") }
        trees.forEach { (case, data) -> withClue(case) { data.path("nodes").size() shouldBeGreaterThan 0 } }
        val edges = trees.values.flatMap { it.path("edges").toList() }
        val nodes = trees.values.flatMap { it.path("nodes").toList() }
        edges.map { it.path("relationship").asText() }.toSet() shouldBe RELATIONSHIPS
        edges.flatMap { it.path("constraints").toList() }.map(::constraintToken).toSet() shouldBe CONSTRAINTS
        nodes
            .mapNotNull { n ->
                n.path("operational").takeUnless { it.isNull }?.let { "${n.path("family").asText()}:$it" }
            }.toSet() shouldBe
            OPERATIONAL
        withClue("KNOWN GAP #902: no provider sets an API's enabled fact; when it lands, re-stamp both API goldens and drop this") {
            nodes.filter { it.path("family").asText() == "api_endpoint" }.map { it.path("operational").isNull }.toSet() shouldBe setOf(true)
        }
        nodes.mapNotNull { it.path("lifecycle").takeUnless { l -> l.isNull }?.asText() }.toSet() shouldBe
            setOf("DRAFT", "RELEASED", "DISCARDED")
        // `acyclic` is decided only over a whole closure. A DISCARDED node is terminal and leaves the closure incomplete
        // (G1's walker and its `lifecycle-discarded` golden), so the discarded root is the one incomplete live tree.
        trees.forEach { (case, data) ->
            withClue(case) { data.path("acyclic").asText() shouldBe if (data.path("complete").asBoolean()) "true" else "null" }
        }
        trees.filterValues { !it.path("complete").asBoolean() }.keys shouldBe setOf("tree-pipeline-discarded")
    }

    @Test
    fun `each refusal answers its exact status and code, and every reason the routes produce is there`() {
        CASES.forEach { (case, expected) ->
            val answer = answers().getValue(case)
            withClue("$case: ${answer.body.take(EXCERPT)}") {
                answer.status shouldBe expected.status
                if (expected.code != null) {
                    MAPPER
                        .readTree(answer.body)
                        .path("error")
                        .path("code")
                        .asText() shouldBe expected.code
                }
            }
        }
        val reasons = CASES.keys.filter { it.startsWith("refusal-") }.map { MAPPER.readTree(answers().getValue(it).body) }
        reasons
            .map {
                it
                    .path("error")
                    .path("details")
                    .path("reason")
                    .asText()
            }.toSet() shouldBe REASONS
    }

    @Test
    fun `a template that exists only in another workspace is the same MISSING node, byte for byte, as an absent one`() {
        val foreign = MAPPER.readTree(answers().getValue("missing-template-foreign").body).path("data") as ObjectNode
        val absent = MAPPER.readTree(answers().getValue("missing-template-absent").body).path("data") as ObjectNode
        // Raw, not normalised: a leaked id or hash of the foreign template would differ here. Only the read's clock may.
        foreign.remove("observed_at")
        absent.remove("observed_at")
        MAPPER.writeValueAsString(foreign) shouldBe MAPPER.writeValueAsString(absent)
        foreign.path("nodes").map { it.path("resolution").asText() } shouldContainAll listOf("missing")
        println("event=dependencies.missing_twins data=${MAPPER.writeValueAsString(foreign)}")
    }

    // ------------------------------------------------------------------------------- the world and its answers

    /** One answer: its status and its body as served. */
    data class Answer(
        val status: Int,
        val body: String,
    )

    /** What a case must answer: its status and, for an error, its code. */
    data class Expected(
        val status: Int,
        val code: String? = null,
    )

    private fun answers(): Map<String, Answer> = collected ?: GoldenWorld(port).collect().also { collected = it }

    /** The authored workspace: built once through REST, then read case by case, the MISSING twins last. */
    private class GoldenWorld(
        private val port: Int,
    ) {
        private val admin = session(ADMIN, "admin")
        private val fx = FamilyRootsFixture(port, admin, WORKSPACE_ID, DATASOURCE)

        fun collect(): Map<String, Answer> {
            seedRows()
            val registrar = FamilyRootsFixture(port, session(SUPER_ADMIN, "super_admin"), WORKSPACE_ID, DATASOURCE)
            listOf(DATASOURCE to false, READONLY_DATASOURCE to true).forEach { (name, readonly) ->
                registrar.send("POST", "/api/v1/datasources", datasourceBody(name, readonly)).expect("datasource $name", HTTP_CREATED)
            }
            val world = author()
            val answers = LinkedHashMap<String, Answer>()
            world.forEach { (case, path) -> answers[case] = get(path) }
            REFUSALS.forEach { (case, path) -> answers[case] = get(path) }
            NOT_FOUND.forEach { (family, path) -> answers["not-found-$family"] = get(path) }
            answers["unauthenticated"] =
                given()
                    .port(port)
                    .accept(JSON)
                    .get(world.getValue("tree-pipeline"))
                    .answer()
            answers += missingTwins()
            return answers
        }

        /** Every authored root, by case. */
        private fun author(): Map<String, String> {
            val (board, edges) = fx.dashboardClosure(PREFIX, bindRegion = true)
            val set = edges[0].to
            val source = edges[2].to
            val query = edges[3].to
            val chart = edges[4].to
            release(source)
            val writer = writer(source, query)
            val retired = retired()
            val (imports, _) = fx.closure("template", "$PREFIX/imports")
            val untested = untestedChart("$PREFIX/shape.jsonata")
            val enabled = endpoint(source, "on")
            val disabled = endpoint(source, "off").also { disable(it) }
            val schedules = listOf("enabled", "paused", "blocked").associateWith { schedule(source, it) }
            fx.send("POST", "/api/v1/schedules/${schedules.getValue("paused")}/pause", "").expect("pause", HTTP_OK)
            block(schedules.getValue("blocked"))
            return mapOf(
                "tree-dashboard" to tree("dashboard", board.name),
                "tree-pipeline" to tree("pipeline", source.name),
                "tree-pipeline-writer" to tree("pipeline", writer),
                "tree-pipeline-discarded" to tree("pipeline", retired),
                "tree-template-import" to tree("template", imports.name),
                "tree-template" to tree("template", query.name),
                "tree-parameter-set" to tree("parameter_set", set.name),
                "tree-visualization-evidence-ready" to tree("visualization", chart.name),
                "tree-visualization-evidence-not-ready" to tree("visualization", untested),
                "tree-api-endpoint-enabled" to tree("api_endpoint", "/api$enabled"),
                "tree-api-endpoint-disabled" to tree("api_endpoint", "/api$disabled"),
                "tree-datasource-writable" to "/api/v1/dependencies/datasource?name=$DATASOURCE",
                "tree-datasource-readonly" to "/api/v1/dependencies/datasource?name=$READONLY_DATASOURCE",
            ) + schedules.map { (condition, id) -> "tree-schedule-$condition" to "/api/v1/dependencies/schedule?id=$id" }
        }

        private fun release(pipeline: FamilyRootsFixture.Node) {
            val row = fx.row(pipeline)
            fx
                .send("POST", "/api/v1/pipelines/${row.uuid}/release?release_pinned_templates=true", "", row.hash)
                .expect("release ${pipeline.name}", HTTP_OK)
        }

        /** A pipeline reading the readonly datasource, writing the writable one, and running [child] as a child. */
        private fun writer(
            child: FamilyRootsFixture.Node,
            query: FamilyRootsFixture.Node,
        ): String {
            val write = fx.sqlTemplate("$PREFIX/write.sql", "UPDATE golden_rows SET amount = 1")
            val name = "$PREFIX/writer"
            val document =
                """
                {"name": "$name", "display_name": "Golden writer", "description": "#875", "nodes": [
                  {"id": "read", "type": "DQL", "source": "$READONLY_DATASOURCE", "template": {"id": "${query.name}", "version": 1},
                   "output": {"target": "tempdb", "table": "golden_rows"}, "depends_on": []},
                  {"id": "write", "type": "DML", "source": "$DATASOURCE", "template": {"id": "${write.name}", "version": 1},
                   "depends_on": []},
                  {"id": "child", "type": "PIPELINE", "pipeline": {"name": "${child.name}", "version": 1}, "depends_on": []}]}
                """.trimIndent()
            fx.send("POST", "/api/v1/pipelines", document).expect("pipeline $name", HTTP_CREATED)
            return name
        }

        /** A pipeline released, then its version 1 discarded: the DISCARDED root (a pinned version refuses discard). */
        private fun retired(): String {
            val template = fx.sqlTemplate("$PREFIX/retired.sql")
            val pipeline = fx.pipeline("$PREFIX/retired", template)
            release(pipeline)
            fx.send("POST", "/api/v1/pipelines/${fx.row(pipeline).uuid}/versions/1/discard", "").expect("discard", HTTP_OK)
            return pipeline.name
        }

        /** A visualization over [shape] with no recorded test: its evidence is not ready. */
        private fun untestedChart(shape: String): String {
            val name = "$PREFIX/untested"
            val document =
                """
                {"name":"$name","display_name":"Untested","description":"#875",
                 "renderer":{"kind":"chart","version":"1"},
                 "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                                    {"name":"amount","type":"DECIMAL","nullable":false}]}},
                 "transform":{"template":{"name":"$shape","version":1},"inputs":{"rows":"revenue"}},
                 "config":{"mark":{"type":"bar"},
                           "encoding":{"x":{"field":"month_labels","type":"nominal","sort":"ascending"},
                                       "y":{"field":"amounts","type":"quantitative","aggregate":"sum"}}},
                 "bindings":{"amounts":"amounts","month_labels":"month_labels"},
                 "tests":{"cases":[{"name":"one month","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},
                                      "assertions":[{"kind":"rendered"},{"kind":"mark_count","equals":1}]}]}}
                """.trimIndent()
            fx.send("POST", "/api/v1/visualizations", document).expect("visualization $name", HTTP_CREATED)
            return name
        }

        private fun endpoint(
            pipeline: FamilyRootsFixture.Node,
            leaf: String,
        ): String {
            val path = "/test_tree875_golden/v1/$leaf"
            fx
                .send("POST", "/api/v1/endpoints", """{"path": "$path", "pipeline": "${pipeline.name}", "pipeline_version": 1}""")
                .expect("endpoint $path", HTTP_CREATED)
            return path
        }

        private fun schedule(
            pipeline: FamilyRootsFixture.Node,
            condition: String,
        ): String =
            fx
                .send(
                    "POST",
                    "/api/v1/schedules",
                    """{"name": "$PREFIX/schedule_$condition", "payload": {"pipeline": "${pipeline.name}", "version": 1},
                       "cron": "0 3 * * *", "timezone": "UTC"}""",
                ).expect("schedule $condition", HTTP_CREATED)
                .jsonPath()
                .getString("data.id")

        /** No route sets `is_enabled` (§19.5); the column is what the node's `operational.enabled` reads. */
        private fun disable(path: String) =
            sql("UPDATE published_endpoints SET is_enabled = FALSE WHERE workspace_id = ?::uuid AND path_pattern = ?", WORKSPACE_ID, path)

        /** A block is the scheduler's (an unknown outcome); set here as it would set it. */
        private fun block(id: String) =
            sql("UPDATE schedules SET blocked_at = NOW(), blocked_reason = 'unknown_outcome' WHERE id = ?::uuid", id)

        /**
         * A pipeline pinning a template that is first moved to the foreign workspace (it exists only there), then renamed
         * (it exists nowhere): the same request, read twice.
         */
        private fun missingTwins(): Map<String, Answer> {
            val template = fx.sqlTemplate("$PREFIX/orphan.sql")
            val pipeline = fx.pipeline("$PREFIX/orphan", template)
            val path = tree("pipeline", pipeline.name)
            sql(
                "UPDATE templates SET workspace_id = ?::uuid WHERE workspace_id = ?::uuid AND name = ?",
                FOREIGN_ID,
                WORKSPACE_ID,
                template.name,
            )
            val foreign = get(path)
            sql(
                "UPDATE templates SET name = ? WHERE workspace_id = ?::uuid AND name = ?",
                "$PREFIX/orphan_gone.sql",
                FOREIGN_ID,
                template.name,
            )
            return mapOf("missing-template-foreign" to foreign, "missing-template-absent" to get(path))
        }

        private fun get(path: String): Answer =
            given()
                .port(port)
                .asSession(admin)
                .accept(JSON)
                .get(path)
                .answer()

        private fun Response.answer() = Answer(statusCode, body().asString())

        private fun Response.expect(
            what: String,
            status: Int,
        ): Response = also { withClue("$what: ${asString().take(EXCERPT)}") { statusCode shouldBe status } }

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

        /** Our workspace and a foreign one, the workspace admin who authors everything, and a super admin who registers. */
        private fun seedRows() {
            E2eClean.beforeSeeding()
            DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
                c.createStatement().use { s ->
                    s.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Dependency goldens'), " +
                            "('$FOREIGN_ID', 'depgolden-foreign', 'Foreign')",
                    )
                    listOf(ADMIN to false, SUPER_ADMIN to true).forEach { (id, superAdmin) ->
                        s.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$id', '$id@depgolden.test', 'golden', 'test', 'depgolden-$id', TRUE, $superAdmin)",
                        )
                    }
                    s.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN', 'workspace_admin')",
                    )
                }
            }
        }

        private fun session(
            user: String,
            role: String,
        ): String = E2eSession.jwt(JWT_SECRET, user, "$role@depgolden.test", WORKSPACE)

        private fun datasourceBody(
            name: String,
            readonly: Boolean,
        ) = """{"name": "$name", "display_name": "Dependency goldens", "dialect": "H2", "readonly": $readonly,
               "jdbc_url": "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1", "username": "sa", "password": "sa"}"""
    }

    private companion object {
        const val JSON = "application/json"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val EXCERPT = 600
        const val REQUEST_INVALID = "dependencies.request.invalid"
        const val OBSERVED_AT = "2026-01-01T00:00:00Z"
        val MAPPER = ObjectMapper()

        const val WORKSPACE = "depgolden-e2e"
        val WORKSPACE_ID = UUID.randomUUID().toString()
        val FOREIGN_ID = UUID.randomUUID().toString()
        val ADMIN = UUID.randomUUID().toString()
        val SUPER_ADMIN = UUID.randomUUID().toString()
        const val PREFIX = "test/tree875/golden"
        const val DATASOURCE = "tree875_golden"
        const val READONLY_DATASOURCE = "tree875_golden_ro"
        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        var collected: Map<String, Answer>? = null

        /** A tree request at version 1, the name as written: RestAssured encodes the query once (pre-encoded, it is sent twice). */
        fun tree(
            family: String,
            name: String,
        ) = "/api/v1/dependencies/$family?name=$name&version=1"

        /** One request per `details.reason` the routes produce (erratum (8)(a): `family_unknown` is not one of them). */
        val REFUSALS: Map<String, String> =
            mapOf(
                "refusal-name-invalid" to "/api/v1/dependencies/pipeline?name=Upper/Case&version=1",
                "refusal-version-invalid" to "/api/v1/dependencies/template?name=$PREFIX/query.sql&version=0",
                "refusal-id-invalid" to "/api/v1/dependencies/schedule?id=not-a-uuid",
                "refusal-value-too-long" to "/api/v1/dependencies/dashboard?name=${"a".repeat(201)}&version=1",
                "refusal-selector-incompatible" to "/api/v1/dependencies/datasource?name=$DATASOURCE&version=1",
            )

        /** One absent root per family: each family's own native not-found. */
        val NOT_FOUND: Map<String, String> =
            mapOf(
                "pipeline" to tree("pipeline", "$PREFIX/nowhere"),
                "template" to tree("template", "$PREFIX/nowhere.sql"),
                "parameter_set" to tree("parameter_set", "$PREFIX/nowhere"),
                "visualization" to tree("visualization", "$PREFIX/nowhere"),
                "dashboard" to tree("dashboard", "$PREFIX/nowhere"),
                "api_endpoint" to tree("api_endpoint", "/api/test_tree875_golden/v1/nowhere"),
                "schedule" to "/api/v1/dependencies/schedule?id=0000875e-0000-4000-8000-000000000000",
                "datasource" to "/api/v1/dependencies/datasource?name=tree875_nowhere",
            )

        val NOT_FOUND_CODES =
            mapOf(
                "pipeline" to "pipeline.execution.not_found",
                "template" to "template.not_found",
                "parameter_set" to "parameter.not_found",
                "visualization" to "visualization.not_found",
                "dashboard" to "dashboard.not_found",
                "api_endpoint" to "endpoint.not_found",
                "schedule" to "schedule.not_found",
                "datasource" to "datasource.not_found",
            )

        val TREES =
            listOf(
                "tree-dashboard",
                "tree-pipeline",
                "tree-pipeline-writer",
                "tree-pipeline-discarded",
                "tree-template-import",
                "tree-template",
                "tree-parameter-set",
                "tree-visualization-evidence-ready",
                "tree-visualization-evidence-not-ready",
                "tree-api-endpoint-enabled",
                "tree-api-endpoint-disabled",
                "tree-datasource-writable",
                "tree-datasource-readonly",
                "tree-schedule-enabled",
                "tree-schedule-paused",
                "tree-schedule-blocked",
                "missing-template-foreign",
                "missing-template-absent",
            )

        /** Every route case and what it answers. */
        val CASES: Map<String, Expected> =
            TREES.associateWith { Expected(HTTP_OK) } +
                REFUSALS.keys.associateWith { Expected(HTTP_BAD_REQUEST, REQUEST_INVALID) } +
                NOT_FOUND_CODES.map { (family, code) -> "not-found-$family" to Expected(HTTP_NOT_FOUND, code) } +
                ("unauthenticated" to Expected(HTTP_UNAUTHORIZED))

        /** The web module's cases (`DependenciesGoldenTest`): what no authored workspace can make the routes produce. */
        val WEB_CASES =
            setOf(
                "tree-acyclic-false",
                "tree-limit-depth",
                "tree-limit-nodes",
                "tree-limit-edges",
                "tree-limit-diagnostics",
                "tree-limit-wire-bytes",
                "metadata-unavailable",
                "response-too-large",
            )

        /**
         * A47's list: 3 acyclic + 9 operational + 12 relationships + 4 constraint tokens + 5 reasons + the MISSING twins,
         * plus the payload budget's `wire_bytes` LIMIT 200 (#875 R1: §11.1's limit row names it beside the four walk limits).
         */
        const val A47_FLOOR = 3 + 9 + 12 + 4 + 5 + 2 + 1

        val RELATIONSHIPS =
            setOf(
                "TEMPLATE",
                "CHILD_PIPELINE",
                "DATASOURCE",
                "IMPORT",
                "PARAMETER_SOURCE_TEMPLATE",
                "PARAMETER_SOURCE_DATASOURCE",
                "TRANSFORM_TEMPLATE",
                "PARAMETER_SET",
                "SOURCE_PIPELINE",
                "VISUALIZATION",
                "PIPELINE_PIN",
                "EXECUTOR_TARGET",
            )
        val CONSTRAINTS = setOf("parameter_names", "datasource_access:read", "datasource_access:write", "datasource_dialect")

        /** §11.1's nine variants less the two API ones no provider sets yet (#902, the known-gap assertion above). */
        val OPERATIONAL =
            setOf(
                """schedule:{"condition":"enabled"}""",
                """schedule:{"condition":"paused"}""",
                """schedule:{"condition":"blocked"}""",
                """datasource:{"readonly":true}""",
                """datasource:{"readonly":false}""",
                """visualization:{"evidence_ready":true}""",
                """visualization:{"evidence_ready":false}""",
            )
        val REASONS = setOf("name_invalid", "version_invalid", "id_invalid", "value_too_long", "selector_incompatible")

        fun constraintToken(constraint: JsonNode): String =
            constraint.path("kind").asText() + (constraint.path("mode").takeUnless { it.isMissingNode }?.let { ":${it.asText()}" } ?: "")

        fun repoRoot(): Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { it.resolve("settings.gradle.kts").toFile().exists() }

        val GOLDENS_DIR: Path get() = repoRoot().resolve("modules/web/src/test/resources/dependencies/goldens")

        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_PATTERN = Regex("\"[0-9a-f]{64}\"")

        /** The printer G1's goldens are written in: two-space indent, arrays one element per line, `"key": value`. */
        private val PRINTER =
            DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .apply { indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE) }

        /**
         * The answer with what differs run to run replaced: `observed_at` by a fixed instant; every UUID (the correlation
         * id first) and every 64-hex hash by a placeholder numbered in order of first appearance — so two answers that
         * differ only in those values print the same, and a value that appears twice prints the same placeholder twice.
         */
        fun normalised(body: String): String {
            val tree = MAPPER.readTree(body)
            (tree.path("data") as? ObjectNode)?.takeIf { it.has("observed_at") }?.put("observed_at", OBSERVED_AT)
            val printed = MAPPER.writer(PRINTER).writeValueAsString(tree)
            val uuids = LinkedHashMap<String, String>()
            val hashes = LinkedHashMap<String, String>()
            return HASH_PATTERN
                .replace(
                    UUID_PATTERN.replace(printed) { m -> uuids.getOrPut(m.value) { "00000000-0000-4000-8000-%012d".format(uuids.size) } },
                ) { m ->
                    hashes.getOrPut(m.value) { "\"%064d\"".format(hashes.size + 1) }
                } + "\n"
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
            // API mode: no dispatching context may take other suites' runs (and no scheduler touches the blocked row).
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
