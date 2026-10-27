package co.datapipelines.browser

import co.datapipelines.browser.SiteShotsMain.APP_SHOT_SECTIONS
import co.datapipelines.browser.SiteShotsMain.MIN_CLICKED_SECTIONS
import co.datapipelines.browser.SiteShotsMain.RAIL_SECTIONS
import co.datapipelines.web.ui.AppNav
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #721 — the screenshot driver's rail table is pinned against `AppNav.ITEMS`.
 *
 * `SiteShotsMain` walks the rail in the order of its own `APP_SHOT_SECTIONS` table (#602): the
 * table carries each section's routes, which `AppNav` does not know, so it stays a table. The
 * only thing a rename or a reorder in `AppNav` could do to the walk is print
 * `SKIP <section> (not in this account's rail)`, and the `MIN_CLICKED_SECTIONS` floor catches
 * mass drift only. This class turns one drifted row into a red build. A plain JVM test: it
 * reads two in-memory lists and boots no application, container or browser.
 */
class SiteShotsRailOrderTest {
    private val navSections: List<String> = AppNav.ITEMS.map { it.section }

    @Test
    fun `the driver walks the rail sections in the order of AppNav ITEMS`() {
        RAIL_SECTIONS shouldBe navSections
    }

    @Test
    fun `every table row with a section names an AppNav section`() {
        val known = navSections.toSet()
        val unknown = APP_SHOT_SECTIONS.mapNotNull { it.section }.filterNot { it in known }
        unknown shouldBe emptyList()
    }

    @Test
    fun `settings is the only off-rail row`() {
        val offRail = APP_SHOT_SECTIONS.filter { it.section == null }
        offRail.map { it.routes } shouldBe listOf(listOf("/settings"))
    }

    @Test
    fun `the table names each section once`() {
        RAIL_SECTIONS.shouldBeUnique()
    }

    @Test
    fun `an empty AppNav cannot pass the pin`() {
        navSections.size shouldBeGreaterThanOrEqual MIN_CLICKED_SECTIONS
        RAIL_SECTIONS.size shouldBeGreaterThanOrEqual MIN_CLICKED_SECTIONS
    }
}
