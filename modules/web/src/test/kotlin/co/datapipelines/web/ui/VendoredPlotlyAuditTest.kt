package co.datapipelines.web.ui

import co.datapipelines.visualization.RendererConfigValidators
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

/**
 * #10 L3a — Plotly 4.1.1, vendored as two custom bundles and its strict-CSP stylesheet,
 * audited from the CLASSPATH (the [VendoredAlpineAuditTest] shape). The claims below are the
 * ones that could quietly become false and put a CDN script, a stale bundle or an unmeasured
 * CSP need back into the dashboards surface:
 *
 *  1. **The bytes are the ones recorded.** The manifest's sha256 per file is the built
 *     bundle's; a rebuild that changed one byte goes red here, and so does an edit of a
 *     committed bundle that does not move the manifest.
 *  2. **The trace lists are the spec's** (the implementation spec's §10.4, D63) — the same
 *     lists [RendererConfigValidators] validates configurations against, read from THAT
 *     module's constants so the manifest cannot drift from the validator.
 *  3. **The stylesheet is the release build's own strict-CSP sheet**, the one the host loads
 *     so Plotly's runtime injection can be skipped (`.no-inline-styles`): it must carry the
 *     rules the runtime would otherwise inject, or the charts would render unstyled while
 *     every test still passes on a machine that never enforced the policy.
 *  4. **The first-party runtime and adapters are versioned here too** (§10.1's new
 *     convention): their hashes are recomputed from the served bytes, so a hand edit of a
 *     shipped file must move the manifest in the same commit.
 *  5. **No template loads Plotly from anywhere but the vendored path** — no CDN, in any
 *     template, under any spelling.
 */
class VendoredPlotlyAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val manifest: String =
        resolver
            .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
            .inputStream
            .readBytes()
            .decodeToString()

    private fun classpathBytes(path: String): ByteArray = resolver.getResource("classpath:static/$path").inputStream.readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun block(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\{").find(manifest)?.let { braceFrom(it.range.last) }
            ?: error("the $name block is missing from the vendor manifest — the audit ran vacuously")

    /** The balanced-brace body from the OPENING brace at [start] — flat blocks nest (`files`, `sha256`). */
    private fun braceFrom(start: Int): String {
        var depth = 0
        var end = start
        while (end < manifest.length) {
            when (manifest[end]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return manifest.substring(start, end + 1)
                }
            }
            end++
        }
        error("the manifest block starting at $start never closes")
    }

    @Test
    fun `each vendored plotly file matches the hash the manifest records`() {
        val plotly = block("plotly")
        plotly shouldContain "\"version\": \"4.1.1\""
        plotly shouldContain "\"license\": \"MIT\""
        FILES.forEach { file ->
            val declared = Regex("\"$file\"\\s*:\\s*\"([0-9a-f]{64})\"").find(plotly)?.groupValues?.get(1)
            withClue("the manifest declares a sha256 for $file") { declared shouldNotBe null }
            sha256(classpathBytes(file)) shouldBe declared
        }
    }

    @Test
    fun `the manifest's trace lists are the spec's - the same lists the config validator holds`() {
        val traces = Regex("\"traces\"\\s*:\\s*\\{.*?\\n    \\}", RegexOption.DOT_MATCHES_ALL).find(block("plotly"))?.value
        withClue("the plotly block declares its per-bundle trace lists") { traces shouldNotBe null }

        val twoD = Regex("\"plotly-2d.min.js\"\\s*:\\s*\\[[^]]*\\]").find(traces!!)?.groupValues?.get(0)
        val threeD = Regex("\"plotly-3d.min.js\"\\s*:\\s*\\[[^]]*\\]").find(traces)?.groupValues?.get(0)
        withClue("both bundles name their traces") {
            (twoD != null) shouldBe true
            (threeD != null) shouldBe true
        }
        val declared2D = QUOTED_WORDS.findAll(twoD!!).map { it.groupValues[1] }.toList()
        val declared3D = QUOTED_WORDS.findAll(threeD!!).map { it.groupValues[1] }.toList()
        declared2D shouldContainExactlyInAnyOrder RendererConfigValidators.PLOTLY_2D_TRACES
        declared3D shouldContainExactlyInAnyOrder RendererConfigValidators.PLOTLY_TRACES
    }

    @Test
    fun `the vendored stylesheet is the release build's strict-CSP sheet - the rules the runtime would inject`() {
        // The runtime's injected rules are exactly this sheet (the build compiles both from
        // src/css at the same tag); the host page loads it as a real stylesheet so the
        // pre-placed `.no-inline-styles` element can make the injection a no-op. A plotly.css
        // that lost those prefixes would strand every chart unstyled under the design-around.
        val css = classpathBytes(PLOTLY_CSS).decodeToString()
        css shouldContain ".js-plotly-plot .plotly"
        css shouldContain ".plotly-notifier"
    }

    @Test
    fun `the first-party runtime and adapters match the hashes the manifest records`() {
        val firstParty = block("datapipelines-dashboard-runtime")
        FIRST_PARTY_FILES.forEach { file ->
            val declared = Regex("\"$file\"\\s*:\\s*\"([0-9a-f]{64})\"").find(firstParty)?.groupValues?.get(1)
            withClue("$file must be hash-pinned in the manifest (a PENDING placeholder is not a hash)") {
                (declared != null && !declared.startsWith("PENDING")) shouldBe true
            }
            sha256(classpathBytes(file)) shouldBe declared
        }
    }

    @Test
    fun `no template loads plotly from anywhere but the vendored path`() {
        resolver.getResources("classpath:templates/**/*.html").forEach { resource ->
            val html = resource.inputStream.readBytes().decodeToString()
            withClue(resource.filename ?: "template") {
                html shouldNotContain "cdn.plot.ly"
                html shouldNotContain "cdn.jsdelivr.net/npm/plotly"
                html shouldNotContain "unpkg.com/plotly"
            }
        }
    }

    private companion object {
        const val PLOTLY_CSS = "vendor/plotly/plotly.css"
        val FILES =
            listOf(
                "vendor/plotly/plotly-2d.min.js",
                "vendor/plotly/plotly-3d.min.js",
                PLOTLY_CSS,
            )
        val FIRST_PARTY_FILES =
            listOf(
                "js/datapipelines-dashboard.js",
                "js/datapipelines-dashboard-plotly.js",
                "js/datapipelines-dashboard-table.js",
                "js/datapipelines-dashboard-kpi.js",
            )
        val QUOTED_WORDS = Regex("\"([a-z0-9]+)\"")
    }
}
