package co.datapipelines.browser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The folder walk every explorer expansion in [SiteShotsMain] relies on (#166): the shots
 * photograph whatever pipeline the run names, so the prefixes must come from the NAME, not
 * from a hard-coded `nyc/mobility` pair — a name three folders deep expands three levels.
 */
class SiteShotsFolderPrefixesTest {
    @Test
    fun `every folder above the leaf, outermost first`() {
        SiteShotsMain.folderPrefixes("demo/snow_days/taxi_vs_rideshare_snow_vs_clear_by_borough") shouldBe
            listOf("demo", "demo/snow_days")
    }

    @Test
    fun `the seeded two-level default expands two levels`() {
        SiteShotsMain.folderPrefixes("nyc/mobility/weather_sensitivity_by_borough") shouldBe listOf("nyc", "nyc/mobility")
    }

    @Test
    fun `a bare name has nothing to expand`() {
        SiteShotsMain.folderPrefixes("orphan") shouldBe emptyList()
    }
}
