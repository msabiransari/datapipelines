package co.datapipelines.web.authapi

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeInsufficientException
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * §16 over mocked services: own-key management (including the scopes-⊆-caller guard surfacing as
 * 403), the `/auth/me` shape, and the admin user mutations returning the updated record.
 */
class AuthControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val userService = mockk<UserService>()
    private val controller = AuthController(apiKeyService, apiKeyRepository, userService)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(
        scopes: Set<Scope>,
        method: AuthMethod = AuthMethod.API_KEY,
        keyId: String? = "dpk_abc",
    ) {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "Alice",
                scopes,
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
        scopes = setOf(Scope.READ),
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
        authenticate(setOf(Scope.READ))
        every { apiKeyRepository.findByUser(userId) } returns listOf(key("dpk_live", false), key("dpk_dead", true))

        val items = controller.listKeys().data

        items.size shouldBe 2
        items[1]["is_revoked"] shouldBe true
        items.forEach {
            it.containsKey("keyHash") shouldBe false
            it.containsKey("key") shouldBe false
        }
    }

    @Test
    fun `create returns the plaintext exactly once`() {
        authenticate(setOf(Scope.ADMIN))
        every { apiKeyService.issue(userId, "claude", setOf(Scope.READ), setOf(Scope.ADMIN), any(), null) } returns
            IssuedApiKey(key("dpk_new", false), "dpk_new.secret")

        val data = controller.createKey(CreateApiKeyRequest(name = "claude", scopes = listOf("read"))).data
        data["key"] shouldBe "dpk_new.secret"
        data["scopes"] shouldBe listOf("read")
    }

    @Test
    fun `scopes outside the caller's own are the 403 the service raises`() {
        authenticate(setOf(Scope.READ))
        every { apiKeyService.issue(userId, "x", setOf(Scope.ADMIN), setOf(Scope.READ), any(), null) } throws
            ScopeInsufficientException(Scope.ADMIN, setOf(Scope.READ))

        val error =
            shouldThrow<ScopeInsufficientException> {
                controller.createKey(CreateApiKeyRequest(name = "x", scopes = listOf("admin")))
            }
        error.code shouldBe "auth.scope.insufficient"
    }

    @Test
    fun `an unknown scope token is a 400, never minted`() {
        authenticate(setOf(Scope.ADMIN))
        shouldThrow<Exception> { controller.createKey(CreateApiKeyRequest(name = "x", scopes = listOf("superuser"))) }
        verify(exactly = 0) { apiKeyService.issue(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `me returns the principal shape`() {
        authenticate(setOf(Scope.EXECUTE))
        val data = controller.me().data
        data["user_id"] shouldBe userId.toString()
        data["scopes"] shouldBe listOf("execute")
        data["auth_method"] shouldBe "API_KEY"
        data["key_id"] shouldBe "dpk_abc"
    }

    @Test
    fun `revoke is owner-scoped and idempotent`() {
        authenticate(setOf(Scope.READ))
        every { apiKeyService.revoke("dpk_x", userId) } returns false
        controller.revokeKey("dpk_x")
        verify(exactly = 1) { apiKeyService.revoke("dpk_x", userId) }
    }

    @Test
    fun `user admin mutations return the updated record, and an unknown user is 404`() {
        authenticate(setOf(Scope.ADMIN))
        val target = UUID.randomUUID()
        every { userService.snapshot(target) } returnsMany listOf(user(target, admin = false), user(target, admin = true))
        every { userService.grantAdmin(target, userId) } returns true

        val response = controller.grantAdmin(target)
        response.statusCode.value() shouldBe 200

        val unknown = UUID.randomUUID()
        every { userService.snapshot(unknown) } returns null
        controller.deactivate(unknown).statusCode.value() shouldBe 404
    }

    @Test
    fun `user listing paginates`() {
        authenticate(setOf(Scope.ADMIN))
        every { userService.search("", 0, 3) } returns listOf(user(UUID.randomUUID()), user(UUID.randomUUID()), user(UUID.randomUUID()))
        val data = controller.listUsers(q = null, offset = 0, limit = 2).data
        data.items.size shouldBe 2
        data.pagination.hasMore shouldBe true
    }
}
