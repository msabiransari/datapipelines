package co.datapipelines.web.ui.site

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `/explore` (145 §2) is GENERATED from the page registry and the packaged docs catalog —
 * this is the proof it stayed so. The directory's links must equal the union of both
 * sources exactly (every page once, nothing invented), the render must carry every one of
 * them as a real `<a href>`, and no group may be empty. The 59 original URLs the approved
 * preview linked are therefore covered by construction — and so is any page added later.
 */
class SiteExploreTest {
    private val docs = SitePageRenderer.docs

    @Test
    fun `the directory equals the registry plus the docs catalog, every page exactly once`() {
        val groups = SiteExplore.groups(docs)
        val linked = groups.flatMap { g -> g.links.map { it.path } }
        val expected =
            SitePages.ALL.map { it.path }.filter { it != SitePages.EXPLORE.path } +
                listOf("/docs") +
                docs.index().flatMap { g -> g.docs.map { "/docs/${it.slug}" } }

        withClue("every page is listed once") { linked.sorted() shouldBe expected.sorted() }
        withClue("no group is empty") { groups.filter { it.links.isEmpty() }.map { it.name }.shouldBeEmpty() }
        // Non-vacuity: 34 registry pages + the docs index + the packaged docs.
        linked.size shouldBeGreaterThanOrEqual MIN_LINKS
        groups.map { it.id } shouldBe listOf("product", "connect", "compare", "docs")
    }

    @Test
    fun `the rendered page links every directory entry and titles it`() {
        val html = SitePageRenderer.render(SitePages.EXPLORE)
        val hrefs = HREF.findAll(html).map { it.groupValues[1] }.toSet()
        val missing =
            SiteExplore
                .groups(docs)
                .flatMap { it.links }
                .filterNot { it.path in hrefs && html.contains(it.title.replace("&", "&amp;")) }
                .map { it.path }
        missing.shouldBeEmpty()
        // The engine cluster carries every engine page, one per dialect.
        SitePages.ENGINES
            .map { SitePages.ENGINE_PREFIX + it.slug }
            .filterNot { it in hrefs }
            .shouldBeEmpty()
    }

    private companion object {
        const val MIN_LINKS = 55
        val HREF = Regex("""<a[^>]*\shref="([^"]*)"""")
    }
}
