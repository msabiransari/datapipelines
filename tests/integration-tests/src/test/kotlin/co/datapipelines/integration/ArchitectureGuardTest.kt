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
     * `@Transactional` belongs to the service layer only. The layer is identified by
     * Spring's `@Service` STEREOTYPE pinned to its fully-qualified name, not the
     * `*Service` name suffix (009/F8: a `@Repository class ExecutionCleanupService`
     * passed the old name check). The FQN pin matters (012/F5): Konsist's
     * `hasAnnotationWithName` matches its argument against BOTH the annotation's
     * simple name and its fully-qualified name, so the old `"Service"` argument
     * exempted ANY annotation named Service — a homegrown one from another package
     * took @Transactional silently. Passing the FQN makes the exemption mean
     * exactly `org.springframework.stereotype.Service`. The scan covers INTERFACES
     * and Kotlin `object` declarations too (`classesAndInterfacesAndObjects()`,
     * 009/F8 + 012/F5: `object ExecutionCleanup { @Transactional fun purge() }` is
     * as much a transaction boundary as a class, and escaped
     * `classesAndInterfaces()`). All three service classes at adoption time
     * (JwtService, UserService, ApiKeyService) carry Spring's `@Service` —
     * verified by grep, not recall.
     */
    @Test
    fun `transactional only on service-layer types`() {
        val transacting =
            productionScope()
                .classesAndInterfacesAndObjects()
                .filter { decl ->
                    decl.hasAnnotationWithName("Transactional") ||
                        decl.functions().any { it.hasAnnotationWithName("Transactional") }
                }

        transacting
            .filterNot { it.hasAnnotationWithName("org.springframework.stereotype.Service") }
            .assertEmpty()
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
