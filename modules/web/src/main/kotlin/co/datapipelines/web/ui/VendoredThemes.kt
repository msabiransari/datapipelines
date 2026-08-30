package co.datapipelines.web.ui

import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File

/**
 * The vendored theme CSS files under the design-system directory (T21's class of bug, 025
 * B1): resolving a classpath DIRECTORY through `File(getResource(...).toURI())` works only
 * on an exploded classpath — inside a jar the URL is `jar:...` and `File(...)` dies with
 * `IllegalArgumentException: URI is not hierarchical`. Both consumers of the theme
 * listing shared that flaw: `/settings` 500'd on it, and `ConfigValidator`'s startup theme
 * check silently deferred (`vendoredThemes() == null`) in every jar deployment.
 *
 * The fix is the pattern resolver: `classpath*:.../themes/` + `*.css` enumerates jar
 * entries through the classloader (Spring Boot's `LaunchedURLClassLoader` included), so
 * the SAME code lists themes exploded or packaged.
 *
 * Null means "no vendored theme assets on this classpath at all" — the callers
 * own what that implies (settings shows an EMPTY listing — no fallback list, 027:
 * a classpath without the assets cannot serve any theme it would name; §7 defers
 * the startup check).
 */
object VendoredThemes {
    internal const val THEME_DIR = "static/vendor/design-system/themes"

    /**
     * The vendored theme names, sorted; null when the design-system theme directory is not
     * on [classLoader]'s classpath at all. Never throws — a classpath that cannot be
     * enumerated is the absent case.
     */
    fun names(classLoader: ClassLoader = VendoredThemes::class.java.classLoader): List<String>? {
        val resources =
            runCatching {
                PathMatchingResourcePatternResolver(classLoader).getResources(PATTERN)
            }.getOrNull() ?: return null
        return resources
            .mapNotNull { it.filename?.removeSuffix(CSS_SUFFIX) }
            .sorted()
            .ifEmpty { null }
    }

    private const val PATTERN = "classpath*:$THEME_DIR/*.css"
    private const val CSS_SUFFIX = ".css"
}
