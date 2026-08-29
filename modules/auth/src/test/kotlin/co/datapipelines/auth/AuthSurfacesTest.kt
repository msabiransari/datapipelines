package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * T31 — the unauthenticated split by client shape: an HTML-accepting browser on a UI route
 * gets a 302 to `/login` with a RELATIVE Location (never `sendRedirect`'s Host-derived
 * absolute URL — a poisoned `Host` header must not aim the redirect elsewhere); the API and
 * MCP surfaces never redirect, whatever they accept; non-HTML clients keep the 401 envelope.
 */
class AuthEntryPointT31Test {
    private val entryPoint =
        AuthEntryPoint(
            AuthErrorWriter(
                com.fasterxml.jackson.databind.json.JsonMapper
                    .builder()
                    .build(),
            ),
        )

    private fun commence(
        uri: String,
        accept: String?,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", uri)
        accept?.let { request.addHeader("Accept", it) }
        val response = MockHttpServletResponse()
        entryPoint.commence(
            request,
            response,
            org.springframework.security.authentication
                .InsufficientAuthenticationException("unauthenticated"),
        )
        return response
    }

    @Test
    fun `a browser on a UI route is 302 to the relative login path`() {
        val response = commence("/pipelines", "text/html,application/xhtml+xml,application/xml;q=0.9,STAR/STAR;q=0.8".replace("STAR", "*"))
        response.status shouldBe 302
        response.getHeader("Location") shouldBe "/login"
    }

    @Test
    fun `the root redirects - and the Location never echoes a Host header`() {
        val response = commence("/", "text/html")
        response.status shouldBe 302
        response.getHeader("Location") shouldBe "/login"
    }

    @Test
    fun `api paths NEVER redirect - even a browser-navigated API URL keeps the 401 JSON`() {
        val response = commence("/api/v1/pipelines", "text/html,application/xhtml+xml")
        response.status shouldBe 401
        response.getHeader("Location") shouldBe null
    }

    @Test
    fun `mcp never redirects`() {
        val response = commence("/mcp", "text/html")
        response.status shouldBe 401
    }

    @Test
    fun `a non-HTML client on a UI path keeps the 401 JSON envelope`() {
        val response = commence("/pipelines", "*/*")
        response.status shouldBe 401
        response.contentAsString shouldNotBe ""
    }
}

/**
 * T33 — the `Secure` cookie flag keyed off `datapipelines.auth.base-url`'s scheme, for all
 * three cookies this module mints. Fail-secure: no base-url keeps the flag.
 */
class SecureCookiesT33Test {
    @Test
    fun `an https base-url keeps dp_session Secure`() {
        val properties = AuthProperties(baseUrl = "https://dp.example.com")
        sessionCookie("jwt", properties).secure shouldBe true
    }

    @Test
    fun `no base-url keeps dp_session Secure - fail-secure default`() {
        sessionCookie("jwt", AuthProperties()).secure shouldBe true
    }

    @Test
    fun `an explicit http base-url drops Secure so local login works`() {
        val properties = AuthProperties(baseUrl = "http://localhost:8080")
        val cookie = sessionCookie("jwt", properties)
        cookie.secure shouldBe false
        cookie.getAttribute("SameSite") shouldBe "Lax"
    }

    @Test
    fun `the oauth2 authorization-request cookie follows the same rule in save and expire`() {
        val jwtService =
            JwtService(
                JwtProperties(
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(ByteArray(32)),
                ),
                AuthProperties(),
            )
        val mapper =
            com.fasterxml.jackson.databind.json.JsonMapper
                .builder()
                .build()

        for (secure in listOf(true, false)) {
            val repository = CookieOAuth2AuthorizationRequestRepository(jwtService, mapper, secureCookies = secure)
            val saved = MockHttpServletResponse()
            repository.saveAuthorizationRequest(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
                    .authorizationCode()
                    .authorizationUri("https://idp.example.com/authorize")
                    .clientId("client")
                    .build(),
                MockHttpServletRequest(),
                saved,
            )
            saved.cookies.single().secure shouldBe secure

            val expired = MockHttpServletResponse()
            repository.expireForTest(expired)
            expired.cookies.single().secure shouldBe secure
        }
    }
}

/** Test reach into the private expire path — same cookie rule as save, asserted for both. */
private fun CookieOAuth2AuthorizationRequestRepository.expireForTest(response: HttpServletResponse) {
    val method = CookieOAuth2AuthorizationRequestRepository::class.java.getDeclaredMethod("expire", HttpServletResponse::class.java)
    method.isAccessible = true
    method.invoke(this, response)
}
