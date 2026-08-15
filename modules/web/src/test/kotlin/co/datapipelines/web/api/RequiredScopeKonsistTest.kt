package co.datapipelines.web.api

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import org.junit.jupiter.api.Test

/**
 * Konsist twin of [RequiredScopeCoverageTest] (module-structure.md §7.8): every HTTP
 * handler function on a `@RestController` under `/api/v1` must declare its
 * `@RequiredScope`, at function or class level.
 *
 * The two guards overlap DELIBERATELY (006 prompt): the reflection-based test proves
 * runtime wiring against the live classpath; this one proves the same rule statically
 * from sources — it catches the violation even in code that fails to compile into a
 * runnable context, and it needs no Spring runtime to do it.
 */
class RequiredScopeKonsistTest {
    @Test
    fun `every api handler declares its required scope`() {
        controllers().assertTrue { controller ->
            val classLevel = controller.hasAnnotationWithName("RequiredScope")
            controller
                .functions()
                .filter { fn -> HTTP_MAPPINGS.any(fn::hasAnnotationWithName) }
                .all { fn -> classLevel || fn.hasAnnotationWithName("RequiredScope") }
        }
    }

    /** A guard that scans an empty scope proves nothing — the scan must see the controllers. */
    @Test
    fun `the source scan finds the module's controllers`() {
        controllers().map { it.name } shouldHaveAtLeastSize 8
    }

    private fun controllers() =
        Konsist
            .scopeFromDirectory("modules/web/src/main/kotlin")
            .classes()
            .withAnnotationNamed("RestController")

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
    }
}
