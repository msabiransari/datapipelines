package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.config.EndpointsProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * `/api/x` is a MACHINE surface (§5.2): a browser session is refused, whatever it holds.
 *
 * This is a security claim, so it is tested rather than asserted in prose — and the live stack
 * could not prove it: its scaffolded bootstrap password did not match the seeded hash, so no
 * session could be minted there at all.
 *
 * The second half is the part worth having: the refusal happens **before anything is resolved**.
 * A session-holder must not be able to learn which paths are published by reading which of them
 * 404 and which 401, so the registry is never consulted for them.
 */
class PublishedEndpointSessionRefusalTest {
    private val registry = mockk<EndpointRegistry>()
    private val service =
        PublishedEndpointServeService(
            registry = registry,
            bindings = mockk<EndpointKeyBindingRepository>(),
            authorizer = EndpointAuthorizer(),
            readOnlyRule = mockk<ReadOnlyPipelineRule>(),
            pipelines = mockk(),
            runner = mockk(),
            resultStore = mockk(),
            resultUrls = { "https://dp.test/api/v1/executions/$it/result" },
            resultConfig = ResultConfig(),
            endpointsProperties = EndpointsProperties(),
            audit = mockk(relaxed = true),
            authoring = AuthoringGuard(enabled = true),
            scope = CoroutineScope(Dispatchers.Default),
        )

    @Test
    fun `an OIDC session is refused with 401, and the registry is never consulted`() {
        val outcome = service.serve("/nyc/revenue/Manhattan", session(AuthMethod.OIDC), request())

        val refused = outcome.shouldBeInstanceOf<PublishedEndpointServeService.Outcome.Refused>()
        assertAll(
            { refused.status shouldBe 401 },
            { refused.code shouldBe PipelineErrorCodes.Auth.SESSION_REQUIRED },
            // Before resolution: a session-holder cannot map the registry by the shape of the
            // refusal they get back.
            { verify(exactly = 0) { registry.matcher() } },
        )
    }

    @Test
    fun `an admin session is refused too — the surface is about the CREDENTIAL, not the scopes`() {
        service
            .serve("/nyc/revenue/Manhattan", session(AuthMethod.OIDC, Scope.ADMIN), request())
            .shouldBeInstanceOf<PublishedEndpointServeService.Outcome.Refused>()
            .status shouldBe 401
    }

    @Test
    fun `a promotion credential is refused as well`() {
        // It authenticates a deployment-to-deployment channel, not a caller of endpoints.
        service
            .serve("/nyc/revenue/Manhattan", session(AuthMethod.PROMOTION), request())
            .shouldBeInstanceOf<PublishedEndpointServeService.Outcome.Refused>()
            .status shouldBe 401
    }

    private fun session(
        method: AuthMethod,
        scope: Scope = Scope.EXECUTE,
    ) = AuthenticatedPrincipal(
        userId = UUID.randomUUID(),
        email = "a@b.c",
        displayName = "A",
        scopes = setOf(scope),
        authMethod = method,
        workspaceName = "default",
        workspace = WorkspaceContext(UUID.fromString("defa0000-0000-0000-0000-000000000001"), "default"),
    )

    private fun request() =
        EndpointRequestValidator.Request(
            pathVariables = emptyMap(),
            queryParameters = emptyMap(),
            accept = null,
            resultTtlSecondsHeader = null,
            resultPageRowsHeader = null,
        )
}
