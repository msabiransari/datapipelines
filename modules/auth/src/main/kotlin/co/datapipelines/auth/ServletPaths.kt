package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * The request's path **within this application** — `requestURI` with the servlet context
 * path removed (096 §F, review finding F10).
 *
 * `HttpServletRequest.getRequestURI()` includes the context path. Deployed at the root
 * context — which is how this application ships, and the only way it has ever been run —
 * the context path is `""` and the two are identical, which is exactly what makes this a
 * LATENT bug rather than a live one: every path decision in the auth layer was written
 * against `requestURI`, and every one of them would answer differently under
 * `server.servlet.context-path=/dp`. `/mcp` would stop being recognised as `/mcp`, so the
 * MCP endpoint would start accepting session cookies and demanding CSRF tokens; the
 * promotion prefix would stop matching, so its fail-closed gate would go inert; the login
 * limiter would meter nothing. Each of those fails OPEN, and none of them fails loudly.
 *
 * Two sites already stripped it by hand ([AuthEntryPoint] and `PublishedEndpointController`),
 * which is the tell that the omission elsewhere was an oversight rather than a decision.
 * One spelling for all of them; `ServletPathsTest` pins it with a request that actually
 * carries a context path.
 */
fun HttpServletRequest.appPath(): String = requestURI.removePrefix(contextPath)
