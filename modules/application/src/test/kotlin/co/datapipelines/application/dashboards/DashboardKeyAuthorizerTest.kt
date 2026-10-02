package co.datapipelines.application.dashboards

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The R-EP2 walk over the dashboard NAME space (`EndpointAuthorizerTest`'s table, moved): every
 * §7.7 case is a row — the deciding folder is the MOST SPECIFIC ancestor carrying ANY binding,
 * the presenting key must be among its bound keys, and an unbound name authorises nothing.
 * Pure: no database, no clock, no Spring.
 */
class DashboardKeyAuthorizerTest {
    private val workspace = UUID.randomUUID()
    private val otherWorkspace = UUID.randomUUID()
    private val authorizer = DashboardKeyAuthorizer()
    private val keyA = "dpk_AAAAAAAAAAAA"
    private val keyB = "dpk_BBBBBBBBBBBB"

    private fun binding(
        prefix: String,
        key: String,
        ws: UUID = workspace,
    ) = DashboardKeyBinding(prefix, key, ws, UUID.randomUUID(), Instant.now())

    @Test
    fun `a key bound at a folder serves every dashboard beneath it`() {
        val decision = authorizer.authorize("finance/dashboards/monthly", workspace, keyA, listOf(binding("finance/dashboards", keyA)))
        decision shouldBe DashboardKeyAuthorizer.Decision.Allowed
    }

    @Test
    fun `the walk reaches the root - a root binding serves the whole tree`() {
        val decision = authorizer.authorize("ops/anything/deep", workspace, keyA, listOf(binding(DashboardKeyAuthorizer.ROOT, keyA)))
        decision shouldBe DashboardKeyAuthorizer.Decision.Allowed
    }

    @Test
    fun `the deciding folder is the MOST SPECIFIC ancestor carrying any binding - not the nearest that binds THIS key`() {
        // A bound at finance/dashboards, B at finance/dashboards/private: for a dashboard under
        // private/, the FIRST node with any binding is the deeper one, and it excludes A.
        val bindings =
            listOf(
                binding("finance/dashboards", keyA),
                binding("finance/dashboards/private", keyB),
            )
        authorizer.authorize("finance/dashboards/private/board", workspace, keyA, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Refused(DashboardKeyAuthorizer.Reason.NOT_BOUND_AT_DECIDING_FOLDER)
        authorizer.authorize("finance/dashboards/private/board", workspace, keyB, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Allowed
        // Above the boundary the shallow binding decides and A is served.
        authorizer.authorize("finance/dashboards/public/board", workspace, keyA, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Allowed
        authorizer.authorize("finance/dashboards/public/board", workspace, keyB, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Refused(DashboardKeyAuthorizer.Reason.NOT_BOUND_AT_DECIDING_FOLDER)
    }

    @Test
    fun `a foreign binding at a nearer folder neither decides nor shadows - the walk continues past it (#191)`() {
        // The other workspace's key is bound at the SAME folder A is; for A's workspace the
        // walk skips the foreign row and keeps the ancestor that carries A's binding.
        val bindings =
            listOf(
                binding("finance/dashboards", "dpk_FOREIGN", otherWorkspace),
                binding("finance", keyA),
            )
        authorizer.authorize("finance/dashboards/monthly", workspace, keyA, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Allowed
    }

    @Test
    fun `an unbound name authorises nothing - the failure is UNBOUND, and the answer is never a fall-through`() {
        authorizer.authorize("finance/dashboards/monthly", workspace, keyA, emptyList()) shouldBe
            DashboardKeyAuthorizer.Decision.Refused(DashboardKeyAuthorizer.Reason.UNBOUND)
        // Bindings exist in the workspace — just not on any ancestor of THIS name.
        authorizer.authorize("ops/other/board", workspace, keyA, listOf(binding("finance/dashboards", keyA))) shouldBe
            DashboardKeyAuthorizer.Decision.Refused(DashboardKeyAuthorizer.Reason.UNBOUND)
        // No credential at all (a wiring defect) is the unbound case, never an admission.
        authorizer.authorize("finance/dashboards/monthly", workspace, null, listOf(binding("finance/dashboards", keyA))) shouldBe
            DashboardKeyAuthorizer.Decision.Refused(DashboardKeyAuthorizer.Reason.NOT_BOUND_AT_DECIDING_FOLDER)
    }

    @Test
    fun `a key bound among several at the deciding folder is served`() {
        val bindings =
            listOf(
                binding("finance/dashboards", keyA),
                binding("finance/dashboards", keyB),
            )
        authorizer.authorize("finance/dashboards/monthly", workspace, keyB, bindings) shouldBe
            DashboardKeyAuthorizer.Decision.Allowed
    }

    @Test
    fun `ancestors walk the name's folders most-specific first and end at the root`() {
        DashboardKeyAuthorizer.ancestors("finance/dashboards/monthly") shouldBe
            listOf("finance/dashboards/monthly", "finance/dashboards", "finance", "/")
        DashboardKeyAuthorizer.ancestors("/") shouldBe listOf("/")
    }
}
