package co.datapipelines.web.ui.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The homepage's structured data (073 §D), PARSED rather than pattern-matched.
 *
 * A malformed `application/ld+json` block is the worst kind of SEO defect: the crawler drops
 * it without a word, so the page loses its rich result and nothing anywhere reports a problem.
 * A regex over the template would not catch a trailing comma; `readTree` does.
 *
 * Read off the RENDER, not the template source, because Thymeleaf is between the two — a
 * future edit that lets an expression into the block would break the JSON at serve time only.
 */
class SiteJsonLdTest {
    private val blocks: List<JsonNode> =
        LD_JSON
            .findAll(SitePageRenderer.render(SitePages.HOME))
            .map { MAPPER.readTree(it.groupValues[1]) }
            .toList()

    @Test
    fun `the homepage publishes exactly the two blocks, both valid JSON`() {
        // Parsing happened in the initializer: reaching here means both blocks parsed.
        blocks.size shouldBe 2
        blocks.map { it["@type"].asText() } shouldBe listOf("SoftwareApplication", "FAQPage")
        blocks.all { it["@context"].asText() == "https://schema.org" } shouldBe true
    }

    @Test
    fun `the SoftwareApplication block carries the fields a rich result needs`() {
        val app = blocks.first { it["@type"].asText() == "SoftwareApplication" }
        val missing =
            listOf("name", "url", "applicationCategory", "operatingSystem", "license", "offers", "description")
                .filter { app[it] == null || app[it].isNull }
        missing shouldBe emptyList()
        app["name"].asText() shouldBe "datapipelines.co"
        app["offers"]["price"].asText() shouldBe "0"
        app["license"].asText() shouldBe AGPL_URL
    }

    @Test
    fun `the FAQ block carries five to six real questions, each with a substantial answer`() {
        val questions = blocks.first { it["@type"].asText() == "FAQPage" }["mainEntity"]
        (questions.size() in FAQ_MIN..FAQ_MAX) shouldBe true

        val bad =
            questions.mapNotNull { q ->
                val name = q["name"]?.asText().orEmpty()
                val answer = q["acceptedAnswer"]?.get("text")?.asText().orEmpty()
                when {
                    q["@type"].asText() != "Question" -> "not a Question: $name"

                    !name.endsWith("?") -> "not a question: $name"

                    q["acceptedAnswer"]?.get("@type")?.asText() != "Answer" -> "answer is not an Answer: $name"

                    answer.length < MIN_ANSWER_CHARS -> "answer too thin ($name)"

                    // Every answer must point at the spec it rests on — the schema block is a
                    // claim surface like any other, and 024b's rule does not stop at HTML.
                    !answer.contains("docs/") -> "answer cites no doc ($name)"

                    else -> null
                }
            }
        bad shouldBe emptyList()
    }

    private companion object {
        const val FAQ_MIN = 5
        const val FAQ_MAX = 6
        const val MIN_ANSWER_CHARS = 120
        const val AGPL_URL = "https://www.gnu.org/licenses/agpl-3.0.html"

        val MAPPER = ObjectMapper()
        val LD_JSON =
            Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}
