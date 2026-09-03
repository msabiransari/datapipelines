package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRegistry
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * 042 exit gate 2 — the injection test that fails without the round.
 *
 * The same STRING payloads run through the real render → execute path against a real Postgres,
 * in both template forms:
 *
 *  - **`:label` (the bound form)** — a payload like `x' OR '1'='1` matches no row and a
 *    `; DROP TABLE …` payload is one inert scalar; the statement is never altered. These are
 *    the tests that went RED on the pre-round code (where `:label` was literal text and
 *    execution failed), and green here.
 *  - **`'${label}'` (the old interpolated form)** — the SAME payloads alter the statement:
 *    every row comes back, and a `; DROP` drops. These tests pin the pre-round hole exactly
 *    as the gate demands it be shown; they stay green after the round on purpose, because
 *    enforcement is at SAVE (042 B2/E2): an already-stored template keeps executing, and
 *    the save-time rule is what stops new ones. See `ParameterBindingValidationTest` /
 *    the `ReferenceRules` tests for the refusal side.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParameterBindingIntegrationTest {
    @BeforeAll
    fun seed() {
        jdbc().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS secrets")
                statement.execute("CREATE TABLE secrets (id int PRIMARY KEY, label text NOT NULL)")
                statement.execute("INSERT INTO secrets VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')")
                statement.execute("DROP TABLE IF EXISTS tokens")
                statement.execute("CREATE TABLE tokens (id int PRIMARY KEY, note text NOT NULL)")
                statement.execute("INSERT INTO tokens VALUES (1, 'keep-me'), (2, 'keep-me-too')")
                statement.execute("DROP TABLE IF EXISTS drop_target")
                statement.execute("CREATE TABLE drop_target (id int PRIMARY KEY, note text NOT NULL)")
                statement.execute("INSERT INTO drop_target VALUES (1, 'x')")
            }
        }
    }

    @AfterEach
    fun restore() {
        jdbc().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS drop_target (id int PRIMARY KEY, note text NOT NULL)")
                statement.execute("INSERT INTO drop_target (id, note) SELECT 1, 'x' WHERE NOT EXISTS (SELECT 1 FROM drop_target)")
            }
        }
    }

    // ----------------------------------------------------------------- bound form

    @Test
    fun `a bound STRING parameter with an OR payload matches no rows instead of altering the statement`() =
        runBlocking<Unit> {
            val result = execute("SELECT id FROM secrets WHERE label = :label", "x' OR '1'='1")

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 0
        }

    @Test
    fun `a bound STRING parameter with a semicolon DROP payload leaves the table intact`() =
        runBlocking<Unit> {
            val result = execute("SELECT id FROM secrets WHERE label = :label", "x'; DROP TABLE secrets; --")

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 0
            countOf("secrets") shouldBe 3
        }

    @Test
    fun `a bound STRING parameter on a DML node deletes nothing`() =
        runBlocking<Unit> {
            val result = execute("DELETE FROM tokens WHERE note = :note", "x' OR '1'='1", NodeType.DML)

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 0
            countOf("tokens") shouldBe 2
        }

    // ------------------------------------------------------- the pre-round hole

    @Test
    fun `the interpolated form with an OR payload returns every row — the hole the round closes`() =
        runBlocking<Unit> {
            val result = execute("SELECT id FROM secrets WHERE label = '\${label}'", "x' OR '1'='1")

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single().rowsOut shouldBe 3
        }

    @Test
    fun `the interpolated form with a semicolon DROP payload drops the table — the hole the round closes`() =
        runBlocking<Unit> {
            // The injection works so well that the executor dies draining the now-mangled
            // cursor — the assertion that matters is the destroyed table, not the status.
            shouldThrow<PipelineExecutionFailed> {
                execute("SELECT id FROM drop_target WHERE note = '\${label}'", "x'; DROP TABLE drop_target; --")
            }
            countOf("drop_target") shouldBe 0
        }

    // ------------------------------------------------------------------ helpers

    private suspend fun execute(
        templateBody: String,
        payload: String,
        nodeType: NodeType = NodeType.DQL,
    ): ExecutionResult {
        val engine = engineFor(templateBody)
        return engine.use {
            val harness =
                ExecutorHarness(
                    templateEngine = engine,
                    registry = FakeDatasourceRegistry(mapOf(DATASOURCE to datasource())),
                )
            harness.use {
                val pipeline =
                    Fixtures.pipeline(
                        nodes =
                            listOf(
                                Fixtures.node(
                                    "query",
                                    type = nodeType,
                                    source = DATASOURCE,
                                    output = if (nodeType == NodeType.DQL) co.datapipelines.pipeline.NodeOutput.Caller else null,
                                    template = co.datapipelines.pipeline.TemplateRef(TEMPLATE_ID, 1),
                                ),
                            ),
                        parameters =
                            mapOf(
                                "label" to Parameter(type = LogicalType.STRING, required = true),
                                "note" to Parameter(type = LogicalType.STRING, required = true),
                            ),
                    )
                val request =
                    ExecuteRequest(
                        pipelineId = UUID.randomUUID(),
                        pipelineVersion = 1,
                        pipeline = pipeline,
                        userId = UUID.randomUUID(),
                        workspaceId = UUID.randomUUID(),
                        parameters = mapOf("label" to TextNode(payload), "note" to TextNode(payload)),
                    )
                it.executor.execute(request)
            }
        }
    }

    private fun engineFor(body: String): TemplateEngine {
        val stored =
            TemplateVersion(
                id = TEMPLATE_ID,
                version = 1,
                dialect = Dialect.POSTGRES,
                isLibrary = false,
                imports = emptyList(),
                body = body,
                createdAt = Instant.now(),
                createdBy = UUID.randomUUID(),
            )
        val registry =
            object : TemplateRegistry {
                override fun lookup(
                    id: String,
                    version: Int,
                ): TemplateVersion? = if (id == TEMPLATE_ID && version == 1) stored else null

                override fun existsId(id: String): Boolean = id == TEMPLATE_ID
            }
        return TemplateEngine(registry, cacheSize = 16, renderTimeoutMs = 10_000, maxOutputChars = 1_000_000)
    }

    private fun countOf(table: String): Int {
        val connection = jdbc()
        return connection.use {
            val statement = connection.createStatement()
            statement.use {
                if (!tableExists(statement, table)) 0 else countRowsIn(statement, table)
            }
        }
    }

    private fun countRowsIn(
        statement: java.sql.Statement,
        table: String,
    ): Int =
        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun tableExists(
        statement: java.sql.Statement,
        table: String,
    ): Boolean =
        statement
            .executeQuery("SELECT 1 FROM information_schema.tables WHERE table_name = '$table'")
            .use { it.next() }

    private fun datasource() =
        Datasource(
            name = DATASOURCE,
            displayName = DATASOURCE,
            dialect = Dialect.POSTGRES,
            jdbcUrl = db.jdbcUrl,
            username = db.username,
            password = db.password,
        )

    private fun jdbc() = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    private companion object {
        const val DATASOURCE = "pg_injection"
        const val TEMPLATE_ID = "inj.sql"

        /**
         * A scratch database on the module's shared container: this suite builds its own
         * ad-hoc `secrets`/`tokens`/`drop_target` schema, which the shipped migrations do
         * not create, so it must not see the migrated database the other suites use.
         */
        val db = SharedPostgres.scratchDatabase("injection")
    }
}
