package co.datapipelines.web.api

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method

/**
 * Every HTTP handler on this module's runtime classpath — `web` and every module below it
 * (`auth`, `application`, `mcp-server`, …), because the scan roots at `co.datapipelines` and
 * not at `co.datapipelines.web` (177 §D.1: a guard that scans one module's package misses the
 * controller another module declares — `modules/app`'s health controller sits ABOVE this module
 * and is covered by `RoleWalkE2eTest`, which walks the running application).
 *
 * Shared by the 177 gates (`ReadFloorTest`, `MatrixRowReachabilityTest`) so the two classify
 * one inventory rather than two scans that could disagree about what a handler is.
 */
object HandlerInventory {
    const val BASE_PACKAGE = "co.datapipelines"

    /** One mapped handler method: verb, full path pattern, `Class#method`, and the catalog permission it declares (null = none). */
    data class Handler(
        val verb: String,
        val path: String,
        val name: String,
        val permission: Permission?,
    ) {
        /** `VERB /path` as auth.md §7.6's Surfaces cell writes it: a path variable's regex dropped (`{category}`). */
        val route: String get() = "$verb ${canonical(path)}"
    }

    /** `{name:regex}` → `{name}` — the doc spells a route without the constraint a handler maps it with. */
    fun canonical(path: String): String = PATH_VARIABLE_CONSTRAINT.replace(path) { "{${it.groupValues[1]}}" }

    private val PATH_VARIABLE_CONSTRAINT = Regex("\\{([A-Za-z0-9_]+):[^}]*}")

    /** Every handler, as Spring would map it: class prefix joined to the method path, one row per verb and path. */
    fun handlers(): List<Handler> =
        controllers().flatMap { type ->
            val classPrefix =
                type
                    .getAnnotation(RequestMapping::class.java)
                    ?.value
                    ?.firstOrNull()
                    .orEmpty()
                    .removeSuffix("/")
            val classPermission = type.getAnnotation(RequiredScope::class.java)?.value
            type.declaredMethods.flatMap { method ->
                val (verbs, paths) = mappingOf(method) ?: return@flatMap emptyList()
                val permission = method.getAnnotation(RequiredScope::class.java)?.value ?: classPermission
                paths.flatMap { path ->
                    val suffix = if (path.isEmpty() || path.startsWith("/")) path else "/$path"
                    val full = (classPrefix + suffix).ifEmpty { "/" }
                    verbs.map { verb -> Handler(verb, full, "${type.simpleName}#${method.name}", permission) }
                }
            }
        }

    /** Every production `@Controller` / `@RestController` under [BASE_PACKAGE] on this classpath. */
    fun controllers(): List<Class<*>> =
        listOf(Controller::class.java, RestController::class.java)
            .flatMap { annotation ->
                ClassPathScanningCandidateComponentProvider(false)
                    .apply { addIncludeFilter(AnnotationTypeFilter(annotation)) }
                    .findCandidateComponents(BASE_PACKAGE)
                    .map(BeanDefinition::getBeanClassName)
                    .filterNotNull()
            }.distinct()
            .map { Class.forName(it) }
            // Production coverage only: the test runtime classpath also holds @RestController
            // test probes (e.g. ApiExceptionHandlerTest.ProbeController), which live in the test
            // compile output and are exercised by their own tests.
            .filter { "/main/" in it.protectionDomain.codeSource.location.path }
            .sortedBy { it.name }

    /** The verb-specific shortcut annotations, each read the same way: `value` + `path`, or the bare class prefix. */
    private val shortcuts: List<Pair<String, (Method) -> Pair<Array<String>, Array<String>>?>> =
        listOf(
            "GET" to { m -> m.getAnnotation(GetMapping::class.java)?.let { it.value to it.path } },
            "POST" to { m -> m.getAnnotation(PostMapping::class.java)?.let { it.value to it.path } },
            "PUT" to { m -> m.getAnnotation(PutMapping::class.java)?.let { it.value to it.path } },
            "PATCH" to { m -> m.getAnnotation(PatchMapping::class.java)?.let { it.value to it.path } },
            "DELETE" to { m -> m.getAnnotation(DeleteMapping::class.java)?.let { it.value to it.path } },
        )

    private fun mappingOf(method: Method): Pair<Set<String>, List<String>>? {
        for ((verb, read) in shortcuts) {
            val (value, path) = read(method) ?: continue
            return setOf(verb) to pathsOf(value, path)
        }
        val mapping = method.getAnnotation(RequestMapping::class.java) ?: return null
        val verbs =
            mapping.method
                .map { it.name }
                .toSet()
                .ifEmpty { setOf("GET", "POST", "PUT", "PATCH", "DELETE") }
        return verbs to pathsOf(mapping.value, mapping.path)
    }

    /** A mapping with neither `value` nor `path` maps the class prefix itself — one empty path. */
    private fun pathsOf(
        value: Array<String>,
        path: Array<String>,
    ): List<String> = (value + path).toList().ifEmpty { listOf("") }
}
