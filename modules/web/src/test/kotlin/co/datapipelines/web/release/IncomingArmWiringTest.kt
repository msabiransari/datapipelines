package co.datapipelines.web.release

import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.config.DependencyGraphNonArtifactConfiguration
import co.datapipelines.web.config.DependencyGraphNonArtifactConfigurationWiringTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * #877 §A.4: the PRODUCTION registry — every descriptor bean `ReleaseConfiguration`, `EndpointsConfiguration` and
 * `DependencyGraphNonArtifactConfiguration` declare, over a real PostgreSQL — answers [IncomingResult.Page] for each of
 * the twelve arms of the record §14 table, and [IncomingResult.Unsupported] for every other (target, dependents) pair of
 * the production families. An adapter throws on `Unsupported` (fail closed, a lifecycle verb rolls back), so this is
 * what makes that throw unreachable in a correctly wired app: unregister one arm and this test is red.
 */
class IncomingArmWiringTest {
    private val context =
        ApplicationContextRunner()
            .withAllowBeanDefinitionOverriding(true)
            .withUserConfiguration(
                ReleaseConfiguration::class.java,
                ReleaseWiringCollaborators::class.java,
                RealPostgres::class.java,
                DependencyGraphNonArtifactConfigurationWiringTest.EndpointFamily::class.java,
                DependencyGraphNonArtifactConfigurationWiringTest.Executors::class.java,
                DependencyGraphNonArtifactConfiguration::class.java,
            )

    @Test
    fun `each target family declares exactly the arm table's dependents`() {
        context.run { context ->
            val registry = context.getBean(FamilyRegistry::class.java)
            FamilyKey.PRODUCTION.keys.forEach { target ->
                withClue(target) { registry[target]?.provider?.incomingFrom shouldBe ARMS[target].orEmpty() }
            }
        }
    }

    @Test
    fun `the twelve arms answer a page and every other pair is unsupported`() {
        context.run { context ->
            val answered =
                FamilyKey.PRODUCTION.keys.flatMap { target ->
                    FamilyKey.PRODUCTION.keys.map { dependents -> Triple(target, dependents, ask(context, target, dependents)) }
                }
            answered.forEach { (target, dependents, answer) ->
                withClue("$dependents → $target") {
                    if (dependents in ARMS[target].orEmpty()) {
                        answer.shouldBeInstanceOf<IncomingResult.Page>().edges shouldBe emptyList()
                    } else {
                        answer shouldBe IncomingResult.Unsupported
                    }
                }
            }
            answered.count { it.third is IncomingResult.Page } shouldBe ARMS.values.sumOf { it.size }
            ARMS.values.sumOf { it.size } shouldBe TWELVE
        }
    }

    /** One entity-wide LIVE question of [dependents] into [target], in a transaction on the ceiling's own source. */
    private fun ask(
        context: AssertableApplicationContext,
        target: FamilyKey,
        dependents: FamilyKey,
    ): IncomingResult {
        val provider = requireNotNull(context.getBean(FamilyRegistry::class.java)[target]).provider
        val owner = if (target == FamilyKey.DATASOURCE) OwnerScope.Deployment else OwnerScope.Workspace(WORKSPACE)
        val query = IncomingQuery(IncomingTarget.Entity(target, owner, "nobody"), dependents, IncomingScope.LIVE, null, 1)
        val resolve =
            ResolveContext(
                WORKSPACE,
                MetadataSnapshot.READ_COMMITTED,
                Purpose.INTERACTIVE_READ,
                Authority.Member(UUID.randomUUID()),
                Deadline.after(BUDGET).withStatementLimit(BUDGET),
                ReadIsolation.NONE,
            )
        return requireNotNull(
            TransactionTemplate(DataSourceTransactionManager(DATA_SOURCE)).execute { provider.findIncoming(query, resolve) },
        )
    }

    /** The metadata template over a real PostgreSQL, replacing the collaborators' mock (the ceiling is built over it). */
    @Configuration
    @Suppress("unused") // read by the context runner
    class RealPostgres {
        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(DATA_SOURCE)
    }

    private companion object {
        /** ONE source: the ceiling checks the transaction it bounds is bound to its own. */
        val DATA_SOURCE = SharedPostgres.dataSource()
        val WORKSPACE: UUID = UUID.randomUUID()
        val BUDGET: Duration = Duration.ofSeconds(30)
        const val TWELVE = 12

        /** Record §14's arm table: target family → the dependent families whose stored references it answers. */
        val ARMS: Map<FamilyKey, Set<FamilyKey>> =
            mapOf(
                FamilyKey.TEMPLATE to setOf(FamilyKey.PIPELINE, FamilyKey.TEMPLATE, FamilyKey.PARAMETER_SET, FamilyKey.VISUALIZATION),
                FamilyKey.PIPELINE to setOf(FamilyKey.PIPELINE, FamilyKey.DASHBOARD, FamilyKey.API_ENDPOINT, FamilyKey.SCHEDULE),
                FamilyKey.PARAMETER_SET to setOf(FamilyKey.DASHBOARD),
                FamilyKey.VISUALIZATION to setOf(FamilyKey.DASHBOARD),
                FamilyKey.DATASOURCE to setOf(FamilyKey.PIPELINE, FamilyKey.PARAMETER_SET),
            )
    }
}
