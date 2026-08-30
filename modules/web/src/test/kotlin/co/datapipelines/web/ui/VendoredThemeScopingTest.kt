package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * T40 cause 1's regression guard: every vendored theme file must be scoped to `:root`.
 *
 * The app's theming is a FILE SWAP — the layout loads exactly ONE `themes/{name}.css`
 * through `#theme-link` (pipeline-editor.md §3.4), so a theme file takes effect by being
 * loaded, not by matching an attribute on `<html>`. `dark.css` shipped scoped to
 * `[data-theme="dark"], .dark` while nothing in the app ever stamped that attribute —
 * every rule in the file was inert and the Dark preference rendered the tokenless
 * default look (024 T40, fixed upstream + re-synced in 027).
 *
 * A theme file that re-introduces attribute/class scoping compiles, serves, and swaps
 * "successfully" while rendering nothing — this test is what sees it. The non-vacuity
 * floor is the enumeration itself: it must list every vendored theme, including `dark`.
 */
class VendoredThemeScopingTest {
    @Test
    fun `every vendored theme file is root-scoped for the file-swap architecture`() {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val resources =
            resolver
                .getResources("classpath*:static/vendor/design-system/themes/*.css")
                .filter { it.filename != null }
        resources.shouldNotBeEmpty()
        val offenders = resources.filterNot { it.isRootScoped() }
        offenders.map { it.filename } shouldBe emptyList()
    }

    @Test
    fun `the guard sees the dark theme - a vacuous pass is not a pass`() {
        val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        resolver
            .getResources("classpath*:static/vendor/design-system/themes/*.css")
            .mapNotNull { it.filename }
            .let { names ->
                names.shouldNotBeEmpty()
                names.filter { it.startsWith("dark") } shouldBe listOf("dark.css")
            }
    }

    /** Strip block comments, then the selector of the FIRST rule must be exactly `:root`. */
    private fun org.springframework.core.io.Resource.isRootScoped(): Boolean {
        val css = inputStream.readBytes().decodeToString()
        val noComments = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val firstBrace = noComments.indexOf('{')
        if (firstBrace < 0) return false
        val firstSelector = noComments.substring(0, firstBrace).trim()
        // Attribute-scoped rules ANYWHERE in the file would equally strand the file-swap.
        return firstSelector == ":root" && "[data-theme" !in noComments
    }
}
