package co.datapipelines.web.ui

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.ui.ExtendedModelMap

/** #583 — the Datasources overview route renders the shared frame under `datasource.read`. */
class DatasourceOverviewControllerTest {
    private val controller = DatasourceOverviewController(mockk { every { resolve(any()) } returns "light" })

    @Test
    fun `the route renders the family frame with its title catalog and no numbers of its own yet`() {
        val model = ExtendedModelMap()

        controller.overview(model, MockHttpServletRequest()) shouldBe OverviewController.VIEW

        model["activeTheme"] shouldBe "light"
        val page = model[OverviewController.MODEL] as OverviewPage
        page.family shouldBe "datasources"
        page.title shouldBe "Datasources"
        page.catalogHref shouldBe "/datasources"
        page.lensed shouldBe false
        page.guided shouldBe false
        page.recent.shouldBeNull()
        page.inventory.shouldNotBeNull()
        page.inventory.counts shouldBe emptyList()
        page.inventory.charts shouldBe emptyList()
    }
}
