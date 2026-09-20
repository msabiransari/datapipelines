package co.datapipelines.web.api

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
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

    /** One mapped handler method: verb, full path pattern, `Class#method`, and the operation it declares (null = none). */
    data class Handler(
        val verb: String,
        val path: String,
        val name: String,
        val operation: ScopeMatrix.RestOperation?,
    )

    /** Every handler, as Spring would map it: class prefix joined to the method path, one row per verb and path. */
    fun handlers(): List<Handler> =
        controllers().flatMap { type ->
            val classPrefix = type.getAnnotation(RequestMapping::class.java)?.value?.firstOrNull().orEmpty().removeSuffix("/")
            val classOperation = type.getAnnotation(RequiredScope::class.java)?.value
            type.declaredMethods.flatMap { method ->
                val (verbs, paths) = mappingOf(method) ?: return@flatMap emptyList()
                val operation = method.getAnnotation(RequiredScope::class.java)?.value ?: classOperation
                paths.flatMap { path ->
                    val full = (classPrefix + if (path.isEmpty() || path.startsWith("/")) path else "/$path").ifEmpty { "/" }
                    verbs.map { verb -> Handler(verb, full, "${type.simpleName}#${method.name}", operation) }
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
            .filter { it.protectionDomain.codeSource.location.path.contains("/main/") }
            .sortedBy { it.name }

    private fun mappingOf(method: Method): Pair<Set<String>, List<String>>? {
        method.getAnnotation(GetMapping::class.java)?.let { return setOf("GET") to (it.value + it.path).toList().ifEmpty { listOf("") } }
        method.getAnnotation(PostMapping::class.java)?.let { return setOf("POST") to (it.value + it.path).toList().ifEmpty { listOf("") } }
        method.getAnnotation(PutMapping::class.java)?.let { return setOf("PUT") to (it.value + it.path).toList().ifEmpty { listOf("") } }
        method.getAnnotation(PatchMapping::class.java)?.let { return setOf("PATCH") to (it.value + it.path).toList().ifEmpty { listOf("") } }
        method.getAnnotation(DeleteMapping::class.java)?.let { return setOf("DELETE") to (it.value + it.path).toList().ifEmpty { listOf("") } }
        method.getAnnotation(RequestMapping::class.java)?.let { mapping ->
            val verbs = mapping.method.map { it.name }.toSet().ifEmpty { setOf("GET", "POST", "PUT", "PATCH", "DELETE") }
            return verbs to (mapping.value + mapping.path).toList().ifEmpty { listOf("") }
        }
        return null
    }
}
