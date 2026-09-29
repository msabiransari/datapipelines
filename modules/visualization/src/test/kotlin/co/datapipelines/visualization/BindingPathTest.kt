package co.datapipelines.visualization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [BindingPath]: the grammar (keys, `[index]` steps, nothing else) and resolution inside a stored config. */
class BindingPathTest {
    private val config =
        ArtifactJson.mapper.readTree(
            """{"data":[{"x":"${'$'}.x","marker":{"color":null}}],"layout":{"title":{"text":"R"}}}""",
        )

    @Test
    fun `the grammar - a key, then dotted keys and bracketed indexes`() {
        BindingPath.parse("data[0].x") shouldBe listOf(BindingPath.Step.Key("data"), BindingPath.Step.Index(0), BindingPath.Step.Key("x"))
        BindingPath.parse("layout.title.text")?.size shouldBe 3
        listOf("", "[0]", "data[01]", "data[-1]", "data..x", "data.x.", "\$.data", "data[*]", "data[0]x", "a".repeat(257)).forEach {
            BindingPath.parse(it) shouldBe null
        }
    }

    @Test
    fun `a path resolves only where the stored config has that place - a null leaf counts, a missing one does not`() {
        listOf("data[0].x", "data[0].marker.color", "layout.title.text", "layout").forEach {
            BindingPath.resolves(config, BindingPath.parse(it)!!) shouldBe
                true
        }
        listOf("data[1].x", "data[0].y", "layout.title.text.size", "data.x", "layout[0]").forEach {
            BindingPath.resolves(config, BindingPath.parse(it)!!) shouldBe false
        }
    }
}
