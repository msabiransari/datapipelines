package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldBeEmpty
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
    fun `a PIPELINE node round-trips with its pipeline reference and parameter map intact`() {
        val node =
            Node(
                id = "revenue",
                description = "Monthly revenue component.",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = NodeOutput.Tempdb("stg_revenue"),
                dependsOn = emptyList(),
                pipeline = PipelineNodeRef("test/monthly_revenue", 4),
                parameters =
                    mapOf(
                        "start_date" to Fixtures.json("\"\${start_date}\""),
                        "region" to Fixtures.json("\"EU\""),
                    ),
            )
        val pipeline = Fixtures.pipeline(nodes = listOf(node))

        val written = serializer.write(pipeline)

        // The wire keys are the frozen snake_case names — asserted on the document, not on a
        // round trip that would pass just as happily if both ends agreed on the wrong spelling.
        val serialized = Fixtures.json(written).path("nodes").single()
        serialized.path("type").asText() shouldBe "PIPELINE"
        serialized.path("pipeline").path("name").asText() shouldBe "test/monthly_revenue"
        serialized.path("pipeline").path("version").asInt() shouldBe 4
        serialized.path("parameters").path("region").asText() shouldBe "EU"
        PipelineDeserializer().readOrThrow(written) shouldBe pipeline
    }

    @Test
    fun `a PIPELINE node with no pipeline or parameters emits neither key`() {
        val node =
            Node(
                id = "revenue",
                description = "d",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = null,
                dependsOn = emptyList(),
            )
        val serialized = Fixtures.json(serializer.write(Fixtures.pipeline(nodes = listOf(node)))).path("nodes").single()

        serialized.has("pipeline") shouldBe false
        serialized.has("parameters") shouldBe false
        serialized.has("output") shouldBe false
    }

    @Test
    fun `writePretty produces the same document, indented`() {
        val pipeline = Fixtures.pipeline()

        val pretty = serializer.writePretty(pipeline)

        pretty shouldContain "\n"
        PipelineDeserializer().readOrThrow(pretty) shouldBe pipeline
    }

    @Test
    fun `a context_key body serialises without context_keys - existing bodies are byte-identical`() {
        // 121/D2's hash-neutrality proof, first direction: the new field is nullable under the
        // class's NON_NULL inclusion, so a body written before `context_keys` existed serialises
        // exactly as it did — no stored version's body hash moves (versioning §9.2).
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.calculatorNode()))

        val written = serializer.write(pipeline)

        written.contains("context_keys") shouldBe false
        val node = Fixtures.json(written).path("nodes").single()
        node.path("context_key").asText() shouldBe "run_fiscal_quarter"
        PipelineDeserializer().readOrThrow(written) shouldBe pipeline
    }

    @Test
    fun `a context_keys body round-trips byte-identically`() {
        // The second direction: a multi-output mapping survives serialize → deserialize →
        // serialize unchanged, and the single-shape field stays OFF the wire for it.
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.windowNode()))

        val written = serializer.write(pipeline)

        val node = Fixtures.json(written).path("nodes").single()
        node.has("context_key") shouldBe false
        node.path("context_keys").path("start").asText() shouldBe "window_start"
        node.path("context_keys").path("end").asText() shouldBe "window_end"
        val reread = PipelineDeserializer().readOrThrow(written)
        reread shouldBe pipeline
        serializer.write(reread) shouldBe written
    }

    @Test
    fun `a body without checks serialises without a checks key - existing bodies are byte-identical`() {
        // 140's hash-neutrality proof: `checks` defaults to an empty list under NON_EMPTY
        // inclusion, so a body written before §3.3 existed serializes exactly as it did — no
        // stored version's body hash moves (versioning §9.2, §15.2 additive).
        val pipeline = Fixtures.pipeline()

        val written = serializer.write(pipeline)

        written.contains("checks") shouldBe false
        val reread = PipelineDeserializer().readOrThrow(written)
        reread shouldBe pipeline
        serializer.write(reread) shouldBe written
    }

    @Test
    fun `an explicit empty checks array canonicalizes to the absent form`() {
        // The second hash-neutrality direction: `"checks":[]` on the wire means the same as no
        // key, so it serializes back to the same bytes — the two spellings hash identically.
        val absent = serializer.write(Fixtures.pipeline())
        val explicit = absent.replaceFirst("{", "{\"checks\":[],")

        val reread = PipelineDeserializer().readOrThrow(explicit)

        reread.checks.shouldBeEmpty()
        serializer.write(reread) shouldBe absent
    }

    @Test
    fun `a body with one check round-trips with every field preserved and tolerance omitted stays omitted`() {
        val pipeline =
            Fixtures.pipeline(
                checks =
                    listOf(
                        Fixtures.check(
                            id = "row_count",
                            name = "Orders row count matches the rollup.",
                            datasource = "pg-prod",
                            sql = "SELECT COUNT(*) FROM orders",
                            expected = CheckExpectation(kind = CheckExpectation.KIND_VALUE, value = 1_000_000.0),
                        ),
                    ),
            )

        val written = serializer.write(pipeline)

        val check = Fixtures.json(written).path("checks").single()
        check.properties().map { it.key } shouldContainExactlyInAnyOrder listOf("id", "name", "datasource", "sql", "expected")
        val expected = check.path("expected")
        expected.path("kind").asText() shouldBe "value"
        expected.path("value").asDouble() shouldBe 1_000_000.0
        // Omitted is meaningful: `tolerance` absent is the default (0), `"tolerance": null`
        // would assert something §3.3 does not define.
        expected.has("tolerance") shouldBe false
        val reread = PipelineDeserializer().readOrThrow(written)
        reread shouldBe pipeline
        serializer.write(reread) shouldBe written
    }
}
