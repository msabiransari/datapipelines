package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * §17.2 — "Validation is exhaustive: all checks run, all failures collected, returned
 * together (not fail-fast). This gives authors the full picture on a broken pipeline."
 *
 * ## Why this file exists separately
 *
 * Every other validation spec here builds a pipeline with **one** defect and asserts the code
 * it produces. A fail-fast validator — one that returned after the first rule group found
 * something — passes all of them, because there is never a second failure to lose. The
 * normative property of §17.2 was therefore the one property the suite could not see.
 *
 * So this file builds one pipeline that is broken in every rule group at once and asserts the
 * whole set comes back. It is the regression test for a `return` appearing anywhere in the
 * chain, which is the single most likely way this promise stops being true.
 */
class ExhaustiveValidationTest {
    private val workspaceId = UUID.randomUUID()

    /**
     * One pipeline, defects spanning all six rule groups plus the deserializer's pre-scan:
     *
     *  - §12.1 structural — `name` is not an identifier; two nodes share an id; two tempdb
     *    outputs share a table; a node id is the reserved `tempdb` literal; a `source` carries
     *    a JDBC URL.
     *  - §12.2 DAG — a dependency on a node that does not exist, and a cycle.
     *  - §12.3 caller — two nodes resolve to `caller`.
     *  - §12.4 node type — a DML node carries an output block.
     *  - §12.5 datasource — an unregistered datasource name.
     *  - §12.6 template — a template that is not in the registry.
     *  - §12.7 parameter — a bad key, a missing precision, required-plus-default.
     *  - §12.8 settings — an unknown tempdb config key.
     */
    private val broken =
        Pipeline(
            schemaVersion = 2,
            name = "Not An Identifier",
            displayName = "Broken",
            description = "Broken in every rule group at once.",
            settings =
                PipelineSettings(
                    TempdbSettings(
                        StagingEngine.H2,
                        Fixtures.json("""{"page_size": 8}""").properties().associate { it.key to it.value },
                    ),
                ),
            parameters =
                mapOf(
                    "Bad Key" to Parameter(LogicalType.STRING),
                    "no_precision" to Parameter(LogicalType.DECIMAL),
                    "both" to Parameter(LogicalType.STRING, required = true, default = Fixtures.json("\"x\"")),
                ),
            nodes =
                listOf(
                    // Duplicate id + duplicate tempdb table + a cycle with `dupe` below.
                    Fixtures.node(id = "dupe", output = NodeOutput.Tempdb("t"), dependsOn = listOf("ghost")),
                    Fixtures.node(id = "dupe", output = NodeOutput.Tempdb("t"), source = "pg-unregistered"),
                    // Reserved id, env-specific source, unknown template.
                    Fixtures.node(
                        id = "tempdb",
                        source = "jdbc:postgresql://db.internal:5432/orders",
                        template = TemplateRef("missing.sql", 1),
                        output = NodeOutput.Tempdb("u"),
                    ),
                    // Two caller nodes.
                    Fixtures.node(id = "caller_a", output = NodeOutput.Caller),
                    Fixtures.node(id = "caller_b", output = NodeOutput.Caller),
                    // A DML node with an output block, and a genuine cycle.
                    Fixtures.node(id = "cyc_a", output = NodeOutput.Tempdb("ca"), dependsOn = listOf("cyc_b")),
                    Fixtures.node(id = "cyc_b", output = NodeOutput.Tempdb("cb"), dependsOn = listOf("cyc_a")),
                    Fixtures.node(id = "side", type = NodeType.DML).copy(output = NodeOutput.Caller),
                ),
        )

    private val validator =
        Fixtures.validator(
            templates =
                StubTemplates(
                    lookups = mapOf("missing.sql" to TemplateLookup.TemplateNotFound),
                    defaultLookup = TemplateLookup.Found(Dialect.POSTGRES),
                ),
        )

    @Test
    fun `every rule group reports, in one pass`() {
        val codes = validator.validate(broken, workspaceId).codes

        withClue("codes = $codes") {
            codes shouldContainAll
                listOf(
                    // §12.1
                    Validation.SCHEMA_VERSION_UNSUPPORTED,
                    Validation.NAME_INVALID,
                    Validation.DUPLICATE_NODE_ID,
                    Validation.DUPLICATE_OUTPUT_TABLE,
                    Validation.RESERVED_IDENTIFIER,
                    Validation.FORBIDDEN_ENV_SPECIFIC_VALUE,
                    // §12.2
                    Validation.DANGLING_DEPENDENCY,
                    Validation.CYCLE_DETECTED,
                    // §12.3
                    Validation.MULTIPLE_CALLER_NODES,
                    // §12.4
                    Validation.DML_HAS_OUTPUT,
                    // §12.5
                    Validation.UNKNOWN_DATASOURCE,
                    // §12.6
                    Validation.TEMPLATE_NOT_FOUND,
                    // §12.7
                    Validation.PARAMETER_NAME_INVALID,
                    Validation.PARAMETER_PRECISION_MISSING,
                    Validation.CONFLICTING_REQUIRED_DEFAULT,
                    // §12.8
                    Validation.TEMPDB_CONFIG_INVALID,
                )
        }
    }

    @Test
    fun `the failure count far exceeds the group count - nothing is collapsed`() {
        // A validator that returned one failure per group would satisfy the assertion above.
        // Sixteen distinct codes across six groups is what "all failures collected" means.
        val result = validator.validate(broken, workspaceId)

        result.codes.size shouldBeGreaterThanOrEqual 16
        result.failures.size shouldBeGreaterThanOrEqual result.codes.size
        result.isValid shouldBe false
    }

    @Test
    fun `a rule group that finds nothing does not stop the ones after it`() {
        // The ordering hazard in reverse: a clean §12.1 must not short-circuit §12.7.
        val onlyParameterDefect =
            Fixtures.pipeline(parameters = mapOf("Bad Key" to Parameter(LogicalType.STRING)))

        Fixtures.validator().validate(onlyParameterDefect, workspaceId).codes shouldBe
            listOf(Validation.PARAMETER_NAME_INVALID)
    }

    @Test
    fun `the deserializer's pre-scan is exhaustive across groups too`() {
        // The pre-scan is the other half of §17.2 (§17.2 step 1): five codes live there, and a
        // fail-fast scan would hand the author one of them per round trip.
        val outcome =
            PipelineDeserializer().read(
                """
                {
                  "schema_version": 1,
                  "name": "p", "display_name": "P", "description": "d",
                  "settings": {"tempdb": {"engine": "DUCKDB"}},
                  "parameters": {"p": {"type": "MONEY"}},
                  "nodes": [
                    {"id": "a", "description": "d", "type": "SELECT", "source": "pg-prod",
                     "template": {"id": "t.sql", "version": 1}, "depends_on": []},
                    {"id": "b", "description": "d", "type": "DQL", "source": "pg-prod",
                     "template": {"id": "t.sql", "version": 1}, "depends_on": [],
                     "output": {"target": "kafka"}}
                  ]
                }
                """.trimIndent(),
            )

        (outcome as DeserializationOutcome.Rejected).result.codes shouldContainAll
            listOf(
                Validation.TEMPDB_ENGINE_UNSUPPORTED,
                Validation.PARAMETER_TYPE_INVALID,
                Validation.TYPE_INVALID,
                Validation.OUTPUT_TARGET_INVALID,
            )
    }
}
