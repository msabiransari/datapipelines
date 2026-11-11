package co.datapipelines.web.release

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningLimits
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.web.config.EndpointsConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The production wiring of the release statement bound (#786 R3, record §25.4): ONE [StatementCeiling] bean, the
 * PostgreSQL one, and every release family descriptor — pipeline, template and the API family `EndpointsConfiguration`
 * declares — reads its source under that bean, never under [StatementCeiling.NONE], and through repositories over THAT
 * bean's template (landing review r4: only that template's per-statement hook bounds each statement); the planning walk
 * carries `statement-timeout-seconds` as the statement limit it puts in every provider context.
 */
class ReleaseStatementCeilingWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
            EndpointFamily::class.java,
        )

    @Test
    fun `every release family descriptor reads under the one PostgreSQL statement ceiling`() {
        context.run { context ->
            context.getBeansOfType(StatementCeiling::class.java).size shouldBe 1
            val ceiling = context.getBean(StatementCeiling::class.java).shouldBeInstanceOf<PostgresStatementCeiling>()
            val descriptors = context.getBeansOfType(FamilyDescriptor::class.java).values
            descriptors.map { it.key }.toSet() shouldBe setOf(FamilyKey.PIPELINE, FamilyKey.TEMPLATE, FamilyKey.API_ENDPOINT)
            descriptors.forEach { descriptor ->
                withClue(descriptor.key) { ReleaseDependencyFamilies.ceilingOf(descriptor) shouldBeSameInstanceAs ceiling }
            }
        }
    }

    @Test
    fun `every release source issues its statements through the ceiling's own template - the per-statement hook sees each one`() {
        context.run { context ->
            val ceiling = context.getBean(PostgresStatementCeiling::class.java)
            context.getBeansOfType(FamilyDescriptor::class.java).values.forEach { descriptor ->
                withClue("${descriptor.key}: a repository over the shared template would issue statements no hook bounds (#786 R3)") {
                    val templates = templatesReachable(descriptor.provider, ceiling)
                    templates.shouldNotBeEmpty()
                    templates.forEach { it shouldBeSameInstanceAs ceiling.jdbc }
                }
            }
        }
    }

    @Test
    fun `the planning walk's statement limit follows statement-timeout-seconds`() {
        context.withPropertyValues("datapipelines.release.dependencies.statement-timeout-seconds=3").run { context ->
            val walk = context.getBean(ReleasePlanningWalk::class.java)
            val planning = walk.field("planning").shouldBeInstanceOf<ReleasePlanningLimits>()
            planning.statementTimeout shouldBe Duration.ofSeconds(3)
        }
    }

    @Test
    fun `no production source builds a release descriptor or registry without naming its ceiling`() {
        val roots = listOf("../application/src/main/kotlin", "src/main/kotlin").map(::File)
        roots.forEach { check(it.isDirectory) { "missing source root ${it.absolutePath}" } }
        val sources = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        sources.size shouldBeGreaterThan NON_VACUOUS_FLOOR
        // The two files that DEFINE the ceiling and the descriptor factory name NONE in their own declarations and docs.
        val offenders =
            sources
                .filter { it.name !in setOf("StatementCeiling.kt", "ReleaseDependencyFamilies.kt") }
                .filter { "StatementCeiling.NONE" in it.readText() }
                .map { it.path }
        offenders.shouldBeEmpty()
        val registryCalls = sources.filter { "ReleaseDependencyFamilies.registry(" in it.readText() }.map { it.path }
        withClue("registry() defaults its ceiling to NONE: production assembles the registry from descriptor beans instead") {
            registryCalls.shouldBeEmpty()
        }
    }

    private fun Any.field(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    /**
     * Every JDBC template reachable from [root] through this codebase's own objects — a source, its repositories — without
     * descending into [ceiling] (the oracle) or into a template (a leaf).
     */
    private fun templatesReachable(
        root: Any,
        ceiling: Any,
    ): List<Any> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<Any>()

        fun visit(
            value: Any?,
            depth: Int,
        ) {
            if (value == null || value === ceiling || !seen.add(value)) return
            if (value is JdbcOperations || value is NamedParameterJdbcOperations) {
                found += value
                return
            }
            if (depth > MAX_DEPTH || value is Enum<*> || !value.javaClass.name.startsWith(OWN)) return
            generateSequence<Class<*>>(value.javaClass) { it.superclass }
                .takeWhile { it.name.startsWith(OWN) }
                .flatMap { it.declaredFields.asSequence() }
                .filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
                .forEach { field ->
                    field.isAccessible = true
                    visit(field.get(value), depth + 1)
                }
        }
        visit(root, 0)
        return found
    }

    /** The API family's production bean method: the ceiling argument is the wiring under test. */
    @Configuration
    @Suppress("unused") // read by the context runner
    class EndpointFamily {
        @Bean
        fun endpointDependencyFamily(ceiling: PostgresStatementCeiling): FamilyDescriptor<*> =
            EndpointsConfiguration().endpointDependencyFamily(ceiling)
    }

    private companion object {
        /** The two source roots hold hundreds of files; a scan that reads fewer read the wrong directory. */
        const val NON_VACUOUS_FLOOR = 100

        /** Only this codebase's own objects are walked: a framework object is not a release source's collaborator. */
        const val OWN = "co.datapipelines."

        /** provider → source → repository → template is three hops; the walk allows a little more. */
        const val MAX_DEPTH = 6
    }
}
