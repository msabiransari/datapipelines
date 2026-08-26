package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** pipeline-contract §12.1 — structural validations, happy path and every failing case. */
class StructuralRulesTest {
    private val validator = Fixtures.validator()
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `a well-formed pipeline produces no structural failures`() {
        validator.validate(Fixtures.pipeline(), workspaceId).failures.shouldBeEmpty()
    }

    @Test
    fun `every §12-1 code has a happy path that does not raise it`() {
        // The negative half of each rule. Without it a rule that fired unconditionally would
        // still pass its own positive test, and only surface as "everything is invalid" the
        // first time someone saved a correct pipeline.
        val healthy =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "fetch_orders", output = NodeOutput.Tempdb("stg_orders")),
                        Fixtures.node(
                            id = "cache_it",
                            output = NodeOutput.Datasource("pg-warehouse", "monthly_revenue_cache", WriteMode.REPLACE),
                            dependsOn = listOf("fetch_orders"),
                        ),
                        Fixtures.node(id = "final_report", dependsOn = listOf("fetch_orders")),
                    ),
                settings =
                    PipelineSettings(
                        TempdbSettings(
                            StagingEngine.H2,
                            Fixtures.json("""{"max_memory_mb": 512}""").properties().associate { it.key to it.value },
                        ),
                    ),
            )

        val codes = validator.validate(healthy, workspaceId).codes

        listOf(
            Validation.SCHEMA_VERSION_UNSUPPORTED,
            Validation.NAME_INVALID,
            Validation.DUPLICATE_NODE_ID,
            Validation.DUPLICATE_OUTPUT_TABLE,
            Validation.INVALID_IDENTIFIER,
            Validation.RESERVED_IDENTIFIER,
            Validation.FORBIDDEN_ENV_SPECIFIC_VALUE,
            Validation.PIPELINE_TOO_LARGE,
            Validation.EMPTY_PIPELINE,
            Validation.DANGLING_DEPENDENCY,
            Validation.CYCLE_DETECTED,
            Validation.MULTIPLE_CALLER_NODES,
            Validation.DML_HAS_OUTPUT,
            Validation.DDL_HAS_OUTPUT,
            Validation.OUTPUT_TABLE_MISSING,
            Validation.OUTPUT_DATASOURCE_MISSING,
            Validation.UNKNOWN_DATASOURCE,
            Validation.TEMPLATE_NOT_FOUND,
            Validation.TEMPLATE_VERSION_NOT_FOUND,
            Validation.TEMPLATE_DIALECT_MISMATCH,
            Validation.TEMPLATE_PARAMETER_UNDECLARED,
            Validation.TEMPLATE_RENDER_FAILED,
            Validation.TEMPDB_CONFIG_INVALID,
        ).forEach { code -> withClue(code) { codes shouldNotContain code } }
        codes.shouldBeEmpty()
    }

    @Test
    fun `an unsupported schema_version is rejected`() {
        val codes = validator.validate(Fixtures.pipeline(schemaVersion = 2), workspaceId).codes

        codes shouldContain Validation.SCHEMA_VERSION_UNSUPPORTED
    }

    @Test
    fun `pipeline names must match the frozen identifier rule`() {
        listOf("Monthly_Revenue", "monthly revenue", "monthly-revenue", "", "a".repeat(64)).forEach { name ->
            validator.validate(Fixtures.pipeline(name = name), workspaceId).codes shouldContain Validation.NAME_INVALID
        }
        listOf("a", "monthly_revenue_2026", "a".repeat(63)).forEach { name ->
            validator.validate(Fixtures.pipeline(name = name), workspaceId).codes shouldNotContain Validation.NAME_INVALID
        }
    }

    @Test
    fun `duplicate node ids are rejected`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "a"), Fixtures.node(id = "a", output = null)))

        val failures = validator.validate(pipeline, workspaceId).withCode(Validation.DUPLICATE_NODE_ID)

        failures.single().details["value"] shouldBe "a"
    }

    @Test
    fun `node ids and output tables must match the identifier rule`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "Fetch Orders", output = NodeOutput.Tempdb("STG_ORDERS")),
                    ),
            )

        validator.validate(pipeline, workspaceId).codes shouldContain Validation.INVALID_IDENTIFIER
        validator.validate(pipeline, workspaceId).withCode(Validation.INVALID_IDENTIFIER).size shouldBe 2
    }

    @Test
    fun `the tempdb literal and the reserved namespace are not usable as identifiers`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "tempdb", output = NodeOutput.Tempdb("__internal__")),
                    ),
            )

        validator.validate(pipeline, workspaceId).withCode(Validation.RESERVED_IDENTIFIER).size shouldBe 2
    }

    @Test
    fun `two tempdb outputs may not share a table name`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "a", output = NodeOutput.Tempdb("stg_orders")),
                        Fixtures.node(id = "b", output = NodeOutput.Tempdb("stg_orders")),
                    ),
            )

        validator.validate(pipeline, workspaceId).codes shouldContain Validation.DUPLICATE_OUTPUT_TABLE
    }

    @Test
    fun `a tempdb table and a write-back table may share a name - uniqueness is per namespace`() {
        // §10.1 after SPEC-REVIEW 2.1.9: global uniqueness over-constrained; the namespaces are
        // tempdb, and each target datasource separately.
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "a", output = NodeOutput.Tempdb("orders")),
                        Fixtures.node(
                            id = "b",
                            output = NodeOutput.Datasource("pg-warehouse", "orders", WriteMode.REPLACE),
                        ),
                        Fixtures.node(
                            id = "c",
                            output = NodeOutput.Datasource("pg-meta", "orders", WriteMode.APPEND),
                        ),
                    ),
            )

        validator.validate(pipeline, workspaceId).codes shouldNotContain Validation.DUPLICATE_OUTPUT_TABLE
    }

    @Test
    fun `two write-backs to the SAME datasource may not share a table name`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(
                            id = "a",
                            output = NodeOutput.Datasource("pg-warehouse", "orders", WriteMode.REPLACE),
                        ),
                        Fixtures.node(
                            id = "b",
                            output = NodeOutput.Datasource("pg-warehouse", "orders", WriteMode.APPEND),
                        ),
                    ),
            )

        validator
            .validate(pipeline, workspaceId)
            .withCode(Validation.DUPLICATE_OUTPUT_TABLE)
            .single()
            .details["namespace"] shouldBe
            "datasource:pg-warehouse"
    }

    @Test
    fun `a blank output table draws output_table_missing only, not invalid_identifier as well`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(output = NodeOutput.Tempdb(""))))

        validator.validate(pipeline, workspaceId).codes shouldContainExactly listOf(Validation.OUTPUT_TABLE_MISSING)
    }
}
