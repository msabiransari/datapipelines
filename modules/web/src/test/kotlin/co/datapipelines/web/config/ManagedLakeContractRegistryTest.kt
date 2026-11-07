package co.datapipelines.web.config

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.UUID

/** The managed append exception must never make an external readonly datasource writable. */
class ManagedLakeContractRegistryTest {
    private val workspace = UUID.randomUUID()
    private val registry = mockk<DatasourceRegistry>()

    private fun resolve(
        enabled: Boolean,
        name: String,
        dialect: Dialect,
    ): Boolean? {
        val datasource = mockk<Datasource>()
        every { datasource.dialect } returns dialect
        every { datasource.isReadonly } returns true
        every { registry.getVisibleLive(name, workspace) } returns datasource
        val environment =
            MockEnvironment()
                .withProperty("datapipelines.lake.enabled", enabled.toString())
                .withProperty("datapipelines.lake.name", "managed")
        return DomainConfiguration()
            .contractDatasourceRegistry(registry, environment)
            .describe(name, workspace)
            ?.managedLake
    }

    @Test
    fun `only enabled named lake is managed`() {
        resolve(true, "managed", Dialect.LAKE) shouldBe true
        resolve(false, "managed", Dialect.LAKE) shouldBe false
        resolve(true, "external", Dialect.LAKE) shouldBe false
        resolve(true, "managed", Dialect.POSTGRES) shouldBe false
    }

    @Test
    fun `invisible datasource remains unknown`() {
        every { registry.getVisibleLive("managed", workspace) } returns null
        val environment =
            MockEnvironment()
                .withProperty("datapipelines.lake.enabled", "true")
                .withProperty("datapipelines.lake.name", "managed")
        DomainConfiguration()
            .contractDatasourceRegistry(registry, environment)
            .describe("managed", workspace) shouldBe null
    }
}
