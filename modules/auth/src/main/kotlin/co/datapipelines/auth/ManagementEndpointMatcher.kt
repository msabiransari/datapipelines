package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.context.support.WebApplicationContextUtils

/**
 * The anonymous permit of the MANAGEMENT port (#231 item 1, decided by the failure-flow record §17 row 11;
 * auth.md §8.6.1): `GET /actuator/health` and `GET /actuator/prometheus` — and the discovery index that links
 * them — answer without a credential there, because the port, not a credential, is the boundary. It binds
 * `127.0.0.1` unless an operator sets `MANAGEMENT_SERVER_ADDRESS` (configuration.md §3.14), and the
 * Kubernetes opt-open pairs it with Service/NetworkPolicy isolation (deployment.md §9). A scraper then needs
 * no key in its configuration, a key that would outlive every rotation discipline.
 *
 * Never a [PublicPaths] entry: that list is the APPLICATION port's contract (auth.md §8.3), and the same
 * `/actuator` paths must stay refused there. Two conditions, both required:
 *  1. the request is served by the management child context — its web server's namespace is
 *     [MANAGEMENT_NAMESPACE]. Boot's `EndpointRequest.toAnyEndpoint()` is NOT this test: it matches on the
 *     application context too whenever the management port equals the server port (`ManagementPortType.SAME`),
 *     which would open these paths on the application port; this matcher never matches there, and `auth`
 *     needs no actuator dependency for it;
 *  2. the method is a read ([METHODS]) and the path is one of [PATHS] exactly. Widening
 *     `management.endpoints.web.exposure.include` (an environment variable away) does not widen the anonymous
 *     surface: `env`, `heapdump` or `loggers` would still need a session.
 */
class ManagementEndpointMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean {
        val context = WebApplicationContextUtils.getWebApplicationContext(request.servletContext)
        return context is WebServerApplicationContext &&
            context.serverNamespace == MANAGEMENT_NAMESPACE &&
            request.method in METHODS &&
            request.appPath() in PATHS
    }

    companion object {
        /** Spring Boot's `WebServerNamespace.MANAGEMENT` value (actuator-autoconfigure; `auth` does not depend on it). */
        const val MANAGEMENT_NAMESPACE = "management"

        /** Reads only: nothing anonymous on the management port changes state. */
        val METHODS: Set<String> = setOf("GET", "HEAD")

        /** The anonymous paths of the management port: the index and the two exposed endpoints (application.yml). */
        val PATHS: Set<String> = setOf("/actuator", "/actuator/health", "/actuator/prometheus")
    }
}
