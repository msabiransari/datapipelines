package co.datapipelines.templates

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The per-workspace engine/registry factory (T24): one binding per workspace, shared
 * between `engineFor` and `registryFor`, LRU-bounded with `close()` on eviction — the
 * structural guarantee that no render cache key collides across workspaces.
 */
class WorkspaceTemplateEnginesTest {
    private val repository = mockk<TemplateRepository>(relaxed = true)
    private val engines = WorkspaceTemplateEngines(repository, cacheSize = 10, renderTimeoutMs = 5_000, maxOutputChars = 1_024)

    @Test
    fun `one workspace gets one engine and one registry - the same instances every time`() {
        val workspaceId = UUID.randomUUID()

        engines.engineFor(workspaceId) shouldBe engines.engineFor(workspaceId)
        engines.registryFor(workspaceId) shouldBe engines.registryFor(workspaceId)
    }

    @Test
    fun `two workspaces never share an engine or a registry`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        engines.engineFor(a) shouldNotBe engines.engineFor(b)
        engines.registryFor(a) shouldNotBe engines.registryFor(b)
    }

    @Test
    fun `the registry answers through its OWN workspace id`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val seenBy = mutableListOf<UUID>()
        every { repository.existsId(any(), "tpl") } answers {
            seenBy += firstArg<UUID>()
            true
        }

        engines.registryFor(a).existsId("tpl")
        engines.registryFor(b).existsId("tpl")

        seenBy shouldBe listOf(a, b)
    }

    @Test
    fun `eviction past the ceiling closes the eldest engine and a later request rebuilds`() {
        val bounded = WorkspaceTemplateEngines(repository, cacheSize = 10, renderTimeoutMs = 5_000, maxOutputChars = 1_024)
        val ids = (1..WorkspaceTemplateEngines.MAX_ENGINES + 1).map { UUID.randomUUID() }
        val firstEngine = bounded.engineFor(ids.first())

        ids.forEach { bounded.engineFor(it) }

        bounded.size() shouldBe WorkspaceTemplateEngines.MAX_ENGINES
        // The eldest was evicted and closed; touching it again builds a NEW engine.
        bounded.engineFor(ids.first()) shouldNotBe firstEngine
        bounded.close()
    }

    @Test
    fun `close shuts every live engine down`() {
        val bounded = WorkspaceTemplateEngines(repository, cacheSize = 10, renderTimeoutMs = 5_000, maxOutputChars = 1_024)
        bounded.engineFor(UUID.randomUUID())
        bounded.engineFor(UUID.randomUUID())

        bounded.close()

        bounded.size() shouldBe 0
    }
}
