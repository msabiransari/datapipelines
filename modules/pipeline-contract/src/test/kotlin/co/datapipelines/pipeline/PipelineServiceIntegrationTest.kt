package co.datapipelines.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [PipelineService] against the real shipped schema — the aggregate's use cases end to end
 * (ARCH-AUDIT-2026-08 S5, ruling R6).
 *
 * The point of the round is that the REST controllers and the MCP tools stopped having their own
 * copies of these rules, so the rules are asserted HERE, once, where they now live. Web and MCP
 * keep only "the caller calls the service and maps the result", which is why those suites shrank.
 *
 * The container is the module's shared one ([SharedPostgres]), truncated per test — the same
 * discipline `PipelineRepositoryIntegrationTest` follows, and this suite deliberately reuses its
 * setup shape rather than inventing a second one.
 *
 * What is NOT asserted here, and why: transaction ROLLBACK. The annotations are inert without a
 * Spring proxy, and this suite constructs the service directly, so a rollback assertion made here
 * would be vacuous and — worse — would read as proof. `TransactionRollbackE2eTest` in
 * `tests/integration-tests` boots a real context and is the only place that claim is made.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PipelineServiceIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PipelineRepository
    private lateinit var service: PipelineService
    private lateinit var owner: UUID

    private val serializer = PipelineSerializer()

    /** Whatever the release gate is told about pinned template versions in a given test. */
    private var templateStatus: PipelineVersionStatus? = PipelineVersionStatus.RELEASED

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = PipelineRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name)" +
                " VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default')",
        )
        owner = insertUser()
        templateStatus = PipelineVersionStatus.RELEASED
        service = serviceWith(AuthoringGuard(true))
    }

    private fun serviceWith(authoring: AuthoringGuard): PipelineService {
        val validator = Fixtures.validator()
        return PipelineService(
            pipelines = repository,
            validator = validator,
            drafts = PipelineDraftService(repository, authoring),
            releases =
                PipelineReleaseService(
                    repository,
                    TemplateVersionStatuses { _, _, _ -> templateStatus },
                    validator,
                    authoring,
                ),
            authoring = authoring,
        )
    }

    // ---------------------------------------------------------------- D1: save validation, once

    @Test
    fun `create validates, canonicalizes and stores version 1 as a DRAFT with no released pointer`() {
        val saved = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)

        withClue("§3.2 as ruled by D55 — creation is authoring: v1 lands DRAFT, a human releases it") {
            saved.version?.status shouldBe PipelineVersionStatus.DRAFT
            saved.version?.version shouldBe 1
        }
        withClue("nothing is released yet, and the pointer says so instead of naming an unreviewed version") {
            saved.record.currentVersion.shouldBeNull()
        }
        saved.record.ownerId shouldBe owner
        withClue("the version detail is read back from the row, so it carries the database's own hash") {
            saved.version?.bodyHash?.isNotBlank() shouldBe true
        }
        withClue("the create's own row IS the draft, and the pointer to it is part of the answer") {
            saved.draft?.version shouldBe 1
            saved.draft?.bodyHash shouldBe saved.version?.bodyHash
        }
        withClue("it is executable at once — the working version is the draft (D56)") {
            service.workingVersion(WORKSPACE_ID, saved.record) shouldBe 1
        }
    }

    @Test
    fun `releasing the create's own draft is a first release - no prior release is assumed`() {
        // The release path's preconditions read the DRAFT (`expected_hash` against the draft row),
        // never a released row, so the very first release works exactly like any later one. Before
        // D55 this case could not arise: v1 was born released.
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)

        val released =
            service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)

        released.version.version shouldBe 1
        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 1
        service.findDraft(WORKSPACE_ID, created.record.id).shouldBeNull()
        withClue("and the released body is the one that was drafted") {
            released.bodyJson shouldContain "test/monthly_revenue"
        }
    }

    @Test
    fun `workingVersion is the draft when one exists, else the release, and null when there is neither`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)
        val fresh = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("a fresh create: the draft v1 is the only thing there is to run") {
            service.workingVersion(WORKSPACE_ID, fresh) shouldBe 1
        }

        service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)
        val releasedOnly = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("released, no draft: the release") { service.workingVersion(WORKSPACE_ID, releasedOnly) shouldBe 1 }

        val draft =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Edited")),
                checkNotNull(service.findCurrentVersion(WORKSPACE_ID, created.record.id)).bodyHash,
                owner,
            )
        val withDraft = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("release v1 + draft v2: the DRAFT wins — 'the last version' is what the pipeline IS") {
            service.workingVersion(WORKSPACE_ID, withDraft) shouldBe 2
            withDraft.currentVersion shouldBe 1
        }

        service.discard(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash)
        withClue("discarding the draft falls back to the release") {
            service.workingVersion(WORKSPACE_ID, checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))) shouldBe 1
        }
    }

    @Test
    fun `a pipeline whose only draft was discarded has no working version at all`() {
        // The one "nothing to run" state, and the only way to reach it: creation always writes v1,
        // so a version-less pipeline needs a discard of the sole draft of a never-released one.
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)

        service.discard(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash)

        val record = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("no draft, no release — the surfaces answer their version-not-found refusal") {
            service.workingVersion(WORKSPACE_ID, record).shouldBeNull()
            record.currentVersion.shouldBeNull()
        }
        repository.listVersions(WORKSPACE_ID, created.record.id).shouldBeEmptyList()
    }

    @Test
    fun `create refuses an invalid body before anything is written`() {
        // Two nodes with the same id — a §12 structural failure. The point is that the refusal
        // happens in the service, so REST and MCP cannot disagree about whether it happens.
        val duplicate = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "same"), Fixtures.node(id = "same")))

        shouldThrow<PipelineValidationException> { service.create(WORKSPACE_ID, body(duplicate), owner) }

        countRows("pipelines") shouldBe 0
    }

    @Test
    fun `create refuses when authoring is disabled, before validating`() {
        // versioning §5.5: a promotion receiver's sole writer is promotion. The guard is the
        // service's now — it used to be spelled once in the controller and once in the MCP tool.
        val receiver = serviceWith(AuthoringGuard(false))

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)
            }

        error.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        countRows("pipelines") shouldBe 0
    }

    @Test
    fun `where authoring is disabled no draft can arise, so the execute default is always a release`() {
        // The ruling's second half, held up by a MECHANISM rather than a promise: on a
        // non-development deployment every draft-creating write is refused, so "the last version"
        // is a RELEASED version by construction. The released row is seeded through the import
        // path — a receiver's only writer (versioning §5.5/§10).
        val body = Fixtures.pipeline()
        val record =
            repository.create(WORKSPACE_ID, NewPipeline.from(body, owner), serializer.write(body), owner, CreateLifecycle.RELEASED)
        val receiver = serviceWith(AuthoringGuard(false))

        withClue("create is refused, so a v1 DRAFT cannot be born here") {
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/nope")), owner)
            }.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        }
        withClue("and update is refused, so a draft cannot be opened over the release either") {
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.update(
                    WORKSPACE_ID,
                    record.id,
                    body(renamed("Edited on a receiver")),
                    checkNotNull(receiver.findCurrentVersion(WORKSPACE_ID, record.id)).bodyHash,
                    owner,
                )
            }.code shouldBe PipelineErrorCodes.Versioning.AUTHORING_DISABLED
        }

        withClue("so the working version — the execute default — is the release, by construction") {
            receiver.workingVersion(WORKSPACE_ID, record) shouldBe 1
            countRows("pipeline_versions WHERE status = 'DRAFT'") shouldBe 0
        }
    }

    // ----------------------------------------------------------------------- the draft lifecycle

    @Test
    fun `update opens a draft on the first write and overwrites it on the second`() {
        val created = createReleased()
        val hash = created.version.bodyHash

        val first = service.update(WORKSPACE_ID, created.record.id, body(renamed("First edit")), hash, owner)
        val firstDraft = checkNotNull(first.version)
        withClue("§5.1 — copy-on-write: the first write after a release opens the draft") {
            firstDraft.status shouldBe PipelineVersionStatus.DRAFT
            first.draft shouldNotBe null
        }

        val second =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Second edit")),
                firstDraft.bodyHash,
                owner,
            )

        withClue("§5.2 — later writes overwrite that same draft: one draft row, not a version per save") {
            second.version?.version shouldBe firstDraft.version
            repository.listVersions(WORKSPACE_ID, created.record.id).map { it.version } shouldContainExactly listOf(2, 1)
        }
        second.bodyJson shouldContain "Second edit"
    }

    @Test
    fun `an update whose body equals the released one is a no-op with no draft pointer`() {
        val created = createReleased()
        val hash = created.version.bodyHash

        val noop = service.update(WORKSPACE_ID, created.record.id, body(Fixtures.pipeline()), hash, owner)

        withClue("§5.1 — nothing was opened, so the answer must not paint a draft pointer onto it") {
            noop.version?.status shouldBe PipelineVersionStatus.RELEASED
            noop.draft.shouldBeNull()
        }
        service.findDraft(WORKSPACE_ID, created.record.id).shouldBeNull()
    }

    @Test
    fun `a stale hash is refused with the catalogued conflict`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                service.update(WORKSPACE_ID, created.record.id, body(renamed("x")), "not-the-hash", owner)
            }

        error.code shouldBe PipelineErrorCodes.Versioning.VERSION_CONFLICT
    }

    @Test
    fun `release locks the draft and moves current_version`() {
        val created = createReleased()
        val draft =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Ready")),
                created.version.bodyHash,
                owner,
            )

        val released =
            service.release(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash, owner)

        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 2
        service.findDraft(WORKSPACE_ID, created.record.id).shouldBeNull()
    }

    @Test
    fun `release refuses while a pinned template version is still a draft`() {
        // versioning §6 — templates lock first. The gate reads the TemplateVersionStatuses port,
        // which is how `pipeline-contract` asks `templates` a question without depending on it.
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)
        val draft =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Ready")),
                checkNotNull(created.version).bodyHash,
                owner,
            )
        templateStatus = PipelineVersionStatus.DRAFT

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                service.release(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash, owner)
            }

        error.code shouldBe PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NOT_RELEASED
        withClue("nothing was released: the draft is still there") {
            service.findDraft(WORKSPACE_ID, created.record.id) shouldNotBe null
        }
    }

    @Test
    fun `discard removes a never-executed draft`() {
        val created = createReleased()
        val draft =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Throwaway")),
                created.version.bodyHash,
                owner,
            )

        val outcome = service.discard(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash)

        outcome shouldBe PipelineReleaseService.Discarded.Deleted
        service.findDraft(WORKSPACE_ID, created.record.id).shouldBeNull()
        withClue("the version number returns to the pool — the draft row is gone, not flipped") {
            repository.listVersions(WORKSPACE_ID, created.record.id).map { it.version } shouldContainExactly listOf(1)
        }
    }

    // ---------------------------------------------------------------------------- reads and D2

    @Test
    fun `findWorking returns the draft when one exists, else the released version`() {
        val created = createReleased()

        withClue("no draft: the working version is the released one") {
            service.findWorking(WORKSPACE_ID, created.record.id)?.version?.status shouldBe PipelineVersionStatus.RELEASED
        }

        service.update(WORKSPACE_ID, created.record.id, body(renamed("Edited")), created.version.bodyHash, owner)

        val working = checkNotNull(service.findWorking(WORKSPACE_ID, created.record.id))
        withClue("versioning §7 — an authoring read must show the draft, or an editor rebases on stale content") {
            working.version.status shouldBe PipelineVersionStatus.DRAFT
            working.draft shouldNotBe null
            working.bodyJson shouldContain "Edited"
        }
    }

    @Test
    fun `a read of an unknown pipeline returns null rather than throwing`() {
        // The service does not own the 404: `ApiErrors.pipelineNotFound` on REST and
        // `McpNotFound.pipeline` on MCP are the same catalogued code in two carriers, and
        // choosing the carrier is the surface's job.
        service.findRecord(WORKSPACE_ID, UUID.randomUUID()).shouldBeNull()
        service.findWorking(WORKSPACE_ID, UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `the q filter matches name, display name and description, case-insensitively`() {
        // D2, the rule that had FOUR copies before 056 (REST list, MCP list, the UI list screen
        // and its HTMX partial). One implementation, asserted once.
        // Distinct display names and descriptions, because the three columns are matched
        // separately and a shared fixture value would make the assertions untestable.
        service.create(WORKSPACE_ID, body(named("test/monthly_revenue", "Monthly Revenue", "By customer")), owner)
        service.create(WORKSPACE_ID, body(named("test/daily_churn", "Daily Churn", "Cancellations per day")), owner)

        service.list(WORKSPACE_ID, query = "REVENUE").map { it.name } shouldContainExactly listOf("test/monthly_revenue")
        withClue("display_name is matched too, case-insensitively") {
            service.list(WORKSPACE_ID, query = "daily churn").map { it.name } shouldContainExactly listOf("test/daily_churn")
        }
        withClue("description is matched too") {
            service.list(WORKSPACE_ID, query = "CANCELLATIONS").map { it.name } shouldContainExactly listOf("test/daily_churn")
        }
        service.list(WORKSPACE_ID, query = "unrelated").shouldBeEmptyList()
        withClue("no query means no filtering") { service.list(WORKSPACE_ID).size shouldBe 2 }
    }

    @Test
    fun `the owner filter is pushed to SQL`() {
        val other = insertUser(email = "other@example.com", subject = "sub-2")
        service.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/mine")), owner)
        service.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/theirs")), other)

        service.list(WORKSPACE_ID, ownerId = other).map { it.name } shouldContainExactly listOf("test/theirs")
    }

    @Test
    fun `page reports the truthful total and the draft badges of the rows it returned`() {
        val ids = (1..PAGED_ROWS).map { service.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/p_$it")), owner) }
        service.update(
            WORKSPACE_ID,
            ids.first().record.id,
            body(renamed("Has a draft")),
            checkNotNull(ids.first().version).bodyHash,
            owner,
        )

        val page = service.page(WORKSPACE_ID, query = null, offset = 0, size = 2)

        page.items.size shouldBe 2
        withClue("034 E3 — a COUNT(*), not 'rows so far + 1 if more'") { page.total shouldBe PAGED_ROWS }
        page.hasMore shouldBe true
        withClue("badges cover the rows RETURNED, and only those") {
            page.drafts.keys.all { key -> key in page.items.map { it.id } } shouldBe true
        }
    }

    @Test
    fun `delete soft-deletes and the row stops resolving`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)

        service.delete(WORKSPACE_ID, created.record.id) shouldBe true

        service.findRecord(WORKSPACE_ID, created.record.id).shouldBeNull()
        withClue("§14 — the row stays, so the name stays taken; a second delete finds nothing live") {
            service.delete(WORKSPACE_ID, created.record.id) shouldBe false
            countRows("pipelines") shouldBe 1
        }
    }

    // ---------------------------------------------------------------------------- D6: execute

    @Test
    fun `findExecutable resolves the body and the parsed pipeline for a version`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner)
        val record = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))

        // D55: a create lands a DRAFT, so the version to resolve is the WORKING one —
        // `record.currentVersion` is null here, which is the point of the ruling.
        val working = checkNotNull(service.workingVersion(WORKSPACE_ID, record))
        val executable = checkNotNull(service.findExecutable(WORKSPACE_ID, record, working))

        executable.version shouldBe 1
        executable.pipeline.name shouldBe "test/monthly_revenue"
        withClue("an unknown version resolves to null — the surface owns the 404") {
            service.findExecutable(WORKSPACE_ID, record, UNKNOWN_VERSION).shouldBeNull()
        }
    }

    // -------------------------------------------------------------------------------- helpers

    /**
     * A pipeline with a RELEASED v1 and no draft — what `create` alone used to produce.
     *
     * Since D55 a create lands a DRAFT, so every case whose SUBJECT is a released pipeline
     * (copy-on-write, the no-op guard, releasing a SECOND version) has to release first, exactly
     * as a human would. Written as an explicit two-step rather than hidden behind a flag: the
     * release is the human action the ruling is about, and a fixture that elided it would be the
     * §3.2 assumption creeping back in through the test source.
     */
    private fun createReleased(pipeline: Pipeline = Fixtures.pipeline()): PipelineReleaseService.Released {
        val created = service.create(WORKSPACE_ID, body(pipeline), owner)
        return service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)
    }

    private fun body(pipeline: Pipeline): String = serializer.write(pipeline)

    private fun renamed(displayName: String): Pipeline = Fixtures.pipeline().copy(displayName = displayName)

    private fun named(
        name: String,
        displayName: String,
        description: String,
    ): Pipeline = Fixtures.pipeline(name = name).copy(displayName = displayName, description = description)

    private fun <T> List<T>.shouldBeEmptyList() = withClue("expected no matches") { isEmpty() shouldBe true }

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

    private fun countRows(table: String): Int =
        checkNotNull(jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))

    private companion object {
        /** The V4-seeded `default` workspace, re-inserted after every truncate. */
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        const val PAGED_ROWS = 3
        const val UNKNOWN_VERSION = 99
    }
}
