package co.datapipelines.application.compatibility

import co.datapipelines.datasources.Datasource
import co.datapipelines.executor.LocalWritePlanner
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #1036 §9 on the offline compatibility report: the two synthetic fixtures the record names — a
 * stored version whose external DDL precedes a DQL write-back to the same registration, and a
 * scheduled version writing to two distinct eligible registrations — are reported REFUSED with
 * their exact version and the planner's reason, while the report leases no connection and runs no
 * statement (every non-metadata registry call is counted and refused). The output carries node
 * ids and registration names, never SQL, a URL or a credential.
 */
class LocalWriteCompatibilityReportTest {
    private val workspaceId = UUID.randomUUID()
    private val registrations =
        RegistrationView { name, ws ->
            REGISTRATIONS[name]?.takeIf { ws == workspaceId }?.let {
                Datasource(
                    name = name,
                    displayName = name,
                    dialect = it,
                    jdbcUrl = "",
                )
            }
        }
    private val report = LocalWriteCompatibilityReport(LocalWritePlanner(PipelineResolver { _, _, _ -> null }, registrations, DEPTH))

    @Test
    fun `a stored version with external DDL before a same-registration write-back is refused external_ddl at its exact version`() {
        val entry = report.inspect(listOf(candidate("acme/ddl_then_writeback", 3, "current", ddlThenWriteBack()))).single()

        entry.version shouldBe 3
        entry.sources shouldContainExactly listOf("current")
        entry.outcome shouldBe CompatibilityOutcome.REFUSED
        entry.reason shouldBe "external_ddl"
        nodeIds(entry.details["external_ddl"]) shouldContainExactly listOf("make_table")
        registrations.refusedCalls.get() shouldBe 0
    }

    @Test
    fun `a scheduled version writing to two eligible registrations is refused second_durable_destination at its exact version`() {
        val entry = report.inspect(listOf(candidate("acme/two_destinations", 5, "schedule:nightly", twoDestinations()))).single()

        entry.version shouldBe 5
        entry.sources shouldContainExactly listOf("schedule:nightly")
        entry.outcome shouldBe CompatibilityOutcome.REFUSED
        entry.reason shouldBe "second_durable_destination"
        entry.details["registrations"] shouldBe listOf("wh_a", "wh_b")
        registrations.refusedCalls.get() shouldBe 0
    }

    @Test
    fun `enrolled, unchanged, unqualified and unreadable versions are each named, and the summary counts them`() {
        val entries =
            report.inspect(
                listOf(
                    candidate("acme/one_writer", 1, "current", pipeline(dml("touch", "wh_a"))),
                    candidate("acme/reads_only", 2, "current", pipeline(dql("read", "wh_a"))),
                    candidate("acme/oracle_writer", 4, "current", pipeline(writeBack("load", "ora_main"))),
                    CompatibilityCandidate(
                        workspaceId,
                        "acme",
                        "acme/gone",
                        null,
                        listOf("schedule:old"),
                        null,
                        "schedule payload names no exact pipeline version",
                    ),
                ),
            )

        entries.map { it.outcome to it.reason } shouldContainExactly
            listOf(
                CompatibilityOutcome.ENROLLED to null,
                CompatibilityOutcome.UNCHANGED to null,
                CompatibilityOutcome.REFUSED to "unqualified_transactional_target",
                CompatibilityOutcome.UNREADABLE to "schedule payload names no exact pipeline version",
            )
        val lines = LocalWriteCompatibilityReport.render(entries)
        lines.size shouldBe entries.size + 1
        lines.last() shouldBe
            "SUMMARY versions=4 unchanged=1 enrolled=1 refused=1 unreadable=1"
        registrations.refusedCalls.get() shouldBe 0
    }

