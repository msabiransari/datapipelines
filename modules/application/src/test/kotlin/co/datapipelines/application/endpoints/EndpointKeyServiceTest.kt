package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * Minting a key together with its endpoint bindings (§5.2/§7.7).
 *
 * Two properties carry the weight:
 *
 * 1. **Bindings are validated BEFORE the key is minted.** The plaintext key is returned exactly
 *    once, so a failure between mint and bind leaves an operator holding a secret they can
 *    neither use nor re-read. `a malformed binding path is refused before anything is minted` is
 *    what makes that ordering checkable.
 * 2. **Contradictions are refused out loud, not quietly normalised.** A caller who writes
 *    `{"kind": "endpoint", "scopes": ["admin"]}` holds a mental model this surface has to
 *    correct; silently dropping the scopes would leave them believing the key carries admin.
 */
class EndpointKeyServiceTest {
    private val apiKeys = mockk<ApiKeyService>()
    private val bindings = mockk<EndpointKeyBindingRepository>(relaxed = true)
    private val audit = mockk<AuditEventSink>(relaxed = true)
    private val service = EndpointKeyService(apiKeys, bindings, audit)

    @Test
    fun `an endpoint key is minted with its bindings, in one call`() {
        stubIssue()
        val written = mutableListOf<EndpointKeyBinding>()
        every { bindings.insert(capture(written)) } returns true

        service.issue(principal(), "nyc", emptySet(), ApiKeyKind.ENDPOINT, listOf("/nyc", "/trade"), null)

        assertAll(
            { written.map { it.pathPrefix } shouldBe listOf("/nyc", "/trade") },
            { written.all { it.apiKeyId == KEY_ID } shouldBe true },
            { verify(exactly = 2) { audit.log("endpoint.key_bound", any(), any(), any(), any(), any()) } },
        )
    }

    @Test
    fun `a malformed binding path is refused before anything is minted`() {
        // The ordering that keeps an operator from holding an unusable secret.
        val refused =
            shouldThrow<DatapipelinesException> {
                service.issue(principal(), "bad", emptySet(), ApiKeyKind.ENDPOINT, listOf("/Bad Path"), null)
            }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID },
            { verify(exactly = 0) { apiKeys.issue(any(), any(), any(), any(), any(), any(), any()) } },
            { verify(exactly = 0) { bindings.insert(any()) } },
        )
    }

    @Test
    fun `asking for scopes on an endpoint key is refused rather than quietly dropped`() {
        val refused =
            shouldThrow<DatapipelinesException> {
                service.issue(principal(), "x", setOf(Scope.ADMIN), ApiKeyKind.ENDPOINT, emptyList(), null)
            }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED },
            { refused.message.orEmpty() shouldContain "no scopes" },
        )
    }

    @Test
    fun `bindings on a USER key are refused — a user key is authorised by its scopes`() {
        val refused =
            shouldThrow<DatapipelinesException> {
                service.issue(principal(), "x", setOf(Scope.EXECUTE), ApiKeyKind.USER, listOf("/nyc"), null)
            }

        refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
    }

    @Test
    fun `an endpoint key with no bindings is legal — it simply authorises nothing`() {
        // Coherent to mint and bind later, and the §5.2 rule already says an unbound endpoint key
        // authorises nothing. Refusing it here would be a second, contradictory rule.
        stubIssue()

        service.issue(principal(), "later", emptySet(), ApiKeyKind.ENDPOINT, emptyList(), null)

        verify(exactly = 0) { bindings.insert(any()) }
    }

    @Test
    fun `binding paths are normalised — a trailing slash and a missing leading slash agree`() {
        stubIssue()
        val written = slot<EndpointKeyBinding>()
        every { bindings.insert(capture(written)) } returns true

        service.issue(principal(), "n", emptySet(), ApiKeyKind.ENDPOINT, listOf("nyc/revenue/"), null)

        written.captured.pathPrefix shouldBe "/nyc/revenue"
    }

    @Test
    fun `the root is a legal binding even though it is not a legal endpoint`() {
        // Binding at `/` authorises the whole tree — a real operator intent. Publishing AT `/`
        // is not an endpoint anyone can name, which is why the grammar refuses it.
        stubIssue()
        val written = slot<EndpointKeyBinding>()
        every { bindings.insert(capture(written)) } returns true

        service.issue(principal(), "root", emptySet(), ApiKeyKind.ENDPOINT, listOf("/"), null)

        assertAll(
            { written.captured.pathPrefix shouldBe "/" },
            { EndpointPath.parse("/").isFailure shouldBe true },
        )
    }

    @Test
    fun `bind and unbind are audited with the node they touched`() {
        every { bindings.insert(any()) } returns true
        every { bindings.delete("/nyc", KEY_ID) } returns true

        assertAll(
            { service.bind(principal(), KEY_ID, "/nyc") shouldBe true },
            { service.unbind(principal(), KEY_ID, "/nyc") shouldBe true },
            { verify(exactly = 1) { audit.log("endpoint.key_bound", any(), KEY_ID, any(), any(), any()) } },
            { verify(exactly = 1) { audit.log("endpoint.key_unbound", any(), KEY_ID, any(), any(), any()) } },
        )
    }

    private fun stubIssue() {
        every { apiKeys.issue(any(), any(), any(), any(), any(), any(), any()) } returns
            IssuedApiKey(
                record =
                    ApiKey(
                        id = KEY_ID,
                        userId = ACTOR,
                        name = "k",
                        keyHash = "hash",
                        scopes = emptySet(),
                        isRevoked = false,
                        createdAt = Instant.EPOCH,
                        lastUsedAt = null,
                        expiresAt = null,
                        workspaceId = WORKSPACE,
                        workspaceName = "default",
                        kind = ApiKeyKind.ENDPOINT,
                    ),
                plaintext = "$KEY_ID.secret",
            )
    }

    private fun principal() =
        AuthenticatedPrincipal(
            userId = ACTOR,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_CREATORAAAAA",
            workspaceName = "default",
            workspace = WorkspaceContext(WORKSPACE, "default"),
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val KEY_ID = "dpk_ABCDEFGHIJKL"
    }
}
