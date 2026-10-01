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
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The test-session SURFACES' wiring (#353, L4b) — beside [VisualizationTestConfiguration] (352's backend beans,
 * untouched) rather than inside it, so each wiring test's context stays the size of its own slice:
 *
 * - the starter authority — [WebStarterAuthority], the auth-module re-check a capability-bearing request gets;
 * - the capability operations — [VisualizationTestCapabilities], the preview read and the session-less upload;
 * - the capability links — [TestSessionLinks], built from `datapipelines.auth.base-url` (never a request header),
 *   shared by the REST routes and the two MCP tools so both hand out the same URLs.
 */
@Configuration
class VisualizationTestSurfacesConfiguration {
    @Bean
    @Suppress("LongParameterList") // the authority's ports ARE the wiring
    fun visualizationStarterAuthority(
        users: UserService,
        workspaces: WorkspaceService,
        workspaceRows: WorkspaceRepository,
        keys: ApiKeyRepository,
        liveness: PrincipalLiveness,
    ): StarterAuthority = WebStarterAuthority(users, workspaces, workspaceRows, keys, liveness)

    @Bean
    @Suppress("LongParameterList") // the capability operations' ports ARE the wiring
    fun visualizationTestCapabilities(
        runs: TestRunRepository,
        visualizations: VisualizationRepository,
        sessions: VisualizationTestSessionService,
        fixtures: TestFixtureEvaluator,
        authority: StarterAuthority,
    ): VisualizationTestCapabilities = VisualizationTestCapabilities(runs, visualizations, sessions, fixtures, authority)

    @Bean
    fun visualizationTestSessionLinks(auth: AuthProperties): TestSessionLinks = TestSessionLinks(auth.baseUrl)
}
