package co.datapipelines.integration

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withAnnotationNamed
import com.lemonappdev.konsist.api.verify.assertEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test

/**
 * Cross-module architecture guards (module-structure.md §7.8), encoded with Konsist
 * over every module's PRODUCTION sources. Each guard mirrors a rule that previously
 * existed only as prose or convention, so a violation fails the build instead of
 * waiting for a reviewer to spot it.
 *
 * The scope is sliced to `src/main` deliberately: Spring's TestContext framework
 * field-injects `@Autowired lateinit var` into test classes by design, so the
 * constructor-injection rule below applies to production code only.
 */
class ArchitectureGuardTest {
    /**
     * Constructor injection only (house rule): no `@Autowired` on any property or
     * field in production sources. Spring test classes are exempt by scope, not by
     * silence — see the class KDoc.
     */
    @Test
    fun `no field injection in production code`() {
        productionScope()
            .properties()
            .withAnnotationNamed("Autowired")
            .assertEmpty()
    }

    /**
     * Zero DI stereotypes in production code (015, spec D1): every bean is
     * declared explicitly as a `@Bean` method in a `@Configuration` class, so
     * no class, interface, or object may carry `@Service`, `@Component`, or
     * `@Repository`. Zero allowlist. Component scanning stays ON for
     * `@Configuration` classes and the web edge (`@Controller`, `@RestController`,
     * `@ControllerAdvice`) — those annotations are not matched here (the match is
     * by annotation NAME, so `@Controller`-meta-annotated classes are unaffected).
     */
    @Test
    fun `no stereotype annotations in production code`() {
        productionScope()
            .classesAndInterfacesAndObjects()
            .withAnnotationNamed("Service", "Component", "Repository")
            .assertEmpty()
    }

    /**
     * No declarative transactions anywhere in production code (015, spec D2):
     * `@Transactional` is banned outright on classes, interfaces, objects, AND
     * functions. First-choice atomicity is a single data-modifying CTE; any
     * future multi-statement unit of work uses an injected
     * `TransactionTemplate` (module-structure.md §8.4). This replaces the
     * pre-015 `@Transactional`-requires-`@Service` guard — its `@Service`
     * anchor no longer exists once the stereotype guard above is green. The
     * scan covers interfaces and Kotlin `object` declarations too (009/F8 +
     * 012/F5: `object ExecutionCleanup { @Transactional fun purge() }` is as
     * much a transaction boundary as a class, and escaped
     * `classesAndInterfaces()`).
     */
    @Test
    fun `no declarative transactions in production code`() {
        val transacting =
            productionScope()
                .classesAndInterfacesAndObjects()
                .filter { decl ->
                    decl.hasAnnotationWithName("Transactional") ||
                        decl.functions().any { it.hasAnnotationWithName("Transactional") }
                }

        transacting.assertEmpty()
    }

    /** A guard scanning an empty scope proves nothing — the scope must see production code. */
    @Test
    fun `the production scope actually covers the modules`() {
        productionScope().classes().map { it.name }.shouldNotBeEmpty()
    }

    private fun productionScope() =
        Konsist
            .scopeFromDirectory("modules")
            .slice { file -> !file.path.contains("/src/test/") }
}
