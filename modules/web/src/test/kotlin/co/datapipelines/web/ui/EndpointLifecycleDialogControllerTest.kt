package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.release.ReleaseCoordinator
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * #786 B.3 — the API purge dialog's POST, the irreversible API verb, through MockMvc with the session principal the house
 * partial tests use ([PipelineLifecycleDialogControllerTest] is the pipeline twin):
 *
 * - a typed confirm that does not name `v<n>` is `400 endpoint.version.confirm_mismatch` in Shape C and the service is
 *   NEVER CALLED — a recording verification, not a strict mock (a strict mock makes a missing call unobservable);
 * - the matching confirm purges: a draft purge redirects back onto the API's page, the sole version's purge (the entity
 *   went with it) onto the console list.
 */
class EndpointLifecycleDialogControllerTest {
    private val versions = mockk<EndpointVersionService>()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = USER,
                    email = "probe@test",
                    displayName = "Probe",
                    authMethod = AuthMethod.OIDC,
                    workspace = WorkspaceContext(WORKSPACE, "probe"),
                ),
                null,
                emptyList(),
            )
        mvc =
            MockMvcBuilders
                .standaloneSetup(
                    EndpointLifecycleDialogController(
                        pages = mockk<EndpointVersionsPageModel>(),
                        versions = versions,
                        coordinator = mockk<ReleaseCoordinator>(),
                        themeResolver = mockk<ThemeResolver>(),
                    ),
                ).setControllerAdvice(UiExceptionHandler())
                .build()
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `purge - a typed confirm that does not name the version is 400 and the service never runs`() {
        listOf(null, "", "2", "v3", "V2", " v2").forEach { typed ->
            val request =
                post("/partials/endpoints/$API/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "2")
                    .header("HX-Request", "true")
            if (typed != null) request.param("confirm", typed)
            val body =
                mvc
                    .perform(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(header().string("HX-Retarget", "#toast"))
                    .andReturn()
                    .response.contentAsString
            body shouldContain PipelineErrorCodes.Endpoint.VERSION_CONFIRM_MISMATCH
        }
        verify(exactly = 0) { versions.purgeVersion(any(), any(), any()) }
    }

    @Test
    fun `purge - the matching confirm purges the draft and lands back on the API's page`() {
        every { versions.purgeVersion(any(), API, 2) } returns EndpointVersionService.Purged(version = 2, entityDeleted = false)

        mvc
            .perform(
                post("/partials/endpoints/$API/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "2")
                    .param("confirm", "v2")
                    .header("HX-Request", "true"),
            ).andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/api-console/endpoints/$API?ok=api_draft_purged"))

        verify(exactly = 1) { versions.purgeVersion(any(), API, 2) }
    }

    @Test
    fun `purge - the sole version's purge took the API with it, so it lands on the console list`() {
        every { versions.purgeVersion(any(), API, 1) } returns EndpointVersionService.Purged(version = 1, entityDeleted = true)

        mvc
            .perform(
                post("/partials/endpoints/$API/lifecycle/purge")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("version", "1")
                    .param("confirm", "v1")
                    .header("HX-Request", "true"),
            ).andExpect(header().string("HX-Redirect", "/api-console?ok=api_purged"))
    }

    private companion object {
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val WORKSPACE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val API: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    }
}
