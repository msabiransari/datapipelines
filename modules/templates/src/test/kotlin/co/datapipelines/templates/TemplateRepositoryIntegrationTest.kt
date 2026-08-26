package co.datapipelines.templates

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [TemplateRepository] against a real Postgres running the **shipped** schema.
 *
 * Executes `app`'s real migrations off disk in version order rather than running Flyway — the
 * same discipline `PipelineRepositoryIntegrationTest` documents: module-structure §3.1 rule 2
 * keeps the Flyway dependency in `app` only, so a domain module never gains a schema-creation
 * tool, yet the test still runs the exact DDL the application migrates with (including the D3
 * `no params_schema` column shape and the V4 surrogate-key re-key).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: TemplateRepository
    private lateinit var actor: UUID

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        MIGRATION_PATHS.forEach { jdbc.jdbcTemplate.execute(TemplateFixtures.repoFile(it).readText()) }
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
        id: String? = "fetch_orders.sql",
        body: String = "SELECT 1",
        imports: List<TemplateImport> = emptyList(),
        isLibrary: Boolean = false,
        dialect: Dialect = Dialect.POSTGRES,
    ): TemplateDraft =
        TemplateDraft(
            id = id,
            dialect = dialect,
            displayName = "Fetch Orders",
            description = "Pulls orders.",
            imports = imports,
            body = body,
            isLibrary = isLibrary,
        )

    @Test
    fun `create inserts the template and version 1 together, returning what the database stored`() {
        val stored = repository.create(draft(imports = listOf(TemplateImport("lib.sql", 1, "l"))), actor)

        stored.version shouldBe 1
        stored.id shouldBe "fetch_orders.sql"
        stored.imports shouldContainExactly listOf(TemplateImport("lib.sql", 1, "l"))
        stored.createdBy shouldBe actor
        repository.lookupVersion("fetch_orders.sql", 1).shouldNotBeNull()
    }

    @Test
    fun `an omitted id is auto-generated inside the identifier rule`() {
        val stored = repository.create(draft(id = null), actor)
        TEMPLATE_ID.matches(stored.id) shouldBe true
    }

    @Test
    fun `update appends a new immutable version and bumps current_version`() {
        repository.create(draft(body = "SELECT 1"), actor)

        val v2 = repository.update("fetch_orders.sql", draft(body = "SELECT 2"), actor)

        v2.shouldNotBeNull()
        v2.version shouldBe 2
        v2.body shouldBe "SELECT 2"
        repository.findLatest("fetch_orders.sql")?.version shouldBe 2
        // Version 1 is untouched — immutable per version (§5.1).
        repository.findVersion("fetch_orders.sql", 1)?.body shouldBe "SELECT 1"
        repository.listVersions("fetch_orders.sql").map { it.version } shouldContainExactly listOf(2, 1)
    }

    @Test
    fun `update on an unknown id returns null and writes nothing`() {
        repository.update("nope.sql", draft(), actor).shouldBeNull()
        existsRows("template_versions") shouldBe 0
    }

    @Test
    fun `lookupVersion round-trips imports and dialect`() {
        repository.create(draft(imports = listOf(TemplateImport("lib.sql", 3, "d")), dialect = Dialect.MYSQL), actor)

        val version = repository.lookupVersion("fetch_orders.sql", 1)

        version.shouldNotBeNull()
        version.dialect shouldBe Dialect.MYSQL
        version.imports shouldContainExactly listOf(TemplateImport("lib.sql", 3, "d"))
    }

    @Test
    fun `existsId reflects whether any version exists`() {
        repository.existsId("fetch_orders.sql") shouldBe false
        repository.create(draft(), actor)
        repository.existsId("fetch_orders.sql") shouldBe true
    }

    @Test
    fun `a soft-deleted template disappears from findLatest but its versions still resolve`() {
        repository.create(draft(), actor)

        repository.softDelete("fetch_orders.sql") shouldBe true

        // §5.1: pipelines referencing a deleted template's version continue to work, so the
        // registry lookup must still resolve it, but the current-version projection is gone.
        repository.findLatest("fetch_orders.sql").shouldBeNull()
        repository.lookupVersion("fetch_orders.sql", 1).shouldNotBeNull()
        repository.findVersion("fetch_orders.sql", 1).shouldNotBeNull()
        repository.softDelete("fetch_orders.sql") shouldBe false
    }

    @Test
    fun `update on a soft-deleted template writes nothing and adds no version`() {
        // TPL-TEST-9. §5.1 keeps a deleted template's existing versions resolvable, but a deleted
        // template must not gain new ones — the UPDATE's `is_deleted = FALSE` predicate is the
        // only thing enforcing that, and a CTE whose first leg matches no row must not leave the
        // INSERT leg to run on its own.
        repository.create(draft(body = "SELECT 1"), actor)
        repository.softDelete("fetch_orders.sql")

        repository.update("fetch_orders.sql", draft(body = "SELECT 2"), actor).shouldBeNull()

        existsRows("template_versions") shouldBe 1
        repository.lookupVersion("fetch_orders.sql", 2).shouldBeNull()
        repository.lookupVersion("fetch_orders.sql", 1)?.body shouldBe "SELECT 1"
    }

    @Test
    fun `created_by is the author of THAT version, not of the template`() {
        // TPL-API-2. `templates.created_by` is whoever created the template; a version has its own
        // author. Reading the former made every endpoint that returns a Template credit the
        // original creator for someone else's edit — and disagree with `listVersions`, which reads
        // the version row, about the very same version.
        val updater = insertUser(email = "editor@example.com", subject = "sub-2")
        repository.create(draft(body = "SELECT 1"), actor)

        val v2 = repository.update("fetch_orders.sql", draft(body = "SELECT 2"), updater)

        v2.shouldNotBeNull()
        v2.createdBy shouldBe updater
        withClue("findLatest must agree with the update's own return value") {
            repository.findLatest("fetch_orders.sql")?.createdBy shouldBe updater
        }
        withClue("and with listVersions, which reads the same column") {
            repository.listVersions("fetch_orders.sql").single { it.version == 2 }.createdBy shouldBe updater
            repository.listVersions("fetch_orders.sql").single { it.version == 1 }.createdBy shouldBe actor
        }
        withClue("version 1 still belongs to its own author") {
            repository.findVersion("fetch_orders.sql", 1)?.createdBy shouldBe actor
        }
    }

    @Test
    fun `list returns live templates at their current version, id-ordered`() {
        repository.create(draft(id = "b.sql"), actor)
        repository.create(draft(id = "a.sql"), actor)
        repository.update("a.sql", draft(id = "a.sql", body = "SELECT 2"), actor)
        repository.create(draft(id = "gone.sql"), actor)
        repository.softDelete("gone.sql")

        val page = repository.list()

        page.map { it.id } shouldContainExactly listOf("a.sql", "b.sql")
        withClue("the current version, not every version") { page.single { it.id == "a.sql" }.version shouldBe 2 }
    }

    @Test
    fun `list filters by dialect`() {
        repository.create(draft(id = "pg.sql", dialect = Dialect.POSTGRES), actor)
        repository.create(draft(id = "my.sql", dialect = Dialect.MYSQL), actor)

        repository.list(dialect = Dialect.MYSQL).map { it.id } shouldContainExactly listOf("my.sql")
    }

    @Test
    fun `list searches id, display name and description case-insensitively`() {
        repository.create(draft(id = "orders_daily.sql"), actor)
        repository.create(
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

        repository.list(q = "ORDERS").map { it.id } shouldContainExactly listOf("orders_daily.sql", "unrelated.sql")
        repository.list(q = "rollup").map { it.id } shouldContainExactly listOf("unrelated.sql")
        repository.list(q = "nothing-matches").shouldBeEmpty()
    }

    @Test
    fun `a search term's LIKE metacharacters are matched literally`() {
        // Unescaped, a `q` of "%" matches every row and "_" matches any single character — a
        // search box that silently becomes a full scan (and a wrong result).
        repository.create(draft(id = "plain.sql"), actor)
        repository.create(
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

        repository.list(q = "%").map { it.id } shouldContainExactly listOf("discount.sql")
        repository.list(q = "100% off").map { it.id } shouldContainExactly listOf("discount.sql")
    }

    @Test
    fun `list pages with limit and offset`() {
        listOf("a.sql", "b.sql", "c.sql").forEach { repository.create(draft(id = it), actor) }

        repository.list(limit = 2).map { it.id } shouldContainExactly listOf("a.sql", "b.sql")
        repository.list(offset = 2, limit = 2).map { it.id } shouldContainExactly listOf("c.sql")
        withClue("a negative offset must be clamped, not passed through to the database") {
            repository.list(offset = -5).map { it.id } shouldContainExactly listOf("a.sql", "b.sql", "c.sql")
        }
    }

    @Test
    fun `an oversized limit is clamped to the page maximum`() {
        // The old assertion used limit=Int.MAX over 3 rows, so the clamp half was vacuous — the
        // query would have returned the same 3 rows unclamped (NEW-4a). With more rows than the
        // maximum, only a real clamp produces this result.
        (1..TemplateRepository.MAX_PAGE_LIMIT + 25).forEach { repository.create(draft(id = "t%04d.sql".format(it)), actor) }

        repository.list(limit = 500).size shouldBe TemplateRepository.MAX_PAGE_LIMIT
    }

    @Test
    fun `dialect may change across versions, and each version keeps its own`() {
        // TPL-API-6 had no regression test. rest-api §8.4 allows a template's dialect to change
        // across versions precisely because existing pipelines pin a version — which only holds if
        // the stored per-version value is what is read back.
        repository.create(draft(id = "shift.sql", dialect = Dialect.POSTGRES), actor)

        val v2 = repository.update("shift.sql", draft(id = "shift.sql", dialect = Dialect.MYSQL), actor)

        v2.shouldNotBeNull()
        v2.dialect shouldBe Dialect.MYSQL
        repository.findVersion("shift.sql", 1)?.dialect shouldBe Dialect.POSTGRES
        repository.lookupVersion("shift.sql", 1)?.dialect shouldBe Dialect.POSTGRES
        repository.lookupVersion("shift.sql", 2)?.dialect shouldBe Dialect.MYSQL
    }

    @Test
    fun `imports are stored as JSONB and queryable as such`() {
        repository.create(draft(imports = listOf(TemplateImport("lib.sql", 1, "l"))), actor)

        val alias =
            jdbc.queryForObject(
                """
                SELECT v.imports_json -> 0 ->> 'alias'
                  FROM template_versions v
                  JOIN templates t ON t.id = v.template_id
                 WHERE t.name = :name AND v.version = 1
                """.trimIndent(),
                mapOf("name" to "fetch_orders.sql"),
                String::class.java,
            )

        alias shouldBe "l"
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

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        /**
         * The shipped migrations in version order — V1 alone would miss the `workspaces`
         * re-key (V4) the repository now writes against.
         */
        val MIGRATION_PATHS =
            listOf(
                "modules/app/src/main/resources/db/migration/V1__initial_schema.sql",
                "modules/app/src/main/resources/db/migration/V2__datasource_introspection_include_schemas.sql",
                "modules/app/src/main/resources/db/migration/V3__execution_lineage.sql",
                "modules/app/src/main/resources/db/migration/V4__workspaces_rekey.sql",
            )

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
