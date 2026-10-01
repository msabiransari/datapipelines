package co.datapipelines.executor

import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * [RedisRefreshStartMarkers] against a real Redis (#356): the key is the documented
 * `dp:refresh-start:{workspace}:{refresh}`, it expires by itself, the per-principal bound refuses the NEWEST start
 * (an older start never loses its abort authorization), and `clear` — the start's own exit — frees the bound. The
 * honouring itself (a matching marker recording the abort intent, the engine ending the refresh ABORTED) is
 * `DashboardRuntimeAbortTest`'s and `RefreshEngineTest`'s; the E2E proves it over the real HTTP surface.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRefreshStartMarkersIntegrationTest {
    private val redis = RedisSupport.template()
    private val markers = RedisRefreshStartMarkers(redis)

    private val workspace = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val refresh = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        RedisSupport.flush(redis)
    }

    private fun marker(
        refreshId: UUID = refresh,
        userId: UUID = user,
    ) = RefreshStartMarker(workspace, refreshId, userId, UUID.randomUUID(), UUID.randomUUID())

    @Test
    fun `a registration is readable for that refresh only, with the given expiry and the documented fields`() {
        val instance = UUID.randomUUID()
        val dashboard = UUID.randomUUID()
        val registered =
            markers
                .register(RefreshStartMarker(workspace, refresh, user, instance, dashboard), ttlSeconds = 60, perPrincipalLimit = 4)
        registered shouldBe StartMarkerRegistration.REGISTERED

        val found = markers.find(workspace, refresh)
        found.shouldBeTypeOf<RefreshStartMarker>()
        found.principalUserId shouldBe user
        found.instanceId shouldBe instance
        found.dashboardId shouldBe dashboard
        markers.find(workspace, UUID.randomUUID()) shouldBe null
        markers.find(UUID.randomUUID(), refresh) shouldBe null // another workspace's start is absent, not forbidden
        redis.getExpire("dp:refresh-start:$workspace:$refresh") shouldBeInRange 1L..60L
    }

    @Test
    fun `clear removes the marker and the bound entry, and is idempotent`() {
        markers.register(marker(), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe StartMarkerRegistration.REGISTERED

        markers.clear(workspace, user, refresh)
        markers.clear(workspace, user, refresh)

        markers.find(workspace, refresh) shouldBe null
        // The bound slot is free again: another start registers.
        markers.register(marker(), ttlSeconds = 60, perPrincipalLimit = 1) shouldBe StartMarkerRegistration.REGISTERED
    }

    @Test
    fun `the per-principal bound refuses the newest start and keeps the older ones`() {
        (1L..3L).forEach { n ->
            val id = UUID.nameUUIDFromBytes(n.toString().toByteArray())
            markers.register(marker(refreshId = id), ttlSeconds = 60, perPrincipalLimit = 3) shouldBe StartMarkerRegistration.REGISTERED
        }

        // The fourth start of the same principal is refused; every earlier marker still authorises its own abort.
        val refused = UUID.randomUUID()
        markers.register(marker(refreshId = refused), ttlSeconds = 60, perPrincipalLimit = 3) shouldBe StartMarkerRegistration.AT_BOUND
        markers.find(workspace, refused) shouldBe null

        // Another principal's bound is their own.
        markers.register(marker(refreshId = refused, userId = UUID.randomUUID()), ttlSeconds = 60, perPrincipalLimit = 3) shouldBe
            StartMarkerRegistration.REGISTERED
    }

    @Test
    fun `a second registration of an in-flight id is refused and the first start keeps its marker and its slot`() {
        val instance = UUID.randomUUID()
        markers.register(marker().copy(instanceId = instance), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe
            StartMarkerRegistration.REGISTERED

        // A same-principal replay: refused, the owner's one slot still counted once.
        markers.register(marker(), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe StartMarkerRegistration.ALREADY_IN_FLIGHT
        redis.opsForSet().size("dp:refresh-starts:$workspace:$user") shouldBe 1L

        // Another principal naming the same id: refused, nothing of theirs recorded, the owner unchanged.
        val other = UUID.randomUUID()
        markers.register(marker(userId = other), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe
            StartMarkerRegistration.ALREADY_IN_FLIGHT
        redis.opsForSet().size("dp:refresh-starts:$workspace:$other") shouldBe 0L
        val found = markers.find(workspace, refresh)
        found.shouldBeTypeOf<RefreshStartMarker>()
        found.principalUserId shouldBe user
        found.instanceId shouldBe instance

        // A refused-at-bound start leaves no marker behind for a later replay to collide with.
        (1L..3L).forEach { n ->
            markers.register(marker(refreshId = UUID.nameUUIDFromBytes(n.toString().toByteArray())), ttlSeconds = 60, perPrincipalLimit = 4)
        }
        val atBound = UUID.randomUUID()
        markers.register(marker(refreshId = atBound), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe StartMarkerRegistration.AT_BOUND
        redis.hasKey("dp:refresh-start:$workspace:$atBound") shouldBe false
    }

    @Test
    fun `the marker is not the abort flag - the two key spaces never overlap`() {
        markers.register(marker(), ttlSeconds = 60, perPrincipalLimit = 4) shouldBe StartMarkerRegistration.REGISTERED

        redis.hasKey("dp:refresh-abort:$refresh") shouldBe false
    }
}
