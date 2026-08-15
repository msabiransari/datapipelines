package co.datapipelines.web.api

import co.datapipelines.auth.RequiredScope
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
            .filter { it.protectionDomain.codeSource.location.path.contains("/main/") }
            .map { it.kotlin }
            .sortedBy { it.qualifiedName }

    private fun isHttpHandler(method: java.lang.reflect.Method?): Boolean {
        if (method == null) return false
        return listOf(
            GetMapping::class.java,
            PostMapping::class.java,
            PutMapping::class.java,
            DeleteMapping::class.java,
        ).any { method.getAnnotation(it) != null }
    }

    private companion object {
        const val BASE_PACKAGE = "co.datapipelines.web"
    }
}
