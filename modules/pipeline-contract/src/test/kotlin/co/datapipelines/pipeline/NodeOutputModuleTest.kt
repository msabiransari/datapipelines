package co.datapipelines.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [NodeOutputModule] — the flat NodeOutput binding's OWN edge cases, beside the full-pipeline
 * round-trips (PipelineSerializerTest) and the pre-scan's catalog refusals
 * (PipelineDeserializerTest). This is the reflective layer of the `xmin`/`arg0` failure
 * family, which is why the edges are pinned explicitly:
 *
 * - an ABSENT `table` binds as the empty string, never a parse exception — §17.2 needs every
 *   §12 failure collected together, and a Jackson abort would hide the rest;
 * - an absent `target` binds as CALLER — the pre-scan owns rejecting it, and this reader must
 *   stay lenient so the validator can speak;
 * - an absent `mode` falls back to APPEND — guessing toward REPLACE would TRUNCATE a table;
 * - `caller` serializes to the discriminator alone (§4.7: no fields beyond it).
 */
class NodeOutputModuleTest {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(NodeOutputModule.create())

    @Test
    fun `tempdb round-trips through its wire shape`() {
        val output = mapper.readValue("""{"target":"tempdb","table":"stage_orders"}""", NodeOutput::class.java)

        output shouldBe NodeOutput.Tempdb(table = "stage_orders")
        mapper.writeValueAsString(output) shouldBe """{"target":"tempdb","table":"stage_orders"}"""
    }

    @Test
    fun `caller serializes to the discriminator alone`() {
        mapper.writeValueAsString(NodeOutput.Caller) shouldBe """{"target":"caller"}"""
    }

    @Test
    fun `an absent target binds as caller - the lenient reader lets the validator speak`() {
        mapper.readValue("""{}""", NodeOutput::class.java) shouldBe NodeOutput.Caller
    }

    @Test
    fun `datasource round-trips both modes`() {
        val append = mapper.readValue("""{"target":"datasource","datasource":"pg","table":"t","mode":"append"}""", NodeOutput::class.java)
        val replace = mapper.readValue("""{"target":"datasource","datasource":"pg","table":"t","mode":"replace"}""", NodeOutput::class.java)

        append shouldBe NodeOutput.Datasource("pg", "t", WriteMode.APPEND)
        replace shouldBe NodeOutput.Datasource("pg", "t", WriteMode.REPLACE)
        mapper.writeValueAsString(replace) shouldBe """{"target":"datasource","datasource":"pg","table":"t","mode":"replace"}"""
    }

    @Test
    fun `an absent table on tempdb binds empty - never a parse abort`() {
        val output = mapper.readValue("""{"target":"tempdb"}""", NodeOutput::class.java)

        output shouldBe NodeOutput.Tempdb(table = "")
    }

    @Test
    fun `absent fields on datasource bind empty with the append fallback`() {
        val output = mapper.readValue("""{"target":"datasource"}""", NodeOutput::class.java)

        // The truncation-safe direction: APPEND, never REPLACE.
        output shouldBe NodeOutput.Datasource(datasource = "", table = "", mode = WriteMode.APPEND)
    }

    @Test
    fun `an explicit json null table binds the same as an absent one`() {
        val output = mapper.readValue("""{"target":"tempdb","table":null}""", NodeOutput::class.java)

        output shouldBe NodeOutput.Tempdb(table = "")
    }

    @Test
    fun `a non-string table is absent, not coerced`() {
        val output = mapper.readValue("""{"target":"tempdb","table":42}""", NodeOutput::class.java)

        output shouldBe NodeOutput.Tempdb(table = "")
    }
}
