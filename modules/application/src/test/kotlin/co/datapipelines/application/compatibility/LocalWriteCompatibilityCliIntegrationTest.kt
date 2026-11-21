package co.datapipelines.application.compatibility

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineSerializer
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.scheduler.MissedRunPolicy
import co.datapipelines.scheduler.NewSchedule
import co.datapipelines.scheduler.ScheduleRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * #1036 §9 end to end on the real, migrated metadata store: the operator entry point
 * ([LocalWriteCompatibilityCli.run]) reads the two synthetic fixtures out of PostgreSQL — a
 * stored CURRENT version 3 whose external DDL precedes a same-registration DQL write-back, and a
 * NON-current version 5 that only a live schedule pins, writing to two eligible registrations —
 * and reports each at its exact version with the planner's reason.
 *
 * Zero business leases and statements: the registrations point at unresolvable `.invalid` hosts,
 * so any attempt to open a customer connection would fail the run (exit 1); the run exits 0 and
 * the report's own registry counted no refused call. The session it reads through is read-only
 * on the SERVER (`25006` on a write), and nothing in the output names a URL, host, user or
 * password.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalWriteCompatibilityCliIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var pipelines: PipelineRepository
    private lateinit var schedules: ScheduleRepository
    private lateinit var owner: UUID
    private lateinit var workspaceId: UUID
    private lateinit var workspaceName: String
    private val serializer = PipelineSerializer()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        pipelines = PipelineRepository(jdbc)
        schedules = ScheduleRepository(jdbc, PipelineJson.objectMapper())
    }

    @BeforeEach
    fun seed() {
        // The shared database's discipline: clean what this suite reads (the report reads EVERY
        // workspace), re-seed the V4 default workspace sibling suites expect.
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, datasources, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) " +
                "VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default') ON CONFLICT (id) DO NOTHING",
        )
        owner = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to owner, "email" to "u$owner@example.com", "sub" to "sub-$owner"),
        )
        workspaceId = UUID.randomUUID()
        workspaceName = "w_${workspaceId.toString().replace("-", "")}"
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, :name, 'W', :owner)",
            mapOf("id" to workspaceId, "name" to workspaceName, "owner" to owner),
        )
        listOf("wh_a", "wh_b").forEach(::registration)
    }

    @Test
    fun `the operator entry point reports both fixtures at their exact versions, leasing nothing`() {
        // Fixture (a): a CURRENT stored version 3 — external DDL, then a DQL write-back to the same registration.
        releaseVersions("acme/ddl_then_writeback", List(2) { harmless() } + listOf(ddlThenWriteBack()))
        // Fixture (b): version 5 writes to two registrations; version 6 is current, so only the schedule reaches 5.
        releaseVersions("acme/two_destinations", List(4) { harmless() } + listOf(twoDestinations(), harmless()))
        schedule("nightly", "acme/two_destinations", 5)

        val (exit, out, err) = runCli()

        exit shouldBe 0
        err shouldBe ""
        val lines = out.lines().filter { it.isNotBlank() }
        val prefix = "workspace=$workspaceName pipeline="
        lines.filter { it.startsWith(prefix) }.map { it.substringBefore(" details=") } shouldContainExactly
            listOf(
                "${prefix}acme/ddl_then_writeback version=3 source=current verdict=refused reason=external_ddl",
                "${prefix}acme/two_destinations version=5 source=schedule:nightly verdict=refused reason=second_durable_destination",
                "${prefix}acme/two_destinations version=6 source=current verdict=unchanged",
            )
        lines.single { "version=3" in it } shouldContain "node_id=make_table"
        lines.single { "version=5" in it } shouldContain "registrations=[wh_a, wh_b]"
        lines.last() shouldBe "SUMMARY versions=3 unchanged=1 enrolled=0 refused=2 unreadable=0"
        FORBIDDEN.forEach { secret -> out shouldNotContain secret }
    }

    @Test
    fun `a version current AND pinned is one line naming both sources, and an unreadable pin is listed, not skipped`() {
        releaseVersions("acme/one_writer", listOf(oneWriter()))
        schedule("hourly", "acme/one_writer", 1)
        schedule("stale", "acme/one_writer", 99)

        val lines = runCli().second.lines().filter { it.startsWith("workspace=") }

        lines.map { it.substringBefore(" details=") } shouldContainExactly
            listOf(
                "workspace=$workspaceName pipeline=acme/one_writer version=1 source=current,schedule:hourly verdict=enrolled",
                "workspace=$workspaceName pipeline=acme/one_writer version=99 source=schedule:stale verdict=unreadable " +
                    "reason=stored body not found or not readable",
            )
    }

    @Test
    fun `the report's session is read-only on the server`() {
        val pg = SharedPostgres.postgres
        LocalWriteCompatibilityCli.readOnlyDataSource(pg.jdbcUrl, pg.username, pg.password).use { dataSource ->
            dataSource.connection.use { connection ->
                val refused =
                    shouldThrow<SQLException> {
                        connection.createStatement().use { it.executeUpdate("DELETE FROM workspaces WHERE name = 'default'") }
                    }
                refused.sqlState shouldBe "25006"
            }
        }
    }

    @Test
    fun `an unreachable store exits 1 and the diagnostic names neither the URL nor the user`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env =
            mapOf(
                "SPRING_DATASOURCE_URL" to "jdbc:postgresql://127.0.0.1:1/meta?connectTimeout=1",
                "SPRING_DATASOURCE_USERNAME" to "report_user",
                "SPRING_DATASOURCE_PASSWORD" to "report-password",
            )

        LocalWriteCompatibilityCli.run(env, PrintStream(out, true), PrintStream(err, true)) shouldBe 1

        out.toString() shouldBe ""
        val diagnostic = err.toString()
        diagnostic shouldContain "the report could not be produced"
        listOf("jdbc:", "127.0.0.1", "report_user", "report-password").forEach { diagnostic shouldNotContain it }
    }

    // -- seeding -------------------------------------------------------------------

    private fun runCli(): Triple<Int, String, String> {
        val pg = SharedPostgres.postgres
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env =
            mapOf(
                "SPRING_DATASOURCE_URL" to pg.jdbcUrl,
                "SPRING_DATASOURCE_USERNAME" to pg.username,
                "SPRING_DATASOURCE_PASSWORD" to pg.password,
            )
        val exit = LocalWriteCompatibilityCli.run(env, PrintStream(out, true), PrintStream(err, true))
        return Triple(exit, out.toString(), err.toString())
    }

    /** A POSTGRES registration granted to the workspace, on a host that cannot resolve. */
    private fun registration(name: String) {
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES (:name, :name, 'POSTGRES', :url, 'none', :by)",
            mapOf("name" to name, "url" to "jdbc:postgresql://$name.invalid:5432/warehouse", "by" to owner),
        )
        jdbc.update(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (:name, :ws, :by)",
            mapOf("name" to name, "ws" to workspaceId, "by" to owner),
        )
    }

    /** Releases [bodies] in order as versions 1..n of [name]; the last is current. */
    private fun releaseVersions(
        name: String,
        bodies: List<List<Node>>,
    ) {
        val first = pipeline(name, bodies.first())
        var record: PipelineRecord =
            pipelines.create(
                workspaceId,
                NewPipeline.from(first, ownerId = owner),
                serializer.write(first),
                owner,
                CreateLifecycle.RELEASED,
                WriteSurface.SESSION,
            )
        bodies.drop(1).forEachIndexed { index, nodes ->
            val current = checkNotNull(pipelines.findCurrentVersionDetail(workspaceId, record.id))
            // Each body differs (its description carries the version) — an identical write is a no-op.
            val body = pipeline(name, nodes, description = "v${index + 2}")
            val draft =
                checkNotNull(
                    pipelines.createDraft(workspaceId, record.id, serializer.write(body), current.bodyHash, owner, WriteSurface.SESSION),
                )
            record =
                checkNotNull(
                    pipelines.releaseDraft(workspaceId, record.id, name, body.displayName, body.description, draft.bodyHash, owner),
                ).record
        }
    }

    private fun schedule(
        name: String,
        pipeline: String,
        version: Int,
    ) {
        val json = PipelineJson.objectMapper()
        schedules.insert(
            NewSchedule(
                id = UUID.randomUUID(),
                workspaceId = workspaceId,
                name = name,
                executorId = "pipeline",
                payloadSchemaVersion = 2,
                payload = json.createObjectNode().put("pipeline", pipeline).put("version", version),
                parameters = json.createObjectNode(),
                targetRef = "pipeline:$pipeline",
                cron = "0 * * * *",
                timezone = "UTC",
                missedRunPolicy = MissedRunPolicy.SKIP,
                nextDueAt = Instant.parse("2026-11-22T00:00:00Z"),
                createdBy = owner,
                idempotencyKey = null,
                idempotencyHash = null,
                notificationRecipients = emptyList(),
            ),
        )
    }

    private fun pipeline(
        name: String,
        nodes: List<Node>,
        description: String = "v1",
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = name,
        displayName = "Fixture",
        description = description,
        settings = PipelineSettings(),
        parameters = emptyMap(),
        nodes = nodes,
    )

    private fun harmless() = listOf(node("read", NodeType.DQL, "wh_a", NodeOutput.Caller))

    private fun oneWriter() = listOf(node("touch", NodeType.DML, "wh_a", NodeOutput.Discard, operation = "update"))

    private fun ddlThenWriteBack() =
        listOf(
            node("make_table", NodeType.DDL, "wh_a", null),
            node("load", NodeType.DQL, "tempdb", NodeOutput.Datasource("wh_a", "t", WriteMode.APPEND), dependsOn = listOf("make_table")),
        )

    private fun twoDestinations() =
        listOf(
            node("touch", NodeType.DML, "wh_a", NodeOutput.Discard, operation = "update"),
            node("copy", NodeType.DQL, "tempdb", NodeOutput.Datasource("wh_b", "t", WriteMode.APPEND), dependsOn = listOf("touch")),
        )

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
        template = TemplateRef("test/$id.sql", 1),
        output = output,
        operation = operation,
        dependsOn = dependsOn,
    )

    private companion object {
        val FORBIDDEN = listOf("jdbc:", ".invalid", "warehouse", "password", "SELECT", "INSERT", "CREATE TABLE")
    }
}
