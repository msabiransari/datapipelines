package co.datapipelines.web.ui

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** One fixture pins the server rule and real rendered layout alongside the three JS rules. */
class FamilyPathRuleParityTest {
    private val engine = ShellRenderFixture.engine()

    @Test
    fun `shared family path fixture has a non-vacuous coverage floor`() {
        withClue("fixture rows=${familyPaths.size}, floor=$FAMILY_PATH_FLOOR") {
            (familyPaths.size >= FAMILY_PATH_FLOOR) shouldBe true
        }
        familyPaths.any { it.section == null } shouldBe true
        familyPaths.any { it.family == null } shouldBe true
        familyPaths.mapNotNull { it.section }.toSet() shouldBe AppNav.ITEMS.map { it.section }.toSet()
        familyPaths.map { it.path }.distinct().size shouldBe familyPaths.size
    }

    @Test
    fun `AppNav agrees with the shared family path fixture`() {
        familyPaths.forEach { row ->
            withClue("AppNav path=${row.path}") {
                AppNav.ITEMS.filter { AppNav.matches(it.section, row.path) }.map { it.section } shouldBe listOfNotNull(row.section)
                val item = row.section?.let { AppNav.bySection(it).shouldNotBeNull() }
                AppNav.crumbFor(row.path) shouldBe
                    if (item != null) item.group to item.label else offRailCrumbs[row.path]?.let { null to it }
            }
        }
    }

    @Test
    fun `rendered layout agrees with the shared family path fixture`() {
        familyPaths.forEach { row ->
            withClue("layout path=${row.path}") {
                val html =
                    engine.process(
                        "pipelines/list",
                        ShellRenderFixture.context().apply {
                            ShellRenderFixture.fillList(this)
                            setVariable("currentPath", row.path)
                            setVariable("crumbGroup", AppNav.crumbFor(row.path)?.first)
                            setVariable("crumbPage", AppNav.crumbFor(row.path)?.second)
                        },
                    )
                val active = Regex("""<a [^>]*class="app-nav-link active"[^>]*>""").findAll(html).toList()
                active.map { Regex("""data-nav-section="([^"]+)"""").find(it.value).shouldNotBeNull().groupValues[1] } shouldBe
                    listOfNotNull(row.section)
                val browser = Regex("""<aside class="app-browser"[^>]*>""").find(html).shouldNotBeNull().value
                browser.contains("hidden") shouldBe (row.family == null)
                val panels = PANEL.findAll(html).toList()
                panels.map { it.groupValues[1] }.toSet() shouldBe familyPaths.mapNotNull { it.family }.toSet()
                panels.filterNot { it.value.contains("hidden") }.map { it.groupValues[1] } shouldBe listOfNotNull(row.family)
                val label = row.section?.let { AppNav.bySection(it).shouldNotBeNull().label } ?: offRailCrumbs[row.path] ?: "datapipelines"
                html.substringAfter("class=\"app-crumb-page\"").substringAfter(">").substringBefore("<") shouldBe label
            }
        }
    }

    private data class FamilyPath(
        val path: String,
        val section: String?,
        val family: String?,
    )

    private val familyPaths =
        requireNotNull(javaClass.getResourceAsStream("/shell/family-path-rule.json")) {
            "Missing shared family path fixture"
        }.use { input ->
            ObjectMapper().readTree(input).map { row ->
                FamilyPath(
                    row["path"].asText(),
                    row["section"].takeUnless { it.isNull }?.asText(),
                    row["family"].takeUnless { it.isNull }?.asText(),
                )
            }
        }

    private val offRailCrumbs =
        mapOf("/settings/api-keys" to "API keys", "/api-keys" to "API keys", "/settings/password" to "Password", "/settings" to "Settings")

    private companion object {
        const val FAMILY_PATH_FLOOR = 96
        val PANEL = Regex("""<div class="app-nav-tree app-browser-tree" id="nav-tree-([a-z-]+)" data-nav-tree="([a-z-]+)"[^>]*>""")
    }
}
