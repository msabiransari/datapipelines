package co.datapipelines.web.ui

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 085 §B icon audit (the SiteAssetAuditTest / VendoredFontsAuditTest pattern, swept not
 * recalled). The app draws EVERY icon from ONE source — the vendored
 * `static/vendor/icons/lucide-sprite.svg` — and three sets must stay equal, in both
 * directions:
 *
 *  1. **Referenced** — every `lucide-sprite.svg#<id>` an app template (excluding the
 *     marketing `templates/site/`) emits, plus the ids the JS builders reference:
 *     `graph.js`/`init.js` assemble `<use>` hrefs by concatenation, so the literal
 *     regex cannot see them — they are recovered from the four places an id can
 *     honestly appear (the `TYPE_ICONS` map, `icon: "…"` fact rows, `iconSvg("…")`
 *     calls, and full literals like toast.js's `#x`). A blunt "quoted word that is a
 *     sprite id" match is deliberately NOT used: init.js quotes `copy` for
 *     `execCommand` and toast.js quotes `info` as a VARIANT, neither an icon
 *     reference — a match that counts those would let a glyph pass as referenced when
 *     nothing draws it.
 *  2. **Vendored** — the sprite's `<symbol>` ids. Nothing referenced may be absent
 *     (a 404 glyph renders as an empty box), and nothing vendored may be unreferenced
 *     (the subset is deliberately exact — a glyph nobody uses is weight and a lie about
 *     what the app draws).
 *  3. **Recorded** — the manifest's `lucide-sprite.subset` list (and the key set of its
 *     per-icon `subset_sha256` map) must equal the sprite's symbol set, so the recorded
 *     provenance cannot drift from the bytes.
 *
 * The second sweep pins the retirement the round made: no `&times;` / `&larr;` / `&rarr;`
 * icon-acting entities in app templates — the × and the arrows are sprite icons now, and
 * an entity creeping back is exactly the regression an absence needs a test for (the
 * TemplateTreeRenderTest discipline). `&hellip;`/`&middot;`/`&ndash;` are typography and
 * stay unguarded, as does the literal `→` inside data strings.
 */
class IconSpriteAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val appTemplates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null && "/templates/site/" !in it.url.toString() }
            .associate { it.url.toString().substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    private val spriteIds: Set<String> =
        SYMBOL
            .findAll(
                resolver
                    .getResource("classpath:static/vendor/icons/lucide-sprite.svg")
                    .inputStream
                    .readBytes()
                    .decodeToString(),
            ).map { it.groupValues[1] }
            .toSet()

    /** Template references are full literals; JS references come from icon-reference contexts only. */
    private val referencedIds: Set<String> =
        buildSet {
            appTemplates.values.forEach { html ->
                USE_REF.findAll(html).forEach { add(it.groupValues[1]) }
            }
            JS_ICON_SOURCES.forEach { path ->
                val source =
                    resolver
                        .getResource("classpath:static/$path")
                        .inputStream
                        .readBytes()
                        .decodeToString()
                USE_REF.findAll(source).forEach { add(it.groupValues[1]) }
                FACT_ICON.findAll(source).forEach { add(it.groupValues[1]) }
                ICON_SVG_CALL.findAll(source).forEach { add(it.groupValues[1]) }
                TYPE_ICONS
                    .find(source)
                    ?.let { block ->
                        TYPE_ICON_VALUE.findAll(block.groupValues[1]).forEach { add(it.groupValues[1]) }
                    }
            }
        }

    @Test
    fun `the sweep is non-vacuous - templates, JS sources and a real sprite`() {
        appTemplates.size shouldBeGreaterThan 30
        spriteIds.size shouldBeGreaterThan 30
        referencedIds.shouldNotBeEmpty()
    }

    @Test
    fun `every referenced glyph exists in the sprite`() {
        (referencedIds - spriteIds).sorted() shouldBe emptyList()
    }

    @Test
    fun `every vendored glyph is referenced somewhere - the subset is exact`() {
        (spriteIds - referencedIds).sorted() shouldBe emptyList()
    }

    @Test
    fun `the manifest records exactly the sprite's symbol set`() {
        val manifest =
            ObjectMapper()
                .readTree(
                    resolver
                        .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
                        .inputStream,
                ).get("lucide-sprite")
        manifest.get("subset").map { it.asText() }.toSet() shouldBe spriteIds
        manifest
            .get("subset_sha256")
            .properties()
            .map { it.key }
            .toSet() shouldBe spriteIds
    }

    @Test
    fun `no icon-acting entity survives in an app template - close and arrows are sprite icons`() {
        val offenders =
            appTemplates
                .filter { (_, source) -> ENTITY.any { it in source } }
                .map { (name, _) -> name }
                .sorted()
        offenders shouldBe emptyList()
    }

    private companion object {
        /** The JS files that build icon markup (graph/init by concatenation, toast as a literal). */
        val JS_ICON_SOURCES =
            listOf(
                "js/pipeline-editor/graph.js",
                "js/pipeline-editor/init.js",
                "js/toast.js",
            )

        val SYMBOL = Regex("""<symbol id="([^"]+)"""")
        val USE_REF = Regex("""lucide-sprite\.svg#([a-z0-9-]+)""")

        /** graph.js's fact rows: `{ kind: "source", icon: "db", … }`. */
        val FACT_ICON = Regex("""icon:\s*"([a-z0-9-]+)"""")

        /** graph.js's open-details glyph: `iconSvg("maximize", "ds-icon-xs")`. */
        val ICON_SVG_CALL = Regex("""iconSvg\("([a-z0-9-]+)"""")

        /** The TYPE_ICONS map and its values (`DQL: "db"`). */
        val TYPE_ICONS = Regex("""TYPE_ICONS\s*=\s*\{([^}]*)\}""")
        val TYPE_ICON_VALUE = Regex(""":\s*"([a-z0-9-]+)"""")

        val ENTITY = listOf("&times;", "&larr;", "&rarr;")
    }
}
