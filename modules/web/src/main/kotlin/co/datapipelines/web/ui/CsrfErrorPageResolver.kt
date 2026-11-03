package co.datapipelines.web.ui

import co.datapipelines.auth.AuthAttributes
import co.datapipelines.auth.CsrfInvalidException
import co.datapipelines.web.api.CorrelationId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView

/**
 * Renders the browser-shaped CSRF refusal (#782) as the error/403 PAGE (ui-screens §5.1).
 *
 * The auth module's access-denied handler forwards a refused browser form navigation to the
 * error page with the refusal stashed in [AuthAttributes.CSRF_REFUSAL]; Boot's error
 * controller asks its ErrorViewResolvers, and this one claims requests carrying the stash.
 * Resolvers WITHOUT a claim return null — Boot then falls through to the default resolution
 * (the plain error/403 for a plain 403, whitelabel otherwise), so a future non-CSRF
 * sendError source keeps today's behaviour. The `@Order(HIGHEST_PRECEDENCE)` is the same
 * load-bearing order the UI advice carries: Boot's DefaultErrorViewResolver sits behind it,
 * and only a claiming resolver may answer.
 *
 * The page carries the refusal's FIXED user message (`th:text`-rendered, never request
 * data), the request's correlation id, and the deployment theme. The correlation id reads
 * the MDC directly: the forward runs MID-CHAIN, before the correlation filter's finally
 * clears the slot, so it is the same id the response header carries.
 *
 * Declared as an explicit `@Bean` in [UiConfig] (with the `@Order(HIGHEST_PRECEDENCE)` there),
 * not by a stereotype: the house rule is zero DI stereotypes (015 / module-structure §8.4).
 */
class CsrfErrorPageResolver(
    private val uiProperties: UiProperties = UiProperties(),
) : ErrorViewResolver {
    override fun resolveErrorView(
        request: HttpServletRequest,
        @Suppress("UNUSED_PARAMETER") status: HttpStatus,
        @Suppress("UNUSED_PARAMETER") model: Map<String, Any?>,
    ): ModelAndView? {
        val refusal = request.getAttribute(AuthAttributes.CSRF_REFUSAL) as? CsrfInvalidException ?: return null
        val page = ModelAndView("error/403", HttpStatus.FORBIDDEN)
        page.addObject("detail", refusal.userMessage)
        page.addObject("correlationId", CorrelationId.current())
        page.addObject("activeTheme", uiProperties.theme)
        return page
    }
}
