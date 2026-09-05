package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog
import co.datapipelines.web.ui.DocsController
import co.datapipelines.web.ui.SiteController
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.ui.ExtendedModelMap
import org.springframework.ui.Model
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * Renders any public page THROUGH ITS REAL CONTROLLER, offline (073).
 *
 * One renderer, three consumers: the SEO guards, the claim/heading/alt sweeps, and the
 * `websiteExport` static fallback. That is deliberate — the alternative is a guard that
 * asserts against a model it assembled itself, which proves the template renders and
 * nothing about what the app serves. Here the handler picks the view, fills the model and
 * sets the headers; the only thing mocked is the servlet container.
 *
 * Lives in the test source set because the offline render needs spring-test's mock web
 * context, exactly like `SiteExportMain` (which now delegates to it).
 */
object SitePageRenderer {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private val siteController = SiteController()
    private val pagesController = SitePagesController()

    /** The packaged docs, memoized once per JVM like the production bean. */
    val docs: DocsCatalog by lazy { DocsCatalog(javaClass.classLoader) }

    private val docsController by lazy { DocsController(docs) }

    /** The rendered HTML of one registry page, exactly as the app would serve it anonymously. */
    fun render(page: SitePage): String {
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()
        val view =
            when {
                page.path == SitePages.HOME.path -> siteController.home(model, response)
                page.path == SitePages.PILLAR.path -> pagesController.pillar(model, response)
                page.path.startsWith(SitePages.ENGINE_PREFIX) ->
                    checkNotNull(
                        pagesController
                            .engine(page.path.removePrefix(SitePages.ENGINE_PREFIX), model, response)
                            .viewName,
                    ) { "no view for engine page ${page.path}" }
                page.path == SitePages.ADD_TO_CLAUDE_CODE.path -> pagesController.addToClaudeCode(model, response)
                page.path == SitePages.AI_DATA_PIPELINE.path -> pagesController.aiDataPipeline(model, response)
                page.path == SitePages.TEXT_TO_SQL_AGENT.path -> pagesController.textToSqlAgent(model, response)
                page.path == SitePages.COMPARE_AIRFLOW.path -> pagesController.compareAirflow(model, response)
                page.path == SitePages.COMPARE_DBT.path -> pagesController.compareDbt(model, response)
                page.path == SitePages.FEDERATED_QUERY.path -> pagesController.federatedQuery(model, response)
                else -> error("SitePageRenderer has no handler for ${page.path} — add it beside the controller's")
            }
        return process(view, model)
    }

    /** The anonymous docs index, through [DocsController]. */
    fun renderDocsIndex(): String {
        val model = ExtendedModelMap()
        val view = docsController.index(model, MockHttpServletResponse())
        return process(view, model)
    }

    /** One anonymous doc page, through [DocsController]. */
    fun renderDoc(slug: String): String {
        val model = ExtendedModelMap()
        val mav = docsController.doc(slug, model, MockHttpServletResponse())
        return process(checkNotNull(mav.viewName) { "no view for doc $slug" }, model)
    }

    private fun process(
        view: String,
        model: Model,
    ): String {
        val context = webContext()
        model.asMap().forEach { (k, v) -> context.setVariable(k, v) }
        return engine.process(view, context)
    }

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
