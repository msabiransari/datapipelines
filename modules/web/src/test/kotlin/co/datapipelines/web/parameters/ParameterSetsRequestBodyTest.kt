package co.datapipelines.web.parameters

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.pipelines.IfMatchHeader
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * How the parameter-set routes READ a `String` body (#323, the #291 shape — `RequestBodies`):
 * through the request mapper (`RequestLimits.requestMapper` over the module's mapper), a body that
 * cannot be read answering the family's catalogued 400 (`parameter.validation.body_invalid`,
 * `details.reason: malformed_json`) — never the `Throwable` backstop's 500, which reported the
 * caller's own mistake as ours with an ERROR line and a stack in the operator's log.
 *
 * On the wire — the status is the point, so the refusal is read through the real
 * [ApiExceptionHandler], not off the thrown exception (`ParameterSetsControllerTest` calls the
 * handlers directly): the real controller behind it in standalone MVC (the scope interceptor and
 * the filters have their own suites). The collaborators are STRICT mocks with nothing stubbed —
 * every case here is refused before any of them is reached, and a call would fail the case.
 */
class ParameterSetsRequestBodyTest {
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                ParameterSetsController(
                    mockk<ParameterSetService>(),
                    mockk<ParameterSetRepository>(),
                    mockk<ParameterEvaluator>(),
                    // The REAL transfer service (its collaborators strict): the import reads the
                    // envelope inside it, so the import case must reach its parse.
                    ParameterSetTransferService(mockk(), mockk(), mockk(), mockk()),
                    ParametersConfig(),
                    EVERYTHING_LENS,
                ),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(UUID.randomUUID(), "acme"),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a malformed body on create is the family's 400, not the 500 backstop`() {
        mvc
            .perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(MALFORMED))
            .andExpectMalformed()
    }

    @Test
    fun `a malformed body on the draft write is the family's 400, not the 500 backstop`() {
        mvc
            .perform(
                put("$ROOT/${UUID.randomUUID()}")
                    .header(IfMatchHeader.NAME, "\"sha256:abc\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MALFORMED),
            ).andExpectMalformed()
    }

    @Test
    fun `a malformed body on evaluate is the family's 400, not the 500 backstop`() {
        mvc
            .perform(post("$ROOT/${UUID.randomUUID()}/evaluate").contentType(MediaType.APPLICATION_JSON).content(MALFORMED))
            .andExpectMalformed()
    }

    /** The import reads its envelope in [ParameterSetTransferService] — the family's fourth `String` body. */
    @Test
    fun `a malformed body on import is the family's 400, not the 500 backstop`() {
        mvc
            .perform(post("$ROOT/import").contentType(MediaType.APPLICATION_JSON).content(MALFORMED))
            .andExpectMalformed()
    }

    /**
     * The request mapper's bounds apply (#291): a body nested past [RequestLimits.MAX_NESTING_DEPTH]
     * is refused as unreadable before the reader sees it. The module's own mapper (Jackson's default
     * nesting, 1,000) would have parsed it and handed the reader an array.
     */
    @Test
    fun `a body nested past the request bound is refused as unreadable before the reader sees it`() {
        val tooDeep = "[".repeat(RequestLimits.MAX_NESTING_DEPTH + 1) + "]".repeat(RequestLimits.MAX_NESTING_DEPTH + 1)
        mvc
            .perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(tooDeep))
            .andExpectMalformed()
    }

    /**
     * A WELL-FORMED body still reaches the reader: `{}` is refused by the reader's own first rule
     * (the name), never as unreadable — the parse change moved no well-formed body.
     */
    @Test
    fun `a well-formed body still reaches the reader - its own refusal, not malformed_json`() {
        mvc
            .perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(ParameterErrorCodes.NAME_INVALID))
    }

    private fun ResultActions.andExpectMalformed(): ResultActions =
        andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value(ParameterErrorCodes.BODY_INVALID))
            .andExpect(jsonPath("$.error.details.reason").value(ApiErrors.MALFORMED_JSON))

    private companion object {
        const val ROOT = "/api/v1/parameter-sets"

        /** The issue's reproduction: an object opened and never closed. */
        const val MALFORMED = "{"
    }
}
