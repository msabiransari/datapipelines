package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #585 — the API home's counting, over fixtures: every endpoint lands in exactly one of the
 * console's four states, every [ApiKeyKind] has a bar (a zero is a zero), and a key is live,
 * expired or revoked — exactly one, by the Keys page's own definitions. The narrowing is the
 * Keys page's function, asked for both roles.
 */
class OverviewApiInventoryTest {
    @Test
    fun `each endpoint is counted in exactly one state and legacy rows are their own bucket`() {
        val released = endpoint("/nyc/v1/released")
        val drafted = endpoint("/nyc/v1/drafted")
        val orphan = endpoint("/nyc/v1/orphan")
        val inventory =
            EndpointInventory(
                valid = listOf(released, drafted, orphan),
                legacyCount = 2,
                served =
                    mapOf(
                        released.pipelineId to ServedVersion(3, draft = false),
                        drafted.pipelineId to ServedVersion(1, draft = true),
                    ),
                names = emptyMap(),
            )

        inventory.counts() shouldBe
            mapOf(
                EndpointState.SERVING_RELEASE to 1L,
                EndpointState.SERVING_DRAFT to 1L,
                EndpointState.NOTHING_TO_SERVE to 1L,
                EndpointState.LEGACY to 2L,
            )
        inventory.stateOf(orphan) shouldBe EndpointState.NOTHING_TO_SERVE
    }

    @Test
    fun `every kind has a live bucket and each key is live, expired or revoked - exactly one`() {
        val keys =
            listOf(
                key(ApiKeyKind.MCP),
                key(ApiKeyKind.MCP),
                key(ApiKeyKind.ENDPOINT),
                key(ApiKeyKind.ENDPOINT, expiresAt = NOW.minusSeconds(60)),
                key(ApiKeyKind.SERVER, revoked = true),
                // Revoked AND past its expiry: counted once, as revoked (the Keys page sorts revoked first).
                key(ApiKeyKind.DASHBOARD, revoked = true, expiresAt = NOW.minusSeconds(60)),
                key(ApiKeyKind.DASHBOARD, expiresAt = NOW.plusSeconds(60)),
            )

        val counts = KeyCounts.of(keys, NOW)

        counts.liveByKind.keys shouldContainExactlyInAnyOrder ApiKeyKind.entries
        counts.liveByKind shouldBe
            mapOf(
                ApiKeyKind.MCP to 2L,
                ApiKeyKind.ENDPOINT to 1L,
                ApiKeyKind.SERVER to 0L,
                ApiKeyKind.DASHBOARD to 1L,
            )
        counts.expired shouldBe 1L
        counts.revoked shouldBe 2L
        counts.visible shouldBe keys.size.toLong()
        (counts.liveByKind.values.sum() + counts.expired + counts.revoked) shouldBe counts.visible
    }

    @Test
    fun `the narrowing is the Keys page's - all keys with api_key read, else the caller's own mcp keys`() {
        val me = UUID.randomUUID()
        val mine = key(ApiKeyKind.MCP, createdBy = me)
        val minedEndpoint = key(ApiKeyKind.ENDPOINT, createdBy = me)
        val theirs = key(ApiKeyKind.MCP)
        val all = listOf(mine, minedEndpoint, theirs)

        keysVisibleTo(principal(me, WorkspaceRole.VIEWER), all) shouldContainExactly listOf(mine)
        keysVisibleTo(principal(me, WorkspaceRole.AUTHOR), all) shouldContainExactly listOf(mine)
        keysVisibleTo(principal(me, WorkspaceRole.WORKSPACE_ADMIN), all) shouldContainExactly all
    }

    @Test
    fun `the kind words are the Keys page's, for every kind`() {
        ApiKeyKind.entries.map { ApiKeyRows.kindLabel(it) } shouldContainExactly
            listOf("MCP key", "API key", "Server key", "Dashboard key")
    }

    private fun endpoint(path: String): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = WORKSPACE,
            pathPattern = path,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun key(
        kind: ApiKeyKind,
        revoked: Boolean = false,
        expiresAt: Instant? = null,
        createdBy: UUID = UUID.randomUUID(),
    ): ApiKey =
        ApiKey(
            id = "dpk_" + UUID.randomUUID().toString().take(8),
            userId = UUID.randomUUID(),
            name = "k",
            keyHash = "h",
            isRevoked = revoked,
            createdAt = NOW,
            lastUsedAt = null,
            expiresAt = expiresAt,
            workspaceId = WORKSPACE,
            workspaceName = "acme",
            kind = kind,
            createdBy = createdBy,
        )

    private fun principal(
        userId: UUID,
        role: WorkspaceRole,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(WORKSPACE, "acme", role))

    private companion object {
        val WORKSPACE: UUID = UUID.randomUUID()
        val NOW: Instant = Instant.parse("2026-10-19T02:00:00Z")
    }
}
