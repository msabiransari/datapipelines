package co.datapipelines.web.ui

import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 064 §Part 2.2 — the design-system audit's NON-TEXT twin on the vendored CSS.
 *
 * The design-system repo's `scripts/audit-contrast.js` now checks WCAG 1.4.11 non-text
 * pairs and gates its own build — but a FUTURE re-vendor could carry a failing theme
 * back into this repo while every build here stays green. This test is the guard on THIS
 * side: it reads the VENDORED `themes/` CSS files and re-implements the audit's contrast math
 * (the JS, translated line for line) against the same floors, so the vendored tree and
 * the design-system audit cannot drift apart silently.
 *
 * The floors are 1.4.11, not 1.4.3: structure must survive a bad monitor, not just
 * text. Component boundaries (border-default/hover/focus on the surface they are drawn
 * on) need 3:1; separators (border-subtle) need 2:1 — 3:1 makes every table heavy, the
 * shipped 1.1:1 was invisible on the owner's office monitor; surface-selected needs
 * 1.5:1 and its selection is carried by the border-focus accent bar, because a tint
 * alone cannot reach 3:1 without turning grey.
 */
class VendoredNonTextContrastTest {
    @Test
    fun `every vendored theme passes the non-text structure floors`() {
        val themes = vendoredThemes()
        themes.shouldNotBeEmpty()

        val failures = themes.flatMap { (file, branches) ->
            branches.flatMap { (branch, vars) ->
                val label = if (branches.size > 1) "$file ($branch)" else file
                NON_TEXT_PAIRS.mapNotNull { pair ->
                    val ratio = contrastRatio(vars[pair.fg], vars[pair.bg])
                    if (ratio == null || ratio < pair.min) {
                        "$label: ${pair.name} — ${ratio?.let { "%.2f".format(it) + ":1" } ?: "missing token"} " +
                            "(floor ${pair.min}:1)"
                    } else {
                        null
                    }
                }
            }
        }
        failures shouldBe emptyList()
    }

    @Test
    fun `the guard sees all nine vendored themes and both auto branches - a vacuous pass is not a pass`() {
        val names = vendoredThemes().map { it.first }.toList()
        names.shouldNotBeEmpty()
        names shouldBe
            listOf(
                "auto.css", "dark.css", "forest.css", "healthcare.css", "light.css",
                "minimal.css", "ocean.css", "professional.css", "saas.css",
            )
        // auto.css audits BOTH its light default block and its dark @media block.
        vendoredThemes().first { it.first == "auto.css" }.second.size shouldBe 2
    }

    @Test
    fun `the guard would have failed the pre-round tokens - the math can go red`() {
        // The 2026-09-03 defect, measured: every boundary shipped as a 1.05-1.35:1
        // surface step. The same contrastRatio this guard uses must see those values
        // as failures, or the guard could pass a re-vendored regression.
        val shippedLight = mapOf(
            "border-default" to "#e2e8f0",
            "border-subtle" to "#f1f5f9",
            "surface-selected" to "#e8f0fe",
            "surface-default" to "#ffffff",
        )
        (contrastRatio(shippedLight["border-default"]!!, shippedLight["surface-default"]!!)!! < 3.0) shouldBe true
        (contrastRatio(shippedLight["border-subtle"]!!, shippedLight["surface-default"]!!)!! < 2.0) shouldBe true
        (contrastRatio(shippedLight["surface-selected"]!!, shippedLight["surface-default"]!!)!! < 1.5) shouldBe true

        // And the shipped dark theme — 1.72:1 on border-default — fails 3:1 the same way.
        (contrastRatio("#374151", "#111827")!! < 3.0) shouldBe true
    }

    // ------------------------------------------------------------------ the vendored files

    private fun vendoredThemes(): List<Pair<String, List<Pair<String, Map<String, String>>>>> {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        return resolver
            .getResources("classpath*:static/vendor/design-system/themes/*.css")
            .filter { it.filename != null }
            .onEach { TestRepoFiles.requireInModuleResources("static/vendor/design-system/themes/" + it.filename) }
            .sortedBy { it.filename }
            .map { resource -> resource.filename!! to parseTheme(resource.inputStream.readBytes().decodeToString()) }
    }

    /** scripts/audit-contrast.js's parseTheme — the auto theme audits both branches. */
    private fun parseTheme(content: String): List<Pair<String, Map<String, String>>> {
        val mediaMatch = DARK_MEDIA.find(content)
        if (mediaMatch != null) {
            val defaultBlock = content.substring(0, content.indexOf("@media"))
            return listOf(
                "light" to extractVars(defaultBlock),
                "dark" to extractVars(mediaMatch.groupValues[1]),
            )
        }
        return listOf("default" to extractVars(content))
    }

    /** scripts/audit-contrast.js's extractVars — the private `--_*` declarations, translated. */
    private fun extractVars(cssBlock: String): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        VAR_REGEX.findAll(cssBlock).forEach { match ->
            vars[match.groupValues[1]] = match.groupValues[2].trim()
        }
        return vars
    }

    // ------------------------------------------- the audit script's math, line for line

    private fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
        val match = HEX_REGEX.matchEntire(hex.trim()) ?: return null
        return Triple(
            match.groupValues[1].toInt(16),
            match.groupValues[2].toInt(16),
            match.groupValues[3].toInt(16),
        )
    }

    private fun luminance(rgb: Triple<Int, Int, Int>): Double {
        val linear = listOf(rgb.first, rgb.second, rgb.third).map { v ->
            val vd = v / 255.0
            if (vd <= 0.03928) vd / 12.92 else Math.pow((vd + 0.055) / 1.055, 2.4)
        }
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
    }

    private fun contrastRatio(hex1: String?, hex2: String?): Double? {
        if (hex1 == null || hex2 == null) return null
        val rgb1 = hexToRgb(hex1) ?: return null
        val rgb2 = hexToRgb(hex2) ?: return null
        val l1 = luminance(rgb1)
        val l2 = luminance(rgb2)
        val bright = maxOf(l1, l2)
        val dark = minOf(l1, l2)
        return (bright + 0.05) / (dark + 0.05)
    }

    private companion object {
        /** scripts/audit-contrast.js's NON_TEXT_PAIRS (WCAG 1.4.11 floors), translated. */
        val NON_TEXT_PAIRS =
            listOf(
                NonTextPair("Border Default on Surface Default", "border-default", "surface-default", 3.0),
                NonTextPair("Border Hover on Surface Default", "border-hover", "surface-default", 3.0),
                NonTextPair("Border Focus on Surface Default", "border-focus", "surface-default", 3.0),
                NonTextPair("Border Subtle on Surface Default", "border-subtle", "surface-default", 2.0),
                NonTextPair("Surface Selected vs Surface Default", "surface-selected", "surface-default", 1.5),
            )

        val HEX_REGEX = Regex("""#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})""", RegexOption.IGNORE_CASE)
        val VAR_REGEX = Regex("""--_([a-z0-9-]+):\s*([^;]+);""")
        val DARK_MEDIA = Regex("""@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)\s*\{([\s\S]*)\}""")
    }

    private data class NonTextPair(val name: String, val fg: String, val bg: String, val min: Double)
}
