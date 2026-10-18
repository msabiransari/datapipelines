package co.datapipelines.visualization

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #580 — the repository module's own proof of the catalog `lifecycle` filter ([ArtifactRepository.listIn] /
 * [ArtifactRepository.countIn], one SQL shape for both kinds) and the Dashboards Recently updated adapter: real
 * lifecycle writes put one object in each inventory bucket, and every bucket's listed names and total equal the
 * inventory's count for it, unlensed and narrowed; a narrowed row is always at a RELEASED version.
 */
class ArtifactLifecycleFilterIntegrationTest {
    @BeforeEach
    fun reset() = VisualizationTestDb.reset()

    @Test
    fun `the visualization catalog filter lists each bucket's members and counts the inventory's number`() {
        val body = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body
        exercise(VisualizationRepository(VisualizationTestDb.jdbc), body, body.copy(displayName = "Changed visualization"))
    }

    @Test
    fun `the dashboard catalog filter lists each bucket's members and counts the inventory's number`() {
        val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        exercise(DashboardRepository(VisualizationTestDb.jdbc), body, body.copy(displayName = "Changed dashboard"))
    }

    @Test
    fun `the filter composes with search and paging and an empty lens reads nothing`() {
        val repository = DashboardRepository(VisualizationTestDb.jdbc)
        val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        seed(repository, body, body.copy(displayName = "Changed dashboard"))

        repository.listIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, null, "both").names() shouldContainExactly
            listOf(name(repository, "both"))
        repository.countIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, null, "both") shouldBe 1
        // The term is literal: `%` matches only a `%`, never everything.
        repository.countIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, null, "%") shouldBe 0
        repository.listIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, null, null, offset = 1, limit = 1).names() shouldContainExactly
            listOf(name(repository, "draft_only"))
        repository.listIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, emptySet(), null).shouldBeEmpty()
        repository.countIn(WORKSPACE, LifecycleBucket.WITH_DRAFTS, emptySet(), null) shouldBe 0
        repository.countIn(OTHER_WORKSPACE, LifecycleBucket.RELEASED_ONLY, null, null) shouldBe 1
    }

    @Test
    fun `the dashboard service pages a bucket through the lens it is given`() {
        val harness = LifecycleHarness()
        val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        seed(harness.dashboardRepository, body, body.copy(displayName = "Changed dashboard"))
        val all = ReadLens.Only(NAMES.map { name(harness.dashboardRepository, it) }.toSet())

        val viewer = harness.dashboards.listIn(WORKSPACE, ReadLens.Everything, LifecycleBucket.WITH_DRAFTS, null, 0, 1)
        viewer.total shouldBe 2
        viewer.items.names() shouldContainExactly listOf(name(harness.dashboardRepository, "both"))
        val promoter = harness.dashboards.listIn(WORKSPACE, all, LifecycleBucket.WITH_RELEASES, null)
        promoter.total shouldBe 2
        promoter.items.map { it.detail.status }.distinct() shouldContainExactly listOf(PipelineVersionStatus.RELEASED)
        harness.dashboards.listIn(WORKSPACE, all, LifecycleBucket.WITH_DRAFTS, null).total shouldBe 0
    }

    @Test
    fun `the dashboard recent list is newest first, skips the discarded and hides a draft from a narrowed lens`() {
        val repository = DashboardRepository(VisualizationTestDb.jdbc)
        val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
        seed(repository, body, body.copy(displayName = "Changed dashboard"))

        val viewer = repository.recent(WORKSPACE, ReadLens.Everything)
        // Seeded in order released, both, draft_only (then discarded): the last write is newest.
        viewer.map { it.name.substringAfterLast('/') } shouldContainExactly listOf("draft_only", "both", "released")
        viewer.associate { it.name.substringAfterLast('/') to it.hasDraft } shouldBe
            mapOf("draft_only" to true, "both" to true, "released" to false)
        val narrowed = repository.recent(WORKSPACE, ReadLens.Only(NAMES.map { name(repository, it) }.toSet()))
        narrowed.map { it.name.substringAfterLast('/') } shouldContainExactly listOf("both", "released")
        repository.recent(WORKSPACE, ReadLens.Everything, limit = 1).single().name shouldBe name(repository, "draft_only")
    }

    private fun <B : Any> exercise(
        repository: ArtifactRepository<B>,
        body: B,
        edited: B,
    ) {
        seed(repository, body, edited)
        val everything =
            mapOf(
                LifecycleBucket.DRAFT_ONLY to listOf("draft_only"),
                LifecycleBucket.RELEASED_ONLY to listOf("released"),
                LifecycleBucket.RELEASED_WITH_DRAFT to listOf("both"),
                LifecycleBucket.DISCARDED_ONLY to listOf("discarded"),
                LifecycleBucket.WITH_DRAFTS to listOf("both", "draft_only"),
                LifecycleBucket.WITH_RELEASES to listOf("both", "released"),
            )
        // Through a lens admitting every name, only RELEASED versions count: the draft-only and discarded objects
        // leave, and `both` is released-only (its draft hidden).
        val narrowed =
            mapOf(
                LifecycleBucket.RELEASED_ONLY to listOf("both", "released"),
                LifecycleBucket.WITH_RELEASES to listOf("both", "released"),
            )
        val all = NAMES.map { name(repository, it) }.toSet()
        listOf<Pair<Set<String>?, Map<LifecycleBucket, List<String>>>>(null to everything, all to narrowed).forEach { (names, expected) ->
            val inventory = repository.inventory(WORKSPACE, names?.let { ReadLens.Only(it) } ?: ReadLens.Everything)
            LifecycleBucket.entries.forEach { bucket ->
                withClue("${repository.kind} $bucket narrowed=${names != null}") {
                    val rows = repository.listIn(WORKSPACE, bucket, names, null)
                    rows.names() shouldContainExactly expected[bucket].orEmpty().map { name(repository, it) }
                    repository.countIn(WORKSPACE, bucket, names, null).toLong() shouldBe bucket.countIn(inventory)
                    if (names != null) rows.forEach { it.detail.status shouldBe PipelineVersionStatus.RELEASED }
                }
            }
        }
        // `both` through the lens shows its RELEASED version 1, never the draft on top of it.
        val both = repository.listIn(WORKSPACE, LifecycleBucket.RELEASED_ONLY, all, null).single { it.record.name.endsWith("/both") }
        both.detail.version shouldBe 1
        // Unlensed, a discarded-only row (NULL pointer, no draft) lists its newest version.
        val discarded = repository.listIn(WORKSPACE, LifecycleBucket.DISCARDED_ONLY, null, null).single()
        discarded.detail.status shouldBe PipelineVersionStatus.DISCARDED
    }

    /**
     * One object per exclusive bucket, through the repository's own lifecycle writes, plus a released object in the
     * other workspace that no read of [WORKSPACE] may see.
     */
    private fun <B : Any> seed(
        repository: ArtifactRepository<B>,
        body: B,
        edited: B,
    ) {
        fun create(
            leaf: String,
            workspace: UUID = WORKSPACE,
        ): Pair<UUID, ArtifactVersionDetail> {
            val id = UUID.randomUUID()
            return id to repository.create(workspace, id, name(repository, leaf), body, AUTHOR, CreateLifecycle.DRAFT, WriteSurface.MCP)
        }

        fun release(
            created: Pair<UUID, ArtifactVersionDetail>,
            workspace: UUID = WORKSPACE,
        ) = requireNotNull(repository.releaseDraft(workspace, created.first, created.second.bodyHash, AUTHOR))

        release(create("released"))
        val both = create("both")
        val bothReleased = release(both)
        requireNotNull(repository.createDraft(WORKSPACE, both.first, edited, bothReleased.bodyHash, AUTHOR, WriteSurface.MCP))
        create("draft_only")
        val gone = create("discarded")
        val goneReleased = release(gone)
        val goneDraft =
            requireNotNull(repository.createDraft(WORKSPACE, gone.first, edited, goneReleased.bodyHash, AUTHOR, WriteSurface.MCP))
        requireNotNull(repository.discardVersion(WORKSPACE, gone.first, goneReleased.version, AUTHOR, draftEligible = true))
        requireNotNull(repository.purgeDraft(WORKSPACE, gone.first, goneDraft.bodyHash, draftEligible = true))
        release(create("released", OTHER_WORKSPACE), OTHER_WORKSPACE)
    }

    private fun name(
        repository: ArtifactRepository<*>,
        leaf: String,
    ) = "filter/${repository.kind.index}/$leaf"

    private fun List<ArtifactVersion<*>>.names() = map { it.record.name }

    private companion object {
        val NAMES = listOf("released", "both", "draft_only", "discarded")
    }
}
