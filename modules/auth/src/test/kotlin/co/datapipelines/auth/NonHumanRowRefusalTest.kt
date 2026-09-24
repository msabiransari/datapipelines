package co.datapipelines.auth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * #215 A.6 / record gates 5 and 11: only a PERSON signs in, holds a credential or is administered.
 * A key's `service` identity and the System row are refused on every path that could otherwise
 * turn one into a session, a password, an admin or a user-admin target — each guard its own test
 * here, so removing any one of them goes red by name. (OIDC linking and the identity reset are
 * `UserServiceIdentityLinkingTest`'s; membership and invitation acceptance are
 * `WorkspaceServiceTest`'s; the REST and partial user-admin routes are `AuthControllerTest`'s and
 * `AdminUsersControllerTest`'s; the SQL half of the local-credential read is
 * `NonHumanRowRepositoryIntegrationTest`'s.)
 */
class NonHumanRowRefusalTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val properties = AuthProperties()
    private val userService = UserService(userRepository, AuthCache(properties), properties, auditLogger)

    private val identityId = UUID.randomUUID()
    private val systemId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()

    init {
        every { userRepository.findById(identityId) } returns row(identityId, UserKind.SERVICE)
        every { userRepository.findById(systemId) } returns row(systemId, UserKind.SYSTEM)
    }

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun row(
        id: UUID,
        kind: UserKind,
    ) = User(
        id = id,
        email = if (kind == UserKind.SYSTEM) "system@system.invalid" else "dpk_identitykey1@keys.invalid",
        displayName = "row",
        provider = if (kind == UserKind.SYSTEM) UserService.SYSTEM_PROVIDER else UserService.KEY_PROVIDER,
        providerSubject = "subject",
        isActive = true,
        isAdmin = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        kind = kind,
    )

    @Test
    fun `a valid session token naming a non-human row authenticates nobody and clears the cookie`() {
        val secret = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES) { (it + 3).toByte() })
        val jwtService = JwtService(JwtProperties(secret), properties)
        val filter =
            JwtAuthenticationFilter(jwtService, userService, ClientAddressResolver(emptyList()), PrincipalLiveness(userService) { true })

        listOf(identityId, systemId).forEach { id ->
            val token = jwtService.issue(checkNotNull(userRepository.findById(id)))
            val request = MockHttpServletRequest("GET", "/api/v1/pipelines")
            request.setCookies(Cookie(OidcSuccessHandler.SESSION_COOKIE, token))
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, MockFilterChain())

            SecurityContextHolder.getContext().authentication.shouldBeNull()
            response.getCookie(OidcSuccessHandler.SESSION_COOKIE).shouldNotBeNull().maxAge shouldBe 0
        }
    }

    @Test
    fun `a password reset never gives a non-human row a credential`() {
        val passwords =
            LocalPasswordService(userRepository, userService, mockk(relaxed = true), AuthCache(properties), auditLogger, properties)

        passwords.resetPassword(identityId, actorId).shouldBeNull()
        passwords.resetPassword(systemId, actorId).shouldBeNull()

        verify(exactly = 0) { userRepository.setPassword(any(), any(), any()) }
    }

    @Test
    fun `user administration refuses a non-human row before it mutates - activate, deactivate, grant and revoke admin`() {
        listOf(identityId, systemId).forEach { id ->
            userService.administrableUser(id).shouldBeNull()
            userService.deactivate(id, actorId) shouldBe false
            userService.activate(id, actorId) shouldBe false
            userService.grantAdmin(id, actorId) shouldBe false
            userService.revokeAdmin(id, actorId) shouldBe false
        }

        verify(exactly = 0) { userRepository.setActive(any(), any()) }
        verify(exactly = 0) { userRepository.grantAdmin(any()) }
        verify(exactly = 0) { userRepository.revokeAdmin(any()) }
    }

    private companion object {
        const val SECRET_BYTES = 32
    }
}
