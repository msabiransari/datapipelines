package co.datapipelines.templates

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [RepositoryTemplateRegistry]'s caching contract.
 *
 * Two properties carry real consequences and neither is visible from a passing render:
 *  - **Positive lookups are cached** — sound only because a version is immutable
 *    (templates.md §5.1). A cache that could serve a stale body would break the promise that
 *    `{id, version}` renders the same SQL forever.
 *  - **Absence is never cached** — a null can become a real version the moment the row is
 *    written, so caching it would hide a just-saved template from the next render.
 */
class RepositoryTemplateRegistryTest {
    private val repository = mockk<TemplateRepository>()

    @Test
    fun `a resolved version is read once and served from cache thereafter`() {
        every { repository.lookupVersion("lib.sql", 1) } returns TemplateFixtures.version("lib.sql")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10)

        repeat(3) { registry.lookup("lib.sql", 1)?.key shouldBe "lib.sql@1" }

        verify(exactly = 1) { repository.lookupVersion("lib.sql", 1) }
    }

    @Test
    fun `absence is not cached - a later write is visible`() {
        every { repository.lookupVersion("late.sql", 1) } returns null andThen TemplateFixtures.version("late.sql")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10)

        registry.lookup("late.sql", 1).shouldBeNull()

        registry.lookup("late.sql", 1)?.key shouldBe "late.sql@1"
        verify(exactly = 2) { repository.lookupVersion("late.sql", 1) }
    }

    @Test
    fun `the cache is bounded - the least recently used entry is evicted`() {
        (1..3).forEach { n ->
            every { repository.lookupVersion("lib$n.sql", 1) } returns TemplateFixtures.version("lib$n.sql")
        }
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 2)

        registry.lookup("lib1.sql", 1)
        registry.lookup("lib2.sql", 1)
        registry.lookup("lib1.sql", 1) // lib1 is now the most recently used; lib2 is the eldest
        registry.lookup("lib3.sql", 1) // evicts lib2
        registry.lookup("lib2.sql", 1) // must go back to the repository

        verify(exactly = 2) { repository.lookupVersion("lib2.sql", 1) }
        verify(exactly = 1) { repository.lookupVersion("lib1.sql", 1) }
    }

    @Test
    fun `each version of one id is its own cache entry`() {
        every { repository.lookupVersion("lib.sql", 1) } returns TemplateFixtures.version("lib.sql", version = 1, body = "v1")
        every { repository.lookupVersion("lib.sql", 2) } returns TemplateFixtures.version("lib.sql", version = 2, body = "v2")
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10)

        registry.lookup("lib.sql", 1)?.body shouldBe "v1"
        registry.lookup("lib.sql", 2)?.body shouldBe "v2"
    }

    @Test
    fun `existsId is never cached - it is the question a new version changes`() {
        every { repository.existsId("lib.sql") } returns false andThen true
        val registry = RepositoryTemplateRegistry(repository, cacheSize = 10)

        registry.existsId("lib.sql") shouldBe false
        registry.existsId("lib.sql") shouldBe true
    }
}
