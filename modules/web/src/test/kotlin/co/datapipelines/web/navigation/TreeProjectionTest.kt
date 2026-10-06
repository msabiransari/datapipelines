package co.datapipelines.web.navigation

import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.NavigationRequest
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.ReadLens
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
        load: (NavigationRequest) -> List<NavigationRow>,
    ): TreePage = projection.page(family, workspaceId, actorId, lens, root, parent, query, cursor, load)
}
