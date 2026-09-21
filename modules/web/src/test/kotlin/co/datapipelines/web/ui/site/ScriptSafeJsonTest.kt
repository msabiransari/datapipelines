package co.datapipelines.web.ui.site

import co.datapipelines.pipeline.PipelineJson
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The unit contract of [ScriptSafeJson] (185): whatever the JSON carries, the output
 * contains no sequence that could close the script block it is inserted into, and a
 * JSON parser reading the output back sees the ORIGINAL string — the escaping is
 * invisible above the JSON grammar.
 */
class ScriptSafeJsonTest {
    private val mapper = PipelineJson.objectMapper()

    @Test
    fun `a closing-tag payload carries no closing-tag sequence and round-trips`() {
        val payload = """x</script><script>alert(1)</script>"""
        val json = mapper.writeValueAsString(mapOf("display_name" to payload))
        val safe = ScriptSafeJson.forScriptBlock(json)

        (safe.contains("</", ignoreCase = true)) shouldBe false
        val roundTrip = mapper.readTree(safe)["display_name"].asText()
        roundTrip shouldBe payload
    }

    @Test
    fun `an uppercase closing tag is escaped too`() {
        val payload = """x</SCRIPT>alert("owned")"""
        val safe = ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(mapOf("d" to payload)))

        (safe.contains("</", ignoreCase = true)) shouldBe false
        mapper.readTree(safe)["d"].asText() shouldBe payload
    }

    @Test
    fun `an HTML comment opener cannot survive raw and round-trips`() {
        val payload = "<!-- <script>alert(1)</script> -->"
        val safe = ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(mapOf("d" to payload)))

        safe.contains("<!--") shouldBe false
        mapper.readTree(safe)["d"].asText() shouldBe payload
    }

    @Test
    fun `JS line separators are escaped and round-trip`() {
        val payload = "a\u2028b\u2029c"
        val safe = ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(mapOf("d" to payload)))

        safe.any { it == '\u2028' || it == '\u2029' } shouldBe false
        mapper.readTree(safe)["d"].asText() shouldBe payload
    }

    @Test
    fun `hazard-free json passes through byte-identical`() {
        val json =
            mapper.writeValueAsString(
                linkedMapOf(
                    "name" to "acme/finance/report",
                    "display_name" to "Quarterly \"Report\" — 100%",
                    "unicode" to "确定 ✓ 𝚺",
                    "slash" to "/tmp/out.parquet",
                ),
            )
        ScriptSafeJson.forScriptBlock(json) shouldBe json
    }
}
