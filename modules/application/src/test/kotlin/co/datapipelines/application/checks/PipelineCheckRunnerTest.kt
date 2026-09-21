package co.datapipelines.application.checks

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.QueryRows
import co.datapipelines.datasources.ResultSchema
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.SqlProbeParameter
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeResult
import co.datapipelines.datasources.TestResult
import co.datapipelines.datasources.ValidationResult
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineCheck
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The check run itself (140), over a RECORDING probe double and an in-memory repository double.
 *
 * The doubles are mockk class mocks used as recording fakes — every call is stubbed and every
 * invocation is captured for assertion — which is the double this module keeps for final
 * classes (the 074 note in `build.gradle.kts`; `ExecutionLauncherTest`'s KDoc explains why a
 * strict mock would be the backwards double for the repository: a row that was NOT written is a
 * fact this suite asserts by reading the recorded list, never by an unstubbed call throwing).
 *
 * What is deliberately NOT here: a database. The repository's own SQL is proven against real
 * Postgres in `PipelineCheckRunRepositoryIntegrationTest`; this suite proves the run's
 * semantics — binding, resolution, verdicts, and that exactly one row lands per check.
 */
class PipelineCheckRunnerTest {
    private val datasources = FakeDatasourceRegistry()
    private val probeCalls = mutableListOf<ProbeCall>()
    private var probeBehavior: (ProbeCall) -> SqlProbeResult = { probeResult(BigDecimal("74.62")) }
    private val probe: SqlProbe =
        mockk {
            every { probe(any(), any(), any(), any(), any()) } answers {
                val call =
                    ProbeCall(
                        datasource = firstArg(),
                        sql = secondArg(),
                        parameters = thirdArg(),
                        limit = arg(3),
                    )
                probeCalls += call
                probeBehavior(call)
            }
        }
    private val inserted = mutableListOf<NewPipelineCheckRun>()
    private val runs: PipelineCheckRunRepository =
        mockk {
            every { insert(any()) } answers {
                val run = firstArg<NewPipelineCheckRun>()
                inserted += run
                storedRow(run)
            }
        }
    private val runner = PipelineCheckRunner(pipelines = mockk(), datasources = datasources, probe = probe, runs = runs)

    // ------------------------------------------------------------------------------- binding

    @Test
    fun `defaults fill the execute way and an undeclared supplied key never reaches the probe`() {
        val parameters =
            mapOf(
                "month" to Parameter(LogicalType.DATE, default = TextNode("2026-08-01")),
                "window" to Parameter(LogicalType.INTEGER, default = IntNode(10)),
            )
        val pipeline = pipeline(parameters, check(datasource = "main", expected = valueExpectation()))
        datasources.register("main")

        runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, supplied("""{"window": 3, "bogus": 1}"""), CheckRunVia.MCP, ACTOR)

