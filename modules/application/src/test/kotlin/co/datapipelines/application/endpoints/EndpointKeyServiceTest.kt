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
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
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
    private val publishedEndpoints = mockk<PublishedEndpointRepository>()
    private val service = EndpointKeyService(apiKeys, bindings, audit, publishedEndpoints)

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
    fun `a USER key is refused on this surface for every role - the login hook mints those (D16)`() {
        // 179: on-demand `user` minting is gone, with or without bindings, with or without
        // scopes. The refusal is FIRST in the funnel so it never depends on which other
        // argument would have failed, and it is its own catalogued code because the recovery
        // differs: sign in, don't retry with different arguments.
        listOf(emptyList(), listOf("/nyc")).forEach { paths ->
            val refused =
                shouldThrow<DatapipelinesException> {
                    service.issue(principal(), "x", setOf(Scope.EXECUTE), ApiKeyKind.USER, paths, null)
                }
            refused.code shouldBe "auth.key_kind_not_mintable"
        }
    }

    @Test
    fun `the kind matrix - each kind accepts exactly what its authority is made of (091, 179)`() {
        // One table for the whole rule, because the failure it guards against is a kind added
        // later falling through a check written as "is user" instead of "is not endpoint".
        // Since 179 the USER row is a refusal in every column: the login hook mints those.
        stubIssue()

        assertAll(
            // user: never mintable on demand (D16)
            { notMintable { service.issue(principal(), "u", setOf(Scope.READ), ApiKeyKind.USER, emptyList(), null) } },
            { notMintable { service.issue(principal(), "u", emptySet(), ApiKeyKind.USER, listOf("/nyc"), null) } },
            // scopes: refused on both scopeless kinds
            { refusalFor { service.issue(principal(), "e", setOf(Scope.READ), ApiKeyKind.ENDPOINT, emptyList(), null) } },
            { refusalFor { service.issue(principal(), "s", setOf(Scope.READ), ApiKeyKind.SERVER, emptyList(), null) } },
            // bindings: only an ENDPOINT key
            { service.issue(principal(), "e", emptySet(), ApiKeyKind.ENDPOINT, listOf("/nyc"), null) },
            { refusalFor { service.issue(principal(), "s", emptySet(), ApiKeyKind.SERVER, listOf("/nyc"), null) } },
            // expiry: every kind takes one — a promotion credential that never expires is the
            // whole problem 091 set out to fix.
            { service.issue(principal(), "s", emptySet(), ApiKeyKind.SERVER, emptyList(), Instant.parse("2027-01-01T00:00:00Z")) },
        )
    }

    @Test
    fun `bindings on a SERVER key are refused, and none are written`() {
        // The specific fall-through the matrix above guards: before 091's fix the binding check
        // read `kind == USER`, so a server key's bindings would have been written and then
        // consulted by nothing — an authority the operator believes in and the system ignores.
        stubIssue()

        val refused =
            shouldThrow<DatapipelinesException> {
                service.issue(principal(), "s", emptySet(), ApiKeyKind.SERVER, listOf("/nyc"), null)
            }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED },
            { verify(exactly = 0) { bindings.insert(any()) } },
        )
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

        service.issue(principal(), "n", emptySet(), ApiKeyKind.ENDPOINT, listOf("nyc/v1/revenue/"), null)

        written.captured.pathPrefix shouldBe "/nyc/v1/revenue"
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
        every { bindings.delete("/nyc", KEY_ID, any()) } returns true

        assertAll(
            { service.bind(principal(), KEY_ID, "/nyc") shouldBe true },
            { service.unbind(principal(), KEY_ID, "/nyc") shouldBe true },
            { verify(exactly = 1) { audit.log("endpoint.key_bound", any(), KEY_ID, any(), any(), any()) } },
            { verify(exactly = 1) { audit.log("endpoint.key_unbound", any(), KEY_ID, any(), any(), any()) } },
        )
    }

    // ----------------------------------------------------------------------- #191

    @Test
    fun `a prefix outside the workspace's published tree is refused at bind (#191)`() {
        // The workspace publishes under /nyc and /trade only; /lending belongs to nobody here.
        // The refusal names only the caller's own tree — whether ANOTHER workspace publishes at
        // /lending is exactly what the message must not reveal.
        val refused =
            shouldThrow<DatapipelinesException> {
                service.bind(principal(), KEY_ID, "/lending")
            }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID },
            { refused.message.orEmpty() shouldContain "your workspace" },
            { refused.message.orEmpty() shouldNotContain "other" },
            { verify(exactly = 0) { bindings.insert(any()) } },
            { verify(exactly = 0) { audit.log(any(), any(), any(), any(), any(), any()) } },
        )
    }

    @Test
    fun `a prefix the workspace publishes - or an ancestor of one - is accepted (#191)`() {
        every { bindings.insert(any()) } returns true

        assertAll(
            { service.bind(principal(), KEY_ID, "/nyc") shouldBe true },
            { service.bind(principal(), KEY_ID, "/nyc/v1") shouldBe true },
            { service.bind(principal(), KEY_ID, "/nyc/v1/revenue") shouldBe true },
        )
    }

    @Test
    fun `issue validates its binding paths against the workspace's published tree too (#191)`() {
        // The same property at the mint-and-bind funnel: a key cannot be CREATED bound at a
        // foreign prefix any more than it can be bound there later.
        val refused =
            shouldThrow<DatapipelinesException> {
                service.issue(principal(), "x", emptySet(), ApiKeyKind.ENDPOINT, listOf("/lending"), null)
            }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID },
            { verify(exactly = 0) { apiKeys.issue(any(), any(), any(), any(), any(), any(), any()) } },
        )
    }

    @Test
    fun `the root binding stays legal - it is confined at serve time, not refused at bind (#191)`() {
        // Binding at `/` was always legal and stays so; the #191 rule that keeps it inside the
        // workspace lives in EndpointAuthorizer, not here. Refusing it here would break a real
        // operator intent the form still offers first.
        every { bindings.insert(any()) } returns true

        service.bind(principal(), KEY_ID, "/") shouldBe true
    }

    /** The workspace's published tree the #191 bind-time check reads: two patterns under /nyc and /trade. */
    @BeforeEach
    fun stubPublishedTree() {
        every { publishedEndpoints.findByWorkspace(neq(WORKSPACE)) } returns emptyList()
        every { publishedEndpoints.findByWorkspace(WORKSPACE) } returns
            listOf(publishedAt("/nyc/v1/revenue/{borough}"), publishedAt("/trade/v1/summary"))
    }

    private fun publishedAt(pattern: String) =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = WORKSPACE,
            pathPattern = pattern,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 60,
            description = "",
            isEnabled = true,
            createdBy = ACTOR,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

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

    /** Asserts the block refuses with the kind-contradiction code. */
    private fun refusalFor(block: () -> Unit) {
        shouldThrow<DatapipelinesException> { block() }.code shouldBe PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
    }

    /** Asserts the block refuses with the D16 not-mintable-on-demand code. */
    private fun notMintable(block: () -> Unit) {
        shouldThrow<DatapipelinesException> { block() }.code shouldBe "auth.key_kind_not_mintable"
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