    @Test
    fun `the rendered report names workspace, pipeline, version, source, verdict and nodes without SQL or credentials`() {
        val lines =
            LocalWriteCompatibilityReport.render(
                report.inspect(
                    listOf(
                        candidate("acme/ddl_then_writeback", 3, "current", ddlThenWriteBack()),
                        candidate("acme/two_destinations", 5, "schedule:nightly", twoDestinations()),
                    ),
                ),
            )

        lines[0] shouldContain
            "workspace=acme pipeline=acme/ddl_then_writeback version=3 source=current verdict=refused reason=external_ddl"
        lines[0] shouldContain "node_id=make_table"
        lines[1] shouldContain
            "pipeline=acme/two_destinations version=5 source=schedule:nightly verdict=refused reason=second_durable_destination"
        lines[1] shouldContain "registrations=[wh_a, wh_b]"
        lines.forEach { line -> FORBIDDEN.forEach { line shouldNotContain it } }
    }

    @Test
    fun `the registration view refuses every connection and counts the attempt`() {
        val datasource = Datasource(name = "wh_a", displayName = "wh_a", dialect = Dialect.POSTGRES, jdbcUrl = "")

        shouldThrow<IllegalStateException> { registrations.poolFor(datasource) }
        shouldThrow<IllegalStateException> { registrations.testConnection("wh_a") }
        shouldThrow<IllegalStateException> { registrations.get("wh_a") }

        registrations.refusedCalls.get() shouldBe 3
    }

    // -- the §9 fixtures --------------------------------------------------------------

    /** External DDL on `wh_a`, then a DQL whose write-back targets the same registration. */
    private fun ddlThenWriteBack(): Pipeline =
        pipeline(
            ddl("make_table", "wh_a"),
            writeBack("load", "wh_a", dependsOn = listOf("make_table")),
        )

    /** A DML on `wh_a` and a write-back to `wh_b`: two eligible registrations. */
    private fun twoDestinations(): Pipeline = pipeline(dml("touch", "wh_a"), writeBack("copy", "wh_b", dependsOn = listOf("touch")))

    private fun candidate(
        name: String,
        version: Int,
        source: String,
        pipeline: Pipeline,
    ) = CompatibilityCandidate(workspaceId, "acme", name, version, listOf(source), pipeline)

    private fun pipeline(vararg nodes: Node): Pipeline =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "acme/fixture",
            displayName = "Fixture",
            description = "synthetic",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = nodes.toList(),
        )

    private fun dml(
        id: String,
        source: String,
    ) = node(id, NodeType.DML, source, NodeOutput.Discard, operation = "update")

    private fun writeBack(
        id: String,
        target: String,
        dependsOn: List<String> = emptyList(),
    ) = node(id, NodeType.DQL, "tempdb", NodeOutput.Datasource(target, "t", WriteMode.APPEND), dependsOn = dependsOn)

    private fun dql(
        id: String,
        source: String,
    ) = node(id, NodeType.DQL, source, NodeOutput.Caller)

    private fun ddl(
        id: String,
        source: String,
    ) = node(id, NodeType.DDL, source, null)

    private fun node(
        id: String,
        type: NodeType,
        source: String,
        output: NodeOutput?,
        operation: String? = null,
        dependsOn: List<String> = emptyList(),
    ) = Node(
        id = id,
        description = "node $id",
        type = type,
        source = source,
        template = TemplateRef(id, 1),
        output = output,
        operation = operation,
        dependsOn = dependsOn,
    )

    @Suppress("UNCHECKED_CAST")
    private fun nodeIds(described: Any?): List<String?> = (described as List<Map<String, Any?>>).map { it["node_id"] as String? }

    private companion object {
        const val DEPTH = 5
        val REGISTRATIONS = mapOf("wh_a" to Dialect.POSTGRES, "wh_b" to Dialect.MYSQL, "ora_main" to Dialect.ORACLE)
        val FORBIDDEN = listOf("SELECT", "INSERT", "CREATE TABLE", "jdbc:", "password", "secret")
    }
}
