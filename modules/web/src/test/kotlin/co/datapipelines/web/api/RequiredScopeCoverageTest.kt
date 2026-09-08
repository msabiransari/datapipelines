package co.datapipelines.web.api

import co.datapipelines.auth.PublicPaths
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeInterceptor
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.server.PathContainer
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

/**
 * The mechanical guard for `auth`'s default-deny (AUTH-SEC-9): every handler this module
 * exposes under `/api/v1` must declare its §7.6 operation. `ScopeInterceptor` denies an
 * unannotated handler at run time; this test fails the build at the moment one is added.
 *
 * The controller list is a **classpath scan** of `co.datapipelines.web` for `@RestController`,
 * not a hand-maintained list: a controller added to the module is covered the moment it lands,
 * with nothing to remember to update. (Born 2026-08-14 as a fix-cycle finding: the schema
 * introspection controller shipped absent from the previous hand-list, uncovered.)
 */
class RequiredScopeCoverageTest {
    private val controllers: List<KClass<*>> = scanForRestControllers()

    @Test
    fun `every api handler declares its section-7-6 operation`() {
        val missing = mutableListOf<String>()
        controllers.forEach { controller ->
            val classLevel = controller.findAnnotation<RequiredScope>() != null
            controller.functions
                .filter { it.javaMethod?.getAnnotation(RequestMapping::class.java) != null || isHttpHandler(it.javaMethod) }
                .forEach { fn ->
                    val annotated = fn.findAnnotation<RequiredScope>() != null || classLevel
                    if (!annotated) missing.add("${controller.simpleName}#${fn.name}")
                }
        }
        missing shouldBe emptyList()
    }

    /**
     * The /partials arm of the same guard (022 review F6): the htmx partials are reachable
     * with an API key like any route, and `ScopeInterceptor` now default-denies unannotated
     * handlers there — this fails the build the moment an unannotated partial lands. UI
     * page handlers (full-screen GETs outside `/partials`) are governed by the filter
     * chain's authentication rule, not the scope matrix.
     */
    @Test
    fun `every partials handler declares its section-7-6 operation`() {
        val missing = mutableListOf<String>()
        uiControllers().forEach { controller ->
            val classLevelScope = controller.findAnnotation<RequiredScope>() != null
            controller.functions
                .filter { isHttpHandler(it.javaMethod) }
                .filter { fn -> fullPath(controller, fn.javaMethod).startsWith(PARTIALS_PREFIX) }
                .forEach { fn ->
                    val annotated = fn.findAnnotation<RequiredScope>() != null || classLevelScope
                    if (!annotated) missing.add("${controller.simpleName}#${fn.name}")
                }
        }
        missing shouldBe emptyList()
    }

    /**
     * Non-vacuity for the arm above (096 §D, review finding F6). The arm used to test the
     * METHOD path against `/partials/`, so the five handlers on `DashboardPartialController`
     * and `ExecutionHistoryPartialController` — which declare `@RequestMapping("/partials")`
     * at CLASS level, no trailing slash — were silently skipped by the guard. All five were
     * annotated, so nothing was broken; the guard simply was not guarding them, and a sixth
     * arriving unannotated would have shipped. Naming the two controllers here is what stops
     * a future normalisation regression from re-hiding them.
     */
    @Test
    fun `the partials arm actually reaches the class-level -partials controllers`() {
        val reached =
            uiControllers()
                .flatMap { controller ->
                    controller.functions
                        .filter { isHttpHandler(it.javaMethod) }
                        .filter { fn -> fullPath(controller, fn.javaMethod).startsWith(PARTIALS_PREFIX) }
                        .map { fn -> "${controller.simpleName}#${fn.name}" }
                }.toSet()

        listOf(
            "DashboardPartialController#stats",
            "ExecutionHistoryPartialController#listPartial",
        ).forEach { reached shouldContain it }
    }

    /**
     * The third arm (096 §C, review finding F3): **every** handler this module exposes that
     * the §8.3 allowlist does not make public must declare its §7.6 operation.
     *
     * The first two arms cover `/api/v1` and `/partials`. Thirteen authenticated UI pages
     * sat outside both — `/dashboard`, the four list screens, the two detail screens, the two
     * editors, three settings screens and `/workspaces` — with no scope floor at all, plus
     * `/admin/users`, which authorized itself with a hand-written `if` that no guard could
     * see. They were reachable by any `user` key at the implicit read floor. This arm is the
     * mechanical statement of the rule `ScopeInterceptor.isScopeGoverned` now enforces at
     * runtime, so the build fails where the request would 403.
     */
    @Test
    fun `every non-public handler declares its section-7-6 operation`() {
        val missing = mutableListOf<String>()
        uiControllers().forEach { controller ->
            val classLevelScope = controller.findAnnotation<RequiredScope>() != null
            controller.functions
                .filter { isHttpHandler(it.javaMethod) }
                .filter { fn -> !isPublic(fullPath(controller, fn.javaMethod)) }
                .forEach { fn ->
                    val annotated = fn.findAnnotation<RequiredScope>() != null || classLevelScope
                    if (!annotated) missing.add("${controller.simpleName}#${fn.name}")
                }
        }
        missing shouldBe emptyList()
    }

