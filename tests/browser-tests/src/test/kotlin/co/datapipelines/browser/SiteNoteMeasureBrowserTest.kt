package co.datapipelines.browser

import com.microsoft.playwright.options.LoadState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #741: every short note set directly in a section container (`section.section > .container >
 * p.section-note`) reads at `--site-measure`, measured on every marketing route of the sitemap.
 *
 * The cap is read from the token, never typed: a temporary probe styled `width:
 * var(--site-measure)` is appended inside each note (so it resolves `ch` in the note's own
 * font), measured while attached, and removed BEFORE the note and the document are measured
 * (a probe left attached could itself cause the narrow sideways scroll). At 1440 the cap must
 * bind on most notes; at 390 every note fills its content box with no sideways scroll. A red
 * control deletes the one `.section-note` rule from the live CSSOM, requires notes that run
 * the full container, restores the rule and requires green. Tutorial-step paragraphs
 * (`.workflow-steps`, exempt from the guard's rule 6) are printed at 1440, never asserted.
 * CI-only, like the other site browser classes: written here, executed by CI.
 */
class SiteNoteMeasureBrowserTest : BrowserSuite() {
    @Test
    fun `section notes read at the site measure at desktop and fill the column on a phone`() {
        startTrace()
        val routes = marketingRoutes()
        withClue("marketing route discovery is non-vacuous") { routes.size shouldBeGreaterThanOrEqual MIN_MARKETING_ROUTES }

        val offenders = mutableListOf<String>()
        val noteRoutes = mutableListOf<String>()
        var measured = 0
        var binding = 0
        page.setViewportSize(WIDE_WIDTH, VIEWPORT_HEIGHT)
        routes.forEach { route ->
            val shape = measure(route)
            if (shape.notes.isNotEmpty()) noteRoutes += route
            measured += shape.notes.size
            binding += shape.notes.count { binds(it) }
            offenders += desktopOffenders(route, WIDE_WIDTH, shape)
            shape.steps.filter { num(it["width"]) > num(it["cap"]) + TOLERANCE }.forEach {
                println("741 workflow-steps wider than the measure: $route ${it["label"]} ${it["width"]}px (measure ${it["cap"]}px)")
            }
        }
        println("741 note sweep: ${routes.size} routes, ${noteRoutes.size} with notes, $measured notes at $WIDE_WIDTH, $binding capped")
        withClue("notes measured at $WIDE_WIDTH") { measured shouldBeGreaterThanOrEqual MIN_NOTES }
        withClue("notes whose container is wider than the measure (the cap binds)") { binding shouldBeGreaterThanOrEqual MIN_BINDING }

        page.setViewportSize(NARROW_WIDTH, VIEWPORT_HEIGHT)
        noteRoutes.forEach { route ->
            val shape = measure(route)
            shape.notes.forEach { n ->
                if (!near(n["width"], num(n["content"]))) {
                    offenders += "$route at $NARROW_WIDTH: ${n["label"]} is ${n["width"]}px in a ${n["content"]}px content box"
                }
            }
            offenders += desktopOffenders(route, NARROW_WIDTH, shape)
        }
        withClue("section-note measure offenders") { offenders.shouldBeEmpty() }
    }

    @Test
    fun `without the section-note rule the notes run the full container`() {
        startTrace()
        page.setViewportSize(WIDE_WIDTH, VIEWPORT_HEIGHT)
        val route =
            marketingRoutes().firstOrNull { r ->
                measure(r).notes.any { binds(it) }
            }
        checkNotNull(route) { "no route renders a capped section-note — the red control would be vacuous" }
        val saved = page.evaluate(REMOVE_RULE_JS)
        try {
            val broken = currentShape()
            val fullWidth = broken.notes.filter { binds(it) && near(it["width"], num(it["content"])) }
            val found = desktopOffenders(route, WIDE_WIDTH, broken)
            println("741 red control on $route: ${found.size} offenders, ${fullWidth.size} notes at the container width")
            withClue("with the rule deleted, $route's notes must run the full container: $found") {
                (fullWidth.isNotEmpty() && found.isNotEmpty()) shouldBe true
            }
        } finally {
            page.evaluate("s => Array.from(document.styleSheets).find(x => x.href === s.href).insertRule(s.rule, s.index)", saved)
        }
        withClue("restored .section-note rule") { desktopOffenders(route, WIDE_WIDTH, currentShape()).shouldBeEmpty() }
    }

    private data class Shape(
        val notes: List<Map<String, Any>>,
        val steps: List<Map<String, Any>>,
        val docOver: Double,
    )

    private fun marketingRoutes(): List<String> {
        page.navigate("$baseUrl/sitemap.xml")
        return LOC
            .findAll(page.content())
            .map { it.groupValues[1].removePrefix(SITE_ORIGIN).ifEmpty { "/" } }
            .filterNot { it.startsWith("/docs") }
            .toList()
    }

    private fun measure(route: String): Shape {
        page.navigate("$baseUrl$route")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.evaluate("async () => { await document.fonts.ready; }")
        return currentShape()
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentShape(): Shape {
        val raw = page.evaluate(SWEEP_JS) as Map<String, Any>
        return Shape(
            notes = raw["notes"] as List<Map<String, Any>>,
            steps = raw["steps"] as List<Map<String, Any>>,
            docOver = num(raw["docOver"]),
        )
    }

    /** The note's computed cap is the token's width, and its box is min(content, cap). */
    private fun desktopOffenders(
        route: String,
        w: Int,
        shape: Shape,
    ): List<String> {
        val found = mutableListOf<String>()
        shape.notes.forEach { n ->
            val at = "$route at $w: ${n["label"]}"
            if (n["maxWidth"] == "none") {
                found += "$at has max-width none"
                return@forEach
            }
            val cap = num(n["cap"])
            if (!near(n["probe"], cap)) found += "$at caps at ${cap}px but the measure token is ${n["probe"]}px"
            val want = minOf(num(n["content"]), cap)
            if (!near(n["width"], want)) found += "$at is ${n["width"]}px (want min(content ${n["content"]}, cap $cap) = $want)"
        }
        if (shape.docOver > 0) found += "$route at $w scrolls sideways by ${shape.docOver}px"
        return found
    }

    /** The container's content box is wider than the measure token by a clear margin. */
    private fun binds(note: Map<String, Any>): Boolean = num(note["content"]) > num(note["probe"]) + MIN_BIND_MARGIN

    private fun num(value: Any?): Double = (value as Number).toDouble()

    private fun near(
        value: Any?,
        want: Double,
    ): Boolean = Math.abs(num(value) - want) <= TOLERANCE

    private companion object {
        val LOC = Regex("""<loc>([^<]+)</loc>""")
        const val SITE_ORIGIN = "https://datapipelines.co"
        const val WIDE_WIDTH = 1440
        const val NARROW_WIDTH = 390
        const val VIEWPORT_HEIGHT = 900
        const val TOLERANCE = 1.5
        const val MIN_MARKETING_ROUTES = 41

        /** #741's inventory: 29 notes in 11 templates (engine.html's renders once per engine page). */
        const val MIN_NOTES = 25
        const val MIN_BINDING = 20
        const val MIN_BIND_MARGIN = 50.0

        /** Deletes the ONE `.section-note` style rule; returns where it was so it can be restored. */
        const val REMOVE_RULE_JS =
            """() => {
                 const hits = Array.from(document.styleSheets).flatMap(s => Array.from(s.cssRules).flatMap((r, index) =>
                   r.type === CSSRule.STYLE_RULE && r.selectorText === '.section-note'
                     ? [{href: s.href, index, rule: r.cssText}] : []));
                 if (hits.length !== 1) throw new Error('expected exactly one .section-note rule, got ' + hits.length);
                 const saved = hits[0]; Array.from(document.styleSheets).find(s => s.href === saved.href).deleteRule(saved.index);
                 return saved;
               }"""

        /**
         * Per note: the computed max-width (`cap`), the token's width read by a probe inside the
         * note (attached, measured, removed), then the note's own width and its container's
         * content width. Per tutorial-step paragraph: the same probe and width, for the print.
         */
        const val SWEEP_JS =
            """() => {
                 const round = (n) => Math.round(n * 10) / 10;
                 const label = (el) => { const sec = el.closest('section'); const h = sec && sec.querySelector('h2[id]');
                   const i = Array.from(el.parentElement.children).indexOf(el);
                   return (h ? '#' + h.id : '?') + ' p:nth-child(' + (i + 1) + ')'; };
                 const tokenWidth = (el) => { const probe = document.createElement('div');
                   probe.style.setProperty('display', 'block'); probe.style.setProperty('width', 'var(--site-measure)');
                   el.appendChild(probe); const w = probe.getBoundingClientRect().width; probe.remove(); return w; };
                 const content = (c) => { const s = getComputedStyle(c);
                   return c.getBoundingClientRect().width - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight); };
                 const read = (p) => { const probe = tokenWidth(p); const maxWidth = getComputedStyle(p).maxWidth;
                   return { label: label(p), maxWidth: maxWidth, cap: maxWidth === 'none' ? -1 : round(parseFloat(maxWidth)),
                            probe: round(probe), width: round(p.getBoundingClientRect().width),
                            content: round(content(p.parentElement)) }; };
                 const notes = Array.from(document.querySelectorAll('section.section > .container > p.section-note')).map(read);
                 const steps = Array.from(document.querySelectorAll('.workflow-steps .container > p')).map(p => {
                   const r = read(p); return { label: r.label, width: r.width, cap: r.probe }; });
                 return { notes: notes, steps: steps,
                          docOver: document.documentElement.scrollWidth - document.documentElement.clientWidth };
               }"""
    }
}
