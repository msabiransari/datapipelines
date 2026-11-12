package co.datapipelines.web.incoming

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DependencyProvider
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingQuery
import co.datapipelines.dependencies.IncomingResult
import co.datapipelines.dependencies.IncomingScope
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ReadIsolation
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.config.DependencyGraphNonArtifactConfiguration
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass
import co.datapipelines.web.incoming.IncomingParityCorpus.ParityArm
import co.datapipelines.web.release.ReleaseConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * #877 §E.1 — A27 two-direction agreement over [IncomingParityCorpus]: for every one of the twelve arms and each scope,
 * the inverse (side a: the PRODUCTION target descriptors' `findIncoming`, built by the production bean factories over a
 * real PostgreSQL, paged to exhaustion at page size 1 and at the configured size, for every corpus target key as an
 * entity and at every version the corpus names) equals the raw extraction (side b: [IncomingParityOracle], every stored
 * version of every dependent family, filtered by the [SCOPE_TABLE] this test commits). The symmetric difference is
 * reported as a COUNT per (arm, scope) and must be zero; before anything is compared the corpus's class coverage per arm
 * is asserted (the non-vacuity floor), and every (arm, scope) must select at least one occurrence.
 */
class IncomingParityIntegrationTest {
    private val dataSource = SharedPostgres.dataSource()
    private val corpus = IncomingParityCorpus(dataSource)
    private val oracle = IncomingParityOracle(NamedParameterJdbcTemplate(dataSource), corpus.workspaceId, corpus.userId)

    /** ONE DataSource: the ceiling checks that the transaction it bounds is bound to its own source. */
    private val ceiling = PostgresStatementCeiling(dataSource)
    private val providers: Map<FamilyKey, DependencyProvider<*>> =
        ReleaseConfiguration().let { release ->
            mapOf(
                FamilyKey.TEMPLATE to release.templateDependencyFamily(ceiling).provider,
                FamilyKey.PIPELINE to release.pipelineDependencyFamily(ceiling).provider,
                FamilyKey.PARAMETER_SET to release.parameterSetDependencyFamily(ceiling).provider,
                FamilyKey.VISUALIZATION to release.visualizationDependencyFamily(ceiling).provider,
                FamilyKey.DATASOURCE to DependencyGraphNonArtifactConfiguration().datasourceDependencyFamily(ceiling).provider,
            )
        }

    @BeforeEach
    fun build() {
        corpus.build()
    }

    @Test
    fun `the inverse equals the raw extraction on every arm and scope, at page size 1 and at the configured size`() {
        val extracted = oracle.enumerate()
        assertClassCoverage(extracted)
        val configured =
            IncomingQuery(
                entity(ParityArm.TEMPLATE_FROM_PIPELINE, IncomingParityCorpus.LIB),
                FamilyKey.PIPELINE,
                LIVE,
                null,
            ).limit
        val report =
            ParityArm.ALL.flatMap { arm ->
                IncomingScope.entries.map { scope ->
                    val targets = targetsOf(arm)
                    val expected = targets.map { target -> extracted.filter { selected(arm, scope, target, it) }.map { it.occurrence } }
                    val differences =
                        listOf(1, configured).map { limit ->
                            targets.zip(expected).flatMap { (target, wanted) ->
                                symmetricDifference(inverse(arm, scope, target, limit), wanted)
                            }
                        }
                    Row(
                        arm,
                        scope,
                        expected.sumOf { it.size },
                        differences[0].size,
                        differences[1].size,
                        differences.flatten().take(SAMPLES),
                    )
                }
            }
        println(render(report, configured))
        report.forEach { row ->
            withClue("${row.arm} ${row.scope}: the corpus selects nothing — the comparison would be vacuous") {
                row.expected shouldBeGreaterThan
                    0
            }
        }
        report.filter { it.differenceAtOne != 0 || it.differenceAtConfigured != 0 }.shouldBeEmpty()
    }

    @Test
    fun `the reverse-only plants are ignored by both directions`() {
        val extracted = oracle.enumerate().map { it.occurrence }
        IncomingParityCorpus.IGNORED.forEach { plant ->
            withClue("${plant.arm} ${plant.name}@${plant.version} ${plant.path} (${plant.why})") {
                val hit = { occurrence: ParityOccurrence -> occurrence.matches(plant) }
                extracted.count { hit(it) && it.targetFamily == plant.arm.target } shouldBe 0
                IncomingScope.entries.forEach { scope ->
                    targetsOf(plant.arm).sumOf { target -> inverse(plant.arm, scope, target, 1).count(hit) } shouldBe 0
                }
            }
        }
        // Non-vacuity: the plants' own versions DO carry counted references on both sides (the SCRIPT and DML templates).
        extracted.count { it.dependentName == IncomingParityCorpus.RULES_PIPELINE && it.targetFamily == FamilyKey.TEMPLATE } shouldBe 4
    }

