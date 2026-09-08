package co.datapipelines.web.ui

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceTestOutcome
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourcePoolForm
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
import java.time.Instant

/**
 * Render-level guard for the 028 datasources SPA table + toast notifications.
 * Unit tests pin the controller's model; THIS class pins the swap contracts the
 * browser depends on — the two defects that made the screen break its layout
 * were both contract failures invisible to controller tests:
 *
 *  1. The list fragment's ROOT must carry `id="datasource-list-wrapper"`. The
 *     page's placeholder div is replaced by the fragment on first render; when
 *     the id lived on that placeholder, every later pager/filter swap targeted
 *     an element that no longer existed and htmx failed silently.
 *  2. The Test button must NOT swap rows. It appends a server-rendered toast to
 *     the layout's `#toast` stack (`hx-swap="beforeend"`) — the old contract
 *     swapped one row for a two-row fragment and then fetched the WHOLE list
 *     into a `<tr>` ("Back to list"), which the browser's table parser tore
 *     apart (the 028 screenshot's nested headers and duplicated pagers).
 *
 * Engine infra mirrors TemplateHtmxRenderAuditTest (same WebContext shape).
 */
class DatasourcesTemplateRenderTest {
    @Test
    fun `list fragment renders the stable swap root and the toast-delivered test button`() {
        val html = engine().process("partials/datasources", context().apply { fillListModel() })

        html shouldContain "id=\"datasource-list-wrapper\""
        html shouldContain "hx-post=\"/partials/datasources/pg-prod/test\""
        html shouldContain "hx-target=\"#toast\""
        html shouldContain "hx-swap=\"beforeend\""
        html shouldContain "hx-disabled-elt=\"this\""
        // The dead View button (hx-get of the REST JSON endpoint, swapped raw into
        // the button) is gone — §4.5's row action is Test.
        html shouldNotContain "/api/v1/datasources"
        // The pager keeps the active filter in its URLs.
        html shouldContain "q=trip"
        html shouldContain "dialect=POSTGRES"
    }

    @Test
    fun `list fragment renders the design-system table and badges`() {
        val html = engine().process("partials/datasources", context().apply { fillListModel() })

        html shouldContain "<table class=\"ds-table\">"
        // The dialect chip and the readonly restriction are badges now (029).
        html shouldContain "ds-badge ds-badge-default"
        html shouldContain "ds-badge ds-badge-warning"
        // The migration is only done when the inline table styles are GONE.
        html shouldNotContain "border-collapse: collapse"
        html shouldNotContain "padding: var(--gap-sm) var(--gap-md); text-align: left"
    }

    /**
     * 061/T84 gate 4 — the column that would have made the 2026-09-02 incident visible.
     * A datasource whose last probe FAILED renders `failed`, the timestamp, and the driver's
     * message, with no execution having run; one that has never been probed says so rather
     * than implying health. This is the render half; the recording half is
     * `DatasourceTestOutcomeIntegrationTest`.
     */
    @Test
    fun `the last-test column renders ok, failed with its message, and never-tested`() {
        val html =
            engine().process(
                "partials/datasources",
                context().apply {
                    fillListModel()
                    setVariable(
                        "datasources",
                        listOf(
                            datasource("pg-prod", lastTest = DatasourceTestOutcome(Instant.parse("2026-08-30T09:15:00Z"), true, "16.2")),
                            datasource(
                                "sample-trips",
                                lastTest =
                                    DatasourceTestOutcome(
                                        Instant.parse("2026-08-30T09:15:00Z"),
                                        false,
                                        "FATAL: password authentication failed for user \"dp_demo_ro\"",
                                    ),
                            ),
                            datasource("never-probed"),
                        ),
                    )
                },
            )

        html shouldContain "<th>Last test</th>"
        html shouldContain "ds-badge ds-badge-success"
        html shouldContain "ds-badge ds-badge-danger"
        html shouldContain ">failed<"
        html shouldContain ">never tested<"
        // The driver's own sentence is what ends the investigation, so it is on the row.
        html shouldContain "password authentication failed"
        html shouldContain "2026-08-30"
    }

