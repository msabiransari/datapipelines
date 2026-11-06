package co.datapipelines.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext

/**
 * #708 / #231 item 1 — the management port's anonymous permit is scoped twice: to the management child
 * context, and to the index plus the two exposed endpoints for reads only. `ApplicationSmokeTest` proves
 * the booted management port answers 200 and the application port refuses; this pins the edges the smoke
 * boot cannot reach — the SAME-port case (an application-context request on an `/actuator` path), a write
 * method, an unexposed endpoint, and a health sub-path.
 */
class ManagementEndpointMatcherTest {
    private val matcher = ManagementEndpointMatcher()

    /** A request whose servlet context's root web context is a web-server context in [namespace] (`null` = the application's). */
    private fun request(
        namespace: String?,
        path: String,
        method: String = "GET",
    ): MockHttpServletRequest {
        val context = AnnotationConfigServletWebServerApplicationContext().apply { serverNamespace = namespace }
        val servletContext =
            MockServletContext().apply {
                setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context)
            }
        return MockHttpServletRequest(servletContext, method, path)
    }

    @Test
    fun `the management context admits reads of the index, health and the scrape`() {
        ManagementEndpointMatcher.PATHS.forEach { path ->
            ManagementEndpointMatcher.METHODS.forEach { method ->
                matcher.matches(request(ManagementEndpointMatcher.MANAGEMENT_NAMESPACE, path, method)) shouldBe true
            }
        }
    }

    @Test
    fun `the application context never matches - the same paths on the application port stay closed`() {
        ManagementEndpointMatcher.PATHS.forEach { path ->
            matcher.matches(request(namespace = null, path = path)) shouldBe false
        }
    }

    @Test
    fun `a request with no web application context does not match`() {
        matcher.matches(MockHttpServletRequest("GET", "/actuator/prometheus")) shouldBe false
    }

    @Test
    fun `writes, unexposed endpoints and sub-paths need a session even on the management port`() {
        val management = ManagementEndpointMatcher.MANAGEMENT_NAMESPACE
        matcher.matches(request(management, "/actuator/prometheus", "POST")) shouldBe false
        matcher.matches(request(management, "/actuator/env")) shouldBe false
        matcher.matches(request(management, "/actuator/heapdump")) shouldBe false
        matcher.matches(request(management, "/actuator/health/liveness")) shouldBe false
        matcher.matches(request(management, "/actuator/prometheus/")) shouldBe false
    }
}
