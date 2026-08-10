package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The canonical multi-node shape, written the way the specs' own worked examples write it:
 * **unquoted** references to staged tables and columns.
 *
 * ## Why this suite exists in its current form
 *
 * It began as a defect record. `H2StagingFactory` built `…;MODE=PostgreSQL`, and — verified against
 * the pinned driver — `MODE=PostgreSQL` alone does **not** lower-fold unquoted identifiers, while
 * staging creates every staged table and column quoted and lower-case (staging §4.5). So
 * `SELECT n FROM stg_orders` folded to `N` / `STG_ORDERS` and failed with `Column "N" not found`,
 * breaking fetch → stage → join → caller end to end for exactly the style dag-executor §6.5,
 * pipeline-contract §10.1 and templates.md §11 all use.
 *
 * staging v1.10 added `DATABASE_TO_LOWER=TRUE` to the URL. This suite is now the **positive**
 * contract on the executor side of that fix: the unquoted form works, so a regression in the URL
 * fails here as well as in staging's own tests. Keeping it phrased as the author-facing behaviour
 * — rather than as an assertion about a JDBC parameter — is the point: it is what a pipeline author
 * would actually write.
 */
class StagedIdentifierCaseFoldingTest {
    @Test
    fun `an unquoted reference to a staged table and column resolves`() =
        runBlocking<Unit> {
            val result = runStagedReadback(downstreamSql = "SELECT n FROM stg ORDER BY n")

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single { it.nodeId == "read" }.rowsOut shouldBe 2
        }

    @Test
    fun `a star projection over a staged table resolves and carries the source column name`() =
        runBlocking<Unit> {
            val harness = harness("SELECT * FROM stg")
            harness.use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(stagePlusRead())))

                result.status shouldBe ExecutionStatus.SUCCESS
                val view = h.resultStore.describe(result.resultRef.shouldNotBeNull()).shouldNotBeNull()
                // Lower-folded on the way in, so the wire schema keeps the author's own spelling.
                view.schema.single().name shouldBe "n"
                view.totalRows shouldBe 2
            }
        }

    @Test
    fun `the explicitly quoted form still works, so neither spelling is now broken`() =
        runBlocking<Unit> {
            // The fix must not have traded one folding failure for its mirror image: a template
            // that quotes (the workaround authors were forced into) has to keep working too.
            val result = runStagedReadback(downstreamSql = """SELECT "n" FROM "stg" ORDER BY "n"""")

            result.status shouldBe ExecutionStatus.SUCCESS
            result.nodeStats.single { it.nodeId == "read" }.rowsOut shouldBe 2
        }

    @Test
    fun `an unquoted join across two staged tables resolves`() =
        runBlocking<Unit> {
            // The shape templates.md §11 actually shows: two staged tables joined by name, unquoted.
            val source = h2Datasource("cf_join", listOf("CREATE TABLE src (n INT)", "INSERT INTO src VALUES (1), (2)"))
            val nodes =
                listOf(
                    Fixtures.node("a", source = "cf_join", output = NodeOutput.Tempdb("stg_a")),
                    Fixtures.node("b", source = "cf_join", output = NodeOutput.Tempdb("stg_b")),
                    Fixtures.node("joined", output = NodeOutput.Caller, dependsOn = listOf("a", "b")),
                )
            val sql =
                mapOf(
                    "a" to "SELECT n FROM src",
                    "b" to "SELECT n FROM src",
                    "joined" to "SELECT a.n FROM stg_a a JOIN stg_b b ON a.n = b.n ORDER BY a.n",
                )

            ExecutorHarness(
                templateEngine = Fixtures.templateEngine(sql),
                registry = FakeDatasourceRegistry(mapOf("cf_join" to source)),
            ).use { h ->
                val result = h.executor.execute(Fixtures.request(Fixtures.pipeline(nodes)))

                result.status shouldBe ExecutionStatus.SUCCESS
                result.nodeStats.single { it.nodeId == "joined" }.rowsOut shouldBe 2
            }
        }

    // ------------------------------------------------------------------ helpers

    private suspend fun runStagedReadback(downstreamSql: String): ExecutionResult =
        harness(downstreamSql).use { h ->
            h.executor.execute(Fixtures.request(Fixtures.pipeline(stagePlusRead())))
        }

    private fun harness(downstreamSql: String): ExecutorHarness {
        val source = h2Datasource("cf_src", listOf("CREATE TABLE src (n INT)", "INSERT INTO src VALUES (1), (2)"))
        return ExecutorHarness(
            templateEngine = Fixtures.templateEngine(mapOf("fetch" to "SELECT n FROM src", "read" to downstreamSql)),
            registry = FakeDatasourceRegistry(mapOf("cf_src" to source)),
        )
    }

    /** fetch (datasource → tempdb table `stg`) → read (tempdb → caller). */
    private fun stagePlusRead() =
        listOf(
            Fixtures.node("fetch", source = "cf_src", output = NodeOutput.Tempdb("stg")),
            Fixtures.node("read", output = NodeOutput.Caller, dependsOn = listOf("fetch")),
        )
}
