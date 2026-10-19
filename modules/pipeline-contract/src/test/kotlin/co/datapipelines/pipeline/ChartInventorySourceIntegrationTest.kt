package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import kotlin.random.Random

/**
 * #598a — the chart group's ONE statement against the shared migrated Postgres: `OverviewE2eTest`'s
 * lifecycle fixture plus two mature pipelines, counted unlensed and through a narrowed lens, and a
 * seeded random workspace checked against a Kotlin oracle. A discarded release is not a release:
 * `b/released_discarded` is Live with ONE release, though its discarded row carries a `released_at`
 * (this fixture's insert stamps every non-draft row, as a release later discarded does).
 */
class ChartInventorySourceIntegrationTest {
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @BeforeEach
    fun seedContext() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'Charts', 'google', :email)",
            mapOf("id" to actor, "email" to "$actor@charts.test"),
        )
        listOf(workspace, otherWorkspace).forEach { id ->
            jdbc.update(
                "INSERT INTO workspaces (id, name, display_name) VALUES (:id, :name, 'Charts')",
                mapOf("id" to id, "name" to "charts_$id"),
            )
        }
    }

    @AfterEach
    fun cleanOwnRows() {
        val params = mapOf("ids" to listOf(workspace, otherWorkspace))
        jdbc.update("DELETE FROM pipelines WHERE workspace_id IN (:ids)", params)
        jdbc.update("DELETE FROM workspaces WHERE id IN (:ids)", params)
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actor))
    }

    private fun seedFixture() {
        insert("a/draft", listOf("DRAFT"))
        insert("a/discarded_draft", listOf("DISCARDED", "DRAFT"))
        insert("b/released", listOf("RELEASED"))
        insert("b/released_twice", listOf("RELEASED", "RELEASED"))
        insert("b/released_discarded", listOf("RELEASED", "DISCARDED"))
        insert("c/both", listOf("RELEASED", "DRAFT"))
        insert("c/both_hidden_draft", listOf("RELEASED", "DRAFT"))
        insert("d/discarded", listOf("DISCARDED"))
        insert("d/discarded_twice", listOf("DISCARDED", "DISCARDED"))
        insert("m/four", List(FOUR) { "RELEASED" })
        insert("m/ten_draft", List(TEN) { "RELEASED" } + "DRAFT")
        insert("b/released", List(TEN) { "RELEASED" } + "DRAFT", otherWorkspace)
    }

    @Test
    fun `unlensed, every object is in one lifecycle part and one release bin`() {
        seedFixture()
        val charts = ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, ReadLens.Everything)
        println("event=598a.charts lens=everything $charts")

        charts.lifecycle shouldBe Inventory(draftOnly = 2, releasedOnly = 4, releasedWithDraft = 3, discardedOnly = 2)
        charts.maturity.associate { it.releases to (it.draftOpen to it.noDraft) } shouldBe
            mapOf(
                ReleaseBin.ZERO to (2L to 2L),
                ReleaseBin.ONE to (2L to 2L),
                ReleaseBin.TWO_TO_THREE to (0L to 1L),
                ReleaseBin.FOUR_TO_NINE to (0L to 1L),
                ReleaseBin.TEN_PLUS to (1L to 0L),
            )
        charts.lifecycle.objects shouldBe 11L
        charts.maturity.sumOf { it.total } shouldBe charts.lifecycle.objects
        withClue("the chart's lifecycle is the four-bucket inventory's, bucket for bucket") {
            charts.lifecycle shouldBe InventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, ReadLens.Everything)
        }
    }

    /** A promoter's lens: the admitted names' RELEASED rows only, so A, C and D are zero and no bin has a draft. */
    @Test
    fun `through a narrowed lens, only released rows are admitted and no draft is counted`() {
        seedFixture()
        val lens = ReadLens.Only(setOf("b/released", "b/released_twice", "c/both_hidden_draft"))
        val charts = ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, lens)
        println("event=598a.charts lens=narrowed $charts")

        charts.lifecycle shouldBe Inventory(draftOnly = 0, releasedOnly = 3, releasedWithDraft = 0, discardedOnly = 0)
        charts.maturity.associate { it.releases to (it.draftOpen to it.noDraft) } shouldBe
            mapOf(
                ReleaseBin.ZERO to (0L to 0L),
                ReleaseBin.ONE to (0L to 2L),
                ReleaseBin.TWO_TO_THREE to (0L to 1L),
                ReleaseBin.FOUR_TO_NINE to (0L to 0L),
                ReleaseBin.TEN_PLUS to (0L to 0L),
            )
        charts.maturity.sumOf { it.total } shouldBe charts.lifecycle.objects
        charts.lifecycle shouldBe InventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, lens)
    }

    @Test
    fun `an empty workspace is all zeros and the foreign workspace is never counted`() {
        insert("b/released", listOf("RELEASED", "DRAFT"), otherWorkspace)

        ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, ReadLens.Everything) shouldBe ChartInventory.EMPTY
        ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, ReadLens.Only(setOf("b/released"))) shouldBe
            ChartInventory.EMPTY
    }

    /**
     * A seeded random workspace against a Kotlin oracle computed from the same status lists — the
     * oracle is the rule written out (admitted rows; d/r/x flags; releases = RELEASED rows), never
     * the statement under test. Both arms; the sum invariant holds on each.
     */
    @Test
    fun `a random workspace counts what the rule says, unlensed and narrowed`() {
        val random = Random(RANDOM_SEED)
        val objects =
            List(RANDOM_OBJECTS) { index ->
                val statuses =
                    List(random.nextInt(0, MAX_RELEASES + 1)) { "RELEASED" } +
                        List(random.nextInt(0, MAX_DISCARDS + 1)) { "DISCARDED" } +
                        (if (random.nextBoolean()) listOf("DRAFT") else emptyList())
                "r/p$index" to statuses.ifEmpty { listOf("DRAFT") }.shuffled(random)
            }
        objects.forEach { (name, statuses) -> insert(name, statuses) }
        val narrowed = objects.map { it.first }.filter { random.nextBoolean() }.toSet()

        listOf<ReadLens>(ReadLens.Everything, ReadLens.Only(narrowed)).forEach { lens ->
            val admitted =
                objects.mapNotNull { (name, statuses) ->
                    when (lens) {
                        is ReadLens.Only -> statuses.filter { it == "RELEASED" }.takeIf { name in lens.names && it.isNotEmpty() }
                        else -> statuses
                    }
                }
            val expected = oracle(admitted)
            val actual = ChartInventorySource.aggregate(jdbc, NavigationFamily.PIPELINES, workspace, lens)
            println("event=598a.charts.random lens=${if (lens is ReadLens.Only) "narrowed" else "everything"} $actual")
            actual shouldBe expected
            actual.maturity.sumOf { it.total } shouldBe actual.lifecycle.objects
        }
    }

    private fun oracle(admitted: List<List<String>>): ChartInventory {
        fun flags(statuses: List<String>) = Triple("DRAFT" in statuses, "RELEASED" in statuses, "DISCARDED" in statuses)
        val lifecycle =
            Inventory(
                draftOnly = admitted.count { flags(it).let { (d, r, _) -> d && !r } }.toLong(),
                releasedOnly = admitted.count { flags(it).let { (d, r, _) -> r && !d } }.toLong(),
                releasedWithDraft = admitted.count { flags(it).let { (d, r, _) -> d && r } }.toLong(),
                discardedOnly = admitted.count { flags(it).let { (d, r, x) -> x && !d && !r } }.toLong(),
            )
        val bins =
            ReleaseBin.entries.map { bin ->
                val inBin = admitted.filter { bin.contains(it.count { status -> status == "RELEASED" }.toLong()) }
                MaturityBin(bin, draftOpen = inBin.count { "DRAFT" in it }.toLong(), noDraft = inBin.count { "DRAFT" !in it }.toLong())
            }
        return ChartInventory(lifecycle, bins)
    }

    /** `OverviewE2eTest`'s insert: every non-draft row is stamped released, a discarded one discarded too. */
    private fun insert(
        name: String,
        statuses: List<String>,
        workspaceId: UUID = workspace,
    ) {
        val id = UUID.randomUUID()
        val current = statuses.indexOfLast { it == "RELEASED" }.takeIf { it >= 0 }?.plus(1)
        jdbc.update(
            "INSERT INTO pipelines (id, workspace_id, name, display_name, current_version, owner_id) " +
                "VALUES (:id, :workspace, :name, :name, :current, :actor)",
            mapOf("id" to id, "workspace" to workspaceId, "name" to name, "current" to current, "actor" to actor),
        )
        statuses.forEachIndexed { index, status ->
            jdbc.update(
                "INSERT INTO pipeline_versions (pipeline_id, version, body_json, status, body_hash, created_by, " +
                    "released_at, released_by, discarded_at, discarded_by) " +
                    "VALUES (:id, :version, CAST('{}' AS jsonb), :status, :hash, :actor, " +
                    "CASE WHEN :status <> 'DRAFT' THEN NOW() END, CASE WHEN :status <> 'DRAFT' THEN :actor END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN NOW() END, " +
                    "CASE WHEN :status = 'DISCARDED' THEN :actor END)",
                mapOf("id" to id, "version" to index + 1, "status" to status, "hash" to "a".repeat(HASH_LENGTH), "actor" to actor),
            )
        }
    }

    private companion object {
        const val FOUR = 4
        const val TEN = 10
        const val HASH_LENGTH = 64
        const val RANDOM_SEED = 598
        const val RANDOM_OBJECTS = 60
        const val MAX_RELEASES = 12
        const val MAX_DISCARDS = 2
    }
}
