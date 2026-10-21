package co.datapipelines.web.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** #659: section ownership requires a whole path segment, including off-rail crumbs. */
class AppNavTest {
    @Test
    fun `every section owns its root descendants and overview`() {
        AppNav.ITEMS.forEach { item ->
            listOf(item.section, "${item.section}/x", "/overview${item.section}", "/overview${item.section}/x").forEach { path ->
                AppNav.matches(item.section, path) shouldBe true
                AppNav.activeFor(path) shouldBe item
                AppNav.crumbFor(path) shouldBe (item.group to item.label)
            }
        }
    }

    @Test
    fun `bare prefixes own no item and no crumb`() {
        AppNav.ITEMS.forEach { item ->
            listOf("${item.section}x", "/overview${item.section}x").forEach { path ->
                AppNav.matches(item.section, path) shouldBe false
                AppNav.activeFor(path) shouldBe null
                AppNav.crumbFor(path) shouldBe null
            }
        }
        AppNav.matches("/dashboard", "/dashboards") shouldBe false
        AppNav.activeFor("/dashboards") shouldBe AppNav.bySection("/dashboards")
        AppNav.crumbFor("/settingsx") shouldBe null
        AppNav.crumbFor("/api-keysx") shouldBe null
    }

    @Test
    fun `the longest off-rail key wins at its boundary`() {
        AppNav.crumbFor("/settings/api-keys") shouldBe (null to "API keys")
        AppNav.crumbFor("/settings/api-keys/x") shouldBe (null to "API keys")
        AppNav.crumbFor("/settings/password") shouldBe (null to "Password")
        AppNav.crumbFor("/settings/api-keysx") shouldBe (null to "Settings")
        AppNav.crumbFor(null) shouldBe null
        AppNav.activeFor("") shouldBe null
    }
}
