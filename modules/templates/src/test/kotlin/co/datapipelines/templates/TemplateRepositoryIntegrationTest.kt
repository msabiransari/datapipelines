package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [TemplateRepository] against a real Postgres running the **shipped** schema.
 *
 * Runs against the shipped schema — `app`'s real migrations in version order, applied once
 * per JVM by [SharedPostgres]: module-structure §3.1 rule 2 keeps the Flyway dependency in
 * `app` only, so a domain module never gains a schema-creation tool, yet the test runs the
 * exact DDL the application migrates with (including the D3 `no params_schema` column shape
 * and the V4 surrogate-key re-key).
 *
 * `LargeClass` is suppressed for the same reason `PipelineRepositoryIntegrationTest`
 * suppresses it: the version-lifecycle round made this suite the repository's contract in
 * one place — CRUD, listing, and the draft/release lifecycle read against the SAME shipped
 * schema — and splitting it would scatter one table's invariants across files.
 */
@Suppress("LargeClass")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: TemplateRepository
    private lateinit var actor: UUID

    /** The V4-seeded `default` workspace this suite re-seeds after every truncate — a pinned literal, not a guess. */
    private val workspaceId: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    /**
     * Binds the JDBC template to the module's shared, already-migrated container, over its
     * **pooled** source (round 062): every statement here is a fixture write or an ordinary
     * read, and nothing in this suite asserts anything about connection or transaction
     * identity, so paying a fresh connect + authentication handshake per statement bought
     * nothing. `SharedPostgres.dataSource()` — unpooled — stays for the suites that do.
     */
    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = TemplateRepository(jdbc)
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace the repository pins is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE templates, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        actor = insertUser()
    }

    private fun draft(
        id: String? = "test/fetch_orders.sql",
        body: String = "SELECT 1",
        imports: List<TemplateImport> = emptyList(),
        isLibrary: Boolean = false,
        dialect: Dialect = Dialect.POSTGRES,
        displayName: String = "Fetch Orders",
    ): TemplateDraft =
        TemplateDraft(
            id = id,
            dialect = dialect,
            displayName = displayName,
            description = "Pulls orders.",
            imports = imports,
            body = body,
            isLibrary = isLibrary,
        )

    @Test
    fun `create inserts the template and version 1 together, returning what the database stored`() {
        val stored = repository.createReleased(workspaceId, draft(imports = listOf(TemplateImport("test/lib.sql", 1, "l"))), actor)

        stored.version shouldBe 1
        stored.id shouldBe "test/fetch_orders.sql"
        stored.imports shouldContainExactly listOf(TemplateImport("test/lib.sql", 1, "l"))
        stored.createdBy shouldBe actor
        repository.lookupVersion(workspaceId, "test/fetch_orders.sql", 1).shouldNotBeNull()
    }

    @Test
    fun `the DRAFT create lands version 1 as a draft that no released-only read can see`() {
        // D55: `templates_create`, `POST /templates` and the editor all land a DRAFT; only the
        // import path (promotion, seeders) lands RELEASED. Asserted against the database, and
        // against the reads that are supposed to mean "latest RELEASED".
        val stored = repository.create(workspaceId, draft(), actor, CreateLifecycle.DRAFT)

        stored.version shouldBe 1
        stored.status shouldBe PipelineVersionStatus.DRAFT
        checkNotNull(repository.findDraftDetail(workspaceId, "test/fetch_orders.sql")).version shouldBe 1
        withClue("nothing is released, so the latest-released lookup reports nothing at all") {
            repository.findCurrentVersions(workspaceId, listOf("test/fetch_orders.sql")) shouldBe emptyMap()
            repository.findVersionStatus(workspaceId, "test/fetch_orders.sql", 1) shouldBe PipelineVersionStatus.DRAFT
        }

        val released = repository.releaseDraft(workspaceId, "test/fetch_orders.sql", stored.bodyHash, actor)

        withClue("the first release is an ordinary release: the pointer moves to 1") {
            checkNotNull(released).status shouldBe PipelineVersionStatus.RELEASED
            repository.findCurrentVersions(workspaceId, listOf("test/fetch_orders.sql")) shouldBe
                mapOf("test/fetch_orders.sql" to 1)
        }
    }

    @Test
    fun `an omitted id is auto-generated inside the identifier rule`() {
        val stored = repository.createReleased(workspaceId, draft(id = null), actor)
        isValidTemplateName(stored.id) shouldBe true
    }

    @Test
    fun `update appends a new immutable version and bumps current_version`() {
        repository.createReleased(workspaceId, draft(body = "SELECT 1"), actor)

        val v2 = repository.appendReleasedVersion(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), actor)

        v2.shouldNotBeNull()
        v2.version shouldBe 2
        v2.body shouldBe "SELECT 2"
        repository.findLatest(workspaceId, "test/fetch_orders.sql")?.version shouldBe 2
        // Version 1 is untouched — immutable per version (§5.1).
        repository.findVersion(workspaceId, "test/fetch_orders.sql", 1)?.body shouldBe "SELECT 1"
        repository.listVersions(workspaceId, "test/fetch_orders.sql").map { it.version } shouldContainExactly listOf(2, 1)
    }

    @Test
    fun `update on an unknown id returns null and writes nothing`() {
        repository.appendReleasedVersion(workspaceId, "test/nope.sql", draft(), actor).shouldBeNull()
        existsRows("template_versions") shouldBe 0
    }

    @Test
    fun `lookupVersion round-trips imports and dialect`() {
        repository.createReleased(
            workspaceId,
            draft(imports = listOf(TemplateImport("test/lib.sql", 3, "d")), dialect = Dialect.MYSQL),
            actor,
        )

        val version = repository.lookupVersion(workspaceId, "test/fetch_orders.sql", 1)

        version.shouldNotBeNull()
        version.dialect shouldBe Dialect.MYSQL
        version.imports shouldContainExactly listOf(TemplateImport("test/lib.sql", 3, "d"))
    }

    @Test
    fun `existsId reflects whether any version exists`() {
        repository.existsId(workspaceId, "test/fetch_orders.sql") shouldBe false
        repository.createReleased(workspaceId, draft(), actor)
        repository.existsId(workspaceId, "test/fetch_orders.sql") shouldBe true
    }

    @Test
    fun `findCurrentVersions answers latest released per id, absent for deleted, empty for no ids`() {
        // 040 D5's lookup: current_version IS the latest released version by the lifecycle's
        // invariant, and a draft must not move the answer.
        repository.createReleased(workspaceId, draft(id = "test/a.sql", body = "SELECT 1"), actor)
        val a = repository.findLatest(workspaceId, "test/a.sql")!!
        val aDraft =
            repository.createDraft(workspaceId, "test/a.sql", draft(id = "test/a.sql", body = "SELECT 2"), a.bodyHash, actor)!!
        repository.createReleased(workspaceId, draft(id = "test/b.sql"), actor)
        repository.createReleased(workspaceId, draft(id = "test/c.sql"), actor)
        repository.softDelete(workspaceId, "test/c.sql")
        checkNotNull(aDraft)

        repository.findCurrentVersions(workspaceId, listOf("test/a.sql", "test/b.sql", "test/c.sql", "test/nope.sql")) shouldBe
            mapOf("test/a.sql" to 1, "test/b.sql" to 1)
        repository.findCurrentVersions(workspaceId, emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `a soft-deleted template disappears from findLatest but its versions still resolve`() {
        repository.createReleased(workspaceId, draft(), actor)

        repository.softDelete(workspaceId, "test/fetch_orders.sql") shouldBe true

        // §5.1: pipelines referencing a deleted template's version continue to work, so the
        // registry lookup must still resolve it, but the current-version projection is gone.
        repository.findLatest(workspaceId, "test/fetch_orders.sql").shouldBeNull()
        repository.lookupVersion(workspaceId, "test/fetch_orders.sql", 1).shouldNotBeNull()
        repository.findVersion(workspaceId, "test/fetch_orders.sql", 1).shouldNotBeNull()
        repository.softDelete(workspaceId, "test/fetch_orders.sql") shouldBe false
    }

    @Test
    fun `update on a soft-deleted template writes nothing and adds no version`() {
        // TPL-TEST-9. §5.1 keeps a deleted template's existing versions resolvable, but a deleted
        // template must not gain new ones — the UPDATE's `is_deleted = FALSE` predicate is the
        // only thing enforcing that, and a CTE whose first leg matches no row must not leave the
        // INSERT leg to run on its own.
        repository.createReleased(workspaceId, draft(body = "SELECT 1"), actor)
        repository.softDelete(workspaceId, "test/fetch_orders.sql")

        repository.appendReleasedVersion(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), actor).shouldBeNull()

        existsRows("template_versions") shouldBe 1
        repository.lookupVersion(workspaceId, "test/fetch_orders.sql", 2).shouldBeNull()
        repository.lookupVersion(workspaceId, "test/fetch_orders.sql", 1)?.body shouldBe "SELECT 1"
    }

    @Test
    fun `created_by is the author of THAT version, not of the template`() {
        // TPL-API-2. `templates.created_by` is whoever created the template; a version has its own
        // author. Reading the former made every endpoint that returns a Template credit the
        // original creator for someone else's edit — and disagree with `listVersions`, which reads
        // the version row, about the very same version.
        val updater = insertUser(email = "editor@example.com", subject = "sub-2")
        repository.createReleased(workspaceId, draft(body = "SELECT 1"), actor)

        val v2 = repository.appendReleasedVersion(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), updater)

        v2.shouldNotBeNull()
        v2.createdBy shouldBe updater
        withClue("findLatest must agree with the update's own return value") {
            repository.findLatest(workspaceId, "test/fetch_orders.sql")?.createdBy shouldBe updater
        }
        withClue("and with listVersions, which reads the same column") {
            repository.listVersions(workspaceId, "test/fetch_orders.sql").single { it.version == 2 }.createdBy shouldBe updater
            repository.listVersions(workspaceId, "test/fetch_orders.sql").single { it.version == 1 }.createdBy shouldBe actor
        }
        withClue("version 1 still belongs to its own author") {
            repository.findVersion(workspaceId, "test/fetch_orders.sql", 1)?.createdBy shouldBe actor
        }
    }

    @Test
    fun `list returns live templates at their current version, id-ordered`() {
        repository.createReleased(workspaceId, draft(id = "test/b.sql"), actor)
        repository.createReleased(workspaceId, draft(id = "test/a.sql"), actor)
        repository.appendReleasedVersion(workspaceId, "test/a.sql", draft(id = "test/a.sql", body = "SELECT 2"), actor)
        repository.createReleased(workspaceId, draft(id = "test/gone.sql"), actor)
        repository.softDelete(workspaceId, "test/gone.sql")

        val page = repository.list(workspaceId)

        page.map { it.id } shouldContainExactly listOf("test/a.sql", "test/b.sql")
        withClue("the current version, not every version") { page.single { it.id == "test/a.sql" }.version shouldBe 2 }
    }

    @Test
    fun `list filters by dialect`() {
        repository.createReleased(workspaceId, draft(id = "test/pg.sql", dialect = Dialect.POSTGRES), actor)
        repository.createReleased(workspaceId, draft(id = "test/my.sql", dialect = Dialect.MYSQL), actor)

        repository.list(workspaceId, dialect = Dialect.MYSQL).map { it.id } shouldContainExactly listOf("test/my.sql")
    }

    @Test
    fun `list searches id, display name and description case-insensitively`() {
        repository.createReleased(workspaceId, draft(id = "test/orders_daily.sql"), actor)
        repository.createReleased(
            workspaceId,
            TemplateDraft(
                id = "unrelated.sql",
                dialect = Dialect.POSTGRES,
                displayName = "Revenue Rollup",
                description = "Aggregates ORDERS by month.",
                imports = emptyList(),
                body = "SELECT 1",
                isLibrary = false,
            ),
            actor,
        )

        repository.list(workspaceId, q = "ORDERS").map { it.id } shouldContainExactly listOf("test/orders_daily.sql", "unrelated.sql")
        repository.list(workspaceId, q = "rollup").map { it.id } shouldContainExactly listOf("unrelated.sql")
        repository.list(workspaceId, q = "nothing-matches").shouldBeEmpty()
    }

    @Test
    fun `a search term's LIKE metacharacters are matched literally`() {
        // Unescaped, a `q` of "%" matches every row and "_" matches any single character — a
        // search box that silently becomes a full scan (and a wrong result).
        repository.createReleased(workspaceId, draft(id = "test/plain.sql"), actor)
        repository.createReleased(
            workspaceId,
            TemplateDraft(
                id = "discount.sql",
                dialect = Dialect.POSTGRES,
                displayName = "100% off",
                description = "Promo.",
                imports = emptyList(),
                body = "SELECT 1",
                isLibrary = false,
            ),
            actor,
        )

        repository.list(workspaceId, q = "%").map { it.id } shouldContainExactly listOf("discount.sql")
        repository.list(workspaceId, q = "100% off").map { it.id } shouldContainExactly listOf("discount.sql")
    }

    @Test
    fun `list matches the rendered dialect column`() {
        // The templates table renders a dialect badge per row (partials/templates.html), so the
        // search rule (029: a screen's search covers every rendered column) requires the dialect
        // wire value to be searchable. These two templates share no name/display-name/description
        // substring with "sqlite" — a hit can only come from the dialect column.
        repository.createReleased(workspaceId, draft(id = "test/inventory.sql", dialect = Dialect.SQLITE), actor)
        repository.createReleased(workspaceId, draft(id = "test/fetch_orders.sql", dialect = Dialect.POSTGRES), actor)

        repository.list(workspaceId, q = "sqlite").map { it.id } shouldContainExactly listOf("test/inventory.sql")
    }

    @Test
    fun `count is the list page's truthful total under the same filters`() {
        // 034 E3: the pager's "of M" comes from this count, so it must agree with list()
        // under EVERY filter combination — the two share one WHERE (LIST_WHERE) to make
        // drift structural, and this test is the behavior proof of the sharing.
        repository.createReleased(workspaceId, draft(id = "test/a.sql"), actor)
        repository.createReleased(workspaceId, draft(id = "test/b.sql", dialect = Dialect.MYSQL), actor)
        repository.createReleased(
            workspaceId,
            TemplateDraft(
                id = "rollup.sql",
                dialect = Dialect.SQLITE,
                displayName = "Revenue Rollup",
                description = "Aggregates orders.",
                imports = emptyList(),
                body = "SELECT 1",
                isLibrary = false,
            ),
            actor,
        )
        repository.createReleased(workspaceId, draft(id = "test/gone.sql"), actor)
        repository.softDelete(workspaceId, "test/gone.sql")

        repository.count(workspaceId) shouldBe 3
        repository.count(workspaceId, dialect = Dialect.MYSQL) shouldBe 1
        repository.count(workspaceId, q = "rollup") shouldBe 1
        repository.count(workspaceId, q = "sqlite") shouldBe 1 // the dialect column is searchable
        repository.count(workspaceId, dialect = Dialect.SQLITE, q = "revenue") shouldBe 1
        repository.count(workspaceId, q = "nothing-matches") shouldBe 0
        // And the agreement itself: the count equals the unpaged list size, not page-size-capped.
        repository.list(workspaceId, limit = 2).size shouldBe 2
        repository.count(workspaceId) shouldBe repository.list(workspaceId, limit = 200).size
    }

    @Test
    fun `list pages with limit and offset`() {
        listOf("test/a.sql", "test/b.sql", "test/c.sql").forEach { repository.createReleased(workspaceId, draft(id = it), actor) }

        repository.list(workspaceId, limit = 2).map { it.id } shouldContainExactly listOf("test/a.sql", "test/b.sql")
        repository.list(workspaceId, offset = 2, limit = 2).map { it.id } shouldContainExactly listOf("test/c.sql")
        withClue("a negative offset must be clamped, not passed through to the database") {
            repository.list(workspaceId, offset = -5).map { it.id } shouldContainExactly listOf("test/a.sql", "test/b.sql", "test/c.sql")
        }
    }

    @Test
    fun `an oversized limit is clamped to the page maximum`() {
        // The old assertion used limit=Int.MAX over 3 rows, so the clamp half was vacuous — the
        // query would have returned the same 3 rows unclamped (NEW-4a). With more rows than the
        // maximum, only a real clamp produces this result.
        (1..TemplateRepository.MAX_PAGE_LIMIT + 25).forEach {
            repository.createReleased(
                workspaceId,
                draft(id = "t%04d.sql".format(it)),
                actor,
            )
        }

        repository.list(workspaceId, limit = 500).size shouldBe TemplateRepository.MAX_PAGE_LIMIT
    }

    @Test
    fun `dialect may change across versions, and each version keeps its own`() {
        // TPL-API-6 had no regression test. rest-api §8.4 allows a template's dialect to change
        // across versions precisely because existing pipelines pin a version — which only holds if
        // the stored per-version value is what is read back.
        repository.createReleased(workspaceId, draft(id = "test/shift.sql", dialect = Dialect.POSTGRES), actor)

        val v2 =
            repository.appendReleasedVersion(
                workspaceId,
                "test/shift.sql",
                draft(id = "test/shift.sql", dialect = Dialect.MYSQL),
                actor,
            )

        v2.shouldNotBeNull()
        v2.dialect shouldBe Dialect.MYSQL
        repository.findVersion(workspaceId, "test/shift.sql", 1)?.dialect shouldBe Dialect.POSTGRES
        repository.lookupVersion(workspaceId, "test/shift.sql", 1)?.dialect shouldBe Dialect.POSTGRES
        repository.lookupVersion(workspaceId, "test/shift.sql", 2)?.dialect shouldBe Dialect.MYSQL
    }

    @Test
    fun `imports are stored as JSONB and queryable as such`() {
        repository.createReleased(workspaceId, draft(imports = listOf(TemplateImport("test/lib.sql", 1, "l"))), actor)

        val alias =
            jdbc.queryForObject(
                """
                SELECT v.imports_json -> 0 ->> 'alias'
                  FROM template_versions v
                  JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND v.version = 1
                """.trimIndent(),
                mapOf("name" to "test/fetch_orders.sql"),
                String::class.java,
            )

        alias shouldBe "l"
    }

    // =============================================================================================
    // The draft/release lifecycle (versioning §3–§6, V6) — the template mirror of §13's floor.
    // =============================================================================================

    @Test
    fun `createDraft copies the released version to a draft and leaves the released row untouched`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        val detail =
            checkNotNull(
                repository.createDraft(
                    workspaceId,
                    "test/fetch_orders.sql",
                    draft(body = "SELECT 2"),
                    released.bodyHash,
                    actor,
                ),
            )

        detail.version shouldBe 2
        detail.status shouldBe PipelineVersionStatus.DRAFT
        detail.bodyHash shouldNotBe released.bodyHash
        detail.updatedBy shouldBe actor
        // §3.4: the pointer does not move while a draft exists.
        repository.findLatest(workspaceId, "test/fetch_orders.sql")?.version shouldBe 1
        // The metadata (display_name/description) moved at save time — the template asymmetry.
        repository.findLatest(workspaceId, "test/fetch_orders.sql")?.displayName shouldBe "Fetch Orders"
        checkNotNull(repository.findVersion(workspaceId, "test/fetch_orders.sql", 2)).body shouldBe "SELECT 2"
    }

    @Test
    fun `a no-op createDraft - content identical to released - returns RELEASED, creates no draft, still moves metadata`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        // Same CONTENT (§4.1's field object), new metadata — the no-op is defined on the
        // hash, and display_name/description are not in it (§6's asymmetry).
        val noop =
            checkNotNull(
                repository.createDraft(
                    workspaceId,
                    "test/fetch_orders.sql",
                    draft(body = "SELECT 1", displayName = "Renamed, same SQL"),
                    released.bodyHash,
                    actor,
                ),
            )

        noop.version shouldBe 1
        noop.status shouldBe PipelineVersionStatus.RELEASED
        noop.bodyHash shouldBe released.bodyHash
        // No draft row, no burned number; the metadata moved because a metadata-only save
        // is a real save of the index row.
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions", Int::class.java) shouldBe 1
        repository.findDraftDetail(workspaceId, "test/fetch_orders.sql").shouldBeNull()
        repository.findLatest(workspaceId, "test/fetch_orders.sql")?.displayName shouldBe "Renamed, same SQL"
        // The next real CONTENT change allocates v2 — the number was never consumed.
        checkNotNull(
            repository.createDraft(
                workspaceId,
                "test/fetch_orders.sql",
                draft(body = "SELECT 2"),
                released.bodyHash,
                actor,
            ),
        ).version shouldBe 2
    }

    @Test
    fun `a no-op createDraft with a stale hash writes nothing - the no-op arm carries the guard`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 1"), "stale", actor).shouldBeNull()

        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions", Int::class.java) shouldBe 1
        repository.findDraftDetail(workspaceId, "test/fetch_orders.sql").shouldBeNull()
    }

    @Test
    fun `identical content while a draft exists is a stale base, not a no-op`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        val draft =
            checkNotNull(
                repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor),
            )

        // The released body PUT back unchanged while a draft exists: 409 material (null),
        // never "RELEASED, no draft" — the draft owns the working state.
        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 1"), released.bodyHash, actor).shouldBeNull()
        checkNotNull(repository.findDraftDetail(workspaceId, "test/fetch_orders.sql")).bodyHash shouldBe draft.bodyHash
    }

    @Test
    fun `a draft edited back to its released parent is left alone - never auto-discarded`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        val draft =
            checkNotNull(
                repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor),
            )

        val reverted =
            checkNotNull(repository.writeDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 1"), draft.bodyHash, actor))

        reverted.status shouldBe PipelineVersionStatus.DRAFT
        checkNotNull(repository.findDraftDetail(workspaceId, "test/fetch_orders.sql")).version shouldBe draft.version
    }

    @Test
    fun `writeDraft overwrites the draft in place - no new row`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        val first =
            checkNotNull(
                repository.createDraft(
                    workspaceId,
                    "test/fetch_orders.sql",
                    draft(body = "SELECT 2"),
                    released.bodyHash,
                    actor,
                ),
            )

        val second =
            checkNotNull(repository.writeDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 3"), first.bodyHash, actor))

        second.version shouldBe 2
        checkNotNull(repository.findVersion(workspaceId, "test/fetch_orders.sql", 2)).body shouldBe "SELECT 3"
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions", Int::class.java) shouldBe 2
    }

    @Test
    fun `stale hashes write nothing on every template mutation`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor)

        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 9"), "stale", actor).shouldBeNull()
        repository.writeDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 9"), "stale", actor).shouldBeNull()
        repository.releaseDraft(workspaceId, "test/fetch_orders.sql", "stale", actor).shouldBeNull()
        repository.discardDraft(workspaceId, "test/fetch_orders.sql", "stale") shouldBe false

        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions", Int::class.java) shouldBe 2
        checkNotNull(repository.findDraftDetail(workspaceId, "test/fetch_orders.sql")).version shouldBe 2
    }

    @Test
    fun `releaseDraft flips the draft and bumps current_version`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        val draftDetail =
            checkNotNull(
                repository.createDraft(
                    workspaceId,
                    "test/fetch_orders.sql",
                    draft(body = "SELECT 2"),
                    released.bodyHash,
                    actor,
                ),
            )

        val out = checkNotNull(repository.releaseDraft(workspaceId, "test/fetch_orders.sql", draftDetail.bodyHash, actor))

        out.version shouldBe 2
        out.status shouldBe PipelineVersionStatus.RELEASED
        out.releasedAt shouldNotBe null
        out.releasedBy shouldBe actor
        repository.findLatest(workspaceId, "test/fetch_orders.sql")?.version shouldBe 2
        repository.findDraftDetail(workspaceId, "test/fetch_orders.sql").shouldBeNull()
    }

    @Test
    fun `discardDraft hard-deletes the draft and returns the number to the pool`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        val draftDetail =
            checkNotNull(
                repository.createDraft(
                    workspaceId,
                    "test/fetch_orders.sql",
                    draft(body = "SELECT 2"),
                    released.bodyHash,
                    actor,
                ),
            )

        repository.discardDraft(workspaceId, "test/fetch_orders.sql", draftDetail.bodyHash) shouldBe true

        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions", Int::class.java) shouldBe 1
        checkNotNull(
            repository.createDraft(
                workspaceId,
                "test/fetch_orders.sql",
                draft(body = "SELECT 3"),
                released.bodyHash,
                actor,
            ),
        ).version shouldBe 2
    }

    @Test
    fun `a second DRAFT row violates the one-draft partial unique index`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor)

        val templateId =
            checkNotNull(
                jdbc.queryForObject(
                    "SELECT id FROM templates WHERE name = 'test/fetch_orders.sql'",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        val thrown =
            io.kotest.assertions.throwables.shouldThrow<org.springframework.dao.DuplicateKeyException> {
                jdbc.update(
                    """
                    INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, body_hash, status, created_by)
                    VALUES (:id, 99, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'x', 'h', 'DRAFT', :actor)
                    """.trimIndent(),
                    mapOf("id" to templateId, "actor" to actor),
                )
            }
        thrown.mostSpecificCause.message?.contains("uq_template_versions_one_draft") shouldBe true
    }

    @Test
    fun `the template draft service branches, and a stale base is a version conflict`() {
        val service = TemplateDraftService(repository, co.datapipelines.pipeline.AuthoringGuard(true))
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        val first = service.write(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor)
        val second = service.write(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 3"), first.bodyHash, actor)
        second.version shouldBe 2

        val stale =
            io.kotest.assertions.throwables.shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                service.write(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 4"), released.bodyHash, actor)
            }
        stale.code shouldBe PipelineErrorCodes.Template.VERSION_CONFLICT
        stale.details["current_body_hash"] shouldBe second.bodyHash

        val notFound =
            io.kotest.assertions.throwables.shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                service.write(workspaceId, "test/nope.sql", draft(id = "test/nope.sql"), released.bodyHash, actor)
            }
        notFound.code shouldBe PipelineErrorCodes.Template.NOT_FOUND
    }

    @Test
    fun `the template draft service refuses writes when authoring is disabled - and imports create no draft`() {
        // C2/C3's template mirror: the write path fails closed, and the import paths
        // (promotion) land RELEASED rows without ever opening a draft — if any import
        // statement below grew draft-creation logic, this is the test that goes red.
        val disabled = TemplateDraftService(repository, co.datapipelines.pipeline.AuthoringGuard(false))
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        val refused =
            io.kotest.assertions.throwables.shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                disabled.write(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 9"), released.bodyHash, actor)
            }
        refused.code shouldBe PipelineErrorCodes.Template.AUTHORING_DISABLED
        refused.details["config_key"] shouldBe co.datapipelines.pipeline.AuthoringGuard.CONFIG_KEY

        checkNotNull(
            repository.importTemplateVersion(workspaceId, draft(id = "test/promoted.sql"), 4, "hash-4", java.time.Instant.EPOCH, actor),
        )
        checkNotNull(
            repository.insertReleasedVersion(
                workspaceId,
                "test/fetch_orders.sql",
                draft(body = "SELECT 5"),
                3,
                "hash-3",
                java.time.Instant.EPOCH,
                actor,
            ),
        )
        checkNotNull(repository.appendReleasedVersion(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 6"), actor))

        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_versions WHERE status = 'DRAFT'", Int::class.java) shouldBe 0
        repository.findDraftDetail(workspaceId, "test/fetch_orders.sql").shouldBeNull()
    }

    @Test
    fun `findAllDraftTemplateNames names every draft - the boot check's evidence`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor)

        repository.findAllDraftTemplateNames() shouldContainExactly listOf("test/fetch_orders.sql")
    }

    @Test
    fun `computeBodyHash agrees with the stored hash - the template one-expression rule`() {
        // The expected imports JSON comes through writeImports, never hand-written: Jackson
        // serializes every getter, including TemplateImport's computed `key`, so the wire
        // form of an import is richer than its declared fields.
        val imports = listOf(TemplateImport("test/lib.sql", 1, "l"))
        repository.createReleased(workspaceId, draft(imports = imports), actor)
        val stored = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))

        stored.bodyHash shouldBe
            repository.computeBodyHash("freemarker", "POSTGRES", false, TemplateJson.writeImports(imports), "SELECT 1")
    }

    @Test
    fun `V6 backfilled template rows are RELEASED with a hash the runtime agrees with`() {
        // The template half of the A2 proof: a row the way pre-V6 code wrote it, stamped by
        // the migration's own expression, passes its first draft-write precondition.
        jdbc.update(
            """
            INSERT INTO templates (name, display_name, description, current_version, workspace_id, created_by)
            VALUES ('test/legacy.sql', 'Legacy', '', 1, :ws, :actor)
            """.trimIndent(),
            mapOf("ws" to workspaceId, "actor" to actor),
        )
        jdbc.jdbcTemplate.execute(
            """
            INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, status, body_hash, created_by)
            SELECT id, 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'RELEASED', 'pending', '$actor'
              FROM templates WHERE name = 'test/legacy.sql'
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute(
            // The migration's own stamping expression, rerun by hand: the row was inserted
            // pre-V6-style in a post-V6 schema, and this is the backfill being proven.
            """
            UPDATE template_versions
               SET status = 'RELEASED', released_at = created_at, released_by = created_by,
                   body_hash = encode(
                       sha256(
                           convert_to(jsonb_build_object('engine', engine, 'dialect', dialect, 'is_library', is_library,
                                              'imports', imports_json, 'body', body)::text, 'UTF8')
                       ), 'hex')
             WHERE template_id IN (SELECT id FROM templates WHERE name = 'test/legacy.sql')
            """.trimIndent(),
        )

        val stored = checkNotNull(repository.findVersion(workspaceId, "test/legacy.sql", 1))
        stored.status shouldBe PipelineVersionStatus.RELEASED
        stored.bodyHash shouldBe repository.computeBodyHash("freemarker", "POSTGRES", false, "[]", "SELECT 1")

        // The first precondition check against this pre-migration row succeeds.
        checkNotNull(
            repository.createDraft(
                workspaceId,
                "test/legacy.sql",
                draft(id = "test/legacy.sql", body = "SELECT 2"),
                stored.bodyHash,
                actor,
            ),
        )
    }

    @Test
    fun `preserved-version imports create at the exact number and fill gaps without moving the pointer`() {
        // §9.2's template mirror: a NEW template lands at the source's exact version; a
        // gap below current is harmless; a taken number writes nothing.
        val promoted = draft(id = "test/promoted.sql", body = "SELECT 9")
        val created =
            repository.importTemplateVersion(
                workspaceId,
                promoted,
                4,
                "hash-v4",
                java.time.Instant.parse("2026-08-31T14:03:11Z"),
                actor,
            )
        created.version shouldBe 4
        repository.findLatest(workspaceId, "test/promoted.sql")?.version shouldBe 4
        checkNotNull(repository.findVersionDetail(workspaceId, "test/promoted.sql", 4)).bodyHash shouldBe "hash-v4"

        // A gap at v2 on the existing v-4 template: pointer stays, no metadata ride.
        repository.insertReleasedVersion(
            workspaceId,
            "test/promoted.sql",
            draft(id = "test/promoted.sql", body = "SELECT 2"),
            2,
            "hash-v2",
            null,
            actor,
        )
        repository.findLatest(workspaceId, "test/promoted.sql")?.version shouldBe 4

        // The taken v4 again: suppressed (null), nothing written.
        val reinserted =
            repository.insertReleasedVersion(
                workspaceId,
                "test/promoted.sql",
                draft(id = "test/promoted.sql", body = "SELECT 9"),
                4,
                "hash-v4",
                null,
                actor,
            )
        reinserted.shouldBeNull()
    }

    @Test
    fun `findVersionStatus answers for the pin check and findDrafts feeds the badge`() {
        repository.createReleased(workspaceId, draft(), actor)
        val released = checkNotNull(repository.findLatest(workspaceId, "test/fetch_orders.sql"))
        templates_statusHelpers(released)
    }

    private fun templates_statusHelpers(released: Template) {
        // §6's release-pin check reads this: RELEASED for the current, null for a miss.
        repository
            .findVersionStatus(workspaceId, "test/fetch_orders.sql", 1)
            .shouldBe(PipelineVersionStatus.RELEASED)
        repository.findVersionStatus(workspaceId, "test/fetch_orders.sql", 9).shouldBeNull()

        repository.createDraft(workspaceId, "test/fetch_orders.sql", draft(body = "SELECT 2"), released.bodyHash, actor)
        val drafts = repository.findDrafts(workspaceId, listOf("test/fetch_orders.sql", "absent.sql"))
        drafts.keys shouldContainExactly setOf("test/fetch_orders.sql")
        drafts["test/fetch_orders.sql"]?.version shouldBe 2
    }

    private fun insertUser(
        email: String = "owner@example.com",
        subject: String = "sub-1",
    ): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES (:email, 'Owner', 'google', :subject)
                RETURNING id
                """.trimIndent(),
                mapOf("email" to email, "subject" to subject),
                UUID::class.java,
            ),
        )

    private fun existsRows(table: String): Int =
        checkNotNull(jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))
}

/**
 * The RELEASED create — what this suite's fixtures mean. Named rather than defaulted for the same
 * reason as the pipeline side: `lifecycle` is required on the production signature so that no new
 * create path lands RELEASED by omission (D55).
 */
private fun TemplateRepository.createReleased(
    workspaceId: java.util.UUID,
    draft: TemplateDraft,
    createdBy: java.util.UUID,
): Template = create(workspaceId, draft, createdBy, co.datapipelines.pipeline.CreateLifecycle.RELEASED)
