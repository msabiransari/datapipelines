package co.datapipelines.web.authapi

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §16 over mocked services: own-key management, the create request's role (never scopes, #215),
 * the `/auth/me` shape, and the admin user mutations returning the updated record — or the
 * not-found a non-person row gets (A.6).
 */
class AuthControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val userService = mockk<UserService>()

    // 074 §7.7 — the REAL issuance service over the mocked key service, so the controller's
    // kind/bindings handling is exercised rather than stubbed away. The binding repository is
    // relaxed: this suite asks "what was issued", not "what was written to the binding table",
    // which EndpointKeyServiceTest owns. The published-tree mock answers everything empty —
    // these requests carry no bindings — so the #191 bind-time check is a no-op here.
    private val bindingRepository = mockk<EndpointKeyBindingRepository>(relaxed = true)
    private val auditSink = mockk<AuditEventSink>(relaxed = true)
    private val publishedEndpoints = mockk<co.datapipelines.application.endpoints.PublishedEndpointRepository>()

    private val endpointKeys = EndpointKeyService(apiKeyService, bindingRepository, auditSink, publishedEndpoints)
    private val controller = AuthController(apiKeyService, apiKeyRepository, userService, endpointKeys)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        method: AuthMethod = AuthMethod.API_KEY,
        keyId: String? = "dpk_abc",
    ) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "Alice",
                method,
                keyId,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun key(
        id: String,
        revoked: Boolean,
    ) = ApiKey(
        id = id,
        userId = userId,
        name = "agent",
        keyHash = "hash",
        isRevoked = revoked,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        lastUsedAt = null,
        expiresAt = null,
        workspaceId = workspaceId,
        workspaceName = "acme",
    )

    private fun user(
        id: UUID,
        admin: Boolean = false,
        active: Boolean = true,
    ) = User(
        id = id,
        email = "u@example.com",
        displayName = "U",
        provider = "google",
        providerSubject = "sub",
        isActive = active,
        isAdmin = admin,
        createdAt = Instant.parse("2026-08-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

    @Test
    fun `api-keys lists ALL the caller's keys, revoked included, and never a hash or secret`() {
        authenticate()
        every { apiKeyRepository.findByUser(userId) } returns listOf(key("dpk_live", false), key("dpk_dead", true))
        every { userService.snapshot(userId) } returns user(userId)

        val items = controller.listKeys().data

        items.size shouldBe 2
        items[1]["is_revoked"] shouldBe true
        items.forEach {
            it.containsKey("keyHash") shouldBe false
            it.containsKey("key") shouldBe false
        }
    }

    /**
     * #215 §16.1: the create response names the key's ROLE, the IDENTITY it acts as and the
     * person who CREATED it — three facts, since an `endpoint` key is no longer its creator.
     */
    @Test
    fun `create returns the plaintext exactly once, with the role, the identity and the creator`() {
        authenticate()
        val identity = UUID.randomUUID()
        every { apiKeyService.issue(any(), "ci", any(), null, ApiKeyKind.ENDPOINT) } returns
            IssuedApiKey(
                key("dpk_new", false).copy(kind = ApiKeyKind.ENDPOINT, role = KeyRole.API_CALLER, userId = identity, createdBy = userId),
                "dpk_new.secret",
            )
        every { userService.snapshot(identity) } returns user(identity).copy(displayName = "ci")

        val data = controller.createKey(CreateApiKeyRequest(name = "ci", kind = "endpoint")).data
        assertAll(
            { data["key"] shouldBe "dpk_new.secret" },
            { data["kind"] shouldBe "endpoint" },
            { data["role"] shouldBe "api_caller" },
            { data["identity"] shouldBe mapOf("id" to identity.toString(), "display_name" to "ci") },
            { data["created_by"] shouldBe userId.toString() },
            { data.containsKey("scopes") shouldBe false },
        )
    }

    /**
     * 179 (D16) — the default kind is `user`, and `user` is refused on EVERY request surface:
     * a pre-179 client posting what it always posted gets the catalogued 400 that says what
     * changed, never a silently different credential.
     */
    @Test
    fun `an absent or user kind is the not-mintable refusal, and no key is issued`() {
        authenticate()

        val defaulted = shouldThrow<DatapipelinesException> { controller.createKey(CreateApiKeyRequest(name = "claude")) }
        defaulted.code shouldBe "auth.key_kind_not_mintable"

        val explicit =
            shouldThrow<DatapipelinesException> { controller.createKey(CreateApiKeyRequest(name = "claude", kind = "user")) }
        explicit.code shouldBe "auth.key_kind_not_mintable"
        verify(exactly = 0) { apiKeyService.issue(any(), any(), any(), any(), any()) }
    }

    /** #215 PK8: scopes are gone — a request that still sends them is refused BY NAME, before any mint. */
    @Test
    fun `a request that still sends scopes is refused by name before any mint`() {
        authenticate()

        val error =
            shouldThrow<DatapipelinesException> {
                controller.createKey(CreateApiKeyRequest(name = "x", kind = "endpoint", scopes = listOf("author")))
            }
        error.code shouldBe "pipeline.execution.invalid_parameter_type"
        error.details["field"] shouldBe "scopes"
        verify(exactly = 0) { apiKeyService.issue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unknown role token is a 400, and a role the kind cannot hold is refused - neither is minted`() {
        authenticate()

        val unknown =
            shouldThrow<DatapipelinesException> {
                controller.createKey(CreateApiKeyRequest(name = "x", kind = "endpoint", role = "admin"))
            }
        unknown.code shouldBe "pipeline.execution.invalid_parameter_type"

        val mismatched =
            shouldThrow<DatapipelinesException> {
                controller.createKey(CreateApiKeyRequest(name = "x", kind = "endpoint", role = "promotion_receiver"))
            }
        mismatched.code shouldBe "endpoint.key_kind_refused"
        verify(exactly = 0) { apiKeyService.issue(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `me returns the principal shape`() {
        authenticate()
        val data = controller.me().data
        data["user_id"] shouldBe userId.toString()
        // #215: the role the principal is judged as, never a scope set.
        data["role"] shouldBe "viewer"
        data.containsKey("scopes") shouldBe false
        data["auth_method"] shouldBe "API_KEY"
        data["key_id"] shouldBe "dpk_abc"
    }

    @Test
    fun `revoke is creator-scoped and idempotent`() {
        authenticate()
        every { apiKeyService.revoke("dpk_x", userId) } returns false
        controller.revokeKey("dpk_x")
        verify(exactly = 1) { apiKeyService.revoke("dpk_x", userId) }
    }

    @Test
    fun `user admin mutations return the updated record, and an unknown user is 404`() {
        authenticate()
        val target = UUID.randomUUID()
        every { userService.administrableUser(target) } returnsMany listOf(user(target, admin = false), user(target, admin = true))
        every { userService.grantAdmin(target, userId) } returns true

        val response = controller.grantAdmin(target)
        response.statusCode.value() shouldBe 200

        val unknown = UUID.randomUUID()
        every { userService.administrableUser(unknown) } returns null
        controller.deactivate(unknown).statusCode.value() shouldBe 404
    }

    /**
     * #215 A.6 (gate 11): a key's identity and the System row are not people — every user-admin
     * route answers the unknown-user 404 for them, looked up BEFORE the mutation, so nothing is
     * flipped. `administrableUser` is the one lookup (human rows only); the service guards again.
     */
    @Test
    fun `every user-admin route answers 404 for a non-person row, before any mutation`() {
        authenticate()
        val identity = UUID.randomUUID()
        every { userService.administrableUser(identity) } returns null

        assertAll(
            { controller.getUser(identity).statusCode.value() shouldBe 404 },
            { controller.deactivate(identity).statusCode.value() shouldBe 404 },
            { controller.activate(identity).statusCode.value() shouldBe 404 },
            { controller.grantAdmin(identity).statusCode.value() shouldBe 404 },
            { controller.revokeAdmin(identity).statusCode.value() shouldBe 404 },
        )
        verify(exactly = 0) { userService.deactivate(any(), any()) }
        verify(exactly = 0) { userService.activate(any(), any()) }
        verify(exactly = 0) { userService.grantAdmin(any(), any()) }
        verify(exactly = 0) { userService.revokeAdmin(any(), any()) }
    }

    @Test
    fun `user listing paginates`() {
        authenticate()
        every { userService.search("", 0, 3) } returns listOf(user(UUID.randomUUID()), user(UUID.randomUUID()), user(UUID.randomUUID()))
        val data = controller.listUsers(q = null, offset = 0, limit = 2).data
        data.items.size shouldBe 2
        data.pagination.hasMore shouldBe true
    }
}
