package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [PipelineRepository] against a real Postgres running the **shipped** schema.
 *
 * ## Why the migration is executed rather than run through Flyway
 *
 * module-structure §3.1 rule 2: "The Flyway dependency (`flyway-core` +
 * `flyway-database-postgresql`) and every migration script under
 * `src/main/resources/db/migration/` live in `app` only. Domain modules read and write
 * tables; they never create or alter them." Adding Flyway here — even as a test dependency —
 * would put a schema-creation tool in a module the rule says must not have one.
 *
 * So the test reads `app`'s real `V1__initial_schema.sql` off disk and executes it. It is the
 * same file the application migrates with: change the DDL and this test runs the new DDL, or
 * fails because the file moved. What it deliberately does *not* prove is that Flyway can
 * apply it (checksums, transactional wrapping, the advisory lock) — that belongs to `app`'s
 * own suite, where the dependency lives.
 *
 * ## Container lifetime
 *
 * One container for the class, truncated before each test. Per the shared-test-container
 * discipline the project already follows: each spec cleans the tables it touches, in FK
 * order, rather than dropping the schema.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var owner: UUID

    private val serializer = PipelineSerializer()

    /**
     * Creates the schema once for the class.
     *
     * `@BeforeAll` under `PER_CLASS` rather than a `@BeforeEach` guarded by a static
     * `schemaCreated` flag: the flag was mutable state shared across instances that JUnit is
     * free to stop honouring (a parallel or re-ordered run would have two instances race it),
     * and it encoded "run once" in a place the framework already expresses directly.
     */
    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        jdbc.jdbcTemplate.execute(Fixtures.repoFile(MIGRATION_PATH).readText())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        // pipeline_versions cascades from pipelines; users is the FK parent of both.
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        owner = insertUser()
    }

    @Test
    fun `create inserts the pipeline and version 1 together, returning what the database stored`() {
        val body = Fixtures.pipeline()

        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        record.currentVersion shouldBe 1
        record.name shouldBe "monthly_revenue"
        record.isDeleted shouldBe false
        // Server-generated, not asserted from a hand-built object (metadata-db §6.1).
        record.createdAt shouldNotBe null
        repository.findVersionBody(record.id, 1) shouldNotBe null
    }

    @Test
    fun `the stored body round-trips through the deserializer`() {
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        val stored = PipelineDeserializer().readOrThrow(checkNotNull(repository.findLatestBody(record.id)))

        stored shouldBe body
    }

    @Test
    fun `update appends a new immutable version and bumps current_version`() {
        val v1 = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(v1, owner), serializer.write(v1), owner)
        val v2 =
            v1.copy(
                displayName = "Monthly Revenue v2",
                nodes = v1.nodes + Fixtures.node(id = "extra", output = NodeOutput.Tempdb("stg_extra")),
            )

        val updated = checkNotNull(repository.update(record.id, v2, serializer.write(v2), owner))

        updated.currentVersion shouldBe 2
        updated.displayName shouldBe "Monthly Revenue v2"
        repository.listVersions(record.id).map { it.version } shouldContainExactly listOf(2, 1)
        // Version 1's body is untouched — pipelines are immutable per version (§2).
        PipelineDeserializer().readOrThrow(checkNotNull(repository.findVersionBody(record.id, 1))) shouldBe v1
        PipelineDeserializer().readOrThrow(checkNotNull(repository.findVersionBody(record.id, 2))) shouldBe v2
    }

    @Test
    fun `update sets updated_at, because this schema has no triggers`() {
        // metadata-db §2: every UPDATE sets updated_at in its own SET clause; "an UPDATE that
        // forgets updated_at is a bug in the repository method".
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        val updated = checkNotNull(repository.update(record.id, body, serializer.write(body), owner))

        // Strictly greater, not `>=`. A repository method that forgot `updated_at = NOW()`
        // leaves the INSERT default in place, which satisfies `>=` — so the old assertion could
        // not fail, and the rule metadata-db §2 calls "a bug in the repository method" was
        // untested. NOW() is transaction-start time and these are two separate statements.
        (updated.updatedAt > record.updatedAt) shouldBe true
        updated.createdAt shouldBe record.createdAt
    }

    @Test
    fun `update on an unknown id returns null and writes nothing`() {
        val body = Fixtures.pipeline()

        repository.update(UUID.randomUUID(), body, serializer.write(body), owner).shouldBeNull()

        countRows("pipeline_versions") shouldBe 0
    }

    @Test
    fun `findById and findByName both skip soft-deleted pipelines`() {
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        repository.findById(record.id) shouldBe record
        repository.findByName("monthly_revenue") shouldBe record

        repository.softDelete(record.id) shouldBe true

        repository.findById(record.id).shouldBeNull()
        repository.findByName("monthly_revenue").shouldBeNull()
        // A second delete finds nothing live to delete.
        repository.softDelete(record.id) shouldBe false
        // The row survives, so the name stays taken (metadata-db §4.4).
        countRows("pipelines") shouldBe 1
    }

    @Test
    fun `findAll filters by owner and lists live pipelines only`() {
        val other = insertUser(email = "other@example.com", subject = "sub-2")
        val a = Fixtures.pipeline(name = "pipeline_a")
        val b = Fixtures.pipeline(name = "pipeline_b")
        val c = Fixtures.pipeline(name = "pipeline_c")
        repository.create(NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(NewPipeline.from(b, other), serializer.write(b), other)
        val deleted = repository.create(NewPipeline.from(c, owner), serializer.write(c), owner)
        repository.softDelete(deleted.id)

        repository.findAll().map { it.name } shouldContainExactly listOf("pipeline_b", "pipeline_a")
        repository.findAll(owner).map { it.name } shouldContainExactly listOf("pipeline_a")
    }

    @Test
    fun `findAll paginates with limit and offset at the SQL level`() {
        val a = Fixtures.pipeline(name = "pipeline_a")
        val b = Fixtures.pipeline(name = "pipeline_b")
        val c = Fixtures.pipeline(name = "pipeline_c")
        repository.create(NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(NewPipeline.from(b, owner), serializer.write(b), owner)
        repository.create(NewPipeline.from(c, owner), serializer.write(c), owner)

        repository.findAll(null, limit = 2, offset = 0).map { it.name } shouldContainExactly listOf("pipeline_c", "pipeline_b")
        repository.findAll(null, limit = 2, offset = 2).map { it.name } shouldContainExactly listOf("pipeline_a")
        repository.findAll(null, limit = 1, offset = 1).map { it.name } shouldContainExactly listOf("pipeline_b")
        repository.findAll(null, limit = 5, offset = 0).size shouldBe 3
    }

    @Test
    fun `findAllByDatasource returns only pipelines whose current body references the datasource`() {
        val bodyPg = Fixtures.pipeline(name = "pg_pipeline", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val bodyMysql = Fixtures.pipeline(name = "mysql_pipeline", nodes = listOf(Fixtures.node(source = "mysql-prod")))
        val outputNode =
            Fixtures.node(
                source = "tempdb",
                output = NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.REPLACE),
            )
        val bodyOutput =
            Fixtures.pipeline(
                name = "output_ds",
                nodes = listOf(outputNode),
            )
        repository.create(NewPipeline.from(bodyPg, owner), serializer.write(bodyPg), owner)
        repository.create(NewPipeline.from(bodyMysql, owner), serializer.write(bodyMysql), owner)
        repository.create(NewPipeline.from(bodyOutput, owner), serializer.write(bodyOutput), owner)

        repository.findAllByDatasource("pg-prod").map { it.name } shouldContainExactly listOf("pg_pipeline")
        repository.findAllByDatasource("mysql-prod").map { it.name } shouldContainExactly listOf("mysql_pipeline")
        repository.findAllByDatasource("pg-warehouse").map { it.name } shouldContainExactly listOf("output_ds")
        repository.findAllByDatasource("nonexistent") shouldContainExactly emptyList()
    }

    @Test
    fun `findAllByDatasource respects limit and offset`() {
        val a = Fixtures.pipeline(name = "a", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val b = Fixtures.pipeline(name = "b", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val c = Fixtures.pipeline(name = "c", nodes = listOf(Fixtures.node(source = "pg-prod")))
        repository.create(NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(NewPipeline.from(b, owner), serializer.write(b), owner)
        repository.create(NewPipeline.from(c, owner), serializer.write(c), owner)

        repository.findAllByDatasource("pg-prod", limit = 2, offset = 0).map { it.name } shouldContainExactly listOf("c", "b")
        repository.findAllByDatasource("pg-prod", limit = 2, offset = 2).map { it.name } shouldContainExactly listOf("a")
    }

    @Test
    fun `findAllByDatasource with owner filter scopes to that owner`() {
        val other = insertUser(email = "other@example.com", subject = "sub-2")
        val mine = Fixtures.pipeline(name = "mine", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val theirs = Fixtures.pipeline(name = "theirs", nodes = listOf(Fixtures.node(source = "pg-prod")))
        repository.create(NewPipeline.from(mine, owner), serializer.write(mine), owner)
        repository.create(NewPipeline.from(theirs, other), serializer.write(theirs), other)

        repository.findAllByDatasource("pg-prod", ownerId = owner).map { it.name } shouldContainExactly listOf("mine")
        repository.findAllByDatasource("pg-prod").map { it.name } shouldContainExactly listOf("theirs", "mine")
    }

    @Test
    fun `countAll returns the number of live pipelines`() {
        repository.countAll() shouldBe 0

        repository.create(NewPipeline.from(Fixtures.pipeline(name = "a"), owner), serializer.write(Fixtures.pipeline(name = "a")), owner)
        repository.countAll() shouldBe 1

        repository.create(NewPipeline.from(Fixtures.pipeline(name = "b"), owner), serializer.write(Fixtures.pipeline(name = "b")), owner)
        repository.countAll() shouldBe 2

        val bodyC = Fixtures.pipeline(name = "c")
        val deleted = repository.create(NewPipeline.from(bodyC, owner), serializer.write(bodyC), owner)
        repository.softDelete(deleted.id)
        repository.countAll() shouldBe 2
    }

    @Test
    fun `an unknown version body reads as null, not as an exception`() {
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        repository.findVersionBody(record.id, 99).shouldBeNull()
        repository.findLatestBody(UUID.randomUUID()).shouldBeNull()
        repository.listVersions(UUID.randomUUID()) shouldContainExactly emptyList()
    }

    @Test
    fun `creating a second pipeline with a taken name raises duplicate_name`() {
        val body = Fixtures.pipeline()
        repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)
            }

        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
        thrown.details["name"] shouldBe "monthly_revenue"
        // Nothing partially written: the CTE is one statement.
        countRows("pipelines") shouldBe 1
        countRows("pipeline_versions") shouldBe 1
    }

    @Test
    fun `renaming a pipeline onto a taken name raises duplicate_name too`() {
        val taken = Fixtures.pipeline(name = "already_taken")
        repository.create(NewPipeline.from(taken, owner), serializer.write(taken), owner)
        val mine = Fixtures.pipeline(name = "mine")
        val record = repository.create(NewPipeline.from(mine, owner), serializer.write(mine), owner)
        val renamed = mine.copy(name = "already_taken")

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.update(record.id, renamed, serializer.write(renamed), owner)
            }

        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
        // Atomicity, asserted on the writes and not only on the counter: the failed UPDATE must
        // leave no version row behind. `current_version` alone would still read 1 if the CTE had
        // inserted an orphan version and only the pipelines UPDATE rolled back.
        repository.findById(record.id)?.currentVersion shouldBe 1
        repository.listVersions(record.id).size shouldBe 1
        countRows("pipeline_versions") shouldBe 2
    }

    @Test
    fun `a soft-deleted pipeline's name stays taken - uniqueness is global`() {
        // V1 declares `name TEXT NOT NULL UNIQUE` — a plain constraint, not a partial index on
        // `is_deleted = FALSE`. §12.1 and metadata-db §4.4 now agree that this is deliberate:
        // execution history references the name, so a deleted pipeline's name is not reusable
        // until the row is hard-deleted.
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)
        repository.softDelete(record.id) shouldBe true

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)
            }

        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
    }

    @Test
    fun `a primary-key collision is NOT reported as a duplicate name`() {
        // §11.3's import path re-uses a pipeline id deliberately. Mapping every duplicate-key
        // violation to duplicate_name would tell the caller to rename something that is fine.
        val body = Fixtures.pipeline()
        val id = UUID.randomUUID()
        repository.create(NewPipeline.from(body, owner, id), serializer.write(body), owner)
        val sameIdDifferentName = NewPipeline.from(Fixtures.pipeline(name = "other_name"), owner, id)

        val thrown =
            shouldThrow<DuplicateKeyException> {
                repository.create(sameIdDifferentName, serializer.write(body), owner)
            }

        thrown.mostSpecificCause.message?.contains("pipelines_pkey") shouldBe true
    }

    @Test
    fun `two concurrent creates of the same name - one wins, the other gets duplicate_name`() {
        // The whole reason the constraint is the authority and not a read-then-write pre-check:
        // both threads pass any SELECT-first check, and one still violates. This test is what
        // proves the loser gets a 409-shaped catalog error rather than a raw
        // DuplicateKeyException surfacing as a 500.
        val body = Fixtures.pipeline()
        val bodyJson = serializer.write(body)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val attempts =
                (1..2).map {
                    // Separate repository instances on separate DataSources: same connection
                    // would serialise the two statements and prove nothing.
                    val isolated = PipelineRepository(NamedParameterJdbcTemplate(dataSource()))
                    pool.submit<Throwable?> {
                        start.await()
                        runCatching { isolated.create(NewPipeline.from(body, owner), bodyJson, owner) }
                            .exceptionOrNull()
                    }
                }
            start.countDown()
            val outcomes = attempts.map { it.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            outcomes.count { it == null } shouldBe 1
            val loser = outcomes.filterNotNull().single()
            loser.shouldBeInstanceOf<DatapipelinesException>()
            loser.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
            countRows("pipelines") shouldBe 1
            countRows("pipeline_versions") shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the body is stored as JSONB and is queryable as such`() {
        // metadata-db §2: JSON columns are JSONB, bound as String and CAST in SQL. If the
        // parameter were bound as text into a text column this operator would fail.
        val body = Fixtures.pipeline()
        val record = repository.create(NewPipeline.from(body, owner), serializer.write(body), owner)

        val nameFromJsonb =
            jdbc.queryForObject(
                "SELECT body_json ->> 'name' FROM pipeline_versions WHERE pipeline_id = :id AND version = 1",
                mapOf("id" to record.id),
                String::class.java,
            )

        nameFromJsonb shouldBe "monthly_revenue"
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

    /**
     * A fresh `DataSource` on the container.
     *
     * `DriverManagerDataSource` hands out a new physical connection per `getConnection()`, so
     * two instances give the concurrency test two genuinely independent sessions — a shared
     * pooled connection would serialise the two inserts and prove nothing.
     */
    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private fun countRows(table: String): Int =
        checkNotNull(jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))

    private companion object {
        const val MIGRATION_PATH = "modules/app/src/main/resources/db/migration/V1__initial_schema.sql"

        /** Generous: the assertion is about the outcome, not about how fast Postgres is. */
        const val CONCURRENCY_TIMEOUT_SECONDS = 30L

        /** Matches deploy/docker-compose.dev.yml — the schema requires Postgres 16+ (§2). */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
