package co.datapipelines.browser

import com.microsoft.playwright.Page

/**
 * #788 — the resource browser's width range as the LIVE page holds it: app.css's `:root`
 * custom properties read as computed styles. The stylesheet is the range's one source
 * (`rail.js` and `browser-resize.mjs` re-derive it at run time the same way), so a browser
 * case derives its expectations from this read instead of restating the numbers — a value
 * change in app.css re-derives the suite without a test edit.
 *
 * [min]/[max] are the user's range (`--app-browser-min` / `--app-browser-max`),
 * [bandDefault] the band default IN EFFECT at the current viewport (the width bands
 * re-declare it inside their media rules — the value the page answers with),
 * [mainMin] what `<main>` keeps beside the browser (`--app-main-min-width`), the fixed
 * input of the maximum arithmetic the separator's geometry tests share.
 */
internal data class BrowserRange(
    val min: Int,
    val max: Int,
    val bandDefault: Int,
    val mainMin: Int,
) {
    /** The effective default: the band's value, lifted to the range's floor by the stylesheet's clamp. */
    val effectiveDefault: Int get() = maxOf(min, bandDefault)

    /** The widest the browser may be at [viewport] beside a [menu]-wide rail — the clamp's cap. */
    fun maximumAt(
        viewport: Int,
        menu: Int,
    ): Int = maxOf(min, minOf(max, viewport - menu - mainMin))
}

internal fun browserRange(page: Page): BrowserRange {
    val read =
        page.evaluate(
            """() => { const s = getComputedStyle(document.documentElement);
                  const px = (name) => Number.parseFloat(s.getPropertyValue(name));
                  return { min: px('--app-browser-min'), max: px('--app-browser-max'),
                    default: px('--app-browser-default'), mainMin: px('--app-main-min-width') }; }""",
        ) as Map<*, *>

    fun bound(name: String): Int {
        val value = (read[name] as? Number)?.toDouble()
        check(value != null && value > 0.0) {
            "$name did not resolve to a positive px value on the page (read: ${read[name]}) — app.css is the range's one source"
        }
        return value.toInt()
    }
    val min = bound("min")
    val max = bound("max")
    check(min <= max) { "the browser's range is inverted on the page: min=$min max=$max" }
    return BrowserRange(min = min, max = max, bandDefault = bound("default"), mainMin = bound("mainMin"))
}
