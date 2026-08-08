package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [PipelineSerializer] — the wire shapes §15.1 freezes.
 *
 * Assertions are on the emitted JSON's own keys, not on a round trip: a round trip would pass
 * just as happily if both ends agreed on `displayName`, and the frozen contract says
 * `display_name`. This is the Java-Beans naming trap that once shipped an entire feature
 * broken (`val xMin` → `xmin`), which is why every field carries an explicit `@JsonProperty`
 * and why this test reads keys.
 */
class PipelineSerializerTest {
    private val serializer = PipelineSerializer()

    @Test
    fun `top-level keys are the frozen snake_case names`() {
        val json = Fixtures.json(serializer.write(Fixtures.pipeline()))

        json.properties().map { it.key } shouldContainExactlyInAnyOrder
            listOf("schema_version", "name", "display_name", "description", "settings", "parameters", "nodes")
    }

    @Test
    fun `node keys are the frozen names, including depends_on`() {
        val node = Fixtures.json(serializer.write(Fixtures.pipeline())).path("nodes").first()

        node.properties().map { it.key } shouldContainExactlyInAnyOrder
            listOf("id", "description", "type", "source", "template", "output", "depends_on")
    }

    @Test
    fun `each NodeOutput variant serializes with its discriminator and only its own fields`() {
        val outputs =
            listOf(
                NodeOutput.Caller,
                NodeOutput.Tempdb("stg_orders"),
                NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.REPLACE),
            )
        val expected =
            listOf(
                listOf("target"),
                listOf("target", "table"),
                listOf("target", "datasource", "table", "mode"),
            )

        outputs.forEachIndexed { index, output ->
            val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(output = output)))
            val json =
                Fixtures
                    .json(serializer.write(pipeline))
                    .path("nodes")
                    .first()
                    .path("output")

            json.properties().map { it.key } shouldContainExactlyInAnyOrder expected[index]
            json.path("target").asText() shouldBe output.target.wire
        }
    }

    @Test
    fun `a DML node emits no output key at all, rather than a null one`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(type = NodeType.DML)))

        val node = Fixtures.json(serializer.write(pipeline)).path("nodes").first()

        node.has("output") shouldBe false
    }

    @Test
    fun `parameter descriptors omit the fields they do not declare`() {
        val pipeline =
            Fixtures.pipeline(parameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true)))

        val descriptor = Fixtures.json(serializer.write(pipeline)).path("parameters").path("start_date")

        // `precision`, `scale`, `default` and `description` are absent, not null: omitted
        // carries meaning throughout this contract, and `"precision": null` would assert
        // something §6.1 does not define.
        descriptor.properties().map { it.key } shouldContainExactlyInAnyOrder listOf("type", "required")
    }

    @Test
    fun `enum values are the catalog wire strings, never Enum name`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(output = NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.APPEND)),
                    ),
            )

        val written = serializer.write(pipeline)

        written shouldContain "\"mode\":\"append\""
        written shouldContain "\"target\":\"datasource\""
        written shouldContain "\"type\":\"DQL\""
        written shouldContain "\"engine\":\"H2\""
    }

    @Test
    fun `writePretty produces the same document, indented`() {
        val pipeline = Fixtures.pipeline()

        val pretty = serializer.writePretty(pipeline)

        pretty shouldContain "\n"
        PipelineDeserializer().readOrThrow(pretty) shouldBe pipeline
    }
}
