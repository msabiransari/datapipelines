package co.datapipelines.web.bootstrap

import co.datapipelines.application.endpoints.ManagedEndpointKeys
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.DemoWorkspaceSeeder
import co.datapipelines.auth.WorkspaceRepository
import java.util.UUID

/**
 * The demo seeder's managed identity (#224), stated once: in the workspace named [DemoWorkspaceSeeder.DEMO_WORKSPACE],
 * the live `endpoint`-kind keys named [DemoEndpointSeeder.MANAGED_NAME], oldest first — the first is the managed one
 * ([DemoEndpointSeeder] reconciles it on every boot; any later one is an operator's row it only warns about).
 *
 * #1111: that managed key is the deployment's PUBLIC read credential, so its namespace-writes elevation is refused
 * ([ManagedEndpointKeys]). Identity is the workspace's id plus the key's id — a key of the same name in any other
 * workspace is an ordinary key, and the name alone grants or refuses nothing.
 */
class DemoManagedEndpointKey(
    private val keyRepository: ApiKeyRepository,
    private val workspaceRepository: WorkspaceRepository,
) : ManagedEndpointKeys {
    override fun isManaged(
        apiKeyId: String,
        workspaceId: UUID,
    ): Boolean {
        val demo = workspaceRepository.findByName(DemoWorkspaceSeeder.DEMO_WORKSPACE) ?: return false
        return demo.id == workspaceId && liveManagedKeys(keyRepository, workspaceId).firstOrNull()?.id == apiKeyId
    }

    companion object {
        /** Live managed-name endpoint keys of [demoId], oldest first — the managed row is the first. */
        fun liveManagedKeys(
            keyRepository: ApiKeyRepository,
            demoId: UUID,
        ): List<ApiKey> =
            keyRepository
                .findByWorkspaceAndName(demoId, DemoEndpointSeeder.MANAGED_NAME)
                .filter { it.kind == ApiKeyKind.ENDPOINT }
                .sortedBy { it.createdAt }
    }
}