    @Test
    fun `list empty state uses the ds-empty primitive`() {
        val html =
            engine().process(
                "partials/datasources",
                context().apply {
                    fillListModel()
                    setVariable("datasources", emptyList<Datasource>())
                },
            )

        html shouldContain "class=\"ds-empty\""
        html shouldContain "class=\"ds-empty-title\""
        html shouldNotContain "ds-empty-state" // a class with no CSS anywhere (D4)
    }

    @Test
    fun `toast fragment renders the design-system toast with the model's variant`() {
        val html =
            engine().process(
                "partials/toast",
                context().apply {
                    setVariable("variant", "success")
                    setVariable("title", "Connection succeeded")
                    setVariable("message", "pg-prod — Server version: 15.4")
                },
            )

        html shouldContain "ds-toast ds-toast-success"
        html shouldContain "ds-toast-close"
        html shouldContain "Connection succeeded"
        html shouldContain "pg-prod — Server version: 15.4"
    }

    @Test
    fun `datasources page wires the spa filter controls and the toast stack`() {
        val html = engine().process("datasources/list", context().apply { fillPageModel() })

        // Filter controls re-fetch ONLY the fragment, addressed by id (the register
        // modal carries its own name="dialect" — name-based includes would cross-wire).
        html shouldContain "id=\"ds-filter-q\""
        html shouldContain "hx-get=\"/partials/datasources\""
        html shouldContain "hx-trigger=\"input changed delay:300ms, search\""
        html shouldContain "hx-include=\"#ds-filter-dialect\""
        html shouldContain "hx-include=\"#ds-filter-q\""
        html shouldContain "id=\"ds-filter-spinner\""
        // The list still renders inside the page, and the layout serves the stack
        // plus the toast lifecycle script.
        html shouldContain "id=\"datasource-list-wrapper\""
        // 079 §D: the stack's offset moved off an inline style= onto .app-toast-stack.
        html shouldContain "id=\"toast\" class=\"ds-toast-stack app-toast-stack\""
        html shouldContain "/js/toast.js"
    }

