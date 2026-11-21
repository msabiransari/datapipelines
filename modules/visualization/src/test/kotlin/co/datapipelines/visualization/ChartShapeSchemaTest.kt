package co.datapipelines.visualization

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * The four shapes G2 adds to the skeleton's `$defs` (design record 2026-11-14-js-rendered-charts §4.4; brief 1050 D1,
 * A.1), each a closed object: valid and invalid instances against the definition. A definition no served type uses yet
 * (`size`, `count`, `level`) is reached through a schema this test builds that refers to it, over the served files.
 */
class ChartShapeSchemaTest {
    @Test
    fun `shade - the four required members, the two scales, mid with diverging only, legend as a place or null`() {
        val ok = """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "sequential"}"""
        valid("shade", ok)
        valid("shade", """{"field": "v", "aggregate": "count", "type": "quantitative", "scale": "sequential", "legend": null}""")
        valid(
            "shade",
            """{"field": "v", "aggregate": "mean", "type": "quantitative", "scale": "diverging", """ +
                """"mid": -2.5, "legend": {"orient": "right"}}""",
        )
        valid("shade", """{"field": "v", "aggregate": "max", "type": "quantitative", "scale": "diverging"}""")
        for (member in listOf("field", "aggregate", "type", "scale")) invalid("shade", without(ok, member), "without $member")
        // A shade always carries its field, with every aggregate (the record's row; a value with count carries none).
        for (aggregate in listOf("sum", "mean", "min", "max", "count")) {
            invalid("shade", """{"aggregate": "$aggregate", "type": "quantitative", "scale": "sequential"}""", "$aggregate without field")
        }
        invalid(
            "shade",
            """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "sequential", "mid": 0}""",
            "mid beside sequential",
        )
        invalid(
            "shade",
            """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "sequential", "sort": null}""",
            "an unknown member",
        )
        invalid("shade", """{"field": "v", "aggregate": "sum", "type": "nominal", "scale": "sequential"}""", "type nominal")
        invalid("shade", """{"field": "v", "aggregate": "share", "type": "quantitative", "scale": "sequential"}""", "aggregate share")
        invalid("shade", """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "log"}""", "scale log")
        invalid("shade", """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "diverging", "mid": "0"}""", "mid as text")
        invalid(
            "shade",
            """{"field": "v", "aggregate": "sum", "type": "quantitative", "scale": "sequential", "legend": {"orient": "direct"}}""",
            "legend direct",
        )
    }

    @Test
    fun `size - field required, range exactly two numbers each from 4 to 64`() {
        valid("size", """{"field": "v"}""")
        valid("size", """{"field": "v", "range": [4, 64]}""")
        valid("size", """{"field": "v", "range": [6.5, 40.25]}""")
        // The order of the two is a save rule (chart.size.range_invalid), not the schema's.
        valid("size", """{"field": "v", "range": [20, 10]}""")
        invalid("size", """{"range": [6, 40]}""", "without field")
        invalid("size", """{"field": "v", "range": [3, 10]}""", "below 4")
        invalid("size", """{"field": "v", "range": [10, 65]}""", "above 64")
        invalid("size", """{"field": "v", "range": [10]}""", "one number")
        invalid("size", """{"field": "v", "range": [6, 10, 20]}""", "three numbers")
        invalid("size", """{"field": "v", "range": ["6", "10"]}""", "texts")
        invalid("size", """{"field": "v", "title": "x"}""", "an unknown member")
    }

    @Test
    fun `count - aggregate count or share, and no field`() {
        valid("count", """{"aggregate": "count"}""")
        valid("count", """{"aggregate": "share"}""")
        invalid("count", """{}""", "without aggregate")
        invalid("count", """{"aggregate": "sum"}""", "aggregate sum")
        invalid("count", """{"aggregate": "count", "field": "v"}""", "count with a field")
    }

    @Test
    fun `level - field required, nothing else`() {
        valid("level", """{"field": "v"}""")
        invalid("level", """{}""", "without field")
        invalid("level", """{"field": "v", "type": "nominal"}""", "an unknown member")
        invalid("level", """{"field": ""}""", "an empty field")
    }

    private fun without(
        json: String,
        member: String,
    ): String {
        val node = ArtifactJson.mapper.readTree(json) as com.fasterxml.jackson.databind.node.ObjectNode
        node.remove(member)
        return node.toString()
    }

    private fun valid(
        shape: String,
        instance: String,
    ) = withClue("$shape $instance is valid") { problems(shape, instance).shouldBeEmpty() }

    private fun invalid(
        shape: String,
        instance: String,
        why: String,
    ) = withClue("$shape: $why ($instance) is refused") { problems(shape, instance).shouldNotBeEmpty() }

    private fun problems(
        shape: String,
        instance: String,
    ): List<ChartSchemaProblem> {
        val id = ChartSchemas.ID_PREFIX + "test-$shape.schema.json"
        val test =
            """{"${'$'}schema": "https://json-schema.org/draft/2020-12/schema", "${'$'}id": "$id", """ +
                """"${'$'}ref": "skeleton.schema.json#/${'$'}defs/$shape"}"""
        val resources = ChartSchemas.FILES.associate { ChartSchemas.ID_PREFIX + it to String(ChartSchemas.bytes(it)) } + (id to test)
        return ChartSchemaValidator(resources, id).validate(ArtifactJson.mapper.readTree(instance))
    }
}
