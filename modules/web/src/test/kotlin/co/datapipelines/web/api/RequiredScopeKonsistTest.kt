package co.datapipelines.web.api

import co.datapipelines.auth.PublicPaths
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import org.junit.jupiter.api.Test
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser
import java.io.File

/**
 * Konsist twin of [RequiredScopeCoverageTest] (module-structure.md §7.8): every HTTP
 * handler function on a `@RestController` or `@Controller` must declare its
 * `@RequiredScope`, at function or class level — unless the path it serves is on the §8.3
 * public allowlist, which is the runtime's own rule (`ScopeInterceptor.isScopeGoverned`).
 *
 * The two guards overlap DELIBERATELY (006 prompt): the reflection-based test proves
 * runtime wiring against the live classpath; this one proves the same rule statically
 * from sources — it catches the violation even in code that fails to compile into a
 * runnable context, and it needs no Spring runtime to do it.
 *
 * ## Every module, from the Gradle project set (177 §D.1)
 * The scope is EVERY project `settings.gradle.kts` includes that has `src/main/kotlin` —
 * discovered by parsing the include block, never a hand-written list — so a controller in
 * `modules/app` (above `web`, invisible to the reflective twin) is read here, and a module
 * added tomorrow is covered the day it lands. An unannotated non-public handler in ANY module
 * fails by `Class#function`.
 */
class RequiredScopeKonsistTest {
    private val parser = PathPatternParser()
    private val publicPatterns = PublicPaths.PATTERNS.map { parser.parse(it) }

    @Test
    fun `every non-public handler in every module declares its required scope`() {
        val missing =
            controllers().flatMap { controller ->
                val classLevel = controller.hasAnnotationWithName("RequiredScope")
                val prefix = mappedPaths(controller).firstOrNull().orEmpty().removeSuffix("/")
                controller
                    .functions()
                    .filter { fn -> HTTP_MAPPINGS.any(fn::hasAnnotationWithName) }
                    .filterNot { fn -> classLevel || fn.hasAnnotationWithName("RequiredScope") }
                    .filterNot { fn -> paths(fn, prefix).all(::isPublic) }
                    .map { fn -> "${controller.name}#${fn.name} (${controller.path})" }
            }
        missing.shouldBeEmpty()
    }

    /** A guard that scans an empty scope proves nothing — the scan must see the controllers, in more than one module. */
    @Test
    fun `the source scan finds the controllers across the project set`() {
        val found = controllers()
        found.map { it.name } shouldHaveAtLeastSize 8
        // Above `web`: the reflective twin cannot see it, this one must.
        found.map { it.name } shouldContain "HealthController"
        moduleSourceRoots() shouldHaveAtLeastSize MINIMUM_MODULES
    }

    private fun controllers(): List<KoClassDeclaration> =
        moduleSourceRoots().flatMap { root ->
            Konsist
                .scopeFromDirectory(root)
                .classes()
                .filter { it.hasAnnotationWithName("RestController") || it.hasAnnotationWithName("Controller") }
        }

    /**
     * `src/main/kotlin` of every included project, from `settings.gradle.kts` — the Gradle
     * project set is the authority for "which modules exist". Konsist resolves scope paths
     * against the root project it detects, so the roots are repo-relative.
     */
    private fun moduleSourceRoots(): List<String> {
        val settings = repoFile("settings.gradle.kts").readText()
        val includeBlock = settings.substringAfter("include(").substringBefore(")")
        return PROJECT_PATH
            .findAll(includeBlock)
            .map { it.groupValues[1].trimStart(':').replace(':', '/') }
            .map { "$it/src/main/kotlin" }
            .filter { repoFile(it, mustExist = false).isDirectory }
            .toList()
    }

    private fun mappedPaths(controller: KoClassDeclaration): List<String> =
        controller.annotations
            .filter { it.name == "RequestMapping" }
            .flatMap { annotation -> annotation.arguments.mapNotNull { it.value }.map { it.trim('"') } }

    /** The full paths a handler serves: the class prefix joined to each path the mapping names ("" when it names none). */
    private fun paths(
        fn: KoFunctionDeclaration,
        prefix: String,
    ): List<String> {
        val own =
            fn.annotations
                .filter { it.name in HTTP_MAPPINGS }
                .flatMap { annotation ->
                    annotation.arguments
                        .filter { it.name.isNullOrEmpty() || it.name == "value" || it.name == "path" }
                        .mapNotNull { it.value }
                        .map { it.trim('"') }
                }.ifEmpty { listOf("") }
        return own.map { path -> (prefix + if (path.isEmpty() || path.startsWith("/")) path else "/$path").ifEmpty { "/" } }
    }

    private fun isPublic(path: String): Boolean = publicPatterns.any { it.matches(PathContainer.parsePath(path)) }

    private companion object {
        val HTTP_MAPPINGS =
            listOf(
                "RequestMapping",
                "GetMapping",
                "PostMapping",
                "PutMapping",
                "DeleteMapping",
                "PatchMapping",
            )

        /** `":modules:web"` inside the include block. */
        val PROJECT_PATH = Regex("\"(:[a-z0-9:_-]+)\"")

        /** The project set had 12 modules on 2026-09-20; a scan seeing fewer than a handful is a broken parse. */
        const val MINIMUM_MODULES = 5

        fun repoFile(
            relativePath: String,
            mustExist: Boolean = true,
        ): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) {
                    val candidate = File(dir, relativePath)
                    if (!mustExist || candidate.exists()) return candidate
                    error("$relativePath not found under repository root $dir")
                }
                dir = dir.parentFile
            }
            error("repository root (settings.gradle.kts) not found walking up from ${File("").absolutePath}")
        }
    }
}
