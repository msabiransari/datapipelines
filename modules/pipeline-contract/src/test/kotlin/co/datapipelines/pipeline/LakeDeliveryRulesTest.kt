package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Lake
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.StagingEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class LakeDeliveryRulesTest {
    private val workspace = UUID.randomUUID()
    private val registry =
        DatasourceRegistry { name, _ ->
            DatasourceFacts(Dialect.LAKE, readonly = true, managedLake = name == "managed")
        }
    private val land =
        Fixtures
            .node(id = "land", source = "tempdb", output = NodeOutput.Tempdb("landed"))
            .copy(delivery = DeliveryRef("feed", "batch"))
    private val append =
        Fixtures
            .node(id = "append", source = "tempdb", dependsOn = listOf("land"))
            .copy(output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.APPEND))

    private fun pipeline(nodes: List<Node>) =
        Fixtures.pipeline(
            nodes = nodes,
            settings = PipelineSettings(tempdb = TempdbSettings(engine = StagingEngine.DUCKDB)),
        )

    private fun failures(nodes: List<Node>): List<ValidationFailure> {
        val collector = FailureCollector()
        LakeDeliveryRules.check(pipeline(nodes), registry, workspace, collector)
        return collector.toResult().failures
    }

    @Test
    fun `one delivery and its append pass`() {
        failures(listOf(land, append)).shouldBeEmpty()
    }

    @Test
    fun `two delivery nodes are refused`() {
        failures(listOf(land, land.copy(id = "other"), append)).map { it.code } shouldContain Lake.DELIVERY_ONE_PER_PIPELINE
    }

    @Test
    fun `two appends to the same table are refused but distinct tables pass`() {
        val second = append.copy(id = "append_two")
        failures(listOf(land, append, second)).map { it.code } shouldContain Lake.APPEND_ONE_PER_TABLE
        failures(listOf(land, append, second.copy(output = NodeOutput.Datasource("managed", "ns.other", WriteMode.APPEND))))
            .shouldBeEmpty()
    }

    @Test
    fun `append requires delivery but ordinary external output does not`() {
        failures(listOf(append.copy(dependsOn = emptyList()))).map { it.code } shouldContain Lake.APPEND_REQUIRES_DELIVERY
        failures(listOf(append.copy(output = NodeOutput.Datasource("external", "orders", WriteMode.APPEND))))
            .shouldBeEmpty()
    }

    @Test
    fun `rejects producing script must be strict`() {
        val script =
            Fixtures
                .node(id = "check", type = NodeType.SCRIPT, dependsOn = listOf("land"))
                .copy(output = NodeOutput.Tempdb("good", "bad"))
        val sink = append.copy(dependsOn = listOf("check"))
        failures(listOf(land, script, sink)).map { it.code } shouldContainExactly listOf(Lake.APPEND_REJECTS_NOT_STRICT)
        failures(listOf(land, script.copy(strict = true), sink)).shouldBeEmpty()
    }

    @Test
    fun `five node barrier identifies missing sibling and accepts transitive coverage`() {
        val clean = Fixtures.node(id = "clean", dependsOn = listOf("land"), output = NodeOutput.Tempdb("cleaned"))
        val validate = Fixtures.node(id = "validate", dependsOn = listOf("clean"), output = NodeOutput.Tempdb("validated"))
        val sibling = Fixtures.node(id = "audit", dependsOn = listOf("land"), output = NodeOutput.Tempdb("audited"))
        val sink = append.copy(dependsOn = listOf("validate"))
        val result = failures(listOf(land, clean, validate, sibling, sink))
        result.map { it.code } shouldContainExactly listOf(Lake.APPEND_BARRIER)
        result.single().details["missing"] shouldBe listOf("audit")
        failures(listOf(land, clean, validate, sibling, sink.copy(dependsOn = listOf("validate", "audit"))))
            .shouldBeEmpty()
    }

    @Test
    fun `replace refused only for managed lake`() {
        failures(listOf(append.copy(output = NodeOutput.Datasource("managed", "ns.orders", WriteMode.REPLACE))))
            .map { it.code } shouldContainExactly listOf(Lake.APPEND_REPLACE_REFUSED)
        failures(listOf(append.copy(output = NodeOutput.Datasource("external", "orders", WriteMode.REPLACE))))
            .shouldBeEmpty()
    }

    @Test
    fun `delivery requires tempdb DQL and DuckDB`() {
        failures(listOf(land.copy(source = "other"))).map { it.code } shouldContain Lake.DELIVERY_INVALID_NODE
        failures(listOf(land.copy(type = NodeType.DML))).map { it.code } shouldContain Lake.DELIVERY_INVALID_NODE
        val collector = FailureCollector()
        LakeDeliveryRules.check(Fixtures.pipeline(nodes = listOf(land)), registry, workspace, collector)
        collector.toResult().codes shouldContain Lake.DELIVERY_REQUIRES_DUCKDB
    }

    @Test
    fun `delivery binds and round trips while absent block stays absent`() {
        val json = Fixtures.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(pipeline(listOf(land)))
        val rebound = PipelineDeserializer().fromTree(json) as DeserializationOutcome.Parsed
        rebound.pipeline.nodes
            .single()
            .delivery shouldBe DeliveryRef("feed", "batch")
        val absent = Fixtures.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(append)
        absent.has("delivery") shouldBe false
    }

    @Test
    fun `shared validator calls lake rules and permits only managed qualified outputs`() {
        val validator =
            Fixtures.validator(
                datasources = registry,
                templates = StubTemplates(defaultLookup = TemplateLookup.Found(Dialect.DUCKDB)),
            )
        val result = validator.validate(pipeline(listOf(land, append)), workspace)
        result.failures.shouldBeEmpty()
        listOf("tempdb.orders", "__system__.orders").forEach { table ->
            val target = append.copy(output = NodeOutput.Datasource("managed", table, WriteMode.APPEND))
            validator.validate(pipeline(listOf(land, target)), workspace).failures.shouldBeEmpty()
        }
        val malformed = append.copy(output = NodeOutput.Datasource("managed", "ns.orders.extra", WriteMode.APPEND))
        validator.validate(pipeline(listOf(land, malformed)), workspace).codes shouldContain
            PipelineErrorCodes.Validation.INVALID_IDENTIFIER

        val wrongSource = validator.validate(pipeline(listOf(land, append.copy(source = "external"))), workspace)
        wrongSource.codes shouldContain Lake.APPEND_INVALID_NODE
        result.codes.contains(PipelineErrorCodes.Validation.INVALID_IDENTIFIER) shouldBe false
        result.codes.contains(PipelineErrorCodes.Validation.DATASOURCE_READONLY) shouldBe false
        val orphan = Fixtures.validator(datasources = registry).validate(pipeline(listOf(append)), workspace)
        orphan.codes shouldContain Lake.APPEND_REQUIRES_DELIVERY
        val external = append.copy(output = NodeOutput.Datasource("external", "ns.orders", WriteMode.APPEND))
        val refused = Fixtures.validator(datasources = registry).validate(pipeline(listOf(land, external)), workspace)
        refused.codes shouldContain PipelineErrorCodes.Validation.INVALID_IDENTIFIER
        refused.codes shouldContain PipelineErrorCodes.Validation.DATASOURCE_READONLY
    }

    @Test
    fun `nonobject delivery is refused before binding`() {
        val json = Fixtures.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(pipeline(listOf(land)))
        (json.path("nodes").get(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("delivery", "folder")
        val result = PipelineDeserializer().fromTree(json) as DeserializationOutcome.Rejected
        result.result.codes shouldContainExactly listOf(Lake.DELIVERY_INVALID_NODE)
    }
}
