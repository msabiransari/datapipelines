package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [RepositoryTemplateRegistry]'s caching contract.
 *
 * Three properties carry real consequences and none is visible from a passing render:
 *  - **Non-draft lookups are cached** — sound because a RELEASED or DISCARDED version is
 *    immutable (templates.md §5.1). A cache that could serve a stale body would break the
 *    promise that `{id, version}` renders the same SQL forever.
 *  - **A DRAFT is re-read on every lookup** (132) — `templates_update` overwrites the sole
 *    draft IN PLACE (117), same id, same version number, new body. Before 132 the LRU held
 *    the first body read and every later render and execution silently ran it; the 117 E2E
 *    never saw this because it never rendered BEFORE the update.
 *  - **Absence is never cached** — a null can become a real version the moment the row is
 *    written, so caching it would hide a just-saved template from the next render.
 */
class RepositoryTemplateRegistryTest {
    private val repository = mockk<TemplateRepository>()
    private val workspaceId = java.util.UUID.randomUUID()

    @Test
    fun `a resolved version is read once and served from cache thereafter`() {
        every { repository.lookupVersion(any(), "test/lib.sql", 1) } returns TemplateFixtures.version("test/lib.sql")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        repeat(3) { registry.lookup("test/lib.sql", 1)?.key shouldBe "test/lib.sql@1" }

        verify(exactly = 1) { repository.lookupVersion(any(), "test/lib.sql", 1) }
    }

    @Test
    fun `absence is not cached - a later write is visible`() {
        every { repository.lookupVersion(any(), "test/late.sql", 1) } returns null andThen TemplateFixtures.version("test/late.sql")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.lookup("test/late.sql", 1).shouldBeNull()

        registry.lookup("test/late.sql", 1)?.key shouldBe "test/late.sql@1"
        verify(exactly = 2) { repository.lookupVersion(any(), "test/late.sql", 1) }
    }

    @Test
    fun `the cache is bounded - the least recently used entry is evicted`() {
        (1..3).forEach { n ->
            every { repository.lookupVersion(any(), "test/lib$n.sql", 1) } returns TemplateFixtures.version("test/lib$n.sql")
        }
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 2, workspaceId = workspaceId)

        registry.lookup("test/lib1.sql", 1)
        registry.lookup("test/lib2.sql", 1)
        registry.lookup("test/lib1.sql", 1) // lib1 is now the most recently used; lib2 is the eldest
        registry.lookup("test/lib3.sql", 1) // evicts lib2
        registry.lookup("test/lib2.sql", 1) // must go back to the repository

        verify(exactly = 2) { repository.lookupVersion(any(), "test/lib2.sql", 1) }
        verify(exactly = 1) { repository.lookupVersion(any(), "test/lib1.sql", 1) }
    }

    @Test
    fun `each version of one id is its own cache entry`() {
        every { repository.lookupVersion(any(), "test/lib.sql", 1) } returns
            TemplateFixtures.version("test/lib.sql", version = 1, body = "v1")
        every { repository.lookupVersion(any(), "test/lib.sql", 2) } returns
            TemplateFixtures.version("test/lib.sql", version = 2, body = "v2")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.lookup("test/lib.sql", 1)?.body shouldBe "v1"
        registry.lookup("test/lib.sql", 2)?.body shouldBe "v2"
    }

    @Test
    fun `a DRAFT is re-read on every lookup - it is the one key a write overwrites in place`() {
        // The acceptance run of 2026-09-14: create (v1 DRAFT), render, update the same draft,
        // render again — and the second render returned the FIRST body.
        var stored = TemplateFixtures.version("test/wip.sql", body = "SELECT 1", status = PipelineVersionStatus.DRAFT)
        every { repository.lookupVersion(any(), "test/wip.sql", 1) } answers { stored }
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.lookup("test/wip.sql", 1)?.body shouldBe "SELECT 1"
        stored = TemplateFixtures.version("test/wip.sql", body = "SELECT 2", status = PipelineVersionStatus.DRAFT)

        withClue("the overwritten draft's new body, not the one the first lookup read") {
            registry.lookup("test/wip.sql", 1)?.body shouldBe "SELECT 2"
        }
        verify(exactly = 2) { repository.lookupVersion(any(), "test/wip.sql", 1) }
    }

    @Test
    fun `a purged draft is not served from cache - its absence is seen on the next lookup`() {
        var stored: TemplateVersion? = TemplateFixtures.version("test/gone.sql", status = PipelineVersionStatus.DRAFT)
        every { repository.lookupVersion(any(), "test/gone.sql", 1) } answers { stored }
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.lookup("test/gone.sql", 1).shouldNotBeNull()
        stored = null

        registry.lookup("test/gone.sql", 1).shouldBeNull()
    }

    @Test
    fun `every non-draft status is cached - RELEASED and DISCARDED rows are immutable`() {
        listOf(PipelineVersionStatus.RELEASED, PipelineVersionStatus.DISCARDED).forEach { status ->
            val id = "test/${status.name.lowercase()}.sql"
            every { repository.lookupVersion(any(), id, 1) } returns TemplateFixtures.version(id, status = status)
            val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

            repeat(3) { registry.lookup(id, 1)?.status shouldBe status }

            withClue("$status must be read once and cached") { verify(exactly = 1) { repository.lookupVersion(any(), id, 1) } }
        }
    }

    @Test
    fun `a draft that is released is cached from the read that saw it RELEASED`() {
        var stored = TemplateFixtures.version("test/promoted.sql", status = PipelineVersionStatus.DRAFT)
        every { repository.lookupVersion(any(), "test/promoted.sql", 1) } answers { stored }
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.lookup("test/promoted.sql", 1)?.status shouldBe PipelineVersionStatus.DRAFT
        stored = TemplateFixtures.version("test/promoted.sql", status = PipelineVersionStatus.RELEASED)
        repeat(3) { registry.lookup("test/promoted.sql", 1)?.status shouldBe PipelineVersionStatus.RELEASED }

        // One read as a draft, one read that saw the release, none after — the release is
        // the moment the key becomes immutable and therefore cacheable.
        verify(exactly = 2) { repository.lookupVersion(any(), "test/promoted.sql", 1) }
    }

    @Test
    fun `existsId is never cached - it is the question a new version changes`() {
        every { repository.existsId(any(), "test/lib.sql") } returns false andThen true
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10, workspaceId = workspaceId)

        registry.existsId("test/lib.sql") shouldBe false
        registry.existsId("test/lib.sql") shouldBe true
    }
}
