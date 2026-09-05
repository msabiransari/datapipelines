package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.SITE_ORIGIN
import co.datapipelines.web.ui.site.SitePageRenderer
import co.datapipelines.web.ui.site.SitePages
import co.datapipelines.web.ui.site.SitemapXml
import java.io.File

/**
 * 033 Decision 3 — the S3 cold fallback. Renders the public site through the SAME templates
 * AND the same controllers the app serves ([SitePageRenderer]), so an emergency static
 * upload can never drift from what the application does. Invoked by the `websiteExport`
 * Gradle task (modules/web/build.gradle.kts); lives in the test source set because the
 * offline render needs spring-test's mock web context — no production class depends on it.
 *
 * 073 widened it from one page to the whole public surface: the homepage, every intent-cluster
 * page, the public docs index and every packaged doc, plus `robots.txt` and a `sitemap.xml`.
 * Each page is written as `<path>/index.html`, which is the layout S3 static hosting and
 * CloudFront's default-root-object behaviour serve without rewrite rules — and the same
 * shape the app's own routes present.
 *
 * The exported `sitemap.xml` carries no `lastmod`: the build timestamp comes from a Spring
 * bean the app has and this process does not, and an invented date is worse than an absent
 * optional field.
 *
 * Assets are copied by the Gradle task itself (a plain `Copy`); this writes only HTML and
 * the two text files.
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/website-export")
    outDir.mkdirs()

    var pages = 0
    SitePages.ALL.forEach { page ->
        writePage(outDir, page.path, SitePageRenderer.render(page))
        pages++
    }

    writePage(outDir, "/docs", SitePageRenderer.renderDocsIndex())
    pages++
    val slugs = SitePageRenderer.docs.index().flatMap { group -> group.docs.map { it.slug } }
    slugs.forEach { slug ->
        writePage(outDir, "/docs/$slug", SitePageRenderer.renderDoc(slug))
        pages++
    }

    val robots =
        checkNotNull(SiteExportMarker::class.java.classLoader.getResourceAsStream("static/robots.txt")) {
            "static/robots.txt is not on the classpath — the export cannot invent one"
        }.use { it.readBytes().decodeToString() }
    File(outDir, "robots.txt").writeText(robots)

    val locations =
        SitePages.ALL.map { it.canonical } +
            listOf("$SITE_ORIGIN/docs") +
            slugs.map { "$SITE_ORIGIN/docs/$it" }
    File(outDir, "sitemap.xml").writeText(SitemapXml.render(locations, lastmod = null))

    println("website-export: wrote $pages pages + robots.txt + sitemap.xml (${locations.size} urls) to ${outDir.absolutePath}")
}

/** `/` becomes `index.html`; everything else becomes `<path>/index.html`. */
private fun writePage(
    outDir: File,
    path: String,
    html: String,
) {
    val target =
        if (path == "/") {
            File(outDir, "index.html")
        } else {
            File(outDir, path.trim('/')).also { it.mkdirs() }.let { File(it, "index.html") }
        }
    target.parentFile.mkdirs()
    target.writeText(html)
}

/** Class-literal anchor for the classloader lookup above; Kotlin top-level functions have no owner type to name. */
private class SiteExportMarker
