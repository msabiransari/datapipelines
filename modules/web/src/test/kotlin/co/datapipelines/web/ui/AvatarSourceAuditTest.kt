package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #197's second copy — the OIDC picture reaches a page ONLY through the app's own `GET /avatar`
 * proxy ([AvatarController]): the CSP's `img-src` is `'self' data:`, so an `<img>` bound to the
 * stored provider URL (`users.profile_picture_url`) is refused by the browser and renders broken.
 * Lane 197 rewired the shell's avatar menu (layouts/default.html) and left the settings page's
 * identity card (settings/index.html) on the provider URL — the same tag, one file over. A
 * per-template assertion guards one copy; this sweep reads EVERY template, layouts included, and
 * refuses any `src` / `th:src` attribute whose value names the provider URL property.
 */
class AvatarSourceAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep covers the templates and finds both avatars on the proxy`() {
        // Non-vacuity: the resolver must match the app's templates, the two avatars must still
        // exist (their `th:if` reads the property) and both must point at the proxy.
        templates.size shouldBeGreaterThanOrEqual 30
        templates.values.count { it.contains("profilePictureUrl") } shouldBeGreaterThanOrEqual 2
        templates.values.count { it.contains("@{/avatar}") } shouldBeGreaterThanOrEqual 2
    }

    @Test
    fun `no template binds an image source to the stored provider URL`() {
        val offenders = templates.filterValues { SRC_TO_PROVIDER_URL.containsMatchIn(it) }.keys.sorted()
        offenders.shouldBeEmpty()
    }

    companion object {
        /** A `src` or `th:src` attribute whose value mentions the provider URL property. */
        private val SRC_TO_PROVIDER_URL = Regex("""\b(?:th:)?src\s*=\s*"[^"]*profilePictureUrl""")
    }
}