        val call = probeCalls.single()
        call.parameters.getValue("month") shouldBe SqlProbeParameter(LogicalType.DATE, "2026-08-01")
        call.parameters.getValue("window") shouldBe SqlProbeParameter(LogicalType.INTEGER, "3")
        call.parameters.shouldNotContainKey("bogus")
        // The row records the BOUND context — defaults applied, the override honoured, no bogus.
        val recorded = inserted.single()
        val parametersJson = MAPPER.readTree(recorded.parametersJson)
        parametersJson.get("month").asText() shouldBe "2026-08-01"
        parametersJson.get("window").asInt() shouldBe 3
        (parametersJson.has("bogus")) shouldBe false
    }

    @Test
    fun `an unsupplied optional parameter with no default binds null - never guessed`() {
        val parameters = mapOf("tag" to Parameter(LogicalType.STRING))
        val pipeline = pipeline(parameters, check(datasource = "main", expected = valueExpectation()))
        datasources.register("main")

        runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.MCP, ACTOR)

        probeCalls.single().parameters.getValue("tag") shouldBe SqlProbeParameter(LogicalType.STRING, null)
    }

    @Test
    fun `a bind rejection records an error row per check and never touches the probe`() {
        val parameters = mapOf("month" to Parameter(LogicalType.DATE, required = true))
        val pipeline =
            pipeline(
                parameters,
                check(id = "a", datasource = "main", expected = valueExpectation()),
                check(id = "b", datasource = "main", expected = rowsExpectation(2)),
            )
        datasources.register("main")

        val outcomes = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.REST, ACTOR)

        outcomes shouldHaveSize 2
        outcomes.forEach { outcome ->
            outcome.verdict shouldBe CheckRunVerdict.ERROR
            outcome.observed.shouldBeNull()
            outcome.message shouldContain "the parameters did not bind"
        }
        probeCalls shouldHaveSize 0
        // One row per check all the same: the default '{}' (no bound context exists), no duration.
        inserted shouldHaveSize 2
        inserted.forEach { row ->
            row.verdict shouldBe CheckRunVerdict.ERROR
            row.parametersJson shouldBe "{}"
            row.durationMs.shouldBeNull()
        }
    }

    // ------------------------------------------------------------------------------- resolution

    @Test
    fun `an unresolvable datasource errors only its own checks`() {
        val pipeline =
            pipeline(
                emptyMap(),
                check(id = "on_main", datasource = "main", expected = valueExpectation()),
                check(id = "on_ghost", datasource = "ghost", expected = valueExpectation()),
            )
        datasources.register("main")

        val outcomes = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.MCP, ACTOR)

        outcomes.map { it.verdict } shouldBe listOf(CheckRunVerdict.PASS, CheckRunVerdict.ERROR)
        outcomes[1].message shouldContain "'ghost' is not registered or not visible"
        inserted shouldHaveSize 2
    }

    @Test
    fun `a registry read failure errors every check on that datasource`() {
        val pipeline =
            pipeline(
                emptyMap(),
                check(id = "a", datasource = "broken", expected = valueExpectation()),
                check(id = "b", datasource = "broken", expected = rowsExpectation(1)),
            )
        datasources.failOnResolve("broken")

        val outcomes = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.MCP, ACTOR)

        outcomes.map { it.verdict } shouldBe listOf(CheckRunVerdict.ERROR, CheckRunVerdict.ERROR)
        outcomes.forEach { it.message shouldContain "could not be resolved" }
        // The memoized resolution: ONE registry read answered both checks.
        datasources.resolutions shouldBe 1
    }

    // ------------------------------------------------------------------------------- persistence

    @Test
    fun `exactly one row lands per check, carrying via, actor, correlation, duration and observed_json`() {
        val pipeline =
            pipeline(
                emptyMap(),
                check(id = "share", name = "Manhattan share", datasource = "main", expected = valueExpectation()),
                check(
                    id = "row_count",
                    name = "Row count",
                    datasource = "main",
                    expected = rowsExpectation(2),
                    sql = "select count(*) from rollup",
                ),
            )
        datasources.register("main")
        probeBehavior = { call ->
            if (call.sql.contains("count")) {
                SqlProbeResult(QueryRows(SCHEMA, listOf(cellRow(1), cellRow(2)), truncated = false), wallMs = 1, plan = null)
            } else {
                probeResult(BigDecimal("74.62"))
            }
        }

        val outcomes =
            runner.run(
                WORKSPACE,
                PIPELINE_ID,
                VERSION,
                pipeline,
                emptyMap(),
                CheckRunVia.RELEASE,
                ACTOR,
                correlationId = "corr-140",
            )

        outcomes shouldHaveSize 2
        outcomes.forEach { it.verdict shouldBe CheckRunVerdict.PASS }
        inserted shouldHaveSize 2
        val byCheck = inserted.associateBy { it.checkId }
        byCheck.shouldContainKey("share")
        byCheck.getValue("share").let { row ->
            row.pipelineId shouldBe PIPELINE_ID
            row.version shouldBe VERSION
            row.via shouldBe CheckRunVia.RELEASE
            row.ranBy shouldBe ACTOR
            row.correlationId shouldBe "corr-140"
            row.durationMs.shouldNotBeNull()
            row.observedJson shouldBe """{"value":"74.62"}"""
        }
        byCheck.getValue("row_count").observedJson shouldBe """{"rows":2}"""
    }

    @Test
    fun `a rows check asks the probe for one row past the expectation`() {
        val pipeline = pipeline(emptyMap(), check(datasource = "main", expected = rowsExpectation(3)))
        datasources.register("main")

        runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.MCP, ACTOR)

        probeCalls.single().limit shouldBe 4
    }

    @Test
    fun `a probe refusal is an error row, not a throw`() {
        val pipeline = pipeline(emptyMap(), check(datasource = "main", expected = valueExpectation()))
        datasources.register("main")
        probeBehavior = { throw SqlProbeRefusalException("The probe SQL is not a single read-only SELECT.") }

        val outcomes = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.UI, ACTOR)

        outcomes.single().verdict shouldBe CheckRunVerdict.ERROR
        outcomes.single().message shouldContain "not a single read-only SELECT"
        inserted.single().verdict shouldBe CheckRunVerdict.ERROR
        inserted.single().observedJson.shouldBeNull()
    }

    @Test
    fun `a pipeline with no checks runs nothing and writes nothing`() {
        val outcomes = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline(emptyMap()), emptyMap(), CheckRunVia.MCP, ACTOR)

        outcomes shouldBe emptyList()
        probeCalls shouldHaveSize 0
        inserted shouldHaveSize 0
    }

    // ------------------------------------------------------------------------------- the resolving overload

    @Test
    fun `the version-less overload resolves the working version and runs its body`() {
        val body = pipeline(emptyMap(), check(datasource = "main", expected = valueExpectation()))
        val record = record()
        val pipelines =
            mockk<PipelineService> {
                every { findRecord(WORKSPACE, any(), PIPELINE_ID) } returns record
                every { workingVersion(WORKSPACE, any(), record) } returns 2
                every { findExecutable(WORKSPACE, any(), record, 2) } returns
                    PipelineService.ExecutablePipeline(record, 2, "{}", body)
            }
        datasources.register("main")

        val outcomes =
            PipelineCheckRunner(pipelines, datasources, probe, runs)
                .run(WORKSPACE, PIPELINE_ID, version = null, emptyMap(), CheckRunVia.MCP, ACTOR)

        outcomes.shouldNotBeNull()
        outcomes shouldHaveSize 1
        inserted.single().version shouldBe 2
    }

    @Test
    fun `the resolving overload returns null for an unknown pipeline - the surface owns the 404`() {
        val pipelines =
            mockk<PipelineService> {
                every { findRecord(WORKSPACE, any(), PIPELINE_ID) } returns null
            }

        PipelineCheckRunner(pipelines, datasources, probe, runs)
            .run(WORKSPACE, PIPELINE_ID, version = null, emptyMap(), CheckRunVia.MCP, ACTOR)
            .shouldBeNull()
    }

    // ------------------------------------------------------------------------------- 144 — the semantics the guide teaches

    @Test
    fun `a fixed expectation beside a bound window passes on the baseline and fails off it - 144 B`() {
        // The synthetic shape of the 2026-09-15 acceptance miss: the window is a parameter,
        // the expected total was measured at the default. Nothing in the machinery moves the
        // expectation with the window — which is why the guide names this a fixed-baseline
        // drift check and requires the baseline in the check's name.
        val parameters = mapOf("year" to Parameter(LogicalType.INTEGER, default = IntNode(2024)))
        val pipeline =
            pipeline(
                parameters,
                check(
                    id = "baseline_total",
                    name = "Window total (2024 baseline)",
                    datasource = "main",
                    expected = CheckExpectation(kind = CheckExpectation.KIND_VALUE, value = 100.0),
                    sql = "select sum(amount) from facts where year = :year",
                ),
            )
        datasources.register("main")
        probeBehavior = { call ->
            probeResult(if (call.parameters.getValue("year").value == "2024") BigDecimal("100") else BigDecimal("137"))
        }

        // The release-gate shape: no supplied parameters, so the DEFAULT window is what runs.
        val releaseRun = runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.RELEASE, ACTOR)
        releaseRun.single().verdict shouldBe CheckRunVerdict.PASS
        probeCalls.single().parameters.getValue("year") shouldBe SqlProbeParameter(LogicalType.INTEGER, "2024")

        probeCalls.clear()
        val otherWindow =
            runner.run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, supplied("""{"year": 2025}"""), CheckRunVia.MCP, ACTOR)
        otherWindow.single().verdict shouldBe CheckRunVerdict.FAIL
        otherWindow.single().observed shouldBe "137"
    }

    @Test
    fun `a rows-zero expectation passes under both tested parameter sets - binding and comparison, not SQL truth - 144 B`() {
        // The parameterized-invariant shape the guide teaches, tested at the level this suite
        // can honestly reach: the binding (each run binds the supplied/default window) and the
        // comparison (a rows: 0 expectation against an empty result). The probe is stubbed to
        // return zero rows for both calls, so this proves machinery behavior over the TWO
        // tested parameter sets — NOT that the example SQL is an invariant of any real data.
        val parameters = mapOf("year" to Parameter(LogicalType.INTEGER, default = IntNode(2024)))
        val pipeline =
            pipeline(
                parameters,
                check(
                    id = "no_violations",
                    name = "No rule violations in the window",
                    datasource = "main",
                    expected = rowsExpectation(0),
                    sql = "select id from facts where year = :year and violates_rule",
                ),
            )
        datasources.register("main")
        probeBehavior = { SqlProbeResult(QueryRows(SCHEMA, emptyList(), truncated = false), wallMs = 1, plan = null) }

        runner
            .run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, emptyMap(), CheckRunVia.RELEASE, ACTOR)
            .single()
            .verdict shouldBe CheckRunVerdict.PASS
        runner
            .run(WORKSPACE, PIPELINE_ID, VERSION, pipeline, supplied("""{"year": 1999}"""), CheckRunVia.MCP, ACTOR)
            .single()
            .verdict shouldBe CheckRunVerdict.PASS
        // Same expectation, two bound windows — the machinery moves the window, not the expectation.
        probeCalls.map { it.parameters.getValue("year").value } shouldBe listOf("2024", "1999")
    }

    // ------------------------------------------------------------------------------- fixtures

    private data class ProbeCall(
        val datasource: Datasource,
        val sql: String,
        val parameters: Map<String, SqlProbeParameter>,
        val limit: Int,
    )

    private fun pipeline(
        parameters: Map<String, Parameter>,
        vararg checks: PipelineCheck,
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = "test/checks",
        displayName = "Checks",
        description = "",
        settings = PipelineSettings(),
        parameters = parameters,
        nodes = emptyList(),
        checks = checks.toList(),
    )

    private fun check(
        id: String = "c1",
        name: String = "Check $id",
        datasource: String,
        expected: CheckExpectation,
        sql: String = "select share from rollup",
    ) = PipelineCheck(id = id, name = name, datasource = datasource, sql = sql, expected = expected)

    private fun valueExpectation() = CheckExpectation(kind = CheckExpectation.KIND_VALUE, value = 74.62)

    private fun rowsExpectation(rows: Long) = CheckExpectation(kind = CheckExpectation.KIND_ROWS, rows = rows)

    private fun supplied(json: String): Map<String, JsonNode> = MAPPER.readTree(json).properties().associate { it.key to it.value }

    private fun probeResult(cell: Any?) =
        SqlProbeResult(QueryRows(SCHEMA, listOf(cellRow(cell)), truncated = false), wallMs = 1, plan = null)

    private fun cellRow(value: Any?): Map<String, Any?> = mapOf("n" to value)

    private fun record() =
        PipelineRecord(
            id = PIPELINE_ID,
            name = "test/checks",
            displayName = "Checks",
            description = "",
            ownerId = ACTOR,
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun storedRow(run: NewPipelineCheckRun) =
        PipelineCheckRun(
            id = UUID.randomUUID(),
            pipelineId = run.pipelineId,
            version = run.version,
            checkId = run.checkId,
            ranAt = Instant.EPOCH,
            ranBy = run.ranBy,
            via = run.via,
            parametersJson = run.parametersJson,
            observedJson = run.observedJson,
            verdict = run.verdict,
            message = run.message,
            correlationId = run.correlationId,
            durationMs = run.durationMs,
        )

    /** A map-backed registry: named datasources resolve, everything else is invisible. */
    private class FakeDatasourceRegistry : DatasourceRegistry {
        private val registered = mutableMapOf<String, Datasource>()
        private val failing = mutableSetOf<String>()

        var resolutions = 0
            private set

        fun register(name: String) {
            registered[name] = Datasource(name = name, displayName = name, dialect = Dialect.POSTGRES, jdbcUrl = "jdbc:postgresql://x/db")
        }

        fun failOnResolve(name: String) {
            failing += name
        }

        override fun getVisible(
            name: String,
            workspaceId: UUID,
        ): Datasource? {
            resolutions++
            if (name in failing) error("metadata read failed")
            return registered[name]
        }

        override fun list(dialect: Dialect?): List<Datasource> = registered.values.toList()

        override fun get(name: String): Datasource? = registered[name]

        override fun getLive(name: String): Datasource? = registered[name]

        override fun isReadonlyLive(name: String): Boolean? = registered[name]?.let { false }

        override fun exists(name: String): Boolean = name in registered

        override fun save(
            datasource: Datasource,
            actor: UUID,
        ): Datasource = throw UnsupportedOperationException()

        override fun validate(datasource: Datasource): ValidationResult = throw UnsupportedOperationException()

        override fun delete(name: String): DeleteResult = throw UnsupportedOperationException()

        override fun poolFor(datasource: Datasource): ConnectionPool = throw UnsupportedOperationException()

        override fun testConnection(name: String): TestResult? = throw UnsupportedOperationException()
    }

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        val SCHEMA = ResultSchema(listOf(ColumnSchema("n", LogicalType.BIGDECIMAL)), emptyList())
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val PIPELINE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        const val VERSION = 7
    }
}
