package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [ReadLens] — the promoter lens as a value (178, roles design §3.1) — and
 * [PipelineFolderLevel.of], the in-memory tree level a lensed read derives.
 *
 * The level builder is pinned rule by rule against `PipelineTreeQueries`' stated contract
 * (scope by `prefix/`, a folder per first segment of a remainder that carries a `/`, a leaf per
 * remainder that does not, folders by segment with the overflow reported, leaves by name with
 * the truthful total) so that the SQL presentation and the lensed one cannot drift: the two
 * are asserted equal by `PipelineServiceIntegrationTest` on real rows, and this test says WHICH
 * rule broke when they do.
 */
class ReadLensTest {
    @Test
    fun `Everything admits every name and is the fast path, Only admits exactly its names`() {
        ReadLens.Everything.admits("finance/daily") shouldBe true
        ReadLens.Everything.isEverything shouldBe true

        val only = ReadLens.Only(setOf("finance/daily", "ops/nightly"))
        only.admits("finance/daily") shouldBe true
        only.admits("finance/daily_v2") shouldBe false
        only.isEverything shouldBe false

        withClue("an empty lens is legal and admits nothing — the fail-closed view") {
            ReadLens.NOTHING.admits("finance/daily") shouldBe false
            ReadLens.NOTHING.isEverything shouldBe false
        }
        withClue("the lens never prints its names — a toString in a log line is not a listing") {
            only.toString() shouldBe "ReadLens.Only(2 names)"
        }
    }

    @Test
    fun `through keeps the list identity under Everything and filters by the named key under Only`() {
        val rows = listOf(record("a/one"), record("b/two"), record("a/three"))
        (rows.through(ReadLens.Everything) { it.name } === rows) shouldBe true
        rows.through(ReadLens.Only(setOf("a/one", "a/three"))) { it.name }.map { it.name } shouldContainExactly
            listOf("a/one", "a/three")
        rows.through(ReadLens.NOTHING) { it.name } shouldBe emptyList()
    }

    @Test
    fun `the root level is the folders of the first segment and the flat leaves, by the SQL's rules`() {
        val rows = listOf("nyc/mobility/daily", "nyc/mobility/weekly", "nyc/parks", "ops/nightly", "legacy_flat").map(::record)

        val root = PipelineFolderLevel.of(rows, prefix = null)

        withClue("a folder per first segment, counted over its whole subtree, ordered by segment") {
            root.folders shouldContainExactly
                listOf(PipelineFolder("nyc", "nyc", pipelineCount = 3), PipelineFolder("ops", "ops", pipelineCount = 1))
        }
        withClue("a pre-077 flat name is the root's only leaf (the SQL keeps it; withoutLeaves() drops it)") {
            root.pipelines.map { it.name } shouldContainExactly listOf("legacy_flat")
            root.total shouldBe 1
            root.hasMore shouldBe false
        }
    }

    @Test
    fun `a folder level scopes by prefix slash, so a sibling sharing the prefix's letters is out`() {
        val rows = listOf("nyc/mobility/daily", "nyc/mobility/weekly", "nyc/parks", "nycx/other").map(::record)

        val nyc = PipelineFolderLevel.of(rows, prefix = "nyc")

        nyc.folders shouldContainExactly listOf(PipelineFolder("nyc/mobility", "mobility", pipelineCount = 2))
        nyc.pipelines.map { it.name } shouldContainExactly listOf("nyc/parks")
        nyc.total shouldBe 1

        val mobility = PipelineFolderLevel.of(rows, prefix = "nyc/mobility")
        mobility.folders shouldBe emptyList()
        mobility.pipelines.map { it.name } shouldContainExactly listOf("nyc/mobility/daily", "nyc/mobility/weekly")
    }

    @Test
    fun `leaves are paged by name with the truthful total, folders are capped with the overflow reported`() {
        val rows = (1..5).map { record("f/leaf_$it") } + (1..4).map { record("f/sub_$it/x") }

        val page = PipelineFolderLevel.of(rows, prefix = "f", offset = 2, limit = 2, folderLimit = 3)

        page.pipelines.map { it.name } shouldContainExactly listOf("f/leaf_3", "f/leaf_4")
        page.total shouldBe 5
        page.hasMore shouldBe true
        page.folders.map { it.segment } shouldContainExactly listOf("sub_1", "sub_2", "sub_3")
        page.foldersTruncated shouldBe true

        val last = PipelineFolderLevel.of(rows, prefix = "f", offset = 4, limit = 2, folderLimit = 4)
        last.pipelines.map { it.name } shouldContainExactly listOf("f/leaf_5")
        last.hasMore shouldBe false
        last.foldersTruncated shouldBe false
    }

    private fun record(name: String): PipelineRecord =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = name,
            description = "",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
