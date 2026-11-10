package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.sql.Statement
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * #868 G1 section D.4 — the release plan path's closure read (`ReleasePlanningWalk.plan`) over the REAL wired
 * application and the shared test Postgres: ONE read-only REPEATABLE READ transaction on `metadataTransactionManager`
 * (record §7.2 step 2, §7.3; A14's coherence half).
 *
 * The walk is assembled from the PRODUCTION pieces the `ReleaseConfiguration` beans use — the release sources over the
 * production repository classes, `ReleaseDependencyFamilies`, the context's `releaseBounds` and `releasePlanningDeadline`
 * beans and `ReleasePlanningWalk.planningTransaction(metadataTransactionManager)` — with two test seams: a provider hook
 * (a `DependencySource` proxy that runs a probe before each read) and a statement log (the repositories' `JdbcTemplate`
 * records every statement it prepares). This module compiles against `:modules:app` only (module-structure §4.2), so the
 * pieces are reached by reflection, as `ManagedLakeReadsE2eTest` reaches the registry.
 *
 * (a) the hook reads the transaction's isolation and read-only setting; (b) a latch between two provider batches lets a
 * second connection commit a template draft edit — the walk's later batch still reads the pre-edit facts and a fresh walk
 * reads the edit; (c) a write inside the planning transaction is refused by the database; (d) the walk issues exactly
 * the repositories' metadata statements and not one customer-datasource statement (the H2 datasource's own
 * `QUERY_STATISTICS` is the customer-side log). Latches, never sleeps.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DependencyPlanningSnapshotIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newSingleThreadExecutor()
    private var rpcId = 0

    /** Every statement the walk's repositories prepared, in order (the metadata half of the statement log). */
    private val statements: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val dataSource: DataSource get() = context.getBean("dataSource", DataSource::class.java)

    /** A probe on the SAME DataSource: inside the planning transaction it runs on the transaction's own connection. */
    private val probe: JdbcTemplate get() = JdbcTemplate(dataSource)

    // ---- the walk, assembled from the production pieces -------------------------------------------------------

    /** The planning walk with [hook] run before every source read, on the calling thread. */
    private fun walk(hook: (family: String, name: String) -> Unit = { _, _ -> }): PlanningWalk {
        val recording =
            object : JdbcTemplate(dataSource) {
                override fun applyStatementSettings(stmt: Statement) {
                    super.applyStatementSettings(stmt)
                    statements += stmt.toString()
                }
            }
        val named = NamedParameterJdbcTemplate(recording)
        val pipelines = Class.forName(PIPELINE_REPOSITORY).getConstructor(NamedParameterJdbcTemplate::class.java).newInstance(named)
        val templates = Class.forName(TEMPLATE_REPOSITORY).getConstructor(NamedParameterJdbcTemplate::class.java).newInstance(named)
        val deserializer = Class.forName(PIPELINE_DESERIALIZER).getConstructor().newInstance()
        val pipelineSource =
            Class
                .forName(PIPELINE_SOURCE)
                .getConstructor(Class.forName(PIPELINE_REPOSITORY), Class.forName(PIPELINE_DESERIALIZER))
                .newInstance(pipelines, deserializer)
        val templateSource = Class.forName(TEMPLATE_SOURCE).getConstructor(Class.forName(TEMPLATE_REPOSITORY)).newInstance(templates)
        val sources = mapOf(family("PIPELINE") to hooked(pipelineSource, hook), family("TEMPLATE") to hooked(templateSource, hook))

        val families = Class.forName(FAMILIES)
        val registry = families.getMethod("registry", Map::class.java).invoke(families.getField("INSTANCE").get(null), sources)
        val walkType = Class.forName(PLANNING_WALK)
        val companion = walkType.getField("Companion").get(null)
        val manager = context.getBean("metadataTransactionManager", PlatformTransactionManager::class.java)
        val planning =
            companion.javaClass
                .getMethod(
                    "planningTransaction",
                    PlatformTransactionManager::class.java,
                ).invoke(companion, manager)
        // #868 R1: the production speculative-read fence, a savepoint on the same manager.
        val speculativeReads =
            companion.javaClass
                .getMethod("speculativeReads", PlatformTransactionManager::class.java)
                .invoke(companion, manager)
        val constructor = walkType.constructors.single { it.parameterCount == WALK_CONSTRUCTOR_PARAMETERS }
        val walk =
            constructor.newInstance(
                registry,
                context.getBean("releaseBounds"),
                planning,
                speculativeReads,
                context.getBean("releasePlanningDeadline"),
                Clock.systemUTC(),
            )
        return PlanningWalk(walk, planning as TransactionTemplate)
    }

    /** A `DependencySource` that runs [hook] with the identity's family and name, then reads through [real]. */
    private fun hooked(
        real: Any,
        hook: (String, String) -> Unit,
    ): Any {
        val type = Class.forName(DEPENDENCY_SOURCE)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == "resolve") {
                val identity = args.single()
                hook(identity.read("getFamily").toString(), identity.read("getEntityId") as String)
            }
            try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun family(name: String): Any = Class.forName(RELEASE_FAMILY).getField(name).get(null)

    private fun Any.read(getter: String): Any? = javaClass.getMethod(getter).invoke(this)

    /** One assembled walk and its planning template. */
    private inner class PlanningWalk(
        private val walk: Any,
        val planning: TransactionTemplate,
    ) {
        /** `plan(root, actor)`; the closure's nodes as name to body hash, in closure order. */
        fun plan(): Map<String, String?> {
            val root =
                Class
                    .forName(NODE_IDENTITY)
                    .getConstructor(UUID::class.java, Class.forName(RELEASE_FAMILY), String::class.java, Int::class.javaPrimitiveType)
                    .newInstance(UUID.fromString(WORKSPACE_ID), family("PIPELINE"), ROOT, 1)
            val closure =
                try {
                    walk.javaClass
                        .getMethod(
                            "plan",
                            Class.forName(NODE_IDENTITY),
                            UUID::class.java,
                        ).invoke(walk, root, UUID.fromString(ADMIN_ID))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            val nodes = closure.shouldNotBeNull().read("getNodes") as List<*>
            return nodes.filterNotNull().associate { node -> node.read("getName") as String to node.read("getBodyHash") as String? }
        }
    }

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - an H2 datasource, a DRAFT template, a child pipeline and the root that pins both`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SET QUERY_STATISTICS TRUE") }
        rest()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Planning snapshot (868)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
        val (template, failed) = callTool("templates_create", templateArguments("SELECT 1 AS n"))
        withClue("templates_create must succeed: $template") { failed shouldBe false }
        createPipeline(CHILD, listOf(dqlNode()))
        createPipeline(ROOT, listOf(dqlNode(), pipelineNode(CHILD)))

        walk().plan().keys shouldBe setOf(ROOT, TEMPLATE, CHILD)
    }

    @Test
    @Order(2)
    fun `a - inside the walk the transaction is read-only REPEATABLE READ, and outside it the session default is not`() {
        val seen = ArrayList<String>()
        walk { _, _ ->
            seen += probe.queryForObject("SHOW transaction_isolation", String::class.java).orEmpty()
            seen += probe.queryForObject("SELECT current_setting('transaction_read_only')", String::class.java).orEmpty()
        }.plan()
        withClue("every provider read ran in the planning transaction: $seen") {
            seen.chunked(2).toSet() shouldBe setOf(listOf("repeatable read", "on"))
        }
        // The control: the same probe in an ordinary metadata transaction reads the defaults, so (a) is the template's.
        val manager = context.getBean("metadataTransactionManager", PlatformTransactionManager::class.java)
        TransactionTemplate(manager).execute {
            probe.queryForObject("SHOW transaction_isolation", String::class.java) shouldBe "read committed"
            probe.queryForObject("SELECT current_setting('transaction_read_only')", String::class.java) shouldBe "off"
        }
    }

    @Test
    @Order(3)
    fun `b - a draft edit committed between two provider batches is invisible to the walk and visible to the next`() {
        val before = templateHash()
        val reachedSecondBatch = CountDownLatch(1)
        val edited = CountDownLatch(1)
        var paused = false
        val walk =
            walk { _, name ->
                // The root's batch is read first; the hook holds the walk before its first non-root read.
                if (name != ROOT && !paused) {
                    paused = true
                    reachedSecondBatch.countDown()
                    check(edited.await(LATCH_SECONDS, TimeUnit.SECONDS)) { "the edit never committed" }
                }
            }
        val closure = CompletableFuture.supplyAsync({ walk.plan() }, pool)
        withClue("the walk reached its second batch") { reachedSecondBatch.await(LATCH_SECONDS, TimeUnit.SECONDS) shouldBe true }

        // A second connection (the application's MCP writer, its own transaction) commits a draft body edit.
        val (updated, failed) = callTool("templates_update", templateArguments("SELECT 2 AS n") + ("expected_hash" to before))
        withClue("templates_update must commit: $updated") { failed shouldBe false }
        val after = templateHash()
        withClue("the edit is committed and changed the draft's body hash") { after shouldNotBe before }
        edited.countDown()

        withClue("the walk's later batch still reads the snapshot its first batch took") {
            closure.get(LATCH_SECONDS, TimeUnit.SECONDS)[TEMPLATE] shouldBe before
        }
        withClue("a fresh walk reads the committed edit") { walk().plan()[TEMPLATE] shouldBe after }
    }

    @Test
    @Order(4)
    fun `c - a write inside the planning transaction is refused by the database`() {
        var refused: Throwable? = null
        val walk =
            walk { _, name ->
                if (name == ROOT && refused == null) {
                    refused =
                        runCatching { probe.update("UPDATE workspaces SET display_name = display_name WHERE id = ?::uuid", WORKSPACE_ID) }
                            .exceptionOrNull() ?: IllegalStateException("the write was accepted")
                }
            }
        // The refused statement aborts the transaction, so the walk's own read after it fails too: no closure is built.
        shouldThrow<DataAccessException> { walk.plan() }
        withClue("the write inside the planning transaction") {
            refused
                .shouldNotBeNull()
                .shouldBeInstanceOfDataAccess()
                .message
                .orEmpty() shouldContain "read-only transaction"
        }
        // The control: the same write in an ordinary metadata transaction is accepted.
        val manager = context.getBean("metadataTransactionManager", PlatformTransactionManager::class.java)
        TransactionTemplate(manager).execute {
            probe.update("UPDATE workspaces SET display_name = display_name WHERE id = ?::uuid", WORKSPACE_ID) shouldBe 1
        }
    }

    @Test
    @Order(5)
    fun `d - the walk issues the repositories' metadata statements only and no customer-datasource statement`() {
        val customerBefore = customerStatements()
        statements.clear()

        walk().plan().keys shouldBe setOf(ROOT, TEMPLATE, CHILD)

        val log = statements.toList()
        withClue("the metadata statement log: ${log.joinToString("\n")}") {
            log shouldHaveSize EXPECTED_STATEMENTS
            log.flatMap(::tablesOf).toSet() shouldBe METADATA_TABLES
        }
        withClue("customer-datasource statements during the walk") { (customerStatements() - customerBefore).shouldBeEmpty() }

        // Non-vacuity of the customer-side log: another session's statement on the H2 database IS counted.
        val beforeControl = customerStatements()
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute(CONTROL_STATEMENT) }
        (customerStatements() - beforeControl) shouldContain CONTROL_STATEMENT
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private fun Throwable.shouldBeInstanceOfDataAccess(): DataAccessException {
        withClue("expected a DataAccessException, got $this") { (this is DataAccessException) shouldBe true }
        return this as DataAccessException
    }

    /** Every statement execution the H2 database counted, one entry per execution, minus this probe's own reads. */
    private fun customerStatements(): List<String> =
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT SQL_STATEMENT, EXECUTION_COUNT FROM INFORMATION_SCHEMA.QUERY_STATISTICS").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) to rows.getInt(2) else null }
                        .filterNot { (sql, _) -> "QUERY_STATISTICS" in sql }
                        .flatMap { (sql, count) -> List(count) { sql } }
                        .toList()
                }
            }
        }

    private fun tablesOf(sql: String): List<String> = TABLE_REFERENCE.findAll(sql).map { it.groupValues[1].lowercase() }.toList()

    private fun templateHash(): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT v.body_hash FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                        " WHERE t.workspace_id = ?::uuid AND t.name = ? AND v.version = 1",
                ).use { ps ->
                    ps.setString(1, WORKSPACE_ID)
                    ps.setString(2, TEMPLATE)
                    ps.executeQuery().use {
                        it.next() shouldBe true
                        it.getString(1)
                    }
                }
        }

    private fun templateArguments(body: String): Map<String, Any?> =
        mapOf(
            "id" to TEMPLATE,
            "dialect" to "H2",
            "display_name" to "Planning snapshot (868)",
            "description" to "Expects: nothing (plain SQL).",
            "body" to body,
        )

    private fun dqlNode(): Map<String, Any?> =
        mapOf(
            "id" to "n0",
            "type" to "DQL",
            "source" to DATASOURCE,
            "description" to "One row",
            "template" to mapOf("id" to TEMPLATE, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    private fun pipelineNode(child: String): Map<String, Any?> =
        mapOf(
            "id" to "n1",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to child, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    private fun createPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ) {
        val arguments =
            mapOf(
                "name" to name,
                "display_name" to "Planning snapshot (868)",
                "description" to "The planning walk's fixture.",
                "nodes" to nodes,
            )
        val (created, failed) = callTool("pipelines_create", arguments)
        withClue("pipelines_create $name must succeed: $created") { failed shouldBe false }
    }

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val id = synchronized(this) { ++rpcId }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to id,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { body.has("error") shouldBe false }
        val result = body["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun rest() = given().port(port).asSession(SESSION)

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val PIPELINE_REPOSITORY = "co.datapipelines.pipeline.PipelineRepository"
        private const val PIPELINE_DESERIALIZER = "co.datapipelines.pipeline.PipelineDeserializer"
        private const val TEMPLATE_REPOSITORY = "co.datapipelines.templates.TemplateRepository"
        private const val PIPELINE_SOURCE = "co.datapipelines.pipeline.release.PipelineDependencySource"
        private const val TEMPLATE_SOURCE = "co.datapipelines.web.release.TemplateDependencySource"
        private const val DEPENDENCY_SOURCE = "co.datapipelines.pipeline.release.DependencySource"
        private const val RELEASE_FAMILY = "co.datapipelines.pipeline.release.ReleaseFamily"
        private const val NODE_IDENTITY = "co.datapipelines.pipeline.release.ReleaseNodeIdentity"
        private const val FAMILIES = "co.datapipelines.application.dependencies.ReleaseDependencyFamilies"
        private const val PLANNING_WALK = "co.datapipelines.application.dependencies.ReleasePlanningWalk"

        /** registry, bounds, planning transactions, speculative-read fence, deadline, clock. */
        private const val WALK_CONSTRUCTOR_PARAMETERS = 6

        /** Three versions, three repository reads each (record §7.2: statement-count tests enforce batching). */
        private const val EXPECTED_STATEMENTS = 9

        private val METADATA_TABLES = setOf("pipelines", "pipeline_versions", "templates", "template_versions")
        private val TABLE_REFERENCE = Regex("""(?i)\b(?:from|join)\s+([a-z_][a-z0-9_]*)""")
        private const val CONTROL_STATEMENT = "SELECT 868 AS control"

        private const val LATCH_SECONDS = 30L
        private const val TEMPLATE = "test/plan_868.sql"
        private const val CHILD = "test/plan_868_child"
        private const val ROOT = "test/plan_868_root"
        private const val DATASOURCE = "h2-plan-868"
        private const val H2_JDBC_URL = "jdbc:h2:mem:plan868;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "plan-868"

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-868-plan-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-868-plan@datapipelines.test", WORKSPACE)

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
            // API mode: this suite only authors and reads — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Planning snapshot 868')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin (an author there).
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-868-plan@datapipelines.test', 'E2E 868', 'test', 'e2e-868-plan-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', " +
                            "'${KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
