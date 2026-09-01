package co.datapipelines.web.api

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import org.junit.jupiter.api.Test

/**
 * Sibling of [RequiredScopeKonsistTest]: no constructor parameter of type `ObjectMapper`
 * anywhere in this module may carry a default value (module-structure.md §7.8 family of
 * static guards).
 *
 * The rule exists because the default is an illusion in a Spring bean: the container
 * resolves an `ObjectMapper`-typed constructor parameter from the context — Spring's
 * servlet mapper — EVEN WHEN the parameter declares a Kotlin default, so
 * `private val mapper: ObjectMapper = PipelineJson.objectMapper()` silently receives the
 * module-less app mapper instead of the contract's (D9, 2026-08-17; the 032 production
 * 500 fixed by `2db9a71`; T58 here). The only safe shape is a body property.
 *
 * Like its sibling this is a static, source-level proof: it catches the shape in code
 * that never reaches a runnable Spring context, with no runtime needed.
 */
class ObjectMapperDefaultParameterKonsistTest {
    @Test
    fun `no ObjectMapper constructor parameter carries a default value`() {
        classes().assertTrue { klass ->
            val parameters =
                klass.primaryConstructor?.parameters.orEmpty() +
                    klass.secondaryConstructors.flatMap { it.parameters }
            parameters.none { it.type.name == "ObjectMapper" && it.hasDefaultValue() }
        }
    }

    /** A guard that scans an empty scope proves nothing — the scan must see the classes. */
    @Test
    fun `the source scan finds the module's classes`() {
        classes() shouldHaveAtLeastSize 60
    }

    private fun classes() =
        Konsist
            .scopeFromDirectory("modules/web/src/main/kotlin")
            .classes()
}
