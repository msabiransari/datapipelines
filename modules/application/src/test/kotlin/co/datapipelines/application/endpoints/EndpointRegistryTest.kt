package co.datapipelines.application.endpoints

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The per-instance registry cache (§4.1).
 *
 * The cache exists so a request under `/api/x` can be resolved without a metadata-DB round trip —
 * including the `404`s, which are the cheapest thing a hostile caller can ask for. What these
 * tests pin is the pair of behaviours that makes it safe: it really does cache (so a write is not
 * visible until invalidation), and invalidation really does fan out (so peers are not left
 * serving a stale registry).
 */
class EndpointRegistryTest {
    private val repository = mockk<PublishedEndpointRepository>()

    @Test
    fun `the first match loads the registry, and later ones do not re-read it`() {
        every { repository.findAllEnabled() } returns listOf(endpoint("/nyc/revenue"))
        val registry = EndpointRegistry(repository)

        repeat(3) { registry.matcher().match("/nyc/revenue").shouldNotBeNull() }

        verify(exactly = 1) { repository.findAllEnabled() }
    }

    @Test
    fun `a write is invisible until the snapshot is dropped`() {
        // This IS the staleness the Redis channel exists to end on other instances. Asserting it
        // here keeps the cache honest: if `matcher()` re-read every time, the channel would be
        // pointless and this test would fail.
        every { repository.findAllEnabled() } returns emptyList()
        val registry = EndpointRegistry(repository)
        registry.matcher().match("/nyc") shouldBe null

        every { repository.findAllEnabled() } returns listOf(endpoint("/nyc"))

        assertAll(
            { registry.matcher().match("/nyc") shouldBe null },
            {
                registry
                    .let {
                        it.invalidateLocally()
                        it.matcher()
                    }.match("/nyc")
                    .shouldNotBeNull()
            },
        )
    }

    @Test
    fun `invalidate drops the local snapshot AND publishes to peers`() {
        every { repository.findAllEnabled() } returns emptyList()
        var published = 0
        val registry = EndpointRegistry(repository, { published++ })
        registry.matcher()

        registry.invalidate()
        registry.matcher()

        assertAll(
            { published shouldBe 1 },
            { verify(exactly = 2) { repository.findAllEnabled() } },
        )
    }

    @Test
    fun `invalidateLocally does NOT publish — it is what a peer's subscriber calls`() {
        // Without this asymmetry a received invalidation would be re-broadcast, and two instances
        // would keep handing the message back and forth.
        every { repository.findAllEnabled() } returns emptyList()
        var published = 0
        val registry = EndpointRegistry(repository, { published++ })

        registry.invalidateLocally()

        published shouldBe 0
    }

    @Test
    fun `a row whose stored pattern cannot be compiled is dropped, and the rest still serve`() {
        val good = endpoint("/nyc/revenue")
        every { repository.findAllEnabled() } returns listOf(good, good.copy(pathPattern = "/nyc/{"))

        val matcher = EndpointRegistry(repository).reload()

        assertAll(
            { matcher.size shouldBe 1 },
            { matcher.match("/nyc/revenue").shouldNotBeNull() },
        )
    }

    private fun endpoint(pattern: String) =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = UUID.fromString("defa0000-0000-0000-0000-000000000001"),
            pathPattern = pattern,
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
}
