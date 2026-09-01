package co.datapipelines.datasources

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.nio.file.Path
import java.util.UUID

/**
 * The first-boot registration race (ARCH-AUDIT M5): two instances against one fresh database
 * both pass `existsIncludingDeleted`, one wins the insert, and the loser must behave as if the
 * row had already been there — skipped, not a startup failure. Deterministic here: the
 * repository reports "absent" while `save` throws, the exact interleaving the race produces.
 */
class BootstrapDatasourceRegistrarRaceTest {
    @Test
    fun `losing the insert race counts the entry as skipped`() {
        val datasource = Fixtures.h2(name = "sample-trips")
        val repository = mockk<DatasourceRepository>()
        val registry = mockk<DatasourceRegistry>()
        val reader = mockk<BootstrapDatasourceFileReader>()
        every { reader.read(any()) } returns listOf(datasource)
        every { repository.existsIncludingDeleted(datasource.name) } returns false
        every { registry.save(datasource, any()) } throws DuplicateKeyException("datasources_pkey")

        val summary =
            BootstrapDatasourceRegistrar(registry, repository, reader)
                .register(Path.of("bootstrap.yml"), UUID.randomUUID())

        summary.registered shouldBe emptyList()
        summary.skipped shouldContainExactly listOf(datasource.name)
    }
}
