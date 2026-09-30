package co.datapipelines.web.ui

import co.datapipelines.web.pipelines.PromotionService
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * The promotion page's parameter-set rows (#313, ui-screens §4.17) — Thymeleaf, no Spring
 * context, the [PipelineLifecycleDialogRenderTest] harness shape.
 *
 * This arm lives beside [RoleVisibilityRenderTest] rather than in it because that class sits
 * at detekt's LargeClass ceiling (the same reason the discard dialog's schedules arm moved to
 * its dialog's render test); the role question it answers is the same one — the sets table
 * rides the SAME guard as the pipelines table, and the reader gets the rows with no control.
 */
class PromotionSetsRenderTest {
    /**
     * #313 — the page's parameter-set rows (the record's §8.3). A promoter gets
     * `name="parameter_set"` checkboxes inside the form; a reader gets the same rows
     * read-only, no input anywhere. When the plan carries no set candidates, no sets table
     * renders for either role.
     */
    @Test
    fun `the parameter-set table renders inside the same role guard as the pipelines table`() {
        val promoter = render { promotionModel() }
        promoter shouldContain "name=\"parameter_set\""
        promoter shouldContain "acme/sales/region_filters"

        // The sets table is inside the form: a reader's main never carries an input and the
        // read-only sets table carries the rows instead.
        val author =
            render {
                promotionModel()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }
        val authorMain = author.substringAfter("<main").substringBefore("</main>")
        authorMain shouldNotContain "name=\"parameter_set\""
        authorMain shouldNotContain "<form"
        authorMain shouldContain "acme/sales/region_filters"

        // An empty set list renders no sets table, either arm.
        render { promotionModel(sets = emptyList()) } shouldNotContain "Parameter sets</h2>"
        render {
            promotionModel(sets = emptyList())
            withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
        } shouldNotContain "Parameter sets</h2>"
    }

    // ------------------------------------------------------------------ harness

    private fun render(fill: WebContext.() -> Unit): String =
        COMMENT.replace(engine().process("promotion/index", context().apply(fill)), "")

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

    private fun WebContext.promotionModel(sets: List<PromotionService.Candidate> = PROMOTION_SET_CANDIDATES) {
        setVariable("currentPath", "/promotion")
        setVariable("hasTarget", true)
        setVariable("targetBaseUrl", "https://prod.example")
        setVariable("plan", promotionPlan(sets))
    }

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        /** The promotion plan the screen reads (055, `PromotionService.Plan`) — the real type. */
        fun promotionPlan(sets: List<PromotionService.Candidate>) =
            PromotionService.Plan(
                targetBaseUrl = "https://prod.example",
                targetDeployment = "prod",
                targetAuthoringEnabled = false,
                workspace = "acme",
                promotable =
                    listOf(
                        PromotionService.Candidate(
                            name = "nyc/mobility/revenue_by_borough",
                            displayName = "Revenue by borough",
                            localVersion = 2,
                            targetVersion = 1,
                        ),
                    ),
                promotableParameterSets = sets,
                promotableVisualizations = emptyList(),
                promotableDashboards = emptyList(),
                examined = 4,
            )

        /** #313 — the page's set rows: one promotable constants-only set. */
        val PROMOTION_SET_CANDIDATES =
            listOf(
                PromotionService.Candidate(
                    name = "acme/sales/region_filters",
                    displayName = "Region filters",
                    localVersion = 4,
                    targetVersion = 3,
                ),
            )
    }
}
