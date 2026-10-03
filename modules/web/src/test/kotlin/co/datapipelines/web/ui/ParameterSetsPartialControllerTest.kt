package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.web.EVERYTHING_LENS
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

/**
 * [ParameterSetsPartialController] (#415) — the tree route's dispatch, not the template: a `prefix`
 * request is a browse and ignores `q`; the nav scope with a blank `q` is the root level and with a
 * non-empty one the branch's flat search; any other scope is the catalog's flat list. The stamp
 * rides every NAV answer — a level and a search result alike — so the rail can refuse a foreign
 * workspace's or lens's rows.
 */
class ParameterSetsPartialControllerTest {
    private val browse = mockk<ParameterSetsBrowseModel>(relaxed = true)
    private val controller = ParameterSetsPartialController(browse, EVERYTHING_LENS)
    private val response = mockk<HttpServletResponse>(relaxed = true)
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(UUID.randomUUID(), "acme", WorkspaceRole.AUTHOR),
                ),
                null,
                emptyList(),
            )
    }

    @Test
    fun `a prefix request is a browse - the level fills, q is never asked`() {
        authenticate()
        controller.tree(model, response, prefix = "acme/sales", offset = 25, q = "geo", scope = null)

        verify(exactly = 1) { browse.fillLevel(model, any(), any(), "acme/sales", 25) }
        verify(exactly = 0) { browse.fillWrapper(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the branch's blank q is the root level and a non-empty one the flat search - both nav-scoped`() {
        authenticate()
        controller.tree(model, response, prefix = null, offset = null, q = null, scope = "nav")
        verify(exactly = 1) {
            browse.fillWrapper(model, any(), any(), q = null, offset = 0, scope = ParameterSetsBrowseModel.SCOPE_NAV)
        }

        controller.tree(model, response, prefix = null, offset = 50, q = "geo", scope = "nav")
        verify(exactly = 1) {
            browse.fillWrapper(model, any(), any(), q = "geo", offset = 50, scope = ParameterSetsBrowseModel.SCOPE_NAV)
        }
        verify(exactly = 0) { browse.fillLevel(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown scope is the catalog's flat list - the search rides it too`() {
        authenticate()
        controller.tree(model, response, prefix = null, offset = null, q = "geo", scope = null)

        verify(exactly = 1) {
            browse.fillWrapper(model, any(), any(), q = "geo", offset = 0, scope = ParameterSetsBrowseModel.SCOPE_PAGE)
        }
    }

    @Test
    fun `every NAV answer carries the stamp - a level and a search result alike`() {
        authenticate()
        controller.tree(model, response, prefix = null, offset = null, q = "geo", scope = "nav")
        verify(exactly = 1) { response.setHeader(ParameterSetsBrowseModel.NAV_STAMP_HEADER, "acme|all") }

        controller.tree(model, response, prefix = null, offset = null, q = null, scope = "page")
        verify(exactly = 1) { response.setHeader(any(), any()) }
    }
}
