package co.datapipelines.web.config

import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.PrincipalLiveness
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.visualization.StarterAuthority
import co.datapipelines.visualization.TestFixtureEvaluator
import co.datapipelines.visualization.TestRunRepository
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.VisualizationRepository
import co.datapipelines.visualization.VisualizationTestCapabilities
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.visualizations.WebStarterAuthority
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The L4b wiring (#353): `VisualizationTestSurfacesConfiguration` builds the starter authority (the REAL auth-module
 * adapter, never a permissive stand-in), the capability operations over it, and the links from the configured
 * `datapipelines.auth.base-url` — beside 352's configuration, over collaborators of its own.
 */
class VisualizationTestSurfacesConfigurationWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(VisualizationTestSurfacesConfiguration::class.java, Collaborators::class.java)

    @Test
    fun `the surfaces' graph builds over the real starter authority and the configured origin`() {
        context.run { ctx ->
            ctx.getBean(StarterAuthority::class.java).shouldBeInstanceOf<WebStarterAuthority>()
            ctx.getBean(VisualizationTestCapabilities::class.java).shouldNotBeNull()
            val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
            ctx.getBean(TestSessionLinks::class.java).preview(id, "T") shouldBe
                "https://dp.example.com/visualizations/$id/preview?session=T"
        }
    }

    @Configuration
    @Suppress("unused")
    class Collaborators {
        @Bean
        fun authProperties(): AuthProperties = AuthProperties(baseUrl = "https://dp.example.com")

        @Bean
        fun userService(): UserService = mockk(relaxed = true)

        @Bean
        fun workspaceService(): WorkspaceService = mockk(relaxed = true)

        @Bean
        fun workspaceRepository(): WorkspaceRepository = mockk(relaxed = true)

        @Bean
        fun apiKeyRepository(): ApiKeyRepository = mockk(relaxed = true)

        @Bean
        fun principalLiveness(): PrincipalLiveness = mockk(relaxed = true)

        @Bean
        fun testRunRepository(): TestRunRepository = mockk(relaxed = true)

        @Bean
        fun visualizationRepository(): VisualizationRepository = mockk(relaxed = true)

        @Bean
        fun sessions(): VisualizationTestSessionService = mockk(relaxed = true)

        @Bean
        fun fixtures(): TestFixtureEvaluator = mockk(relaxed = true)
    }
}