    /** The floor: every class an arm can have is planted AND extracted for that arm; every other class is listed impossible. */
    private fun assertClassCoverage(extracted: List<OracleRow>) {
        ParityArm.ALL.forEach { arm ->
            val impossible = IncomingParityCorpus.IMPOSSIBLE.getValue(arm)
            CorpusClass.entries.filterNot { it in impossible }.forEach { corpusClass ->
                withClue("$arm plants $corpusClass") {
                    val planted = corpus.inventory.filter { it.arm == arm && it.corpusClass == corpusClass }
                    planted.size shouldBeGreaterThan 0
                    planted.forEach { plant ->
                        extracted.count { row ->
                            row.occurrence.dependentFamily == arm.dependents && row.occurrence.targetFamily == arm.target &&
                                row.occurrence.dependentName == plant.name && row.occurrence.dependentVersion == plant.version
                        } shouldBeGreaterThan (if (corpusClass == CorpusClass.REPEATED) 1 else 0)
                    }
                }
            }
        }
    }

    /** Side (a): every page of one inverse question, in order. */
    private fun inverse(
        arm: ParityArm,
        scope: IncomingScope,
        target: IncomingTarget,
        limit: Int,
    ): List<ParityOccurrence> {
        val provider = providers.getValue(arm.target)
        val answered = mutableListOf<ParityOccurrence>()
        var query = IncomingQuery(target, arm.dependents, scope, null, limit)
        repeat(MAX_PAGES) {
            val page = inTransaction { provider.findIncoming(query, context()) }.shouldBeInstanceOf<IncomingResult.Page>()
            page.edges.size shouldBeLessThanOrEqual limit
            answered +=
                page.edges.map { edge ->
                    ParityOccurrence(
                        edge.dependent.family,
                        edge.dependent.stableEntityId,
                        edge.dependentName,
                        edge.dependent.artifactVersion ?: 0,
                        edge.relationship,
                        edge.sourcePath,
                        arm.target,
                        target.key,
                        edge.storedVersion,
                    )
                }
            query = query.after(page.next ?: return answered)
        }
        error("$arm $scope $target: more than $MAX_PAGES pages")
    }

    /** Side (b) for one question: the extracted occurrence is of [arm], names [target], and [scope] selects its dependent. */
    private fun selected(
        arm: ParityArm,
        scope: IncomingScope,
        target: IncomingTarget,
        row: OracleRow,
    ): Boolean {
        val occurrence = row.occurrence
        val names =
            occurrence.dependentFamily == arm.dependents && occurrence.targetFamily == arm.target && occurrence.targetKey == target.key &&
                (target !is IncomingTarget.ExactVersion || occurrence.storedVersion == target.version)
        return names && SCOPE_TABLE.getValue(arm).selects(scope, row)
    }

    private fun targetsOf(arm: ParityArm): List<IncomingTarget> =
        IncomingParityCorpus.TARGET_KEYS.getValue(arm.target).flatMap { key ->
            if (arm.target == FamilyKey.DATASOURCE) {
                listOf(IncomingTarget.Entity(FamilyKey.DATASOURCE, OwnerScope.Deployment, key))
            } else {
                listOf(entity(arm, key)) +
                    IncomingParityCorpus.TARGET_VERSIONS.map { IncomingTarget.ExactVersion(arm.target, owner(), key, it) }
            }
        }

    private fun entity(
        arm: ParityArm,
        key: String,
    ) = IncomingTarget.Entity(arm.target, owner(), key)

    private fun owner() = OwnerScope.Workspace(corpus.workspaceId)

    private fun context() =
        ResolveContext(
            corpus.workspaceId,
            MetadataSnapshot.READ_COMMITTED,
            Purpose.INTERACTIVE_READ,
            Authority.Member(corpus.userId),
            Deadline.after(BUDGET).withStatementLimit(BUDGET),
            ReadIsolation.NONE,
        )

