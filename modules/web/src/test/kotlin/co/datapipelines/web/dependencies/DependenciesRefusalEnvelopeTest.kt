package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The tree routes' refusals on the wire (#875 G4 §B) through the real [ApiExceptionHandler]: each answers its catalogued
 * code in the §4.2 envelope; only the two retryable 503s (`navigation.view.unavailable`, `dependencies.metadata.unavailable`)
 * carry `Retry-After: 5`; no response echoes the caller's value, the promotion target or a provider's failure text.
 * Handler behavior only — the interceptor order is `DependenciesRouteE2eTest`'s.
 */
class DependenciesRefusalEnvelopeTest {
    private val pipelines = StubFamily(FamilyKey.PIPELINE)
    private val datasources = StubFamily(FamilyKey.DATASOURCE)
    private var view = LensedView.EVERYTHING
    private var wireBytes = TREE_LIMITS.wireBytes

    private fun mvc(): MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                DependenciesController(
                    treeService(
                        listOf(pipelines, datasources),
                        DependencyFamilyTransport.production(DependenciesRequestTest.LENSES).let { t -> t.families.map(t::row) },
                        limits = TREE_LIMITS.copy(wireBytes = wireBytes),
                        view = { view },
                    ),
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    private fun signIn(principal: AuthenticatedPrincipal = member()) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a malformed selector is 400 dependencies request invalid with field and reason, the value not echoed`() {
        signIn()
        val body =
            mvc()
                .perform(tree("pipeline", "name=${enc(PROBE)}&version=1"))
                .andExpect(status().isBadRequest)
                .catalogued(PipelineErrorCodes.Dependencies.REQUEST_INVALID)
                .andExpect(jsonPath("$.error.details.field").value("name"))
                .andExpect(jsonPath("$.error.details.reason").value("name_invalid"))
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn()
                .response.contentAsString

        body shouldNotContain PROBE
    }

    @Test
    fun `an unreachable promotion view is 503 view unavailable with Retry-After, the target not echoed`() {
        signIn(member(WorkspaceRole.PROMOTER))
        view = LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable(TARGET, "connect timed out"))

        val body =
            mvc()
                .perform(tree("pipeline", "name=${enc("acme/root")}&version=1"))
                .andExpect(status().isServiceUnavailable)
                .catalogued(PipelineErrorCodes.Navigation.VIEW_UNAVAILABLE)
                .andExpect(header().string("Retry-After", "5"))
                .andReturn()
                .response.contentAsString

        body shouldNotContain TARGET
        body shouldNotContain "connect timed out"
    }

    @Test
    fun `the datasource route has no lens - the same promoter with the same unreachable target gets its tree`() {
        signIn(member(WorkspaceRole.PROMOTER))
        view = LensedView(ReadLens.NOTHING, ReadLens.NOTHING, LensedView.Unavailable(TARGET, "connect timed out"))
        datasources.put(entityRequest(FamilyKey.DATASOURCE, "warehouse_pg"), lifecycle = null)

        mvc()
            .perform(tree("datasource", "name=${enc("warehouse_pg")}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nodes[0].family").value("datasource"))
    }

    @Test
    fun `a failed metadata read is 503 metadata unavailable with Retry-After, the failure text not echoed`() {
        signIn()
        pipelines.beforeBatch = { _, _ -> throw DataAccessResourceFailureException(FAILURE) }

        val body =
            mvc()
                .perform(tree("pipeline", "name=${enc("acme/root")}&version=1"))
                .andExpect(status().isServiceUnavailable)
                .catalogued(PipelineErrorCodes.Dependencies.METADATA_UNAVAILABLE)
                .andExpect(header().string("Retry-After", "5"))
                .andReturn()
                .response.contentAsString

        body shouldNotContain FAILURE
        body shouldNotContain "acme/root"
    }

    @Test
    fun `a tree over the byte budget is 503 response too large with no Retry-After`() {
        signIn()
        wireBytes = 64
        pipelines.put(exact(FamilyKey.PIPELINE, "acme/root", 1))

        mvc()
            .perform(tree("pipeline", "name=${enc("acme/root")}&version=1"))
            .andExpect(status().isServiceUnavailable)
            .catalogued(PipelineErrorCodes.Dependencies.RESPONSE_TOO_LARGE)
            .andExpect(header().doesNotExist("Retry-After"))
    }

    @Test
    fun `an absent root is the family's native 404, the name not echoed`() {
        signIn()
        val body =
            mvc()
                .perform(tree("pipeline", "name=${enc("acme/absent_root")}&version=1"))
                .andExpect(status().isNotFound)
                .catalogued(PipelineErrorCodes.Execution.NOT_FOUND)
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn()
                .response.contentAsString

        body shouldNotContain "acme/absent_root"
    }

    /** The §4.2 envelope with [code] and its snake_case keys. */
    private fun ResultActions.catalogued(code: String): ResultActions =
        andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.correlation_id").exists())
            .andExpect(jsonPath("$.error.user_message").isNotEmpty)
            .andExpect(jsonPath("$.error.doc_url").isNotEmpty)

    private companion object {
        const val PROBE = "Probe_Value;DROP"
        const val TARGET = "https://promotion-target.internal:8443"
        const val FAILURE = "jdbc:postgresql://metadata-host.internal/dp user=metadata_reader"
    }
}
