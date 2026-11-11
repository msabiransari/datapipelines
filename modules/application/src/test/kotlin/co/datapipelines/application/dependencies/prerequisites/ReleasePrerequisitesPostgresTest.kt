package co.datapipelines.application.dependencies.prerequisites

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.NonArtifactSeed
import co.datapipelines.application.dependencies.ObservedStatements
import co.datapipelines.application.dependencies.PipelineReleaseFamily
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseArtifactSeed
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.MetadataSnapshot
import co.datapipelines.dependencies.Purpose
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * #873 §F.3 b/c/f on a real PostgreSQL, over the production descriptors (the pipeline arm, the template and
 * parameter-set providers, G3's datasource family): the plan's prerequisites and the apply-time [RegistryPrerequisiteCheck]
 * read the same names through the same extractor and agree row for row; a datasource absent from the deployment, one
 * soft-deleted and one granted only to another workspace are ONE outcome — the same row, `available: false` with no
 * `readonly` — while a granted one carries its own `readonly` flag; a registry with no datasource provider answers
 * every name unavailable (fail closed).
 */
class ReleasePrerequisitesPostgresTest {
    private val statements = ObservedStatements(SharedPostgres.dataSource())
    private val seed = ReleaseArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val datasources = NonArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)

    private val artifacts: List<FamilyDescriptor<*>> =
        listOf(
            PipelineReleaseFamily.descriptor(PipelineRepository(ceiling.jdbc), ceiling),
            TemplateReleaseProvider(TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
            ParameterSetReleaseProvider(ParameterSetRepository(ceiling.jdbc), TemplateRepository(ceiling.jdbc), ceiling).descriptor(),
        )
    private val production =
        FamilyRegistry.production(artifacts + DatasourceDependencyFamily.descriptor(DatasourceRepository(ceiling.jdbc), ceiling))

    @BeforeEach
    fun reset() {
        seed.reset()
        datasources.adopt(seed.userId, seed.workspaceId)
        datasources.datasource("ro_ds", readonly = true)
        datasources.datasource("rw_ds")
        datasources.datasource("theirs", grantedTo = listOf(datasources.otherWorkspaceId))
        datasources.datasource("gone", deleted = true)
        seed.template("q", 1)
        NAMES.forEach { name -> seed.pipeline("p_$name", 1, templates = listOf("q" to 1), source = name) }
        seed.pipeline("root", 1, DRAFT, children = NAMES.map { "p_$it" to 1 })
    }

    @Test
    fun `the plan lists each datasource once - granted ones with their readonly flag, the rest unavailable alike`() {
        val planned = checkNotNull(walk(production).planned(root(), seed.userId))

        planned.prerequisites shouldContainExactly
            listOf(
                ReleasePrerequisite("ro_ds", available = true, readonly = true),
                ReleasePrerequisite("rw_ds", available = true, readonly = false),
                ReleasePrerequisite("theirs", available = false, readonly = null),
                ReleasePrerequisite("gone", available = false, readonly = null),
                ReleasePrerequisite("absent", available = false, readonly = null),
            )
        planned.closure.nodes.map { it.identity.entityId } shouldNotBe emptyList<String>()
        withClue("no closure node is a datasource") {
            planned.closure.nodes
                .map { it.identity.family }
                .toSet() shouldBe setOf(ReleaseFamily.PIPELINE, ReleaseFamily.TEMPLATE)
        }
    }

    @Test
    fun `another workspace's datasource is the absent case - the wire row deep-compares equal but for the authored name`() {
        val rows = checkNotNull(walk(production).planned(root(), seed.userId)).prerequisites.associateBy { it.name }

        val theirs = rows.getValue("theirs").toWire()
        val absent = rows.getValue("absent").toWire()

        theirs.filterKeys { it != "name" } shouldBe absent.filterKeys { it != "name" }
        theirs.keys.toList() shouldContainExactly listOf("kind", "name", "available", "readonly")
        theirs shouldBe mapOf("kind" to "datasource", "name" to "theirs", "available" to false, "readonly" to null)
        rows.getValue("theirs").blocker() shouldBe mapOf("kind" to "datasource", "name" to "theirs", "reason" to "unavailable")
    }

    @Test
    fun `the apply-time check reads the same names through the same extractor - it agrees with the plan row for row`() {
        val planned = checkNotNull(walk(production).planned(root(), seed.userId))

        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        checked shouldBe planned.prerequisites
    }

    @Test
    fun `the check sees a disappearance after the plan - a datasource deleted between plan and apply is unavailable`() {
        val planned = checkNotNull(walk(production).planned(root(), seed.userId))
        NamedParameterJdbcTemplate(SharedPostgres.dataSource())
            .update("UPDATE datasources SET is_deleted = TRUE WHERE name = 'rw_ds'", emptyMap<String, Any>())

        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        checked.single { it.name == "rw_ds" } shouldBe ReleasePrerequisite("rw_ds", available = false, readonly = null)
    }

    @Test
    fun `a registry with no datasource provider answers every name unavailable - fail closed`() {
        val planned = checkNotNull(walk(production).planned(root(), seed.userId))

        val checked = inTransaction { check(FamilyRegistry.production(artifacts)).check(planned.closure, seed.userId) }

        checked.map { it.name } shouldContainExactly NAMES
        checked.none { it.available } shouldBe true
    }

    @Test
    fun `a closure that names no datasource needs no prerequisite - and the check reads no datasource`() {
        val closure = checkNotNull(walk(production).plan(seed.identity(ReleaseFamily.TEMPLATE, "q", 1), seed.userId))
        statements.observed.clear()

        val checked = inTransaction { check(production).check(closure, seed.userId) }

        checked.shouldBeEmpty()
        withClue(statements.observed.map { it.first }) {
            statements.observed.none { it.first.contains("datasources", ignoreCase = true) } shouldBe true
        }
    }

    @Test
    fun `planning and the check never connect - an unreachable datasource is available, read from the catalog alone`() {
        // Record §8: a graph read does not test connectivity. The host is RFC 6761 `.invalid`: any connect fails.
        NamedParameterJdbcTemplate(SharedPostgres.dataSource())
            .update("UPDATE datasources SET jdbc_url = :url WHERE name = 'rw_ds'", mapOf("url" to UNREACHABLE_URL))
        statements.observed.clear()

        val planned = checkNotNull(walk(production).planned(root(), seed.userId))
        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        planned.prerequisites.single { it.name == "rw_ds" } shouldBe ReleasePrerequisite("rw_ds", available = true, readonly = false)
        checked shouldBe planned.prerequisites
        withClue("non-vacuity: the walk and the check read the datasource catalog") {
            statements.observed.any { it.first.contains("FROM datasources", ignoreCase = true) } shouldBe true
        }
        withClue(statements.observed.map { it.first }) {
            statements.observed.none { it.first.contains("jdbc_url", ignoreCase = true) } shouldBe true
        }
    }

    @Test
    fun `R1 the check reads six pipeline closure bodies in ONE batch statement and the names in one more`() {
        // The landing's counterexample (873-r4 R1), kept: the refused code issued 18 pipeline reads + 1 datasource read.
        val planned = checkNotNull(walk(production).planned(root(), seed.userId))
        planned.closure.nodes.count { it.identity.family == ReleaseFamily.PIPELINE } shouldBe NAMES.size + 1
        statements.observed.clear()

        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        checked shouldBe planned.prerequisites
        assertBatches(pipelineBatches = 1)
    }

    @Test
    fun `R1 256 pipeline closure members are one pipeline-body batch statement at resolveBatchSize 256`() {
        wideRelease(members = BATCH)

        val planned = checkNotNull(walk(production).planned(seed.identity(ReleaseFamily.PIPELINE, WIDE_ROOT, 1), seed.userId))
        planned.closure.nodes.count { it.identity.family == ReleaseFamily.PIPELINE } shouldBe BATCH
        statements.observed.clear()

        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        checked shouldBe planned.prerequisites
        checked.map { it.name } shouldContainExactly NAMES
        assertBatches(pipelineBatches = 1)
    }

    @Test
    fun `R1 257 pipeline closure members are two pipeline-body batch statements - answers unchanged`() {
        wideRelease(members = BATCH + 1)

        val planned = checkNotNull(walk(production).planned(seed.identity(ReleaseFamily.PIPELINE, WIDE_ROOT, 1), seed.userId))
        planned.closure.nodes.count { it.identity.family == ReleaseFamily.PIPELINE } shouldBe BATCH + 1
        statements.observed.clear()

        val checked = inTransaction { check(production).check(planned.closure, seed.userId) }

        checked shouldBe planned.prerequisites
        checked shouldContainExactly
            listOf(
                ReleasePrerequisite("ro_ds", available = true, readonly = true),
                ReleasePrerequisite("rw_ds", available = true, readonly = false),
                ReleasePrerequisite("theirs", available = false, readonly = null),
                ReleasePrerequisite("gone", available = false, readonly = null),
                ReleasePrerequisite("absent", available = false, readonly = null),
            )
        assertBatches(pipelineBatches = 2)
    }

    @Test
    fun `R1 the pipeline bulk read refuses a batch above the contract ceiling on entry - before any statement`() {
        val reader = PipelineReleaseFamily.descriptor(PipelineRepository(ceiling.jdbc), ceiling).provider as DatasourceReferenceReader
        val requests =
            (0..GraphLimits.MAX_RESOLVE_BATCH_SIZE).map { index ->
                ReleaseDependencyFamilies.request(seed.identity(ReleaseFamily.PIPELINE, "w_$index", 1))
            }
        val context =
            ResolveContext(
                seed.workspaceId,
                MetadataSnapshot.READ_COMMITTED,
                Purpose.RELEASE_PLANNING,
                Authority.Member(seed.userId),
                Deadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
                ReleasePlanningWalk.speculativeReads(manager),
            )
        statements.observed.clear()

        val refused = shouldThrow<IllegalArgumentException> { inTransaction { reader.datasourceReferences(requests, context) } }

        checkNotNull(refused.message) shouldContain "batch holds at most 256 requests, got 257"

        statements.observed.shouldBeEmpty()
    }

    /**
     * A DRAFT root [WIDE_ROOT] pinning `members - 1` released children, child `i` reading datasource `NAMES[i % 5]`: a
     * closure of exactly [members] pipeline versions whose datasource references are nonempty and cover every outcome.
     */
    private fun wideRelease(members: Int) {
        val children = (0 until members - 1).map { "w_$it" }
        children.forEachIndexed { index, name ->
            seed.pipeline(name, 1, templates = listOf("q" to 1), source = NAMES[index % NAMES.size])
        }
        seed.pipeline(WIDE_ROOT, 1, DRAFT, children = children.map { it to 1 })
    }

    /**
     * The check's statements are [pipelineBatches] pipeline-body batch reads, then ONE datasource-name batch read, and
     * nothing else; every one ran under the shared statement ceiling (a live, nonzero `statement_timeout`).
     */
    private fun assertBatches(pipelineBatches: Int) {
        val sql = statements.observed.map { it.first }
        val (names, bodies) = statements.observed.partition { it.first.contains("FROM datasources", ignoreCase = true) }
        withClue("R1: $pipelineBatches pipeline-body batch statement(s) + 1 datasource batch; actual SQL: $sql") {
            bodies.size shouldBe pipelineBatches
            names.size shouldBe 1
        }
        withClue("every pipeline read is a pipeline_versions body read: $sql") {
            bodies.all { it.first.contains("pipeline_versions", ignoreCase = true) } shouldBe true
        }
        withClue("every statement runs under the ceiling: ${statements.timeouts()}") {
            statements.timeouts().none { it == "0" } shouldBe true
        }
    }

    private fun root() = seed.identity(ReleaseFamily.PIPELINE, "root", 1)

    private fun walk(registry: FamilyRegistry) =
        ReleasePlanningWalk(
            registry,
            ReleaseBounds(),
            ReleasePlanningWalk.planningTransaction(manager),
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
        )

    private fun check(registry: FamilyRegistry) =
        RegistryPrerequisiteCheck(
            registry,
            ReleasePlanningWalk.speculativeReads(manager),
            ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS)),
        )

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    private companion object {
        val NAMES = listOf("ro_ds", "rw_ds", "theirs", "gone", "absent")
        const val BUDGET_SECONDS = 30L
        const val BATCH = 256
        const val WIDE_ROOT = "wide_root"
        const val UNREACHABLE_URL = "jdbc:postgresql://dp873-unreachable.invalid:5432/none"
    }
}
