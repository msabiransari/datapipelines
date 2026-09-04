package co.datapipelines.web.ui

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.ClientAddressResolver
import co.datapipelines.auth.JwtService
import co.datapipelines.auth.LocalAuthService
import co.datapipelines.auth.User
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Instant
import java.util.UUID

/**
 * [LocalLoginController] — the form half of the login ceremony, in the unit layer beside
 * LocalLoginE2eTest's wire-level coverage. Pins the four outcomes' redirects (unknown
 * email, OIDC-only and wrong password are ONE answer — the §5A.5 no-oracle rule), the
 * workspace resolution call, the session cookie the success mints, and the disabled 404.
 */
class LocalLoginControllerTest {
    private val localAuthService = mockk<LocalAuthService>()
    private val jwtService = mockk<JwtService>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>()
    private val clientAddressResolver = mockk<ClientAddressResolver>()
    private val controller =
        LocalLoginController(
            localAuthService,
            jwtService,
            auditLogger,
            AuthProperties(local = AuthProperties.Local(enabled = true)),
            workspaceService,
            clientAddressResolver,
        )

    private val userId = UUID.randomUUID()

    private fun user() =
        User(
            id = userId,
            email = "local@x.test",
            displayName = "Local",
            provider = UserService.LOCAL_PROVIDER,
            providerSubject = "local@x.test",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun post(
        email: String,
        password: String,
    ): Pair<MockHttpServletRequest, MockHttpServletResponse> {
        val request = MockHttpServletRequest("POST", "/login")
        request.addParameter("email", email)
        request.addParameter("password", password)
        return request to MockHttpServletResponse()
    }

    private fun stubAuthenticate(result: LocalAuthService.LocalLoginResult) {
        every { clientAddressResolver.clientAddressOf(any()) } returns "127.0.0.1"
        every {
            localAuthService.authenticate(any(), any(), any(), any())
        } returns result
    }

    @Test
    fun `a correct password mints the session and lands on the dashboard`() {
        val user = user()
        stubAuthenticate(LocalAuthService.LocalLoginResult.Success(user))
        every { workspaceService.workspaceForLogin(user, user.email) } returns
            co.datapipelines.auth.WorkspaceContext(UUID.randomUUID(), "acme")
        every { jwtService.issue(user, "acme") } returns "jwt-token"

        val (request, response) = post("local@x.test", "correct horse")
        val view = controller.login("local@x.test", "correct horse", request, response)

        view shouldBe "redirect:/dashboard"
        val cookie = response.cookies.single()
        cookie.name shouldBe "dp_session"
        cookie.value shouldBe "jwt-token"
        verify {
            auditLogger.log(
                event = "auth.login.success",
                userId = userId,
                sourceIp = any(),
                userAgent = any(),
                details = any(),
            )
        }
    }

    @Test
    fun `unknown email, oidc-only and wrong password are one answer`() {
        stubAuthenticate(LocalAuthService.LocalLoginResult.BadCredentials)

        val (request, response) = post("who@x.test", "whatever")
        controller.login("who@x.test", "whatever", request, response) shouldBe "redirect:/login?error=credentials"

        response.cookies.size shouldBe 0
    }

    @Test
    fun `a locked account says locked - even with the correct password`() {
        stubAuthenticate(LocalAuthService.LocalLoginResult.Locked)

        val (request, response) = post("local@x.test", "correct horse")
        controller.login("local@x.test", "correct horse", request, response) shouldBe "redirect:/login?error=locked"
    }

    @Test
    fun `a deactivated account says inactive`() {
        stubAuthenticate(LocalAuthService.LocalLoginResult.Inactive(user()))

        val (request, response) = post("local@x.test", "correct horse")
        controller.login("local@x.test", "correct horse", request, response) shouldBe "redirect:/login?error=inactive"
    }

    @Test
    fun `a deployment without local accounts is a 404, not a login error`() {
        val disabled =
            LocalLoginController(
                localAuthService,
                jwtService,
                auditLogger,
                AuthProperties(), // local.enabled defaults false
                workspaceService,
                clientAddressResolver,
            )
        val (request, response) = post("local@x.test", "pw")

        val error =
            io.kotest.assertions.throwables.shouldThrow<org.springframework.web.server.ResponseStatusException> {
                disabled.login("local@x.test", "pw", request, response)
            }
        error.statusCode shouldBe org.springframework.http.HttpStatus.NOT_FOUND
    }

    @Test
    fun `the controller passes the submission through - normalization is the service's boundary`() {
        val user = user()
        stubAuthenticate(LocalAuthService.LocalLoginResult.Success(user))
        every { workspaceService.workspaceForLogin(user, user.email) } returns null
        every { jwtService.issue(user, null) } returns "jwt"

        val (request, response) = post("  LOCAL@X.TEST ", "pw")
        controller.login("  LOCAL@X.TEST ", "pw", request, response)

        verify { localAuthService.authenticate("  LOCAL@X.TEST ", "pw", any(), any()) }
        response.cookies.single().value shouldBe "jwt"
    }
}