    @Test
    fun `register success renders the modal success node, the OOB list refresh and the OOB toast`() {
        val html =
            engine().process(
                "partials/datasource-registered",
                context().apply {
                    fillListModel()
                    setVariable("registeredName", "pg-new")
                    setVariable("oob", true)
                },
            )

        // The modal's success node: its arrival is what closes the modal (022/F9).
        html shouldContain "Datasource pg-new registered."
        // The refreshed list rides along OOB in the outerHTML (inline) form — the
        // attribute is present ONLY on this path, never baked into the primary fragment.
        Regex("""<div[^>]*id="datasource-list-wrapper"[^>]*hx-swap-oob="true"""")
            .containsMatchIn(html) shouldBe true
        // The toast rides along OOB, WRAPPED (never the attribute on the .ds-toast itself).
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        Regex("""hx-swap-oob="beforeend:#toast"[^>]*>(?:\s|<!--[\s\S]*?-->)*<div class="ds-toast""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "Datasource registered"
    }

    @Test
    fun `the primary list fragment never carries the OOB attribute`() {
        val html = engine().process("partials/datasources", context().apply { fillListModel() })

        html shouldNotContain "hx-swap-oob=\""
    }

// ------------------------------------------------------------- 094 §A: the pool section

    @Test
    fun `the register modal carries the collapsed pool section, prefilled and labelled by layer`() {
        val html = engine().process("datasources/list", context().apply { fillPageModel() })

        // Collapsed by default: a <details> with no `open`, so the eight fields cost the
        // operator nothing until they want them.
        html shouldContain "Connection pool — defaults"
        Regex("""<details[^>]*\sopen""").containsMatchIn(html) shouldBe false
        // Every catalogued key is rendered under the `pool.` form prefix…
        html shouldContain "name=\"pool.maximumPoolSize\""
        html shouldContain "name=\"pool.leakDetectionThreshold\""
        // …prefilled with the effective default, and SAYING which layer supplied it.
        html shouldContain "value=\"10\""
        // Thymeleaf escapes the apostrophe (`&#39;`), so the assertion stops before it — the
        // layer's NAME is the fact under test, not the entity encoding.
        html shouldContain "Default 10 — HikariCP"
        html shouldContain "Default 2 — this server"
        // The dialect select re-fetches the section, because the default is per dialect.
        html shouldContain "id=\"ds-pool-fields\""
        html shouldContain "hx-get=\"/partials/datasources/pool-fields\""
        html shouldContain "hx-target=\"#ds-pool-fields\""
    }

    @Test
    fun `no refused key is ever rendered as a pool input`() {
        // §5.6: readOnly, connectionInitSql and the credential slots are server-managed. The
        // section mirrors `readOnly` as a DISABLED field and offers no input for the others.
        val html = engine().process("partials/datasource-pool-fields", context().apply { fillPoolModel() })

        html shouldNotContain "name=\"pool.readOnly\""
        html shouldNotContain "name=\"pool.connectionInitSql\""
        html shouldNotContain "name=\"pool.password\""
        html shouldContain "id=\"ds-pool-readonly\""
        html shouldContain "disabled"
    }

    // ------------------------------------------------------------- 094 §A: the edit dialog

    @Test
    fun `the edit dialog is a whole backdrop, keeps the name and dialect fixed, and prefills the pool`() {
        val html = engine().process("partials/datasource-edit", context().apply { fillEditModel() })

        // Fetched as a complete backdrop: `.u-backdrop` is display:flex, so nothing opens it.
        html shouldContain "class=\"u-backdrop\""
        html shouldContain "hx-post=\"/partials/datasources/pg-prod\""
        // The immutable pair is shown, disabled — never a select that could repoint a live row.
        html shouldNotContain "name=\"dialect\""
        html shouldNotContain "name=\"name\""
        // A blank secret keeps the stored credential; the stored one is never rendered back.
        html shouldContain "Leave the secret blank to keep the stored credential."
        html shouldNotContain "s3cret"
        // The pool section is here too, prefilled from the ROW where it has values.
        html shouldContain "name=\"pool.maximumPoolSize\""
        html shouldContain "Set on this datasource."
        // …and it carries NO id: only the register modal's section is a swap target, so an id
        // here would be a duplicate the moment this dialog opened over the list page.
        html shouldNotContain "id=\"ds-pool-fields\""
        html shouldContain "id=\"ds-edit-maximumPoolSize\""
    }

    @Test
    fun `only an admin's edit form can write the global flag`() {
        val admin = engine().process("partials/datasource-edit", context().apply { fillEditModel() })
        val member =
            engine().process(
                "partials/datasource-edit",
                context().apply {
                    fillEditModel()
                    setVariable("isAdmin", false)
                },
            )

        // The hidden companion is what makes an UNCHECKED box a deliberate write; without it a
        // member's post can never carry the flag at all.
        admin shouldContain "name=\"globalPresent\""
        admin shouldContain "name=\"global\""
        member shouldNotContain "name=\"globalPresent\""
        member shouldNotContain "name=\"global\""
    }

    // ------------------------------------------------------------- 094 §B: the delete dialog

    @Test
    fun `a datasource in use renders its usages and offers NO confirm button`() {
        val html =
            engine().process(
                "partials/datasource-delete",
                context().apply {
                    fillDeleteModel()
                    setVariable(
                        "usages",
                        listOf(
                            DatasourceReference("sales_daily", 3, "RELEASED", "extract"),
                            DatasourceReference("sales_daily", 4, "DRAFT", "extract"),
                        ),
                    )
                    setVariable("usedByPipelines", listOf("sales_daily"))
                },
            )

        html shouldContain "id=\"ds-delete-in-use\""
        html shouldContain "sales_daily"
        html shouldContain "extract"
        html shouldContain "(v3 released)"
        html shouldContain "(v4 draft)"
        // The refusal cannot be clicked past: there is no form and no button on this branch.
        html shouldNotContain "hx-post=\"/partials/datasources/pg-prod/delete\""
        html shouldNotContain "ds-button-danger"
    }

    @Test
    fun `an unused datasource renders a confirm that names it`() {
        val html = engine().process("partials/datasource-delete", context().apply { fillDeleteModel() })

        html shouldContain "id=\"ds-delete-confirm-text\""
        html shouldContain "hx-post=\"/partials/datasources/pg-prod/delete\""
        html shouldContain "Delete pg-prod"
        html shouldContain "ds-button-danger"
        // The promise the retire-then-close lifecycle now keeps (§5.2).
        html shouldContain "queries already running finish first"
    }

    @Test
    fun `a member looking at a global datasource is told, and offered nothing`() {
        val html =
            engine().process(
                "partials/datasource-delete",
                context().apply {
                    fillDeleteModel()
                    setVariable("forbidden", "Deleting the global datasource 'pg-prod' requires admin.")
                },
            )

        html shouldContain "id=\"ds-delete-forbidden\""
        html shouldContain "requires admin"
        html shouldNotContain "ds-button-danger"
        html shouldNotContain "id=\"ds-delete-usages\""
    }

    @Test
    fun `the saved response closes the dialog, refreshes the list OOB and toasts`() {
        val html =
            engine().process(
                "partials/datasource-saved",
                context().apply {
                    fillListModel()
                    setVariable("savedName", "pg-prod")
                    setVariable("savedVerb", "deleted")
                    setVariable("oob", true)
                },
            )

        // The marker the screen's script closes on — a refusal never carries it.
        html shouldContain "data-ds-saved=\"true\""
        html shouldContain "Datasource pg-prod deleted."
        Regex("""<div[^>]*id="datasource-list-wrapper"[^>]*hx-swap-oob="true"""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "Datasource deleted"
    }

    private fun WebContext.fillPoolModel() {
        setVariable("poolFields", DatasourcePoolForm.fields(Dialect.POSTGRES))
        setVariable("poolReadonly", false)
        setVariable("poolFieldsId", DatasourcePoolForm.SWAP_TARGET_ID)
    }

    private fun WebContext.fillEditModel() {
        val row =
            datasource("pg-prod").copy(
                properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 25)),
            )
        setVariable("datasource", row)
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("credentialKinds", listOf("password", "token", "none"))
        setVariable("poolFields", DatasourcePoolForm.fields(row))
        setVariable("poolReadonly", row.isReadonly)
        setVariable("isAdmin", true)
    }

