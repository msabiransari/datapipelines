package co.datapipelines.web.ui

import co.datapipelines.mcp.McpToolCatalog
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.io.File

/**
 * 033 Decision 3 — the S3 cold fallback. Renders the marketing page through the SAME
 * Thymeleaf template the app serves (`templates/site/index.html`) with its facts baked in,
 * so an emergency static upload can never drift from the app's own markup. Invoked by the
 * `websiteExport` Gradle task (modules/web/build.gradle.kts); lives in the test source set
 * because the offline render needs spring-test's mock web context — no production class
 * depends on it.
 *
 * Assets are copied by the task itself (a plain `Copy`); this writes only `index.html`.
 * Links render context-path-free (`/site/...`, `/vendor/...`), which is exactly the bucket
 * layout the fallback upload produces — see docs/deployment.md.
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/website-export")
    val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }
    val exchange =
        JakartaServletWebApplication
            .buildApplication(MockServletContext())
            .buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
    val html =
        engine.process(
            "site/index",
            WebContext(exchange).apply { setVariable("toolCount", McpToolCatalog.NAMES.size) },
        )

    outDir.mkdirs()
    File(outDir, "index.html").writeText(html)
    println("website-export: wrote ${File(outDir, "index.html").absolutePath} (toolCount=${McpToolCatalog.NAMES.size})")
}
