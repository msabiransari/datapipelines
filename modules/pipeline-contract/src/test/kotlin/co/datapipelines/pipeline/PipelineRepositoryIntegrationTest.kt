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
import java.time.Instant
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
 * So the test reads `app`'s real migrations off disk and executes them in version order. They
 * are the same files the application migrates with: change the DDL and this test runs the new
 * DDL, or fails because a file moved. What it deliberately does *not* prove is that Flyway can
 * apply them (checksums, transactional wrapping, the advisory lock) — that belongs to `app`'s
 * own suite, where the dependency lives.
 *
 * ## Container lifetime
 *
 * One container for the class, truncated before each test. Per the shared-test-container
 * discipline the project already follows: each spec cleans the tables it touches, in FK
 * order, rather than dropping the schema.
 *
 * `LargeClass` is suppressed: this suite is the repository's contract in one place — CRUD,
 * concurrency, and the version lifecycle (V6) read against the SAME shipped schema and
 * fixtures; splitting it would scatter one table's invariants across files, the exact
 * argument `PipelineRepository` itself makes.
 */
@Suppress("LargeClass")
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
        MIGRATION_PATHS.forEach { jdbc.jdbcTemplate.execute(Fixtures.repoFile(it).readText()) }
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        // pipeline_versions cascades from pipelines; users is the FK parent of both.
        // The CASCADE also reaches workspaces (created_by), so the V4-seeded `default`
        // workspace the repository pins is re-seeded after every truncate.
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        owner = insertUser()
    }

    @Test
    fun `create inserts the pipeline and version 1 together, returning what the database stored`() {
        val body = Fixtures.pipeline()

        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        record.currentVersion shouldBe 1
        record.name shouldBe "monthly_revenue"
        record.isDeleted shouldBe false
        // Server-generated, not asserted from a hand-built object (metadata-db §6.1).
        record.createdAt shouldNotBe null
        repository.findVersionBody(WORKSPACE_ID, record.id, 1) shouldNotBe null
    }

    @Test
    fun `the stored body round-trips through the deserializer`() {
        val body = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        val stored = PipelineDeserializer().readOrThrow(checkNotNull(repository.findLatestBody(WORKSPACE_ID, record.id)))

        stored shouldBe body
    }

    @Test
    fun `appendReleasedVersion appends a new RELEASED version and bumps current_version`() {
        // The version-less import path (versioning §9.2: allocate-next-local). The PUT path
        // goes through createDraft/writeDraft — see the lifecycle tests below.
        val v1 = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(v1, owner), serializer.write(v1), owner)
        val v2 =
            v1.copy(
                displayName = "Monthly Revenue v2",
                nodes = v1.nodes + Fixtures.node(id = "extra", output = NodeOutput.Tempdb("stg_extra")),
            )

        val updated = checkNotNull(repository.appendReleasedVersion(WORKSPACE_ID, record.id, v2, serializer.write(v2), owner))

        updated.currentVersion shouldBe 2
        updated.displayName shouldBe "Monthly Revenue v2"
        repository.listVersions(WORKSPACE_ID, record.id).map { it.version } shouldContainExactly listOf(2, 1)
        repository.listVersions(WORKSPACE_ID, record.id).map { it.status } shouldContainExactly
            listOf(PipelineVersionStatus.RELEASED, PipelineVersionStatus.RELEASED)
        // Version 1's body is untouched — RELEASED rows are never UPDATEd (§3.1).
        PipelineDeserializer().readOrThrow(checkNotNull(repository.findVersionBody(WORKSPACE_ID, record.id, 1))) shouldBe v1
        PipelineDeserializer().readOrThrow(checkNotNull(repository.findVersionBody(WORKSPACE_ID, record.id, 2))) shouldBe v2
    }

    @Test
    fun `appendReleasedVersion sets updated_at, because this schema has no triggers`() {
        // metadata-db §2: every UPDATE on pipelines sets updated_at in its own SET clause.
        val body = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        val updated = checkNotNull(repository.appendReleasedVersion(WORKSPACE_ID, record.id, body, serializer.write(body), owner))

        // Strictly greater, not `>=`. A repository method that forgot `updated_at = NOW()`
        // leaves the INSERT default in place, which satisfies `>=` — so the old assertion could
        // not fail, and the rule metadata-db §2 calls "a bug in the repository method" was
        // untested. NOW() is transaction-start time and these are two separate statements.
        (updated.updatedAt > record.updatedAt) shouldBe true
        updated.createdAt shouldBe record.createdAt
    }

    @Test
    fun `appendReleasedVersion on an unknown id returns null and writes nothing`() {
        val body = Fixtures.pipeline()

        repository.appendReleasedVersion(WORKSPACE_ID, UUID.randomUUID(), body, serializer.write(body), owner).shouldBeNull()

        countRows("pipeline_versions") shouldBe 0
    }

    @Test
    fun `findById and findByName both skip soft-deleted pipelines`() {
        val body = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        repository.findById(WORKSPACE_ID, record.id) shouldBe record
        repository.findByName(WORKSPACE_ID, "monthly_revenue") shouldBe record

        repository.softDelete(WORKSPACE_ID, record.id) shouldBe true

        repository.findById(WORKSPACE_ID, record.id).shouldBeNull()
        repository.findByName(WORKSPACE_ID, "monthly_revenue").shouldBeNull()
        // A second delete finds nothing live to delete.
        repository.softDelete(WORKSPACE_ID, record.id) shouldBe false
        // The row survives, so the name stays taken (metadata-db §4.4).
        countRows("pipelines") shouldBe 1
    }

    @Test
    fun `findAll filters by owner and lists live pipelines only`() {
        val other = insertUser(email = "other@example.com", subject = "sub-2")
        val a = Fixtures.pipeline(name = "pipeline_a")
        val b = Fixtures.pipeline(name = "pipeline_b")
        val c = Fixtures.pipeline(name = "pipeline_c")
        repository.create(WORKSPACE_ID, NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(b, other), serializer.write(b), other)
        val deleted = repository.create(WORKSPACE_ID, NewPipeline.from(c, owner), serializer.write(c), owner)
        repository.softDelete(WORKSPACE_ID, deleted.id)

        repository.findAll(WORKSPACE_ID).map { it.name } shouldContainExactly listOf("pipeline_b", "pipeline_a")
        repository.findAll(WORKSPACE_ID, owner).map { it.name } shouldContainExactly listOf("pipeline_a")
    }

    @Test
    fun `findAll paginates with limit and offset at the SQL level`() {
        val a = Fixtures.pipeline(name = "pipeline_a")
        val b = Fixtures.pipeline(name = "pipeline_b")
        val c = Fixtures.pipeline(name = "pipeline_c")
        repository.create(WORKSPACE_ID, NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(b, owner), serializer.write(b), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(c, owner), serializer.write(c), owner)

        repository.findAll(WORKSPACE_ID, null, limit = 2, offset = 0).map { it.name } shouldContainExactly
            listOf("pipeline_c", "pipeline_b")
        repository.findAll(WORKSPACE_ID, null, limit = 2, offset = 2).map { it.name } shouldContainExactly listOf("pipeline_a")
        repository.findAll(WORKSPACE_ID, null, limit = 1, offset = 1).map { it.name } shouldContainExactly listOf("pipeline_b")
        repository.findAll(WORKSPACE_ID, null, limit = 5, offset = 0).size shouldBe 3
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
        repository.create(WORKSPACE_ID, NewPipeline.from(bodyPg, owner), serializer.write(bodyPg), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(bodyMysql, owner), serializer.write(bodyMysql), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(bodyOutput, owner), serializer.write(bodyOutput), owner)

        repository.findAllByDatasource(WORKSPACE_ID, "pg-prod").map { it.name } shouldContainExactly listOf("pg_pipeline")
        repository.findAllByDatasource(WORKSPACE_ID, "mysql-prod").map { it.name } shouldContainExactly listOf("mysql_pipeline")
        repository.findAllByDatasource(WORKSPACE_ID, "pg-warehouse").map { it.name } shouldContainExactly listOf("output_ds")
        repository.findAllByDatasource(WORKSPACE_ID, "nonexistent") shouldContainExactly emptyList()
    }

    @Test
    fun `findAllByDatasource respects limit and offset`() {
        val a = Fixtures.pipeline(name = "a", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val b = Fixtures.pipeline(name = "b", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val c = Fixtures.pipeline(name = "c", nodes = listOf(Fixtures.node(source = "pg-prod")))
        repository.create(WORKSPACE_ID, NewPipeline.from(a, owner), serializer.write(a), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(b, owner), serializer.write(b), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(c, owner), serializer.write(c), owner)

        repository.findAllByDatasource(WORKSPACE_ID, "pg-prod", limit = 2, offset = 0).map { it.name } shouldContainExactly listOf("c", "b")
        repository.findAllByDatasource(WORKSPACE_ID, "pg-prod", limit = 2, offset = 2).map { it.name } shouldContainExactly listOf("a")
    }

    @Test
    fun `findAllByDatasource with owner filter scopes to that owner`() {
        val other = insertUser(email = "other@example.com", subject = "sub-2")
        val mine = Fixtures.pipeline(name = "mine", nodes = listOf(Fixtures.node(source = "pg-prod")))
        val theirs = Fixtures.pipeline(name = "theirs", nodes = listOf(Fixtures.node(source = "pg-prod")))
        repository.create(WORKSPACE_ID, NewPipeline.from(mine, owner), serializer.write(mine), owner)
        repository.create(WORKSPACE_ID, NewPipeline.from(theirs, other), serializer.write(theirs), other)

        repository.findAllByDatasource(WORKSPACE_ID, "pg-prod", ownerId = owner).map { it.name } shouldContainExactly listOf("mine")
        repository.findAllByDatasource(WORKSPACE_ID, "pg-prod").map { it.name } shouldContainExactly listOf("theirs", "mine")
    }

    @Test
    fun `countAll returns the number of live pipelines`() {
        repository.countAll(WORKSPACE_ID) shouldBe 0

        repository.create(
            WORKSPACE_ID,
            NewPipeline.from(Fixtures.pipeline(name = "a"), owner),
            serializer.write(Fixtures.pipeline(name = "a")),
            owner,
        )
        repository.countAll(WORKSPACE_ID) shouldBe 1

        repository.create(
            WORKSPACE_ID,
            NewPipeline.from(Fixtures.pipeline(name = "b"), owner),
            serializer.write(Fixtures.pipeline(name = "b")),
            owner,
        )
        repository.countAll(WORKSPACE_ID) shouldBe 2

        val bodyC = Fixtures.pipeline(name = "c")
        val deleted = repository.create(WORKSPACE_ID, NewPipeline.from(bodyC, owner), serializer.write(bodyC), owner)
        repository.softDelete(WORKSPACE_ID, deleted.id)
        repository.countAll(WORKSPACE_ID) shouldBe 2
    }

    @Test
    fun `an unknown version body reads as null, not as an exception`() {
        val body = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        repository.findVersionBody(WORKSPACE_ID, record.id, 99).shouldBeNull()
        repository.findLatestBody(WORKSPACE_ID, UUID.randomUUID()).shouldBeNull()
        repository.listVersions(WORKSPACE_ID, UUID.randomUUID()) shouldContainExactly emptyList()
    }

    @Test
    fun `creating a second pipeline with a taken name raises duplicate_name`() {
        val body = Fixtures.pipeline()
        repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)
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
        repository.create(WORKSPACE_ID, NewPipeline.from(taken, owner), serializer.write(taken), owner)
        val mine = Fixtures.pipeline(name = "mine")
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(mine, owner), serializer.write(mine), owner)
        val renamed = mine.copy(name = "already_taken")

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.appendReleasedVersion(WORKSPACE_ID, record.id, renamed, serializer.write(renamed), owner)
            }

        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
        // Atomicity, asserted on the writes and not only on the counter: the failed UPDATE must
        // leave no version row behind. `current_version` alone would still read 1 if the CTE had
        // inserted an orphan version and only the pipelines UPDATE rolled back.
        repository.findById(WORKSPACE_ID, record.id)?.currentVersion shouldBe 1
        repository.listVersions(WORKSPACE_ID, record.id).size shouldBe 1
        countRows("pipeline_versions") shouldBe 2
    }

    @Test
    fun `a soft-deleted pipeline's name stays taken - uniqueness is global`() {
        // V1 declares `name TEXT NOT NULL UNIQUE` — a plain constraint, not a partial index on
        // `is_deleted = FALSE`. §12.1 and metadata-db §4.4 now agree that this is deliberate:
        // execution history references the name, so a deleted pipeline's name is not reusable
        // until the row is hard-deleted.
        val body = Fixtures.pipeline()
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)
        repository.softDelete(WORKSPACE_ID, record.id) shouldBe true

        val thrown =
            shouldThrow<DatapipelinesException> {
                repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)
            }

        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME
    }

    @Test
    fun `a primary-key collision is NOT reported as a duplicate name`() {
        // §11.3's import path re-uses a pipeline id deliberately. Mapping every duplicate-key
        // violation to duplicate_name would tell the caller to rename something that is fine.
        val body = Fixtures.pipeline()
        val id = UUID.randomUUID()
        repository.create(WORKSPACE_ID, NewPipeline.from(body, owner, id), serializer.write(body), owner)
        val sameIdDifferentName = NewPipeline.from(Fixtures.pipeline(name = "other_name"), owner, id)

        val thrown =
            shouldThrow<DuplicateKeyException> {
                repository.create(WORKSPACE_ID, sameIdDifferentName, serializer.write(body), owner)
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
                        runCatching { isolated.create(WORKSPACE_ID, NewPipeline.from(body, owner), bodyJson, owner) }
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
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)

        val nameFromJsonb =
            jdbc.queryForObject(
                "SELECT body_json ->> 'name' FROM pipeline_versions WHERE pipeline_id = :id AND version = 1",
                mapOf("id" to record.id),
                String::class.java,
            )

        nameFromJsonb shouldBe "monthly_revenue"
    }

    // =============================================================================================
    // The draft/release lifecycle (versioning §3–§5, V6) — the §13 floor.
    // =============================================================================================

    /** Creates v1 and returns (record, v1 body, v1's stored detail) — the base every lifecycle test starts from. */
    private fun createdPipeline(name: String = "monthly_revenue"): Triple<PipelineRecord, Pipeline, PipelineVersionDetail> {
        val body = Fixtures.pipeline(name = name)
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner)
        val detail = checkNotNull(repository.findCurrentVersionDetail(WORKSPACE_ID, record.id))
        return Triple(record, body, detail)
    }

    @Test
    fun `createDraft copies the released version to a draft and leaves the released row untouched`() {
        val (record, v1, v1Detail) = createdPipeline()
        val v2 = v1.copy(displayName = "Draft name")

        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v2), v1Detail.bodyHash, owner))

        draft.version shouldBe 2
        draft.status shouldBe PipelineVersionStatus.DRAFT
        // The pre-allocated number and the DB-computed hash are what the protocol echoes back.
        draft.bodyHash shouldNotBe v1Detail.bodyHash
        draft.updatedBy shouldBe owner
        draft.updatedAt shouldNotBe null
        // §3.4: current_version does NOT move while a draft exists.
        repository.findById(WORKSPACE_ID, record.id)?.currentVersion shouldBe 1
        // §3.1: the RELEASED row is never UPDATEd — its lifecycle fields are untouched.
        val releasedAfter = checkNotNull(repository.findVersionDetail(WORKSPACE_ID, record.id, 1))
        releasedAfter.bodyHash shouldBe v1Detail.bodyHash
        releasedAfter.updatedBy shouldBe null
        releasedAfter.updatedAt shouldBe null
        countRows("pipeline_versions") shouldBe 2
    }

    @Test
    fun `a no-op createDraft - body identical to released - creates no draft and returns the RELEASED state`() {
        val (record, v1, v1Detail) = createdPipeline()

        val noop = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1), v1Detail.bodyHash, owner))

        // The no-op signal (versioning §5.1): the current RELEASED state, not a draft.
        noop.version shouldBe 1
        noop.status shouldBe PipelineVersionStatus.RELEASED
        noop.bodyHash shouldBe v1Detail.bodyHash
        // No draft row, no burned number: the table still holds exactly v1, and the next
        // REAL change allocates v2 — the number was never consumed by the no-op.
        countRows("pipeline_versions") shouldBe 1
        repository.findDraftDetail(WORKSPACE_ID, record.id) shouldBe null
        val v2 = v1.copy(displayName = "A real change")
        checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v2), v1Detail.bodyHash, owner)).version shouldBe 2
    }

    @Test
    fun `a no-op createDraft with a stale hash still writes nothing - the no-op arm carries the guard`() {
        val (record, v1, v1Detail) = createdPipeline()

        // Identical body but a wrong precondition: D3 (no last-write-wins) outranks
        // tidiness — the write must not be absorbed as a no-op through the stale path.
        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1), "deadbeef", owner).shouldBeNull()
        countRows("pipeline_versions") shouldBe 1
        repository.findById(WORKSPACE_ID, record.id)?.currentVersion shouldBe 1
        v1Detail.bodyHash shouldNotBe "deadbeef"
    }

    @Test
    fun `identical content while a draft exists is a stale base, not a no-op`() {
        val (record, v1, v1Detail) = createdPipeline()
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "someone's draft")), v1Detail.bodyHash, owner))

        // The caller based on the released row and PUT the released body back unchanged
        // while a draft exists: the truthful answer is 409-with-the-draft's-state (null
        // here), never "RELEASED, no draft" — the draft owns the working state.
        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1), v1Detail.bodyHash, owner).shouldBeNull()
        checkNotNull(repository.findDraftDetail(WORKSPACE_ID, record.id)).bodyHash shouldBe draft.bodyHash
    }

    @Test
    fun `a draft edited back to its released parent is left alone - never auto-discarded`() {
        val (record, v1, v1Detail) = createdPipeline()
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner))

        // Reverting the draft's content to exactly the released body: the draft row STAYS
        // (written in place, §5.2) — silently deleting it would destroy its number and
        // updated_by history. Discard is the explicit path.
        val reverted = checkNotNull(repository.writeDraft(WORKSPACE_ID, record.id, serializer.write(v1), draft.bodyHash, owner))

        reverted.status shouldBe PipelineVersionStatus.DRAFT
        checkNotNull(repository.findDraftDetail(WORKSPACE_ID, record.id)).version shouldBe draft.version
    }

    @Test
    fun `writeDraft overwrites the draft in place - no new row`() {
        val (record, v1, v1Detail) = createdPipeline()
        val draft =
            checkNotNull(
                repository.createDraft(
                    WORKSPACE_ID,
                    record.id,
                    serializer.write(v1.copy(description = "first")),
                    v1Detail.bodyHash,
                    owner,
                ),
            )

        val rewritten =
            checkNotNull(
                repository.writeDraft(
                    WORKSPACE_ID,
                    record.id,
                    serializer.write(v1.copy(description = "second")),
                    draft.bodyHash,
                    owner,
                ),
            )

        rewritten.version shouldBe draft.version
        countRows("pipeline_versions") shouldBe 2
        val bodyAfter =
            PipelineDeserializer().readOrThrow(
                checkNotNull(repository.findVersionBody(WORKSPACE_ID, record.id, 2)),
            )
        bodyAfter.description shouldBe "second"
    }

    @Test
    fun `every mutation with a stale hash writes nothing - the precondition is load-bearing`() {
        val (record, v1, v1Detail) = createdPipeline()
        // The draft body must DIFFER from the released one — since the no-op rule (§5.1)
        // an identical body would not open a draft at all.
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner))
        val stale = "deadbeef"

        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1), stale, owner).shouldBeNull()
        repository.writeDraft(WORKSPACE_ID, record.id, serializer.write(v1), stale, owner).shouldBeNull()
        repository.releaseDraft(WORKSPACE_ID, record.id, v1.name, v1.displayName, v1.description, stale, owner).shouldBeNull()
        repository.discardDraft(WORKSPACE_ID, record.id, stale).shouldBeNull()

        countRows("pipeline_versions") shouldBe 2
        repository.findById(WORKSPACE_ID, record.id)?.currentVersion shouldBe 1
        checkNotNull(repository.findDraftDetail(WORKSPACE_ID, record.id)).bodyHash shouldBe draft.bodyHash
    }

    @Test
    fun `releaseDraft flips the draft, bumps the pointer, and rides the metadata`() {
        val (record, v1, v1Detail) = createdPipeline()
        val v2 = v1.copy(name = "monthly_revenue", displayName = "The Draft Name", description = "The draft description")
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v2), v1Detail.bodyHash, owner))

        val released =
            checkNotNull(
                repository.releaseDraft(
                    WORKSPACE_ID,
                    record.id,
                    v2.name,
                    v2.displayName,
                    v2.description,
                    draft.bodyHash,
                    owner,
                ),
            )

        released.version.version shouldBe 2
        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 2
        // §3.5: metadata rides the release — the index row adopts the released body's values.
        released.record.displayName shouldBe "The Draft Name"
        released.record.description shouldBe "The draft description"
        // §8: released_at is DB-generated and now set, by the actor who released.
        released.version.releasedAt shouldNotBe null
        released.version.releasedBy shouldBe owner
        repository.findDraftDetail(WORKSPACE_ID, record.id) shouldBe null
        repository.listVersions(WORKSPACE_ID, record.id).map { it.status } shouldContainExactly
            listOf(PipelineVersionStatus.RELEASED, PipelineVersionStatus.RELEASED)
    }

    @Test
    fun `discarding a never-executed draft hard-deletes it and returns the number to the pool`() {
        val (record, v1, v1Detail) = createdPipeline()
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner))

        repository.discardDraft(WORKSPACE_ID, record.id, draft.bodyHash) shouldBe DiscardOutcome.Deleted

        countRows("pipeline_versions") shouldBe 1
        // The number returns to the pool: a new (genuinely different) draft re-allocates v2.
        checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft again")), v1Detail.bodyHash, owner)).version shouldBe 2
    }

    @Test
    fun `discarding an executed draft flips it to DISCARDED - the FK blocks the delete`() {
        val (record, v1, v1Detail) = createdPipeline()
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner))
        insertExecution(record.id, draft.version)

        val outcome = repository.discardDraft(WORKSPACE_ID, record.id, draft.bodyHash)

        val flipped = outcome.shouldBeInstanceOf<DiscardOutcome.FlippedToDiscarded>()
        flipped.detail.status shouldBe PipelineVersionStatus.DISCARDED
        flipped.detail.version shouldBe 2
        countRows("pipeline_versions") shouldBe 2
        // §3.4: the number stays consumed — a new draft allocates v3, never v2 again.
        checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft three")), v1Detail.bodyHash, owner)).version shouldBe 3
    }

    @Test
    fun `a second DRAFT row violates the one-draft partial unique index`() {
        val (record, v1, v1Detail) = createdPipeline()
        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner)

        // Raw SQL, bypassing the repository's guard entirely: the INDEX is the authority.
        val thrown =
            shouldThrow<DuplicateKeyException> {
                jdbc.update(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by)
                    VALUES (:id, 99, CAST('{}' AS jsonb), 'x', 'DRAFT', :actor)
                    """.trimIndent(),
                    mapOf("id" to record.id, "actor" to owner),
                )
            }
        thrown.mostSpecificCause.message?.contains("uq_pipeline_versions_one_draft") shouldBe true
    }

    @Test
    fun `two concurrent first-writes - exactly one draft, the loser carries the winner's hash`() {
        // B1: the partial unique index is what makes copy-on-write race-safe. Two writers that
        // both saw "released" both insert; one wins; the loser's violation surfaces as
        // pipeline.version.conflict pointing at the WINNER's draft hash (§3.3).
        val (record, v1, v1Detail) = createdPipeline()
        // DIFFERS from the released body: since the no-op rule (§5.1) an identical body
        // would suppress both inserts and prove nothing about the race.
        val bodyJson = serializer.write(v1.copy(description = "concurrent draft"))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            // Each attempt records BOTH outcomes: the detail on success, the error on failure.
            // A loser is legal in two shapes, both 409 material at the service layer: the
            // index/PK violation (true interleaving — both computed v2 from one snapshot) or
            // the suppressed insert (serialized — it saw the winner's draft, wrote nothing).
            data class Attempt(
                val detail: PipelineVersionDetail?,
                val error: Throwable?,
            )

            val attempts =
                (1..2).map {
                    val isolated = PipelineRepository(NamedParameterJdbcTemplate(dataSource()))
                    pool.submit<Attempt> {
                        start.await()
                        var detail: PipelineVersionDetail? = null
                        var error: Throwable? = null
                        try {
                            detail = isolated.createDraft(WORKSPACE_ID, record.id, bodyJson, v1Detail.bodyHash, owner)
                        } catch (t: Throwable) {
                            error = t
                        }
                        Attempt(detail, error)
                    }
                }
            start.countDown()
            val outcomes = attempts.map { it.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            // Exactly one winner; exactly one draft; the released row untouched.
            outcomes.count { it.detail != null } shouldBe 1
            val winnerDetail = outcomes.single { it.detail != null }.detail
            val loser = outcomes.single { it.detail == null }
            if (loser.error != null) {
                val conflict = loser.error.shouldBeInstanceOf<DatapipelinesException>()
                conflict.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
                conflict.details["current_body_hash"] shouldBe winnerDetail?.bodyHash
                conflict.details["current_status"] shouldBe "DRAFT"
            } else {
                // Serialized loser: the guard passed but the NOT EXISTS suppressed the insert —
                // null return, zero rows written, which the service maps to the same 409.
                countRows("pipeline_versions") shouldBe 2
            }
            val liveDraft = checkNotNull(repository.findDraftDetail(WORKSPACE_ID, record.id))
            liveDraft.bodyHash shouldBe winnerDetail?.bodyHash
            liveDraft.version shouldBe 2
            countRows("pipeline_versions") shouldBe 2
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the draft service takes the write branch, checks names early, and maps stale bases to conflicts`() {
        val service = PipelineDraftService(repository)
        val other = Fixtures.pipeline(name = "other_name")
        repository.create(WORKSPACE_ID, NewPipeline.from(other, owner), serializer.write(other), owner)
        val (record, v1, v1Detail) = createdPipeline()

        // First write: copy-on-write branch (§5.1) with the released row's hash.
        val draftOne = v1.copy(description = "draft one")
        val first =
            service.write(
                WORKSPACE_ID,
                record.id,
                draftOne,
                serializer.write(draftOne),
                v1Detail.bodyHash,
                owner,
            )
        first.version.status shouldBe PipelineVersionStatus.DRAFT
        first.version.version shouldBe 2

        // Second write: in-place branch (§5.2) with the DRAFT's hash.
        val draftTwo = v1.copy(description = "draft two")
        val second =
            service.write(
                WORKSPACE_ID,
                record.id,
                draftTwo,
                serializer.write(draftTwo),
                first.version.bodyHash,
                owner,
            )
        second.version.version shouldBe 2
        countRows("pipeline_versions") shouldBe 3 // v1 + one draft… + the other pipeline's v1

        // §3.5: a draft rename onto a taken name fails AT WRITE TIME, not at release.
        val renamed = v1.copy(name = "other_name")
        val thrown =
            shouldThrow<DatapipelinesException> {
                service.write(WORKSPACE_ID, record.id, renamed, serializer.write(renamed), second.version.bodyHash, owner)
            }
        thrown.code shouldBe PipelineErrorCodes.Validation.DUPLICATE_NAME

        // A stale base is a 409 carrying the current draft's state (§4.2).
        val stale =
            shouldThrow<DatapipelinesException> {
                service.write(WORKSPACE_ID, record.id, v1, serializer.write(v1), v1Detail.bodyHash, owner)
            }
        stale.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
        stale.details["current_body_hash"] shouldBe second.version.bodyHash
        stale.details["current_status"] shouldBe "DRAFT"

        // Unknown pipeline: not-found, not a conflict.
        val notFound =
            shouldThrow<DatapipelinesException> {
                service.write(WORKSPACE_ID, UUID.randomUUID(), v1, serializer.write(v1), v1Detail.bodyHash, owner)
            }
        notFound.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `the draft service answers a no-op write with the current released state`() {
        val service = PipelineDraftService(repository)
        val (record, v1, v1Detail) = createdPipeline()

        val written = service.write(WORKSPACE_ID, record.id, v1, serializer.write(v1), v1Detail.bodyHash, owner)

        // §5.1's no-op: RELEASED state, no draft, the STORED body — nothing burned.
        written.version.status shouldBe PipelineVersionStatus.RELEASED
        written.version.version shouldBe 1
        written.bodyJson shouldBe checkNotNull(repository.findVersionBody(WORKSPACE_ID, record.id, 1))
        repository.findDraftDetail(WORKSPACE_ID, record.id) shouldBe null
    }

    @Test
    fun `computeBodyHash agrees with the stored hash of a written row`() {
        // The one-expression rule, mechanically: what the repository would hash a body AS is
        // what the database stored for that body.
        val (record, v1, v1Detail) = createdPipeline()
        v1Detail.bodyHash shouldBe repository.computeBodyHash(serializer.write(v1))
        val draft = checkNotNull(repository.createDraft(WORKSPACE_ID, record.id, serializer.write(v1.copy(description = "draft")), v1Detail.bodyHash, owner))
        draft.bodyHash shouldBe repository.computeBodyHash(serializer.write(v1.copy(description = "draft")))
    }

    @Test
    fun `importPipelineVersion creates a new pipeline at the exact source version`() {
        // §9.2 row 1: absent target — insert as RELEASED at the payload's number, released_at
        // from source, current_version following it. The id is the cross-env identity (D5).
        val body = Fixtures.pipeline(name = "promoted_pipeline")
        val record =
            repository.importPipelineVersion(
                WORKSPACE_ID,
                NewPipeline.from(body, owner, id = UUID.randomUUID()),
                version = 3,
                bodyJson = serializer.write(body),
                bodyHash = "declared-hash",
                releasedAt = Instant.parse("2026-08-31T14:03:11Z"),
                actor = owner,
            )

        record.currentVersion shouldBe 3
        repository.listVersions(WORKSPACE_ID, record.id).map { it.version } shouldContainExactly listOf(3)
        val detail = checkNotNull(repository.findVersionDetail(WORKSPACE_ID, record.id, 3))
        detail.status shouldBe PipelineVersionStatus.RELEASED
        detail.bodyHash shouldBe "declared-hash"
        detail.releasedAt shouldBe Instant.parse("2026-08-31T14:03:11Z")
        detail.releasedBy shouldBe owner
    }

    @Test
    fun `insertReleasedVersion fills gaps and rides metadata only when newest`() {
        // §9.2 rows on an EXISTING pipeline. The true gap shape: the target sits at v3
        // (a preserved import that jumped), and a v2 arriving afterwards must NOT move
        // current_version or the index metadata — "gaps below current_version are expected
        // and harmless" (§9.2).
        val v3 = Fixtures.pipeline(name = "target_pipeline")
        val record =
            repository.importPipelineVersion(
                WORKSPACE_ID,
                NewPipeline.from(v3, owner),
                version = 3,
                bodyJson = serializer.write(v3),
                bodyHash = "hash-v3",
                releasedAt = Instant.EPOCH,
                actor = owner,
            )
        record.currentVersion shouldBe 3

        val v2 = v3.copy(description = "v2 body")
        repository.insertReleasedVersion(
            WORKSPACE_ID,
            record.id,
            2,
            v2.name,
            v2.displayName,
            v2.description,
            serializer.write(v2),
            "hash-v2",
            Instant.EPOCH,
            owner,
        )
        repository.findById(WORKSPACE_ID, record.id)?.currentVersion shouldBe 3
        repository.findById(WORKSPACE_ID, record.id)?.description shouldBe v3.description

        // A same-number re-import writes nothing and answers null (the sequential case);
        // the caller re-reads and classifies per §9.2's conflict rows.
        val reinserted =
            repository.insertReleasedVersion(
                WORKSPACE_ID,
                record.id,
                3,
                v2.name,
                v2.displayName,
                v2.description,
                serializer.write(v3),
                "hash-v3",
                Instant.EPOCH,
                owner,
            )
        reinserted.shouldBeNull()
        val versionsAfter = repository.listVersions(WORKSPACE_ID, record.id).map { it.version }
        versionsAfter shouldContainExactly listOf(3, 2)
    }

    @Test
    fun `findDrafts returns the badge map and releasedAtFor returns both stamps and gaps`() {
        val withDraft = Fixtures.pipeline(name = "has_draft")
        val record = repository.create(WORKSPACE_ID, NewPipeline.from(withDraft, owner), serializer.write(withDraft), owner)
        val releasedHash = checkNotNull(repository.findCurrentVersionDetail(WORKSPACE_ID, record.id)).bodyHash
        repository.createDraft(WORKSPACE_ID, record.id, serializer.write(withDraft.copy(description = "draft")), releasedHash, owner)
        val plain = Fixtures.pipeline(name = "no_draft")
        val plainRecord = repository.create(WORKSPACE_ID, NewPipeline.from(plain, owner), serializer.write(plain), owner)

        val drafts = repository.findDrafts(WORKSPACE_ID, listOf(record.id, plainRecord.id))
        drafts.keys shouldContainExactly setOf(record.id)
        drafts[record.id]?.version shouldBe 2

        // §8's derivation input: released_at present for the released v1, absent for the draft v2.
        val stamps =
            repository.releasedAtFor(
                WORKSPACE_ID,
                listOf(record.id to 1, record.id to 2, plainRecord.id to 1),
            )
        stamps[record.id to 1] shouldNotBe null
        stamps[record.id to 2] shouldBe null
        stamps[plainRecord.id to 1] shouldNotBe null
        val emptyStamps = repository.releasedAtFor(WORKSPACE_ID, emptyList())
        emptyStamps shouldBe emptyMap()
    }

    /** A minimal execution row referencing (pipeline_id, version) — the FK that makes discard a flip. */
    private fun insertExecution(
        pipelineId: UUID,
        version: Int,
    ): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO pipeline_executions
                    (pipeline_id, pipeline_version, status, triggered_by, triggered_via, root_execution_id)
                VALUES (:pipelineId, :version, 'SUCCESS', :actor, 'REST', gen_random_uuid())
                RETURNING execution_id
                """.trimIndent(),
                mapOf("pipelineId" to pipelineId, "version" to version, "actor" to owner),
                UUID::class.java,
            ),
        )

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
        /**
         * The V4-seeded `default` workspace — setUp re-inserts this row after every truncate,
         * so every repository call below is scoped to it.
         */
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /**
         * The shipped migrations in version order — DERIVED from the migration directory
         * (`ShippedMigrations`), never hand-copied: this list said "V1–V4" for two migrations
         * after V4 landed (035/H), and a hand-maintained copy re-acquires exactly that drift
         * the moment the next migration ships.
         */
        val MIGRATION_PATHS: List<String> = ShippedMigrations.paths()

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