    private fun WebContext.fillDeleteModel() {
        setVariable("datasource", datasource("pg-prod"))
        setVariable("forbidden", null)
        setVariable("usages", emptyList<DatasourceReference>())
        setVariable("usedByPipelines", emptyList<String>())
    }

    private fun WebContext.fillListModel() {
        setVariable("datasources", listOf(datasource("pg-prod", isReadonly = true), datasource("sample-trips")))
        setVariable("q", "trip")
        setVariable("selectedDialect", "POSTGRES")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 42)
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillPageModel() {
        // Layout chrome (the UiWorkspaceAdvice set, per UiLayoutChromeAdviceTest).
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/datasources")
        // DatasourceUiController's model.
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("selectedDialect", "")
        setVariable("scopes", setOf("ADMIN"))
        setVariable("isAdmin", true)
        setVariable("memberDatasourcesEnabled", false)
        setVariable("canRegister", true)
        setVariable("bindingHint", "Bound to your active workspace: acme")
        // 094 §A: the register modal's pool section, prefilled for the dialect its select shows
        // first — the same model DatasourceUiController.list adds.
        setVariable("poolFields", DatasourcePoolForm.fields(Dialect.entries.first()))
        setVariable("poolReadonly", false)
        setVariable("poolFieldsId", DatasourcePoolForm.SWAP_TARGET_ID)
        setVariable("datasources", listOf(datasource("pg-prod")))
        setVariable("q", "")
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
    }

    private fun datasource(
        name: String,
        isReadonly: Boolean = false,
        lastTest: DatasourceTestOutcome? = null,
    ) = Datasource(
        name = name,
        displayName = name,
        description = null,
        dialect = Dialect.POSTGRES,
        jdbcUrl = "jdbc:postgresql://db:5432/app",
        username = "readonly",
        isReadonly = isReadonly,
        lastTest = lastTest,
    )

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

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
}
