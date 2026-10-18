package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.web.EVERYTHING_LENS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * [ParameterSetsPartialController] (#415) — the catalog route's dispatch, not the template: every
 * answer is the catalog's flat list, the search riding it, the pager's offset threaded through.
 * Since #466 the route has no nav presentation (the sidebar reads `/api/v1/parameter-sets/tree`),
 * so there is no `prefix` level, no `scope` and no stamp; a stale `scope`/`prefix` is an unbound
 * parameter, pinned over real HTTP in `PromoterLensSweepTest`.
 */
class ParameterSetsPartialControllerTest {
    private val browse = mockk<ParameterSetsBrowseModel>(relaxed = true)
    private val controller = ParameterSetsPartialController(browse, EVERYTHING_LENS)
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
    fun `the catalog without a query is the flat list from page one`() {
        authenticate()
        controller.catalog(model, offset = null, q = null, lifecycle = null)

        verify(exactly = 1) { browse.fillWrapper(model, any(), any(), q = null, offset = 0, lifecycle = null) }
    }

    @Test
    fun `the catalog's search rides the flat list and threads the pager's offset`() {
        authenticate()
        controller.catalog(model, offset = 50, q = "geo", lifecycle = null)

        verify(exactly = 1) { browse.fillWrapper(model, any(), any(), q = "geo", offset = 50, lifecycle = null) }
    }

    @Test
    fun `the catalog's lifecycle reaches the list as its bucket, composed with q and the offset (#582)`() {
        authenticate()
        controller.catalog(model, offset = 25, q = "geo", lifecycle = "with-drafts")

        verify(exactly = 1) {
            browse.fillWrapper(model, any(), any(), q = "geo", offset = 25, lifecycle = LifecycleBucket.WITH_DRAFTS)
        }
    }

    @Test
    fun `an unknown or unoffered lifecycle is the house 400 before any read (#582)`() {
        authenticate()
        for (raw in listOf("discarded", "DRAFT", "nope")) {
            val refused = shouldThrow<ResponseStatusException> { controller.catalog(model, offset = null, q = null, lifecycle = raw) }
            refused.statusCode shouldBe HttpStatus.BAD_REQUEST
            refused.reason shouldBe CatalogLifecycle.BAD_LIFECYCLE
        }
        verify(exactly = 0) { browse.fillWrapper(any(), any(), any(), any(), any(), any()) }
    }
}
