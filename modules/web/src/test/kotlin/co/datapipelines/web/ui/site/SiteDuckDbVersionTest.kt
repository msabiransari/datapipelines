package co.datapipelines.web.ui.site

import co.datapipelines.datasources.DuckDbVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The site names the bundled DuckDB version from the token, never from a literal (#781). The page is
 * rendered through its real controller, so the three places the dp-lake page states the version — the
 * "Iceberg tables register by their metadata file" item, the FAQ answer and that answer's FAQPage
 * JSON-LD — are read as the visitor gets them, and compared with the version catalog, an oracle the
 * token does not own. A source that types the version back in is red on its own.
 */
class SiteDuckDbVersionTest {
    @Test
    fun `the dp-lake page states the catalog's DuckDB version in all three places`() {
        val html = SitePageRenderer.render(SitePages.DP_LAKE)
        val stated = STATEMENT.findAll(html).map { it.groupValues[1] }.toList()
        withClue("versions the rendered dp-lake page states before 'cannot resolve a pyiceberg table': $stated") {
            stated shouldBe List(RENDERED_STATEMENTS) { catalogDuckDbJdbc() }
        }
    }

    @Test
    fun `the site's token is the packaged driver version and the catalog's duckdb-jdbc`() {
        SiteFacts.current().duckdbVersion shouldBe DuckDbVersion.CURRENT.jdbc
        SiteFacts.current().duckdbVersion shouldBe catalogDuckDbJdbc()
    }

    @Test
    fun `neither source types a DuckDB version`() {
        SOURCES.forEach { relative ->
            val text = File(repoRoot(), relative).also { check(it.isFile) { "missing $relative" } }.readText()
            withClue("$relative must render the version from facts.duckdbVersion") {
                text.contains("facts.duckdbVersion") shouldBe true
                TYPED_VERSION.findAll(text).map { it.value }.toList() shouldBe emptyList()
                val pinned = catalogDuckDbJdbc()
                listOf(pinned, DuckDbVersion(pinned).core).forEach { literal ->
                    Regex("""(?<![0-9.])${Regex.escape(literal)}(?![0-9])""").containsMatchIn(text) shouldBe false
                }
            }
        }
    }

    private fun catalogDuckDbJdbc(): String {
        val catalog = File(repoRoot(), "gradle/libs.versions.toml").readText()
        return requireNotNull(Regex("""(?m)^duckdb-jdbc = "([^"]+)"""").find(catalog)) { "no duckdb-jdbc in the catalog" }
            .groupValues[1]
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        /** The item, the visible FAQ answer and the FAQPage JSON-LD answer. */
        const val RENDERED_STATEMENTS = 3
        val STATEMENT = Regex("""DuckDB (\S+) cannot resolve a pyiceberg table""")

        /** A version typed after the engine's name (`DuckDB 1.5`, `duckdb_jdbc v2.0`) — any version, not only today's. */
        val TYPED_VERSION = Regex("""(?i)duckdb(?:[ _-]?jdbc)?[\s:=-]*v?[0-9]+\.[0-9]+""")
        val SOURCES =
            listOf(
                "modules/web/src/main/kotlin/co/datapipelines/web/ui/site/SiteFaqs.kt",
                "modules/web/src/main/resources/templates/site/dp-lake.html",
            )
    }
}
