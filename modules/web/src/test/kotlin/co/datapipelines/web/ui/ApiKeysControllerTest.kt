package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.IssuedApiKey
import co.datapipelines.auth.Scope
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

class ApiKeysControllerTest {
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = ApiKeysController(apiKeyRepository, themeResolver)

    private val userId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.OIDC,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun sampleKey(id: String = "dpk_abc123") =
        ApiKey(
            id = id,
            userId = userId,
            name = "Test Key",
            keyHash = "hash",
            scopes = setOf(Scope.READ),
            isRevoked = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastUsedAt = null,
            expiresAt = null,
        )

    @Test
    fun `api keys page returns view with keys`() {
        authenticate()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())
        every { themeResolver.resolve(any()) } returns "saas"

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.apiKeys(model, mockk(relaxed = true))

        viewName shouldBe "settings/api-keys"
        @Suppress("UNCHECKED_CAST")
        (model["keys"] as List<*>).size shouldBe 1
        model["activeTheme"] shouldBe "saas"
    }
}

class ApiKeysPartialControllerTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val partialController = ApiKeysPartialController(apiKeyService, apiKeyRepository)

    private val userId = UUID.randomUUID()

    private val principal =
        AuthenticatedPrincipal(
            userId = userId,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.OIDC,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun sampleKey(id: String = "dpk_abc123") =
        ApiKey(
            id = id,
            userId = userId,
            name = "Test Key",
            keyHash = "hash",
            scopes = setOf(Scope.READ),
            isRevoked = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastUsedAt = null,
            expiresAt = null,
        )

    private fun sampleIssued() =
        IssuedApiKey(
            record = sampleKey(),
            plaintext = "dpk_abc123.supersecret",
        )

    @Test
    fun `create returns partial with key plaintext`() {
        authenticate()
        every { apiKeyService.issue(any(), any(), any(), any(), any()) } returns sampleIssued()
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey())

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.create("Test Key", "read", null, model)

        viewName shouldBe "partials/api-key-created"
        model["key"] shouldBe "dpk_abc123.supersecret"
        model["keyId"] shouldBe "dpk_abc123"
    }

    @Test
    fun `revoke returns table rows fragment`() {
        authenticate()
        every { apiKeyService.revoke("dpk_abc123", any()) } returns true
        every { apiKeyRepository.findByUser(any()) } returns listOf(sampleKey().copy(isRevoked = true))

        val response = partialController.revoke("dpk_abc123")

        response.statusCode shouldBe HttpStatus.OK
        response.body!! shouldContain "revoked"
    }
}
