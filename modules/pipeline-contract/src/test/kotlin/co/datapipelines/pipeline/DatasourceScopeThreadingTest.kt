package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 134 — the datasource port receives the workspace of the SAVE on every lookup, from the
 * validator's own `workspaceId` argument and nothing ambient.
 *
 * The defect this pins: the `web` adapter resolved a node's datasource through the Spring
 * Security thread-local principal, which an MCP tool call — run on the SDK's scheduler thread —
 * does not carry, so a customer's own datasource was `unknown_datasource` over MCP and `201`
 * over REST for the same body. The port now takes the workspace as a parameter; this test is
 * the unit-level twin of `McpSaveWorkspaceDatasourceE2eTest`: a recording stub asserts that
 * every lookup the validator makes — the `source` check, the `output.datasource` check and the
 * §11.4 portability scan's "is this a registry name" question — carries exactly the workspace
 * `validate` was given. A rule that looked a name up without the workspace (or with `null`)
 * would surface here as a stray tuple.
 */
class DatasourceScopeThreadingTest {
    private val workspaceId = UUID.randomUUID()

    /** Answers every name as Postgres and records who asked for what, from where. */
    private class RecordingDatasources : DatasourceRegistry {
        val lookups = mutableListOf<Pair<String, UUID?>>()

        override fun describe(
            name: String,
            workspaceId: UUID?,
        ): DatasourceFacts? {
            lookups += name to workspaceId
            return DatasourceFacts(Dialect.POSTGRES)
        }
    }

    @Test
    fun `every datasource lookup during a save carries the save's workspace`() {
        val registry = RecordingDatasources()
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "read", source = "pg-prod"),
                        Fixtures.node(
                            id = "write",
                            source = "pg-warehouse",
                            output = NodeOutput.Datasource("pg-meta", "cache", WriteMode.APPEND),
                            dependsOn = listOf("read"),
                        ),
                    ),
            )

        val result = Fixtures.validator(datasources = registry).validate(pipeline, workspaceId)

        withClue("the fixture must be valid so every rule ran: ${result.failures}") { result.isValid shouldBe true }
        withClue("every lookup names the save's workspace — none is workspace-less: ${registry.lookups}") {
            registry.lookups.map { it.second }.distinct() shouldBe listOf(workspaceId)
        }
        // Each name is asked twice: once by the §11.4 portability scan ("is this a registry
        // name?") and once by §12.5 (the facts). Both must ask from the same workspace.
        registry.lookups.map { it.first } shouldContainExactlyInAnyOrder
            listOf("pg-prod", "pg-prod", "pg-warehouse", "pg-warehouse", "pg-meta", "pg-meta")
    }

    @Test
    fun `a datasource visible to another workspace only is unknown to this save`() {
        val other = UUID.randomUUID()
        val scoped =
            DatasourceRegistry { name, ws ->
                if (name == "pg-prod" && ws == other) DatasourceFacts(Dialect.POSTGRES) else null
            }

        val result = Fixtures.validator(datasources = scoped).validate(Fixtures.pipeline(), workspaceId)

        result.codes shouldBe listOf(PipelineErrorCodes.Validation.UNKNOWN_DATASOURCE)
    }
}
