package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.RendererSpec
import co.datapipelines.visualization.VisualizationBody
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/** #399's controller tests' shared fixtures: real records and bodies (no relaxed mock answers a field it never had). */
internal object VisualizationUiFixtures {
    val AT: Instant = Instant.parse("2026-10-01T09:00:00Z")
    val AUTHOR_ID: UUID = UUID.randomUUID()
    const val NAME = "acme/charts/revenue"

    /** A bar chart config (the `chart` kind v1); the UI tests never validate it, they render around it. */
    const val BAR_CONFIG =
        """{"mark":{"type":"bar"},"encoding":{"x":{"field":"month","type":"nominal","sort":null},""" +
            """"y":{"type":"quantitative","aggregate":"count"}}}"""

    fun version(
        id: UUID,
        workspaceId: UUID,
        version: Int,
        status: PipelineVersionStatus,
        currentVersion: Int? = null,
        config: String = BAR_CONFIG,
        body: VisualizationBody? = null,
    ): ArtifactVersion<VisualizationBody> =
        ArtifactVersion(
            ArtifactRecord(id, workspaceId, NAME, "Revenue", "", currentVersion, AT, AT, AUTHOR_ID),
            detail(id, version, status),
            body ?: bodyOf(config),
        )

    fun bodyOf(config: String = BAR_CONFIG): VisualizationBody =
        VisualizationBody(
            displayName = "Revenue",
            renderer = RendererSpec(RendererKind.CHART, "1"),
            inputs = emptyMap(),
            config = ArtifactJson.mapper.readTree(config) as ObjectNode,
        )

    fun detail(
        id: UUID,
        version: Int,
        status: PipelineVersionStatus,
    ) = ArtifactVersionDetail(id, version, status, "hash-$version", AT, AUTHOR_ID)

    /** Sign [role] in for [workspaceId]; the caller clears the context after each test. */
    fun authenticate(
        workspaceId: UUID,
        role: WorkspaceRole,
    ): AuthenticatedPrincipal {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "u@d.p",
                "User",
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme", role),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        return principal
    }
}
