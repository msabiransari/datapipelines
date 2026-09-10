package co.datapipelines.web.ui

import co.datapipelines.application.datasources.DatasourceUpdateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourceWorkspaceRules
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * The §4.5 listing has TWO entry points — `GET /datasources` (the page, first paint) and
 * `GET /partials/datasources` (every later fragment) — and they must answer the same
 * question. Before 097 each implemented its own `filter()`: the page searched three fields,
 * the partial searched ten, so **`GET /datasources?q=postgres` came back empty while typing
 * `postgres` into the same box returned rows** — a reload, a shared link or a boosted
 * navigation showed a different world from the one the user had just been looking at.
 *
 * This test is that defect, stated as a property: for the same `q`, `dialect` and `offset`,
 * the two routes put the SAME rows, in the SAME order, on their models. It is red on the
 * pre-097 base for `q=postgres` (page 0 rows, partial 2) and green once both controllers
 * project through the one [DatasourceBrowseModel].
 *
 * `names` and not `size`: a same-count-different-rows answer is the more embarrassing bug.
 */
class DatasourceBrowseParityTest {
    private val registry = mockk<DatasourceRegistry>()
    private val themeResolver = mockk<ThemeResolver>()

    private val workspaceId = UUID.randomUUID()

    /**
     * Rows chosen so that every one of them is a *miss* for the page's three-field filter and
     * a *hit* for the ten-field one: the word `postgres` appears in the dialect wire, the JDBC
     * URL, the username, the credential kind, the workspace name and the last-test label —
     * never in a name, display name or description.
     */
    private fun rows() =
        listOf(
            Datasource(
                name = "alpha",
                displayName = "Alpha",
                description = "the reporting database",
                dialect = Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://reports.internal:5432/db",
                username = "svc_reports",
            ),
            Datasource(
                name = "beta",
                displayName = "Beta",
                description = "the warehouse",
                dialect = Dialect.POSTGRES,
                jdbcUrl = "jdbc:postgresql://warehouse.internal:5432/db",
                username = "svc_warehouse",
            ),
        )

    private val grants = mockk<co.datapipelines.datasources.DatasourceGrantRepository>(relaxed = true)

    private val rules = DatasourceWorkspaceRules(mockk(relaxed = true), WorkspacesProperties())

    private fun pageController() = DatasourceUiController(DatasourceBrowseModel(registry), WorkspacesProperties(), themeResolver)

    private fun partialController() =
        DatasourcePartialController(
            DatasourceBrowseModel(registry),
            registry,
            rules,
            DatasourceUpdateService(registry, rules),
            grants,
            DatasourceReferences.NONE,
        )

    @BeforeEach
    fun authenticate() {
        every { themeResolver.resolve(any()) } returns "saas"
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    setOf(Scope.ADMIN),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun pageNames(
        q: String?,
        dialect: String? = null,
        offset: Int? = null,
    ): List<String> {
        val model = ExtendedModelMap()
        pageController().list(model, mockk(), q, dialect, offset)
        return names(model)
    }

    private fun partialNames(
        q: String?,
        dialect: String? = null,
        offset: Int? = null,
    ): List<String> {
        val model = ExtendedModelMap()
        partialController().list(model, q, dialect, offset)
        return names(model)
    }

    private fun names(model: ExtendedModelMap): List<String> {
        @Suppress("UNCHECKED_CAST")
        val items = model["datasources"] as List<Datasource>
        return items.map { it.name }
    }

    @Test
    fun `the reported defect - a q the ten-field search matches is matched by the page too`() {
        every { registry.listVisible(null, workspaceId) } returns rows()

        val partial = partialNames("postgres")
        partial.shouldNotBeEmpty()
        pageNames("postgres") shouldBe partial
    }

    @Test
    fun `the page and the partial agree on every column the table renders`() {
        every { registry.listVisible(null, workspaceId) } returns rows()

        listOf("postgres", "reports.internal", "svc_warehouse", "global", "never tested", "alpha", "warehouse")
            .forEach { q ->
                withClue(q) { pageNames(q) shouldBe partialNames(q) }
            }
    }

    @Test
    fun `the page and the partial agree under a dialect filter and an offset`() {
        every { registry.listVisible(Dialect.POSTGRES, workspaceId) } returns rows()

        pageNames(null, dialect = "POSTGRES") shouldBe partialNames(null, dialect = "POSTGRES")
        pageNames("postgres", dialect = "postgres", offset = 1) shouldBe partialNames("postgres", dialect = "postgres", offset = 1)
    }

    @Test
    fun `the page and the partial agree on the paging attributes, not only on the rows`() {
        every { registry.listVisible(null, workspaceId) } returns rows()

        val page = ExtendedModelMap().also { pageController().list(it, mockk(), "postgres", null, 0) }
        val partial = ExtendedModelMap().also { partialController().list(it, "postgres", null, 0) }

        page["total"] shouldBe partial["total"]
        page["hasMore"] shouldBe partial["hasMore"]
        page["offset"] shouldBe partial["offset"]
        page["q"] shouldBe partial["q"]
        page["selectedDialect"] shouldBe partial["selectedDialect"]
    }
}
