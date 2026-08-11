package co.datapipelines.web.api

import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.authapi.AuthController
import co.datapipelines.web.datasources.DatasourcesController
import co.datapipelines.web.executions.ExecutionsController
import co.datapipelines.web.pipelines.PipelineExecuteController
import co.datapipelines.web.pipelines.PipelineTransferController
import co.datapipelines.web.pipelines.PipelinesController
import co.datapipelines.web.templates.TemplatesController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

/**
 * The mechanical guard for `auth`'s default-deny (AUTH-SEC-9): every handler this module
 * exposes under `/api/v1` must declare its §7.6 operation. `ScopeInterceptor` denies an
 * unannotated handler at run time; this test fails the build at the moment one is added.
 */
class RequiredScopeCoverageTest {
    private val controllers =
        listOf(
            PipelinesController::class,
            PipelineTransferController::class,
            PipelineExecuteController::class,
            TemplatesController::class,
            DatasourcesController::class,
            ExecutionsController::class,
            AuthController::class,
        )

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

    private fun isHttpHandler(method: java.lang.reflect.Method?): Boolean {
        if (method == null) return false
        return listOf(
            org.springframework.web.bind.annotation.GetMapping::class.java,
            org.springframework.web.bind.annotation.PostMapping::class.java,
            org.springframework.web.bind.annotation.PutMapping::class.java,
            org.springframework.web.bind.annotation.DeleteMapping::class.java,
        ).any { method.getAnnotation(it) != null }
    }
}
