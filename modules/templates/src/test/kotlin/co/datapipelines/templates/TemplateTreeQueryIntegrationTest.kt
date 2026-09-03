package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The tree's prefix queries against the shipped schema — the guard that the templates tree
 * **cannot degenerate into a flat listing** (template-hierarchy-design §9.1/§9.2, round 047's
 * exit gate 3).
 *
 * ## Why a 3 × 200 fixture and not three rows
 *
 * A three-row fixture cannot tell a prefix query from a full listing: with six templates
 * everything looks like "the right answer" because every answer is small. The whole class of
 * defect this suite exists for is a level that quietly returns the WHOLE workspace and lets
 * the browser sort it out — which is correct-looking on a demo and catastrophic on a real
 * library. So the fixture is deliberately larger than any single level: 3 folders × 200
 * leaves = **600** templates plus 2 flat legacy names, and every assertion below is about a
 * count that would change if a query stopped being a prefix query.
 *
 * ## Falsification (run, not asserted — see the round's handback)
 *
 * Replace [TemplateRepository.listChildTemplates]'s prefix predicate with the unscoped
 * `list(...)` and this suite goes red on `only that folder's direct children come back`
 * (600 names where 200 are expected) and on `no level request returns the whole list`. A
 * guard that cannot go red on the change it guards is not a guard.
 *
 * Schema and container discipline mirror [TemplateRepositoryIntegrationTest]: the shared
 * [SharedPostgres] container arrives already migrated (Flyway lives in `app` alone,
 * module-structure §3.1 rule 2), so this class pays no container start and no migration
 * run — its time is the fixture and the reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateTreeQueryIntegrationTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: TemplateRepository
    private lateinit var actor: UUID

    /** The V4-seeded `default` workspace — a pinned literal, not a guess. */
    private val workspaceId: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    /**
     * The fixture is built ONCE for the class: 602 rows through the real write path is the
     * expensive part, and every test here is a pure read.
     *
     * The template is bound to a small **pooled** DataSource for this class only: 600+
     * `create()` calls over the module's default unpooled source pay a fresh physical
     * connection per statement, which is ~8 s of pure handshake on an otherwise idle box
     * (measured, round 060) — the reads this suite exists for are unaffected by pooling,
     * and the concurrency suites that NEED per-call connections use their own unpooled
     * sources. The pool is closed in [AfterAll].
     */
    @BeforeAll
    fun seed() {
        val pool = SharedPostgres.dataSource()
        val config =
            HikariConfig().apply {
                jdbcUrl = pool.url
                username = pool.username
                password = pool.password
                maximumPoolSize = 4
            }
        dataSource = HikariDataSource(config)
        jdbc = NamedParameterJdbcTemplate(dataSource)
        repository = TemplateRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE templates, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        actor =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('owner@example.com', 'Owner', 'google', 'sub-1') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )

        FOLDERS.forEach { folder ->
            (0 until LEAVES_PER_FOLDER).forEach { i -> create("$folder/${leafName(i)}") }
        }
        // §4.5 forbids renaming, so today's flat names must keep working exactly as they are
        // — they sit at the tree ROOT and are never reorganised into folders.
        create("legacy_flat.sql")
        create("another_legacy")
        // A folder whose name carries a LIKE metacharacter, beside one that a naive
        // (unescaped) pattern would swallow: `a_b/%` matches `axb/…` unless `_` is escaped.
        create("a_b/only_child")
        create("axb/decoy")
        // §5.3: an html template carries no dialect. The type filter must see it.
        create("acme/finance/report.html", type = TemplateType.HTML)
    }

    /** Releases the fixture pool; the shared container outlives this class. */
    @AfterAll
    fun closePool() {
        dataSource.close()
    }

    @Test
    fun `the root level is folders plus the FLAT legacy names, never the whole workspace`() {
        val folders = repository.listChildFolders(workspaceId).map { it.segment }
        val leaves = repository.listChildTemplates(workspaceId, limit = TemplateRepository.MAX_PAGE_LIMIT).map { it.id }

        folders shouldContainExactly listOf("a_b", "acme", "axb", "f1", "f2", "f3")
        // Single-segment names are valid paths that sit at the root (§4.1) — and only those.
        leaves shouldContainExactly listOf("another_legacy", "legacy_flat.sql")
        withClue("the root level must not be the flat listing in disguise") {
            leaves.size shouldBeLessThan TOTAL_TEMPLATES
        }
    }

    @Test
    fun `expanding one folder returns exactly that folder's direct children`() {
        val children = repository.listChildTemplates(workspaceId, "f1", limit = TemplateRepository.MAX_PAGE_LIMIT)

        children.map { it.id } shouldContainExactly (0 until LEAVES_PER_FOLDER).map { "f1/${leafName(it)}" }
        withClue("no name from a sibling folder may appear in f1's level") {
            children.none { it.id.startsWith("f2/") || it.id.startsWith("f3/") } shouldBe true
        }
        repository.countChildTemplates(workspaceId, "f1") shouldBe LEAVES_PER_FOLDER
    }

    @Test
    fun `no level request returns the whole list`() {
        val levels =
            listOf(null, "f1", "f2", "f3", "acme", "acme/finance").associateWith { prefix ->
                repository.listChildTemplates(workspaceId, prefix, limit = TemplateRepository.MAX_PAGE_LIMIT).map { it.id }
            }

        withClue("level sizes: ${levels.mapValues { it.value.size }} of $TOTAL_TEMPLATES in the workspace") {
            levels.values.forEach { it.size shouldBeLessThan TOTAL_TEMPLATES }
            levels.values.map { it.size }.max() shouldBe LEAVES_PER_FOLDER
        }
        // The size check alone is too weak to falsify: a full listing capped at MAX_PAGE_LIMIT
        // is ALSO smaller than the workspace. Disjointness is the property that actually
        // distinguishes a prefix query from a truncated flat list — two levels of a tree can
        // never share a leaf, and two pages of one listing always do.
        val pairs = levels.entries.toList()
        pairs.indices.forEach { i ->
            (i + 1 until pairs.size).forEach { j ->
                val (a, b) = pairs[i] to pairs[j]
                withClue("levels '${a.key}' and '${b.key}' share leaves: ${a.value.intersect(b.value.toSet())}") {
                    a.value.intersect(b.value.toSet()).shouldBeEmpty()
                }
            }
        }
    }

    @Test
    fun `a folder's count is its subtree, and a folder with nothing under it does not exist`() {
        val folders = repository.listChildFolders(workspaceId).associateBy { it.segment }

        folders.getValue("f1").templateCount shouldBe LEAVES_PER_FOLDER
        folders.getValue("f1").path shouldBe "f1"
        // `acme` holds one html template, two levels down.
        folders.getValue("acme").templateCount shouldBe 1
        repository.listChildFolders(workspaceId, "acme").map { it.path } shouldContainExactly listOf("acme/finance")
        // Nothing lives under a leaf, so no folder is derived from one — an empty folder is
        // unrepresentable rather than merely unrendered (§3.1).
        repository.listChildFolders(workspaceId, "f1/${leafName(0)}").shouldBeEmpty()
        repository.listChildTemplates(workspaceId, "f1/${leafName(0)}").shouldBeEmpty()
    }

    @Test
    fun `a prefix's LIKE metacharacters are escaped, so a_b does not swallow axb`() {
        repository.listChildTemplates(workspaceId, "a_b").map { it.id } shouldContainExactly listOf("a_b/only_child")
        repository.listChildFolders(workspaceId, "a_b").shouldBeEmpty()
    }

    @Test
    fun `the type filter narrows a level, and a folder whose whole subtree is filtered out disappears`() {
        // `acme` exists only because of one html template.
        repository.listChildFolders(workspaceId, type = TemplateType.HTML).map { it.segment } shouldContainExactly
            listOf("acme")
        repository.listChildFolders(workspaceId, type = TemplateType.SQL).map { it.segment } shouldContainExactly
            listOf("a_b", "axb", "f1", "f2", "f3")
        repository.listChildTemplates(workspaceId, "acme/finance", type = TemplateType.SQL).shouldBeEmpty()
        repository.listChildTemplates(workspaceId, "acme/finance", type = TemplateType.HTML).map { it.id } shouldContainExactly
            listOf("acme/finance/report.html")
    }

    @Test
    fun `a level pages its leaves, and the count is the truthful total under the same predicate`() {
        val firstPage = repository.listChildTemplates(workspaceId, "f2", offset = 0, limit = 25)
        val secondPage = repository.listChildTemplates(workspaceId, "f2", offset = 25, limit = 25)

        firstPage.size shouldBe 25
        firstPage.map { it.id } shouldContainExactly (0 until 25).map { "f2/${leafName(it)}" }
        secondPage.map { it.id } shouldContainExactly (25 until 50).map { "f2/${leafName(it)}" }
        repository.countChildTemplates(workspaceId, "f2") shouldBe LEAVES_PER_FOLDER
    }

    @Test
    fun `a soft-deleted template leaves the tree, and its folder with it`() {
        repository.create(workspaceId, draft("gone/only_one"), actor)
        repository.listChildFolders(workspaceId).map { it.segment } shouldContainExactly
            listOf("a_b", "acme", "axb", "f1", "f2", "f3", "gone")

        repository.softDelete(workspaceId, "gone/only_one")

        repository.listChildFolders(workspaceId).map { it.segment } shouldContainExactly
            listOf("a_b", "acme", "axb", "f1", "f2", "f3")
        repository.listChildTemplates(workspaceId, "gone").shouldBeEmpty()
    }

    private fun create(
        name: String,
        type: TemplateType = TemplateType.SQL,
    ) = repository.create(workspaceId, draft(name, type), actor)

    private fun draft(
        name: String,
        type: TemplateType = TemplateType.SQL,
    ) = TemplateDraft(
        id = name,
        type = type,
        dialect = if (type == TemplateType.SQL) Dialect.POSTGRES else null,
        displayName = name,
        description = "Fixture.",
        body = if (type == TemplateType.SQL) "SELECT 1" else "<p>hi</p>",
    )

    private companion object {
        val FOLDERS = listOf("f1", "f2", "f3")
        const val LEAVES_PER_FOLDER = 200

        /** 3 × 200 leaves + 2 flat legacy names + `a_b`, `axb` and the html leaf. */
        const val TOTAL_TEMPLATES = 3 * LEAVES_PER_FOLDER + 5

        /** Zero-padded so `name`-ordering and numeric ordering agree and the assertions can be literal. */
        fun leafName(i: Int): String = "leaf_%03d".format(i)
    }
}
