package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.matchers.shouldBe
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
 * 7d (#7, transform-nodes design §9.3/§9.5) — the transform face as markup: the editor page
 * paints it in place of the Freemarker source column, and every user-supplied string — the
 * four panes, a case name, a diff side, a refusal — renders as TEXT. The escaping cases plant
 * markup in each and require it to come back inert.
 */
class TransformFaceRenderTest {
    @Test
    fun `the editor page paints the face for a transform - no rail, no Freemarker preview, the select swaps the face`() {
        val html = render("templates/editor") { page() }

        html shouldContain "class=\"te-source tf-face\""
        html shouldContain "te-body-no-rail"
        html shouldNotContain "Render Context"
        html shouldNotContain "id=\"previewBtn\""
        html shouldNotContain "id=\"templateBody\""
        html shouldContain "hx-get=\"/partials/templates/transform-face?name=test/shape/order_lines.jsonata\""
        html shouldContain "src=\"/js/template-transform-face.js\""
        // Four panes, the language named on the body.
        listOf("tf-body", "tf-contract", "tf-invariants", "tf-tests").forEach { id -> html shouldContain "id=\"$id\"" }
        html shouldContain ">JSONata</span>"
        html shouldContain "name=\"bodyHash\" value=\"hash-v1-0000000000\""
    }

    @Test
    fun `the needs-review marker renders in the editor header only when its flag is set`() {
        render("templates/editor") { page() } shouldNotContain "data-needs-review"
        render("templates/editor") {
            page()
            setVariable("needsReview", true)
        } shouldContain "data-needs-review"
    }

    @Test
    fun `every pane is text - markup planted in the body and a block comes back inert`() {
        val planted = "</textarea><script>alert(1)</script>"
        val html =
            render(TransformFace.VIEW) {
                face()
                setVariable("panes", TransformPanes(planted, "{\"x\": \"<img src=x onerror=alert(2)>\"}", "[]", "[]"))
            }

        html shouldNotContain "<script>alert(1)</script>"
        html shouldNotContain "<img src=x"
        html shouldContain "&lt;/textarea&gt;&lt;script&gt;alert(1)&lt;/script&gt;"
        html shouldContain "&lt;img src=x onerror=alert(2)&gt;"
    }

    @Test
    fun `a read-only face renders the same panes as pre blocks, inert too`() {
        val html =
            render(TransformFace.VIEW) {
                face(editable = false)
                setVariable("panes", TransformPanes("<b>bold</b>", "{}", "[]", "[]"))
            }

        html shouldContain "id=\"tf-body-ro\""
        html shouldContain "&lt;b&gt;bold&lt;/b&gt;"
        html shouldNotContain "<textarea"
    }

    @Test
    fun `the run-suite list shows each case's verdict, the difference and every invariant - all as text`() {
        val suite =
            SuiteResult(
                saveAccepted = false,
                saveCode = "template.test_failed",
                cases =
                    listOf(
                        CaseResult(
                            name = "empty input",
                            passed = true,
                            invariants = listOf(InvariantResult("one_to_one", true, "never lost")),
                        ),
                        CaseResult(
                            name = "<img src=x onerror=alert(3)>",
                            passed = false,
                            diffPath = "$.rejects[0].reason",
                            expected = "\"customer_id missing\"",
                            actual = "\"<script>alert(4)</script>\"",
                            invariants = listOf(InvariantResult("customer_present", false, "every row <b>has</b> a customer")),
                        ),
                        CaseResult(name = "late", passed = false, notRun = true),
                    ),
            )
        val html = render(TransformFace.RESULT_VIEW) { result(suite = suite) }

        html shouldContain "1 of 3 cases pass"
        html shouldContain "Save would refuse them with <code>template.test_failed</code>"
        html shouldContain "data-tf-passed=\"true\""
        html shouldContain "data-tf-passed=\"false\""
        html shouldContain "<code data-tf-diff-path>$.rejects[0].reason</code>"
        html shouldContain "<pre class=\"tf-diff\" data-tf-expected>&quot;customer_id missing&quot;</pre>"
        html shouldContain "&lt;script&gt;alert(4)&lt;/script&gt;"
        html shouldNotContain "<script>alert(4)"
        html shouldNotContain "<img src=x"
        html shouldContain "every row &lt;b&gt;has&lt;/b&gt; a customer"
        html shouldContain "data-tf-invariant=\"customer_present\" data-tf-holds=\"false\""
        html shouldContain "not run — the suite timeout was reached"
    }

    @Test
    fun `a save refusal names its pane, 7b's code and the detail`() {
        val html =
            render(TransformFace.RESULT_VIEW) {
                result(refusals = listOf(FaceRefusal("tests", "template.test_failed", "Test case 'x' failed: <b>diff</b>", "case: x")))
                setVariable("saveRefused", true)
            }

        html shouldContain "Save refused — the draft was not changed."
        html shouldContain "data-tf-pane=\"tests\""
        html shouldContain "<code class=\"tf-code\">template.test_failed</code>"
        html shouldContain "&lt;b&gt;diff&lt;/b&gt;"
        html shouldContain "case: x"
    }

    @Test
    fun `a static refusal from the gate says the suite did not run and lists no cases`() {
        val suite =
            SuiteResult(
                saveAccepted = false,
                saveCode = "template.contract_invalid",
                refusals = listOf(FaceRefusal("contract", "template.contract_invalid", "rejects needs a table output", null)),
            )
        val html = render(TransformFace.RESULT_VIEW) { result(suite = suite) }

        html shouldContain "data-tf-outcome=\"static\""
        html shouldContain "data-tf-pane=\"contract\""
        (html.contains("class=\"tf-cases\"")) shouldBe false
    }

    // ------------------------------------------------------------------ fixtures

    private fun WebContext.face(editable: Boolean = true) {
        val stored =
            TransformFixtures.storedSkeleton(
                status = if (editable) PipelineVersionStatus.DRAFT else PipelineVersionStatus.RELEASED,
            )
        setVariable("template", stored)
        setVariable("templateName", stored.id)
        setVariable("selectedVersion", stored.version)
        setVariable("workingVersion", stored.version)
        setVariable("readOnly", false)
        setVariable("selectedStatus", stored.status.name)
        setVariable("releasedAt", null)
        setVariable("releasedBy", null)
        setVariable("isTransform", true)
        setVariable("faceEditable", editable)
        setVariable("faceHash", stored.bodyHash)
        setVariable("faceHashShort", stored.bodyHash.take(12))
        setVariable("faceLanguage", "JSONata")
        setVariable("panes", TransformPanes.of(stored))
    }

    private fun WebContext.page() {
        face()
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("versions", emptyList<Any>())
        setVariable("hasDraft", true)
        setVariable("draftVersion", 1)
        setVariable("draftHash", "hash-v1-0000000000")
        setVariable("needsReview", false)
    }

    private fun WebContext.result(
        refusals: List<FaceRefusal> = emptyList(),
        suite: SuiteResult? = null,
    ) {
        setVariable("refusals", refusals)
        setVariable("suite", suite)
    }

    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = COMMENT.replace(engine().process(view, context().apply(fill)), "")

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles()

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
    }
}
