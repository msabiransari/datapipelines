package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ReapOutcome
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The [DatasourceRegistry] interface's DEFAULT bodies — the contract every in-memory fake
 * inherits without knowing it. Each default is a deliberate choice recorded in the KDoc
 * beside it (a fake stays source-compatible; the production registry overrides), and this
 * test pins those choices so a later "helpful" default cannot re-open a closed hole: the
 * visibility defaults DELEGATE (no workspace filtering — the production override does that),
 * the pool defaults are INERT, and the lake defaults report NOTHING (no pre-flight, no broken
 * tables) rather than guessing. Merged 2026-09-10 beside 107 + 109, which each added defaults.
 */
class DatasourceRegistryDefaultsTest {
    private val registry: DatasourceRegistry = mockk()
    private val ds = Fixtures.h2(name = "h2-test")
    private val workspace = UUID.randomUUID()

    @Test
    fun `the visibility defaults delegate to the unscoped reads`() {
        every { registry.list(any()) } returns listOf(ds)
        every { registry.get("h2-test") } returns ds
        every { registry.listVisible(any(), any()) } answers { callOriginal() }
        every { registry.getVisible(any(), any()) } answers { callOriginal() }
        every { registry.getVisibleLive(any(), any()) } answers { callOriginal() }
        every { registry.dialectOf(any()) } answers { callOriginal() }

        registry.listVisible(Dialect.H2, workspace) shouldBe listOf(ds)
        registry.getVisible("h2-test", workspace) shouldBe ds
        registry.getVisibleLive("h2-test", workspace) shouldBe ds
        registry.dialectOf("h2-test") shouldBe Dialect.H2
        verify { registry.list(Dialect.H2) }
    }

    @Test
    fun `the pool defaults are inert`() {
        every { registry.retirePool(any()) } answers { callOriginal() }
        every { registry.reapRetiredPools() } answers { callOriginal() }
        every { registry.reconcilePools() } answers { callOriginal() }

        registry.retirePool("h2-test") shouldBe false
        registry.reapRetiredPools() shouldBe ReapOutcome.NOTHING
        registry.reconcilePools() shouldBe 0
    }

    @Test
    fun `testConnection by datasource delegates by name`() {
        every { registry.testConnection("h2-test") } returns null
        every { registry.testConnection(ds) } answers { callOriginal() }

        registry.testConnection(ds).shouldBeNull()
        verify { registry.testConnection("h2-test") }
    }

    @Test
    fun `the lake defaults report nothing rather than guessing`() {
        every { registry.preflightLakeTable(any(), any()) } answers { callOriginal() }
        every { registry.lakeBrokenTables(any()) } answers { callOriginal() }

        registry
            .preflightLakeTable(
                ds,
                LakeRegisteredTable(namespace = listOf("ns"), name = "t", format = "parquet", location = "s3://b/t/"),
            ).shouldBeNull()
        registry.lakeBrokenTables("h2-test").shouldBeEmpty()
    }
}
