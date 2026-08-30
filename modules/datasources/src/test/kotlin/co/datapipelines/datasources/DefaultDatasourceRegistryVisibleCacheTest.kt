package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * `getVisible` serves from the §6.3 metadata cache (025 C4, the 022 review's perf note):
 * the control plane calls it on every REST GET and every save-time validation, and each
 * call was a direct `findVisibleByName` — the one registry read with no cache. The
 * visible cache is keyed (workspaceId, name): two workspaces asking for one global
 * datasource share the row's cache entry only through their own keys, misses are never
 * cached (the §6.3 rule), and expiry bounds staleness exactly as [DatasourceMetadataCache]
 * bounds `get` (DS-SEC-15: local invalidation is the immediacy optimization, the TTL is
 * the cross-instance bound).
 */
class DefaultDatasourceRegistryVisibleCacheTest {
    private val repository = mockk<DatasourceRepository>()
    private val encryptor = mockk<CredentialEncryptor>()
    private val ticker = Ticker()
    private val registry =
        DefaultDatasourceRegistry(
            repository = repository,
            encryptor = encryptor,
            cache = DatasourceMetadataCache(ttl = 60.seconds, ticker = ticker::nanos),
        )

    private val wsA = UUID.randomUUID()
    private val wsB = UUID.randomUUID()

    @Test
    fun `the second read of the same name and workspace comes from the cache`() {
        every { repository.findVisibleByName("sales", wsA) } returns row("sales")

        registry.getVisible("sales", wsA)
        registry.getVisible("sales", wsA)

        verify(exactly = 1) { repository.findVisibleByName("sales", wsA) }
    }

    @Test
    fun `two workspaces have distinct cache keys - no cross-workspace bleed`() {
        every { repository.findVisibleByName("sales", wsA) } returns row("sales")
        every { repository.findVisibleByName("sales", wsB) } returns row("sales")

        registry.getVisible("sales", wsA)
        registry.getVisible("sales", wsB)

        verify(exactly = 1) { repository.findVisibleByName("sales", wsA) }
        verify(exactly = 1) { repository.findVisibleByName("sales", wsB) }
    }

    @Test
    fun `an expired entry re-reads - the TTL bounds staleness`() {
        every { repository.findVisibleByName("sales", wsA) } returns row("sales")

        registry.getVisible("sales", wsA)
        ticker.advanceSeconds(61)
        registry.getVisible("sales", wsA)

        verify(exactly = 2) { repository.findVisibleByName("sales", wsA) }
    }

    @Test
    fun `a miss is never cached - the created-elsewhere row appears on the next read`() {
        every { repository.findVisibleByName("late", wsA) } returns null andThen row("late")

        registry.getVisible("late", wsA) shouldBe null
        // The miss left nothing behind: the row the second read finds is SERVED, not shadowed.
        registry.getVisible("late", wsA)?.name shouldBe "late"
        verify(exactly = 2) { repository.findVisibleByName("late", wsA) }
    }

    private fun row(name: String) =
        DatasourceRow(
            name = name,
            displayName = name,
            description = null,
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "app",
            passwordEncrypted = ByteArray(0),
            properties = DatasourceProperties(),
            queryTimeoutSeconds = null,
            introspectionIncludeSchemas = emptyList(),
            isReadonly = false,
            workspaceId = null,
            workspaceName = null,
            isDeleted = false,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            createdBy = UUID.randomUUID(),
        )

    /** A monotonic fake clock — advances only when the test says so (the cache's own discipline). */
    private class Ticker {
        var nanos: Long = 0

        fun advanceSeconds(seconds: Long) {
            nanos += seconds * 1_000_000_000
        }
    }
}
