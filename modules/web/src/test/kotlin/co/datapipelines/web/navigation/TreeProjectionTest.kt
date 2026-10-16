package co.datapipelines.web.navigation

import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.NavigationRequest
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.ReadLens
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class TreeProjectionTest {
    private val projection = TreeProjection()
    private val workspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val rows = List(201) { index -> NavigationRow("scope/a/match_$index", "Display $index", UUID.randomUUID(), 1, null) }

    @Test
    fun `search includes all ancestors of each delivered match inside the root`() {
        val page = answer(query = "match") { rows }
        page.nodes.count { it.match } shouldBe 200
        page.nodes.filter { it.kind == "folder" }.map { it.path } shouldContainExactly listOf("scope/a")
        page.nodes.all { it.parentKey == null || it.parentKey == "folder:scope/a" } shouldBe true
        page.nextCursor.isNullOrEmpty() shouldBe false
        val resumed =
            answer(query = "match", cursor = page.nextCursor) { request ->
                request.after shouldBe rows[199].orderKey
                listOf(rows.last())
            }
        resumed.nextCursor shouldBe null
        resumed.nodes.count { it.match } shouldBe 1
    }

    @Test
    fun `continuation refuses a different query parent root workspace caller family or admitted view before loading`() {
        val cursor = answer(query = "match") { rows }.nextCursor
        val neverLoad: (NavigationRequest) -> List<NavigationRow> = { error("Foreign continuation reached repository") }
        shouldThrow<ResponseStatusException> { answer(query = "other", cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(parent = "scope/other", cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(root = "", cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(workspaceId = UUID.randomUUID(), cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(actorId = UUID.randomUUID(), cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(family = NavigationFamily.TEMPLATES, cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(lens = ReadLens.Only(setOf("different")), cursor = cursor, load = neverLoad) }
        shouldThrow<ResponseStatusException> { answer(lens = ReadLens.Only(setOf("everything")), cursor = cursor, load = neverLoad) }
    }

    @Test
    fun `malformed tampered cursor and escaping parent are safe bad requests`() {
        listOf("bad", "!.!", "x".repeat(5000)).forEach { cursor ->
            shouldThrow<ResponseStatusException> {
                answer(
                    cursor = cursor,
                ) { error("Must refuse before loading") }
            }.statusCode.value() shouldBe
                400
        }
        val good = answer { rows }.nextCursor.orEmpty()
        shouldThrow<ResponseStatusException> { answer(cursor = good + "x") { error("Must refuse") } }
        shouldThrow<ResponseStatusException> { answer(parent = "sibling") { error("Must refuse") } }
        shouldThrow<ResponseStatusException> { answer(root = "scope/../escape") { error("Must refuse") } }
        shouldThrow<ResponseStatusException> { answer(query = "bad\u0000query") { error("Must refuse") } }
    }

    @Test
    fun `browse does not manufacture ancestors and folder leaf collisions retain distinct keys`() {
        val page =
            answer {
                listOf(
                    NavigationRow("scope/collision", "collision", null, null, null),
                    NavigationRow("scope/collision", "Leaf", UUID.randomUUID(), 1, 2),
                )
            }
        page.nodes.size shouldBe 2
        page.nodes
            .map { it.key }
            .distinct()
            .size shouldBe 2
        page.nodes.all { !it.match && it.parentKey == null } shouldBe true
        page.nodes.last().draftVersion shouldBe 2
    }

    @Test
    fun `response byte budget continues rather than dropping large search projections`() {
        val large =
            List(201) { index ->
                NavigationRow("scope/a/match_$index", "Display".repeat(2000), UUID.randomUUID(), 1, null)
            }
        val first = answer(query = "match") { large }
        (first.nodes.count { it.match } in 1..199) shouldBe true
        first.nextCursor.isNullOrEmpty() shouldBe false
        val count = first.nodes.count { it.match }
        val next =
            answer(query = "match", cursor = first.nextCursor) { request ->
                request.after shouldBe large[count - 1].orderKey
                large.drop(count)
            }
        next.nodes.any { it.key == "artifact:${large[count].id}" } shouldBe true
    }

    /**
     * #533 — the review hook is asked ONCE per page, with the page's artifacts only; every artifact
     * then carries the flag (true for the keys it named), folders carry none, and a page without
     * the hook — every family but templates — carries none at all.
     */
    @Test
    fun `the review hook marks the named artifacts once per page and leaves folders unflagged`() {
        val leaves = rows.take(2)
        val calls = mutableListOf<List<String>>()
        val page =
            answer(family = NavigationFamily.TEMPLATES, needsReview = { artifacts ->
                calls += artifacts.map { it.key }
                setOf(leaves[0].key)
            }) { listOf(NavigationRow("scope/sub", "sub", null, null, null)) + leaves }
        calls shouldBe listOf(leaves.map { it.key })
        page.nodes.associate { it.key to it.needsReview } shouldBe
            mapOf("folder:scope/sub" to null, leaves[0].key to true, leaves[1].key to false)

        answer { leaves }.nodes.map { it.needsReview } shouldBe listOf(null, null)
        answer(family = NavigationFamily.TEMPLATES, needsReview = { error("a page of folders must not ask") }) {
            listOf(NavigationRow("scope/sub", "sub", null, null, null))
        }.nodes.single().needsReview shouldBe null
    }

    /**
     * #533 — the byte budget costs every reviewed artifact with `needs_review: false`, the flag's
     * longest form, so marking can only shorten what was counted. The leaf width is chosen where
     * costing one byte shorter (`null`) would admit one more leaf, so the count tells the two apart.
     */
    @Test
    fun `the byte budget counts a template leaf's needs_review at its longest form`() {
        val mapper = jacksonObjectMapper()
        val budget = TreeProjection.MAX_RESPONSE_BYTES - TreeProjection.ENVELOPE_RESERVE

        fun leaves(width: Int) = List(201) { NavigationRow("scope/t_%03d".format(it), "d".repeat(width), UUID(0, it.toLong()), 1, null) }
        val clean: (List<TreeNode>) -> Set<String> = { emptySet() }

        fun cost(page: TreePage) = page.nodes.sumOf { mapper.writeValueAsBytes(it).size + 1 }
        val base = 6000
        val one = cost(answer(family = NavigationFamily.TEMPLATES, needsReview = clean) { leaves(base).take(1) })
        val width = (base..base + 2000).first { budget / (one + it - base) < budget / (one + it - base - 1) }
        val longest = one + width - base

        val page = answer(family = NavigationFamily.TEMPLATES, needsReview = clean) { leaves(width) }
        page.nodes.size shouldBe budget / longest
        (cost(page) <= budget) shouldBe true
        page.nextCursor.isNullOrEmpty() shouldBe false
    }

    /**
     * #504b — the shared needle rule runs FIRST in the projection: a whitespace-only query is no
     * search (the loader sees null and answers the plain browse level), and an over-long one is
     * the shared refusal before any loader runs.
     */
    @Test
    fun `a whitespace-only query is a browse, not a search`() {
        val loaded =
            answer(query = "   \t ") { request ->
                request.query shouldBe null
                rows.take(1)
            }
        loaded.nodes.count { it.match } shouldBe 0
    }

    @Test
    fun `an over-long query is the shared refusal before any load runs`() {
        val over = "r".repeat(co.datapipelines.pipeline.SearchNeedle.MAX_LENGTH + 1)
        shouldThrow<co.datapipelines.pipeline.SearchQueryTooLongException> {
            answer(query = over) { _ ->
                throw IllegalStateException("the loader must not run past the needle gate")
            }
        }
    }

    @Suppress("LongParameterList") // explicit identity substitutions are the security cases
    private fun answer(
        family: NavigationFamily = NavigationFamily.PIPELINES,
        workspaceId: UUID = workspace,
        actorId: UUID = actor,
        lens: ReadLens = ReadLens.Everything,
        root: String = "scope",
        parent: String = root,
        query: String? = null,
        cursor: String? = null,
        needsReview: ((List<TreeNode>) -> Set<String>)? = null,
        load: (NavigationRequest) -> List<NavigationRow>,
    ): TreePage = projection.page(family, workspaceId, actorId, lens, root, parent, query, cursor, needsReview, load)
}
