package co.datapipelines.web.ui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldHaveKeys
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #456 merge follow-up — the htmx configuration every layout hands the vendored bundle stays
 * inside the enforced CSP. `style-src` has no 'unsafe-inline', so any inline style htmx WRITES
 * is refused: its indicator `<style>` (#188, `includeIndicatorStyles:false`) and, measured in
 * #456, its settle step, which copies an element's `style` attribute across a swap with
 * setAttribute whenever the attribute exists — a `style=""` left behind by a CSSOM write became
 * 22 refusals from htmx.min.js on the datasource dialog and the transform face. htmx's default
 * `attributesToSettle` names "style", so the layouts must override it. The two layouts carry
 * the same config by contract (auth.html points at default.html's note); a drift between them
 * is red here too.
 */
class HtmxConfigAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
    private val objectMapper = ObjectMapper()

    /** Template path → the parsed `content` of its `<meta name="htmx-config">`. */
    private val configs: Map<String, JsonNode> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .mapNotNull { resource ->
                val html = resource.inputStream.readBytes().decodeToString()
                HTMX_CONFIG_META.find(html)?.let { match ->
                    resource.uri.path.substringAfter("/templates/") to objectMapper.readTree(match.groupValues[1])
                }
            }.toMap()

    @Test
    fun `both layouts declare an htmx config`() {
        configs.shouldHaveKeys(*LAYOUTS.toTypedArray())
    }

    @Test
    fun `no layout lets htmx settle the style attribute`() {
        configs.forEach { (path, config) ->
            val settled = config.path("attributesToSettle")
            withClue(path) { settled.isArray shouldBe true }
            val names = settled.map(JsonNode::asText)
            withClue(path) {
                names shouldNotContain "style"
                names shouldContainAll listOf("class", "width", "height")
            }
        }
    }

    @Test
    fun `no layout re-enables eval or the indicator stylesheet`() {
        configs.forEach { (path, config) ->
            withClue(path) {
                config.path("allowEval").isBoolean shouldBe true
                config.path("allowEval").asBoolean() shouldBe false
                config.path("includeIndicatorStyles").isBoolean shouldBe true
                config.path("includeIndicatorStyles").asBoolean() shouldBe false
            }
        }
    }

    @Test
    fun `every layout carries the same config`() {
        configs.values.toSet().size shouldBe 1
    }

    companion object {
        private val LAYOUTS = listOf("layouts/default.html", "layouts/auth.html")

        /** The meta's single-quoted JSON content, as both layouts write it. */
        private val HTMX_CONFIG_META = Regex("""<meta\s+name="htmx-config"\s+content='([^']*)'\s*/?>""")
    }
}