    /**
     * Non-vacuity for the third arm: an `isPublic` that answered `true` for everything — a
     * broken parse, an empty allowlist — would make the arm above pass having checked
     * nothing. The pages the round annotated are named, and the public ones are named too,
     * so the classifier has to get BOTH directions right.
     */
    @Test
    fun `the third arm classifies the pages it exists for`() {
        isPublic("/dashboard") shouldBe false
        isPublic("/pipelines/{id}/editor") shouldBe false
        isPublic("/admin/users") shouldBe false
        isPublic("/api/v1/pipelines") shouldBe false
        isPublic("/partials/pipelines") shouldBe false

        isPublic("/login") shouldBe true
        isPublic("/docs/{slug}") shouldBe true
        isPublic("/compare/dbt") shouldBe true
        isPublic("/") shouldBe true
    }

    /** The scan actually sees the module's controllers — a scan that finds nothing proves nothing. */
    @Test
    fun `the classpath scan finds the module's controllers`() {
        controllers shouldHaveAtLeastSize 8
        controllers.map { it.qualifiedName.orEmpty() } shouldContain
            "co.datapipelines.web.datasources.DatasourceSchemaController"
    }

    private fun scanForRestControllers(): List<KClass<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
            .findCandidateComponents(BASE_PACKAGE)
            .map(BeanDefinition::getBeanClassName)
            .filterNotNull()
            .map { Class.forName(it) }
            // Production coverage only: on the test runtime classpath this package also holds
            // @RestController test probes (e.g. ApiExceptionHandlerTest.ProbeController), which
            // live in the test compile output and are exercised by their own tests.
            .filter {
                it.protectionDomain.codeSource.location.path
                    .contains("/main/")
            }.map { it.kotlin }
            .sortedBy { it.qualifiedName }

    private fun isHttpHandler(method: java.lang.reflect.Method?): Boolean {
        if (method == null) return false
        return listOf(
            GetMapping::class.java,
            PostMapping::class.java,
            PutMapping::class.java,
            PatchMapping::class.java,
            DeleteMapping::class.java,
        ).any { method.getAnnotation(it) != null }
    }

    /** The first path value of whichever HTTP-mapping annotation the method carries; "" when none. */
    private fun httpPath(method: java.lang.reflect.Method?): String {
        if (method == null) return ""
        return listOf(
            method.getAnnotation(GetMapping::class.java)?.value,
            method.getAnnotation(PostMapping::class.java)?.value,
            method.getAnnotation(PutMapping::class.java)?.value,
            method.getAnnotation(PatchMapping::class.java)?.value,
            method.getAnnotation(DeleteMapping::class.java)?.value,
        ).filterNotNull().firstOrNull()?.firstOrNull().orEmpty()
    }

    /**
     * The path Spring actually maps a handler to: the controller's class-level
     * `@RequestMapping` prefix joined to the method's own path (096 §D, review finding F6).
     * Testing the METHOD path alone is what let `@RequestMapping("/partials")` — no trailing
     * slash — hide five handlers from the guard.
     */
    private fun fullPath(
        controller: KClass<*>,
        method: java.lang.reflect.Method?,
    ): String {
        val classPath =
            controller
                .findAnnotation<RequestMapping>()
                ?.value
                ?.firstOrNull()
                .orEmpty()
                .removeSuffix("/")
        val methodPath = httpPath(method)
        val joined = classPath + if (methodPath.isEmpty() || methodPath.startsWith("/")) methodPath else "/$methodPath"
        return joined.ifEmpty { "/" }
    }

    /** True when the §8.3 allowlist makes this path anonymous — the runtime rule, same parser. */
    private fun isPublic(path: String): Boolean = PUBLIC_PATTERNS.any { it.matches(PathContainer.parsePath(path)) }

    private fun uiControllers(): List<KClass<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Controller::class.java)) }
            .findCandidateComponents(BASE_PACKAGE)
            .map(BeanDefinition::getBeanClassName)
            .filterNotNull()
            .map { Class.forName(it) }
            .filter {
                it.protectionDomain.codeSource.location.path
                    .contains("/main/")
            }.map { it.kotlin }
            .sortedBy { it.qualifiedName }

    private companion object {
        const val BASE_PACKAGE = "co.datapipelines.web"

        /**
         * The runtime's own constant (025 C6): the interceptor's default-deny and this
         * build-time scan owned two spellings (`/partials/` vs `/partials`) and could
         * drift apart — one shared home now.
         */
        val PARTIALS_PREFIX = ScopeInterceptor.PARTIALS_PREFIX

        /**
         * The §8.3 allowlist, parsed with the same parser the chain and `ScopeInterceptor`
         * match with — one spelling of "is this public", not a third.
         */
        val PUBLIC_PATTERNS: List<PathPattern> =
            PathPatternParser.defaultInstance.let { parser -> PublicPaths.PATTERNS.map(parser::parse) }
    }
}
