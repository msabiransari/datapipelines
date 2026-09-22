package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #211 — the vendored design system's stagger grid (`.ds-stagger-children > *`) hides its
 * children with a STATIC `opacity: 0` and relies on the entrance animation to show them, so a
 * renderer that never runs CSS animations (print/PDF, some capture tools) draws an empty grid.
 * The site's own stylesheet overrides that rule to keep content visible by default. This pins
 * the BUILT bundle, the file the browser actually receives: the override must follow the
 * vendor rule, and print must switch the animation off. A design-system update or a bundle
 * regeneration that drops the override goes red here, not in a customer's print preview.
 */
class SiteStaggerGridVisibilityTest {
    private val bundle: String =
        PathMatchingResourcePatternResolver()
            .getResource("classpath:static/site/css/site-chrome.css")
            .inputStream
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** Every `.ds-stagger-children > * { … }` block in the bundle, in document order. */
    private fun staggerBlocks(): List<String> =
        Regex("""\.ds-stagger-children > \* \{([^}]*)\}""").findAll(bundle).map { it.groupValues[1] }.toList()

    @Test
    fun `the site override follows the vendor rule and leaves the grid visible without animations`() {
        val blocks = staggerBlocks()
        blocks shouldHaveAtLeastSize 2
        // The vendor rule is the hazard this test exists for; if an update removes it, relax this line.
        blocks.first() shouldContain "opacity: 0"
        // The site's override wins by order: visible by default, the entrance kept through fill-mode.
        val override = blocks.drop(1).last { "animation-fill-mode" in it }
        override shouldContain "opacity: 1"
        override shouldContain "animation-fill-mode: both"
    }

    @Test
    fun `print gets the grid with no animation at all`() {
        val printBlock =
            Regex("""@media print \{\s*\.ds-stagger-children > \* \{([^}]*)\}""").find(bundle)?.groupValues?.get(1)
        (printBlock != null) shouldBe true
        printBlock!! shouldContain "animation: none"
    }
}
