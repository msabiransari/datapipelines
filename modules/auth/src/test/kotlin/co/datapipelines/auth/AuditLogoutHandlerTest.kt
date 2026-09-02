package co.datapipelines.auth

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.util.UUID

/**
 * AU-API-5: `POST /logout` writes the `auth.logout` audit row (auth.md §10.1) with the
 * principal's `user_id`. Without it `audit_log` shows every login and no logout — an
 * incident timeline with only half the story.
 */
class AuditLogoutHandlerTest {
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val handler = AuditLogoutHandler(auditLogger, ClientAddressResolver(emptyList()))

    private val userId = UUID.randomUUID()

    private fun request(): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/logout").apply {
            remoteAddr = "10.0.0.7"
            addHeader("User-Agent", "Mozilla/5.0")
        }

    @Test
    fun `logging out audits auth-logout against the principal's user id`() {
        val principal = AuthenticatedPrincipal(userId, "a@b.com", "A", setOf(Scope.READ), AuthMethod.OIDC, null)
        val authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())

        handler.logout(request(), MockHttpServletResponse(), authentication)

        verify { auditLogger.log("auth.logout", userId, null, "10.0.0.7", "Mozilla/5.0", any()) }
    }

    @Test
    fun `a key-authenticated logout records the key id too`() {
        val principal =
            AuthenticatedPrincipal(userId, "a@b.com", "A", setOf(Scope.READ), AuthMethod.API_KEY, "dpk_ABCDEFGHIJKL")
        val authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())

        handler.logout(request(), MockHttpServletResponse(), authentication)

        verify { auditLogger.log("auth.logout", userId, "dpk_ABCDEFGHIJKL", any(), any(), any()) }
    }

    @Test
    fun `an anonymous logout is still audited, without a user id`() {
        handler.logout(request(), MockHttpServletResponse(), null)

        verify { auditLogger.log("auth.logout", null, null, "10.0.0.7", "Mozilla/5.0", any()) }
    }
}
