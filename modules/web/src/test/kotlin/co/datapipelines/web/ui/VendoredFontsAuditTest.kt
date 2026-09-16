package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

/**
 * 079 §G — the vendored webfonts, audited from the CLASSPATH rather than recalled.
 *
 * The app names "Inter" and "JetBrains Mono" in every theme's font stack but shipped
 * neither file, so it rendered in whatever a visitor's machine happened to have. The
 * families are now vendored under `static/vendor/fonts` (Inter and JetBrains Mono in
 * 079, Manrope for the site in 093, IBM Plex Mono — the site's 133 display face — in
 * 133), from the projects' own release assets, and this audit is the guard on that
 * arrangement. (A glob written into a KDoc opens a NESTED block comment — Kotlin nests
 * them — so the directories are spelled out here rather than learning that again.)
 * Four claims, each of which has a way of quietly becoming false:
 *
 *  1. **Every file the manifest declares is on the classpath.** A `.gitignore` rule, a
 *     partial `git add`, or a resource-filtering change drops a binary silently — the page
 *     then 404s the font and falls back, which looks like "the design changed slightly"
 *     rather than like a bug.
 *  2. **Every hash matches.** A re-download from a different release, or an editor that
 *     "helpfully" normalises a binary, is caught here and not by a reviewer's eye.
 *  3. **Each family carries its OFL.txt.** The SIL Open Font License requires the licence
 *     to travel with the font; the AGPL-3.0 distribution of this app redistributes both.
 *     Upstream Inter names the file LICENSE.txt — it is vendored verbatim as OFL.txt so
 *     one rule covers both directories (recorded in the manifest's `license_file_upstream`).
 *  4. **No stylesheet or template reaches a font host over the network.** The whole point
 *     of vendoring is that no visitor's browser is made to talk to fonts.googleapis.com;
 *     the approved MOCK for this round loads exactly that, so the copy-paste route from
 *     mock to template is a live risk, not a hypothetical one (MISTAKES.md, "Frontend
 *     Dependencies via CDN").
 *
 * The manifest is the single source of truth in both directions: this test derives the
 * expected file list FROM it, so adding a face without recording it fails claim 1 the
 * moment the @font-face references it, and recording one without shipping it fails here.
 */
class VendoredFontsAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val manifest: String =
        resolver
            .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
            .inputStream
            .readBytes()
            .decodeToString()

    /**
     * `path to sha256` for every file the two font blocks declare, read out of the manifest
     * without a JSON dependency: the block is located by its key, and the `sha256` object's
     * `"path": "hex"` pairs are pulled out of it. Deliberately naive — if the manifest's
     * shape changes this returns nothing and the non-vacuity test below fails loudly.
     */
    private fun declaredHashes(family: String): Map<String, String> {
        val start = manifest.indexOf("\"$family\"")
        if (start < 0) return emptyMap()
        val shaAt = manifest.indexOf("\"sha256\"", start)
        if (shaAt < 0) return emptyMap()
        val open = manifest.indexOf('{', shaAt)
        val close = manifest.indexOf('}', open)
        if (open < 0 || close < 0) return emptyMap()
        return PAIR
            .findAll(manifest.substring(open, close))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val declared: Map<String, String> =
        declaredHashes("inter") + declaredHashes("jetbrains-mono") + declaredHashes("manrope") + declaredHashes("ibm-plex-mono")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the manifest declares the four families and every file this round vendored`() {
        // Non-vacuity: a parse that stopped matching would make every assertion below pass
        // by auditing nothing. All four families, and the exact file set, are named here.
        declared.keys.sorted() shouldContainExactly
            listOf(
                "vendor/fonts/ibm-plex-mono/OFL.txt",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-500-normal.woff2",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-600-normal.woff2",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-700-normal.woff2",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-ext-500-normal.woff2",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-ext-600-normal.woff2",
                "vendor/fonts/ibm-plex-mono/ibm-plex-mono-latin-ext-700-normal.woff2",
                "vendor/fonts/inter/InterVariable-Italic.woff2",
                "vendor/fonts/inter/InterVariable.woff2",
                "vendor/fonts/inter/OFL.txt",
                "vendor/fonts/jetbrains-mono/JetBrainsMono-Medium.woff2",
                "vendor/fonts/jetbrains-mono/JetBrainsMono-Regular.woff2",
                "vendor/fonts/jetbrains-mono/JetBrainsMonoSite-Medium.woff2",
                "vendor/fonts/jetbrains-mono/JetBrainsMonoSite-Regular.woff2",
                "vendor/fonts/jetbrains-mono/OFL.txt",
                "vendor/fonts/manrope/OFL.txt",
                "vendor/fonts/manrope/manrope-latin-ext-wght-normal.woff2",
                "vendor/fonts/manrope/manrope-latin-wght-normal.woff2",
            )
        manifest shouldContain "\"license\": \"OFL-1.1\""
    }

    @Test
    fun `every declared font file is on the classpath and hashes as recorded`() {
        declared.keys.shouldNotBeEmpty()

        val problems =
            declared.mapNotNull { (path, want) ->
                val resource = resolver.getResource("classpath:static/$path")
                when {
                    !resource.exists() -> {
                        "$path is declared in the manifest but MISSING from the classpath"
                    }

                    else -> {
                        val got = sha256(resource.inputStream.readBytes())
                        if (got == want) null else "$path HASH MISMATCH (manifest=$want actual=$got)"
                    }
                }
            }
        problems shouldBe emptyList()
    }

    @Test
    fun `each family ships the OFL text, not an empty placeholder`() {
        listOf("inter", "jetbrains-mono", "manrope", "ibm-plex-mono").forEach { dir ->
            val licence =
                resolver
                    .getResource("classpath:static/vendor/fonts/$dir/OFL.txt")
                    .inputStream
                    .readBytes()
                    .decodeToString()
            licence shouldContain "SIL Open Font License, Version 1.1"
            // The heading text differs between the two upstreams ("PERMISSION AND
            // CONDITIONS" vs "PERMISSION & CONDITIONS"), which is exactly why the
            // assertion is on the licence banner and the TERMINATION clause instead.
            licence shouldContain "SIL OPEN FONT LICENSE Version 1.1"
            licence shouldContain "TERMINATION"
            licence.length shouldBeGreaterThanOrEqual MIN_LICENCE_CHARS
        }
    }

    @Test
    fun `no stylesheet or template fetches a font over the network`() {
        val sources =
            (
                resolver.getResources("classpath*:static/**/*.css").toList() +
                    resolver.getResources("classpath*:templates/**/*.html").toList()
            ).filter { it.filename != null }

        // Non-vacuity: the sweep must actually reach the app's stylesheets and templates.
        sources.size shouldBeGreaterThanOrEqual MIN_SWEPT_SOURCES

        val violations =
            sources.flatMap { resource ->
                val text = resource.inputStream.readBytes().decodeToString()
                FONT_HOSTS
                    .filter { text.contains(it) }
                    .map { "${resource.filename} references the font host $it — vendor it instead (§G)" }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `app css declares the four faces against the vendored files`() {
        val css =
            resolver
                .getResource("classpath:static/css/app.css")
                .inputStream
                .readBytes()
                .decodeToString()

        // Relative, never absolute: app.css is served at /css/app.css, so `../vendor/...`
        // survives a non-root context path. An absolute `/vendor/...` would 404 there —
        // the same trap partials/theme-swap.html documents for the theme stylesheet.
        // The JetBrainsMonoSite-* subsets are the SITE's files (site-fonts-mono.css) —
        // the app keeps the full faces, so they are excluded here.
        (declaredHashes("inter") + declaredHashes("jetbrains-mono"))
            .keys
            .filter { it.endsWith(".woff2") && !it.contains("JetBrainsMonoSite") }
            .forEach { path -> css shouldContain "url(\"../${path}\")" }
        css.split("@font-face").size - 1 shouldBe EXPECTED_FACES
        // Every face declares `optional` (090 §B, was 079's `swap`): text must never be
        // invisible while a font downloads, AND a face that arrives after the paint must
        // not reflow it — measured at +8.2px on the dashboard heading under `swap`, which
        // is text changing after it has been read. The DECLARATION is counted (trailing
        // semicolon), not the phrase: the block comment above the faces names the property
        // several times, and a prose mention is not a guarantee.
        css.split("font-display: optional;").size - 1 shouldBe EXPECTED_FACES
        // And the property the round replaced is gone, so a half-applied revert cannot
        // leave two faces on each policy with the count still adding up.
        css shouldNotContain "font-display: swap;"
    }

    /**
     * Site v2: the marketing site declares ITS faces in site.css. Since 145 that is ONE
     * family — Manrope, the approved preview's display AND body face, at `swap`: the h1 is
     * the LCP element and a fallback headline for a beat beats an invisible one (the 133
     * ruling that put the display face on `swap`, now applied to the one face there is).
     * The IBM Plex Mono display faces 133 vendored are no longer referenced by the site
     * (the files and their manifest rows stay until a vendoring round removes them). The
     * JetBrains Mono code faces live in site-fonts-mono.css (late sheet; 133: the SITE-ONLY
     * latin subsets). The app keeps Inter; the two sheets never load together.
     */
    @Test
    fun `site css declares the marketing faces against the vendored files`() {
        val site =
            resolver
                .getResource("classpath:static/site/css/site.css")
                .inputStream
                .readBytes()
                .decodeToString()
        val mono =
            resolver
                .getResource("classpath:static/site/css/site-fonts-mono.css")
                .inputStream
                .readBytes()
                .decodeToString()

        declaredHashes("manrope")
            .keys
            .filter { it.endsWith(".woff2") }
            .forEach { path -> site shouldContain "url(\"../../${path}\")" }
        site.split("@font-face").size - 1 shouldBe EXPECTED_SITE_FACES
        // 145: one face, the display face's policy — swap; nothing optional, nothing Plex.
        site.split("font-display: swap;").size - 1 shouldBe EXPECTED_SITE_FACES
        site shouldNotContain "font-display: optional;"
        site shouldNotContain "ibm-plex-mono"

        declaredHashes("jetbrains-mono")
            .keys
            .filter { it.endsWith("Site-Regular.woff2") || it.endsWith("Site-Medium.woff2") }
            .forEach { path -> mono shouldContain "url(\"../../${path}\")" }
        mono.split("@font-face").size - 1 shouldBe EXPECTED_MONO_FACES
        mono.split("font-display: optional;").size - 1 shouldBe EXPECTED_MONO_FACES

        site shouldNotContain "JetBrainsMono"
        mono shouldNotContain "font-display: swap;"
    }

    private companion object {
        val PAIR = Regex("""\"([^\"]+)\"\s*:\s*\"([0-9a-f]{64})\"""")

        /** fonts.googleapis / fonts.gstatic are the mock's route in; typekit/fontawesome are the neighbours. */
        val FONT_HOSTS =
            listOf(
                "fonts.googleapis",
                "fonts.gstatic",
                "use.typekit.net",
                "fonts.bunny.net",
                "cdn.jsdelivr.net/npm/@fontsource",
            )

        /** The app-side sheet still declares all four faces (the app keeps Inter; the two sheets never load together). */
        const val EXPECTED_FACES = 4

        /** site.css: the Manrope pair (latin, latin-ext) — the one family the site says everything in (145). */
        const val EXPECTED_SITE_FACES = 2
        const val EXPECTED_MONO_FACES = 2
        const val MIN_LICENCE_CHARS = 3_000
        const val MIN_SWEPT_SOURCES = 30
    }
}
