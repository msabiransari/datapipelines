package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 033 asset audit (the 027b pattern), swept not recalled: every asset reference the
 * marketing template can emit — every `<link>`/`<script>`/`<img>` `href`/`src`, in both
 * `th:*="@{...}"` and plain form — resolves under the app's static resources, and NONE of
 * them is an external/CDN URL (cardinal rule: no runtime third-party CDN dependency; the
 * og:/canonical meta tags are `content=` attributes, not asset loads, and nav anchors to
 * GitHub are links, not assets — both deliberately out of scope).
 *
 * Also pinned: the design-system manifest survived the migration (the website/ copy of the
 * design system is DELETED; the app's vendored copy — and its SHA-256 manifest — is the
 * one sync target now), and the retired website asset tree is really gone from the repo.
 *
 * 073 widened the sweep from the homepage to EVERY template in the `templates/site` directory
 * plus the two public docs templates: the cluster pages and the shared layout load the same
 * stylesheets and the same script, and a broken path on one of nine pages is exactly what a
 * single-page audit stops seeing.
 *
 * (Kotlin nests block comments, so a glob written into a KDoc opens one. Spell directories
 * out here rather than learning that again.)
 */
class SiteAssetAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        (
            resolver.getResources("classpath*:templates/site/*.html").toList() +
                resolver.getResources("classpath*:templates/docs/*-public.html").toList()
        ).filter { it.filename != null }
            .associate { it.filename!! to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep covers every public template`() {
        // Non-vacuity: nine site templates plus the two public docs views. A resolver that
        // stopped matching would make both audits below pass by auditing nothing.
        templates.size shouldBe PUBLIC_TEMPLATES
    }

    @Test
    fun `every asset reference resolves under static resources`() {
        val refs = templates.flatMap { (name, source) -> assetRefs(source).map { name to it } }
        refs.shouldNotBeEmpty()

        val missing =
            refs
                .filter { it.second.startsWith("/") }
                .filter { !resolver.getResource("classpath:static${it.second}").exists() }
                .map { "${it.first} references ${it.second} — no static resource serves it" }
        missing shouldBe emptyList()
    }

    @Test
    fun `no asset is loaded from an external or CDN origin`() {
        val external =
            templates.flatMap { (name, source) ->
                assetRefs(source)
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .map { "$name loads $it" }
            }
        external shouldBe emptyList()
    }

    @Test
    fun `the design-system manifest is intact and the website copy is gone`() {
        val manifest =
            resolver
                .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
                .inputStream
                .readBytes()
                .decodeToString()
        // Non-vacuous: the manifest still pins the design-system block with hashes.
        manifest.contains("\"design-system\"") shouldBe true
        manifest.contains("\"sha256\"") shouldBe true
        // The migration must not have left a second vendored copy reachable.
        resolver.getResource("classpath:static/site/vendor/design-system/tokens.css").exists() shouldBe false
    }

    /** Asset-tag references only: href/src of link/script/img, thymeleaf or plain, in source form. */
    private fun assetRefs(source: String): List<String> =
        ASSET_TAG
            .findAll(source)
            // rel="canonical" is metadata, not an asset load — it points at the public origin by design.
            .filter { "rel=\"canonical\"" !in it.value }
            .mapNotNull { ATTR.find(it.value)?.groupValues?.get(1) }
            .map { it.removePrefix("@{").removeSuffix("}") }
            .filter { it.isNotBlank() && !it.startsWith("data:") }
            .toList()

    private companion object {
        /** index + 7 cluster templates + _layout, and the two docs public views. */
        const val PUBLIC_TEMPLATES = 12

        val ASSET_TAG = Regex("""<(?:link|script|img)\b[^>]*>""")
        val ATTR = Regex("""\b(?:th:)?(?:href|src)="([^"]*)"""")
    }
}