    private fun <T> inTransaction(work: () -> T): T =
        checkNotNull(
            TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                work()
            },
        )

    private fun ParityOccurrence.matches(plant: IncomingParityCorpus.Ignored) =
        dependentFamily == plant.arm.dependents && dependentName == plant.name && dependentVersion == plant.version &&
            sourcePath == plant.path

    private data class Row(
        val arm: ParityArm,
        val scope: IncomingScope,
        val expected: Int,
        val differenceAtOne: Int,
        val differenceAtConfigured: Int,
        val samples: List<String>,
    )

    /** The multiset symmetric difference, one line per surplus occurrence: `inverse-only` or `extraction-only`. */
    private fun symmetricDifference(
        inverse: List<ParityOccurrence>,
        extracted: List<ParityOccurrence>,
    ): List<String> {
        val left = inverse.groupingBy { it }.eachCount()
        val right = extracted.groupingBy { it }.eachCount()
        return (left.keys + right.keys).flatMap { occurrence ->
            val surplus = (left[occurrence] ?: 0) - (right[occurrence] ?: 0)
            List(kotlin.math.abs(surplus)) { (if (surplus > 0) "inverse-only " else "extraction-only ") + occurrence }
        }
    }

    private fun render(
        report: List<Row>,
        configured: Int,
    ): String =
        buildString {
            appendLine("A27 PARITY — symmetric difference per (arm, scope); page sizes 1 and $configured")
            appendLine("arm | scope | expected occurrences | difference @1 | difference @$configured")
            report.forEach { appendLine("${it.arm} | ${it.scope} | ${it.expected} | ${it.differenceAtOne} | ${it.differenceAtConfigured}") }
            report.flatMap { it.samples }.forEach { appendLine("  DIFFERENCE $it") }
            appendLine()
            appendLine("SCOPE TABLE (the expectation this test commits; read from the native SQL at the base)")
            ParityArm.ALL.forEach { arm ->
                val rule = SCOPE_TABLE.getValue(arm)
                appendLine("$arm | WORKING: ${rule.working} | LIVE: ${rule.live} | ANY: ${rule.any}")
            }
            appendLine()
            appendLine("CORPUS INVENTORY (arm | class | dependent | version)")
            corpus.inventory.forEach { appendLine("${it.arm} | ${it.corpusClass} | ${it.name} | ${it.version}") }
            IncomingParityCorpus.IMPOSSIBLE.forEach { (arm, classes) ->
                classes.forEach { (corpusClass, why) -> appendLine("$arm | $corpusClass | IMPOSSIBLE | $why") }
            }
            IncomingParityCorpus.IGNORED.forEach { appendLine("${it.arm} | IGNORED | ${it.name} | ${it.version} | ${it.path} | ${it.why}") }
        }

    /**
     * What each scope selects, per arm, as the native SQL selected it at the base (`InverseSql.scope`, the API arm's
     * history rule in `PublishedEndpointVersionRepository.findPipelinePinOccurrences`, `ScheduleTargetQueries`). This lane
     * preserves every row; it harmonizes nothing.
     */
    enum class ScopeRule(
        val working: String,
        val live: String,
        val any: String,
    ) {
        VERSIONED(
            "the entity's DRAFT, else its current pointer, of an entity that still has a DRAFT or RELEASED version",
            "every DRAFT or RELEASED version",
            "every version, DISCARDED included, of an entity that still has a DRAFT or RELEASED version",
        ),
        API_HISTORY(
            "the entity's DRAFT, else its current pointer, of an entity that still has a DRAFT or RELEASED version",
            "every DRAFT or RELEASED version",
            "= LIVE: a DISCARDED API version protects nothing (its restore revalidates its pin; record §14, A39)",
        ),
        SCHEDULE(
            "every schedule not soft-deleted (unversioned)",
            "every schedule not soft-deleted (paused and blocked ones too)",
            "every schedule not soft-deleted",
        ),
        ;

        fun selects(
            scope: IncomingScope,
            row: OracleRow,
        ): Boolean =
            when (this) {
                VERSIONED -> {
                    when (scope) {
                        IncomingScope.WORKING -> row.working
                        IncomingScope.LIVE -> row.status in LIVE_STATUSES
                        IncomingScope.ANY -> row.entityLive
                    }
                }

                API_HISTORY -> {
                    if (scope == IncomingScope.WORKING) row.working else row.status in LIVE_STATUSES
                }

                SCHEDULE -> {
                    !row.deleted
                }
            }
    }

    private companion object {
        val LIVE = IncomingScope.LIVE
        val LIVE_STATUSES = setOf(PipelineVersionStatus.DRAFT, PipelineVersionStatus.RELEASED)
        val BUDGET: Duration = Duration.ofSeconds(30)
        const val MAX_PAGES = 10_000
        const val SAMPLES = 20

        /** The per-family scope table (brief §E.1): every arm's rule. */
        val SCOPE_TABLE: Map<ParityArm, ScopeRule> =
            ParityArm.ALL.associateWith { arm ->
                when (arm) {
                    ParityArm.PIPELINE_FROM_API_ENDPOINT -> ScopeRule.API_HISTORY
                    ParityArm.PIPELINE_FROM_SCHEDULE -> ScopeRule.SCHEDULE
                    else -> ScopeRule.VERSIONED
                }
            }
    }
}
