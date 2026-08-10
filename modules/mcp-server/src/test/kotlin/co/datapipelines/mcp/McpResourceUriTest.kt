package co.datapipelines.mcp

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/** Every §7.1 URI form, and everything that is not one. */
class McpResourceUriTest {
    private val id: UUID = McpFixtures.PIPELINE_ID

    @Test
    fun `parses every documented form`() {
        assertAll(
            {
                McpResourceUri.parse("datapipelines://pipelines/$id") shouldBe
                    McpResourceUri.PipelineLatest("datapipelines://pipelines/$id", id)
            },
            {
                McpResourceUri.parse("datapipelines://pipelines/$id/versions/3") shouldBe
                    McpResourceUri.PipelineVersion("datapipelines://pipelines/$id/versions/3", id, 3)
            },
            {
                McpResourceUri.parse("datapipelines://pipelines/$id/parameters") shouldBe
                    McpResourceUri.PipelineParameters("datapipelines://pipelines/$id/parameters", id)
            },
            {
                McpResourceUri.parse("datapipelines://templates/revenue.sql") shouldBe
                    McpResourceUri.TemplateLatest("datapipelines://templates/revenue.sql", "revenue.sql")
            },
            {
                McpResourceUri.parse("datapipelines://templates/revenue.sql/versions/2") shouldBe
                    McpResourceUri.TemplateVersion("datapipelines://templates/revenue.sql/versions/2", "revenue.sql", 2)
            },
            { McpResourceUri.parse("datapipelines://datasources") shouldBe McpResourceUri.DatasourceList("datapipelines://datasources") },
            {
                McpResourceUri.parse("datapipelines://datasources/pg-prod") shouldBe
                    McpResourceUri.DatasourceByName("datapipelines://datasources/pg-prod", "pg-prod")
            },
            {
                McpResourceUri.parse("datapipelines://executions/$id") shouldBe
                    McpResourceUri.Execution("datapipelines://executions/$id", id)
            },
            {
                McpResourceUri.parse("datapipelines://executions/$id/events") shouldBe
                    McpResourceUri.ExecutionEvents("datapipelines://executions/$id/events", id)
            },
        )
    }

    @Test
    fun `rejects anything that is not a documented form`() {
        assertAll(
            { McpResourceUri.parse("https://example.test/pipelines/$id").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://pipelines").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://pipelines/not-a-uuid").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://pipelines/$id/versions/latest").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://pipelines/$id/nodes").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://users/$id").shouldBeNull() },
            { McpResourceUri.parse("datapipelines://executions/$id/events/1").shouldBeNull() },
        )
    }

    @Test
    fun `builders and the parser agree`() {
        assertAll(
            { McpResourceUri.parse(McpResourceUri.pipeline(id)) shouldBe McpResourceUri.PipelineLatest(McpResourceUri.pipeline(id), id) },
            {
                McpResourceUri.parse(McpResourceUri.template("t")) shouldBe
                    McpResourceUri.TemplateLatest(McpResourceUri.template("t"), "t")
            },
            { McpResourceUri.parse(McpResourceUri.datasources()) shouldBe McpResourceUri.DatasourceList(McpResourceUri.datasources()) },
            {
                McpResourceUri.parse(McpResourceUri.datasource("pg")) shouldBe
                    McpResourceUri.DatasourceByName(McpResourceUri.datasource("pg"), "pg")
            },
            { McpResourceUri.parse(McpResourceUri.execution(id)) shouldBe McpResourceUri.Execution(McpResourceUri.execution(id), id) },
        )
    }
}
