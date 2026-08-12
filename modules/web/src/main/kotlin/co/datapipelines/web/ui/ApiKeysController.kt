package co.datapipelines.web.ui

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class ApiKeysController(
    private val apiKeyRepository: ApiKeyRepository,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/settings/api-keys")
    fun apiKeys(
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = requirePrincipal()
        val keys = apiKeyRepository.findByUser(principal.userId)
        model.addAttribute("keys", keys)
        model.addAttribute("scopes", principal.scopes.map { it.wire }.sorted())
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "settings/api-keys"
    }

    private fun requirePrincipal(): AuthenticatedPrincipal =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
            ?: error("No authenticated principal")
}
