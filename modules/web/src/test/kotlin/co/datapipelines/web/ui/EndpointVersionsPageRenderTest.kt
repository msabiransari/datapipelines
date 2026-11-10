package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * #786 B.3/C.3 — the API versions page and its dialogs, pinned at the RENDER (ui-screens §4.18b, record §24 UX):
 *
 *  - every visible version is a row with its status, its exact pin, `current` and eligibility; selecting one is a LINK
 *    (`?version=n`) — viewing never posts, so it can never switch what the API serves;
 *  - the version card and the API card are separate, and the API card (enabled, bound keys) is the same whichever
 *    version is viewed;
 *  - each verb renders only on the status it applies to and for the row that admits it; authoring-disabled offers no draft;
 *  - the draft picker keeps a noncurrent pin and lists an ineligible version disabled, never hidden;
 *  - the release dialog posts the closure's tree hash and the DRAFT dependencies under consent; a blocker leaves no form.
 */
class EndpointVersionsPageRenderTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000786")

    @Test
    fun `the table lists every version and selecting one is a link that never posts`() {
        val doc = page(page(selected = 2))
        doc.select("[data-api-version]").map { it.attr("data-api-version") } shouldContainExactly listOf("3", "2", "1")
        doc.select("[data-api-version=3] [data-api-status]").text() shouldBe "DRAFT"
        doc.select("[data-api-version=2] [data-api-is-current]").size shouldBe 1
        doc.select("[data-api-version=1]").text() shouldContain "not eligible"
        doc.select("[data-api-version=2]").attr("aria-current") shouldBe "true"
        val link = doc.select("[data-api-version=1] a").single()
        link.attr("href") shouldBe "/api-console/endpoints/$id?version=1"
        link.hasAttr("hx-post") shouldBe false
        doc.select("[data-api-current]").text() shouldBe "serving v2"
        doc.select("[data-api-pin]").text() shouldBe "finance/revenue@2"
    }

    @Test
    fun `the API card is the entity's - the same whichever version is viewed`() {
        val two = page(page(selected = 2)).select("[data-api-entity]").outerHtml()
        val three = page(page(selected = 3)).select("[data-api-entity]").outerHtml()
        two shouldBe three
        val doc = page(page(selected = 3))
        doc.select("[data-api-entity] [data-api-enabled]").text() shouldBe "disabled"
        doc.select("[data-api-entity] [data-api-keys] .app-chip").eachText() shouldContainExactly listOf("ci", "nightly")
    }

    @Test
    fun `each verb renders on its own status and only for the row that admits it`() {
        verbs(page(selected = 3)) shouldContainExactly listOf("edit", "release", "purge")
        verbs(page(selected = 2)) shouldContainExactly listOf("discard")
        verbs(page(selected = 4)) shouldContainExactly listOf("switch", "discard")
        verbs(page(selected = 5)) shouldContainExactly listOf("restore")
        verbs(page(selected = 3, canPublish = false, canRelease = false, canManage = false)) shouldContainExactly emptyList()
        verbs(page(selected = 3, authoringEnabled = false)) shouldContainExactly listOf("release", "purge")
        val release = page(page(selected = 3)).select("[data-api-verb=release]").single()
        release.attr("hx-get") shouldBe "/partials/endpoints/$id/lifecycle/release"
        release.attr("hx-target") shouldBe "#ax-dialog"
        // New draft: offered only with no working draft, a current version, authoring on and the publish row.
        val noDraft = page(selected = 2).let { it.copy(versions = it.versions.filterNot { v -> v.isDraft }, hasDraft = false) }
        page(noDraft).select("[data-api-verb=new-draft]").size shouldBe 1
        page(noDraft.copy(authoringEnabled = false)).select("[data-api-verb=new-draft]").size shouldBe 0
        page(page(selected = 2)).select("[data-api-verb=new-draft]").size shouldBe 0
    }

    @Test
    fun `the draft picker keeps a noncurrent pin and lists an ineligible version disabled`() {
        val dialog =
            EndpointVersionsPageModel.DraftDialog(
                id = id,
                isNew = false,
                ifMatch = "h3",
                pipelines = listOf("finance/costs", "finance/revenue"),
                pipeline = "finance/revenue",
                options =
                    listOf(
                        EndpointVersionsPageModel.PinOption(4, "DRAFT", eligible = false, isCurrent = false),
                        EndpointVersionsPageModel.PinOption(3, "RELEASED", eligible = true, isCurrent = true),
                        EndpointVersionsPageModel.PinOption(2, "RELEASED", eligible = true, isCurrent = false),
                    ),
                selectedVersion = 2,
                timeoutSeconds = 30,
                description = "d",
            )
        val doc = partial("partials/endpoint-lifecycle-draft", dialog)
        doc.select("form").attr("hx-post") shouldBe "/partials/endpoints/$id/lifecycle/draft"
        doc.select("input[name=ifMatch]").attr("value") shouldBe "h3"
        val options = doc.select("[data-api-pipeline-version] option")
        options.map { it.attr("value") } shouldContainExactly listOf("4", "3", "2")
        options.single { it.hasAttr("selected") }.attr("value") shouldBe "2"
        options.single { it.hasAttr("disabled") }.attr("value") shouldBe "4"
        doc.select("[data-api-pipeline] option[selected]").attr("value") shouldBe "finance/revenue"
        doc.select("[data-api-pipeline]").attr("hx-get") shouldBe "/partials/endpoints/$id/lifecycle/draft"
    }

    @Test
    fun `the release dialog posts the tree hash and its draft dependencies under consent - a blocker leaves no form`() {
        val dependency = EndpointVersionsPageModel.Dependency("n1", "finance/revenue@4", "pipeline", "DRAFT")
        val dialog =
            EndpointVersionsPageModel.ReleaseDialog(
                id,
                3,
                "finance/revenue@4",
                "h3",
                "t".repeat(64),
                listOf(dependency),
                emptyList(),
            )
        val doc = partial("partials/endpoint-lifecycle-release", dialog)
        doc.select("form").attr("hx-post") shouldBe "/partials/endpoints/$id/lifecycle/release"
        doc.select("input[name=treeHash]").attr("value") shouldBe "t".repeat(64)
        doc.select("input[name=bodyHash]").attr("value") shouldBe "h3"
        doc.select("input[name=dependency]").map { it.attr("value") } shouldContainExactly listOf("n1")
        doc.select("input[name=includeDependencies]").hasAttr("checked") shouldBe true
        doc.select("[data-api-submit]").attr("data-consent-submit") shouldBe "true"

        val blocked =
            partial(
                "partials/endpoint-lifecycle-release",
                dialog.copy(drafts = emptyList(), blockers = listOf(dependency.copy(status = "MISSING"))),
            )
        blocked.select("form").size shouldBe 0
        blocked.select("[data-api-release-blockers]").text() shouldContain "MISSING"
    }

    @Test
    fun `each single-version dialog posts its own verb for its own version`() {
        val dialog =
            EndpointVersionsPageModel.VersionDialog(
                id,
                "/api/finance/v1/revenue",
                2,
                "RELEASED",
                "finance/revenue@2",
                "h2",
                false,
                true,
                false,
            )
        listOf("discard", "restore", "purge", "switch").forEach { verb ->
            val doc = partial("partials/endpoint-lifecycle-$verb", dialog)
            doc.select("form").attr("hx-post") shouldBe "/partials/endpoints/$id/lifecycle/$verb?version=2"
            doc.select("[data-lifecycle-close]").size shouldBe 2
        }
        partial("partials/endpoint-lifecycle-purge", dialog.copy(isSoleVersion = true)).text() shouldContain "removes the API"

        // The purge alone is irreversible: its button waits for the typed confirm `v2` (ui-screens §5.1).
        val purge = partial("partials/endpoint-lifecycle-purge", dialog)
        purge.select("input[name=confirm][data-confirm-input]").attr("data-confirm-expect") shouldBe "v2"
        val button = purge.select("[data-api-submit]")
        button.hasAttr("data-typed-confirm") shouldBe true
        button.hasAttr("disabled") shouldBe true
        button.hasClass("ds-button-danger") shouldBe true
        listOf("discard", "restore", "switch").forEach { verb ->
            partial("partials/endpoint-lifecycle-$verb", dialog).select("[data-confirm-input]").size shouldBe 0
        }
    }

    private fun verbs(page: EndpointVersionsPageModel.Page): List<String> =
        page(page).select("[data-api-verbs] [data-api-verb]").map { it.attr("data-api-verb") }

    private fun row(
        version: Int,
        status: String,
        pinned: Int,
        isCurrent: Boolean = false,
        eligible: Boolean = true,
    ) = EndpointVersionsPageModel.Row(
        version,
        status,
        "finance/revenue",
        pinned,
        30,
        "",
        "h$version",
        isCurrent,
        eligible,
        Instant.EPOCH,
    )

    private fun page(
        selected: Int,
        canPublish: Boolean = true,
        canRelease: Boolean = true,
        canManage: Boolean = true,
        authoringEnabled: Boolean = true,
    ): EndpointVersionsPageModel.Page {
        val rows =
            listOf(
                row(3, "DRAFT", 4),
                row(2, "RELEASED", 2, isCurrent = true),
                row(1, "RELEASED", 1, eligible = false),
            )
        val extra = listOf(row(4, "RELEASED", 3), row(5, "DISCARDED", 1, eligible = false))
        return EndpointVersionsPageModel.Page(
            id = id,
            url = "/api/finance/v1/revenue",
            enabled = false,
            boundKeyNames = listOf("ci", "nightly"),
            versions = rows,
            selected = (rows + extra).single { it.version == selected },
            currentVersion = 2,
            hasDraft = true,
            authoringEnabled = authoringEnabled,
            canPublish = canPublish,
            canRelease = canRelease,
            canManage = canManage,
        )
    }

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

    private fun context(): WebContext {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles()
        context.setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        context.setVariable("workspaceHeaderFragment", "")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("activeWorkspace", "acme")
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/api-console/endpoints/$id")
        return context
    }

    private fun page(model: EndpointVersionsPageModel.Page): Document {
        val context = context()
        context.setVariable("page", model)
        return Jsoup.parse(engine.process("api/endpoint", context))
    }

    private fun partial(
        view: String,
        dialog: Any,
    ): Document {
        val context = context()
        context.setVariable("dlg", dialog)
        return Jsoup.parse(engine.process(view, context))
    }
}
