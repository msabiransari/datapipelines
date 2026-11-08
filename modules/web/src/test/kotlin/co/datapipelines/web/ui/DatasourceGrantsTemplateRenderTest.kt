package co.datapipelines.web.ui

import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.Dialect
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
 * Render-level guard for the grants dialog (`partials/datasource-grants`, 114 §C.2) and #823's rule: a screen offers
 * only what the server allows. The managed lake's grant to a workspace cannot be revoked (`datasource.managed`), so its
 * rows carry a short muted sentence and NO Revoke button; an ordinary datasource's rows keep the button. The server
 * refusal is the controllers' tests'; THIS is the half a controller test cannot see — the markup.
 *
 * Engine infra mirrors [DatasourcesTemplateRenderTest].
 */
class DatasourceGrantsTemplateRenderTest {
    @Test
    fun `an ordinary datasource's rows keep their Revoke button`() {
        val html = engine().process("partials/datasource-grants", context(managed = false))

        html shouldContain "data-verb=\"datasource-grant-remove\""
        html shouldContain "/partials/datasources/pg-prod/grants/acme/remove"
        html shouldContain "/partials/datasources/pg-prod/grants/globex/remove"
        html shouldNotContain "data-role=\"managed-grant\""
    }

    /** #823 — red at the base: every row, the managed lake's included, drew the button. */
    @Test
    fun `the managed lake's rows carry the sentence and no Revoke button (#823)`() {
        val html = engine().process("partials/datasource-grants", context(managed = true))

        html shouldNotContain "data-verb=\"datasource-grant-remove\""
        html shouldNotContain "/grants/acme/remove"
        html shouldNotContain "/grants/globex/remove"
        // One sentence per row: the cell is never empty, so the screen says WHY there is no button.
        Regex("data-role=\"managed-grant\"").findAll(html).count() shouldBe 2
        html shouldContain "granted by the server"
        // The rest of the dialog is unchanged: the rows, the add form.
        html shouldContain "acme"
        html shouldContain "globex"
        html shouldContain "data-verb=\"datasource-grant-add\""
    }

    private fun context(managed: Boolean): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        ).withRoles().apply {
            setVariable(
                "datasource",
                Datasource(
                    name = if (managed) "lake" else "pg-prod",
                    displayName = "x",
                    dialect = if (managed) Dialect.LAKE else Dialect.POSTGRES,
                    jdbcUrl = "jdbc:postgresql://db:5432/app",
                    username = "app",
                ),
            )
            setVariable("isManaged", managed)
            setVariable(
                "grants",
                listOf("acme", "globex").map {
                    mapOf("workspace" to it, "grantedBy" to "System", "grantedAt" to "2026-11-07T00:00:00Z", "owner" to false)
                },
            )
            setVariable("grantableWorkspaces", listOf("initech"))
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
}
