package co.datapipelines.pipeline

import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

    private fun serviceWith(
        authoring: AuthoringGuard,
        draftTemplates: ExclusiveDraftTemplates = emptyDraftTemplates(),
    ): PipelineService {
        val validator = Fixtures.validator()
        return PipelineService(
            pipelines = repository,
            validator = validator,
            drafts = PipelineDraftService(repository, authoring),
            releases =
                PipelineReleaseService(
                    repository,
                    // Per-ID, not one answer for everything: a BLANK id is not a template at all
                    // (a CALCULATOR or PIPELINE node's empty default ref), and the registry's honest
                    // answer for it is "no such version". Without that distinction the
                    // no-template-pin test below could not go red — the empty ref would come back
                    // RELEASED along with the real one.
                    TemplateVersionStatuses { _, id, _ -> if (id.isBlank()) null else templateStatus },
                    validator,
                    authoring,
                ),
            authoring = authoring,
            draftTemplates = draftTemplates,
        )
    }

    /** 101: the purge port's default double — an always-empty offer (fixtures pin no templates). */
    private fun emptyDraftTemplates(): ExclusiveDraftTemplates =
        object : ExclusiveDraftTemplates {
            override fun exclusiveIds(
                workspaceId: java.util.UUID,
                pipelineId: java.util.UUID,
            ) = emptyList<String>()

            override fun purge(
                workspaceId: java.util.UUID,
                templateId: String,
            ) = Unit
        }

    // ---------------------------------------------------------------- D1: save validation, once

    @Test
    fun `create validates, canonicalizes and stores version 1 as a DRAFT with no released pointer`() {
        val saved = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)

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
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)

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
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
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
                WriteSurface.SESSION,
            )
        val withDraft = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("release v1 + draft v2: the DRAFT wins — 'the last version' is what the pipeline IS") {
            service.workingVersion(WORKSPACE_ID, withDraft) shouldBe 2
            withDraft.currentVersion shouldBe 1
        }

        service.purge(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash)
        withClue("purging the draft falls back to the release") {
            service.workingVersion(WORKSPACE_ID, checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))) shouldBe 1
        }
    }

    @Test
    fun `a pipeline whose only draft was purged is gone entirely`() {
        // 101: the sole-draft purge IS the entity purge (§3.2) — there is no version-less
        // entity any more, D57; the "nothing to run" state is the DISCARDED entity instead.
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)

        service.purge(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash)

        service.findRecord(WORKSPACE_ID, created.record.id).shouldBeNull()
        withClue("the entity row went with its only draft — D57's entity purge") {
            countRows("pipelines") shouldBe 0
        }
        repository.listVersions(WORKSPACE_ID, created.record.id).shouldBeEmptyList()
    }

    @Test
    fun `create refuses an invalid body before anything is written`() {
        // Two nodes with the same id — a §12 structural failure. The point is that the refusal
        // happens in the service, so REST and MCP cannot disagree about whether it happens.
        val duplicate = Fixtures.pipeline(nodes = listOf(Fixtures.node(id = "same"), Fixtures.node(id = "same")))

        shouldThrow<PipelineValidationException> { service.create(WORKSPACE_ID, body(duplicate), owner, WriteSurface.SESSION) }

        countRows("pipelines") shouldBe 0
    }

    @Test
    fun `create refuses when authoring is disabled, before validating`() {
        // versioning §5.5: a promotion receiver's sole writer is promotion. The guard is the
        // service's now — it used to be spelled once in the controller and once in the MCP tool.
        val receiver = serviceWith(AuthoringGuard(false))

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
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
            repository.create(
                WORKSPACE_ID,
                NewPipeline.from(body, owner),
                serializer.write(body),
                owner,
                CreateLifecycle.RELEASED,
                WriteSurface.SESSION,
            )
        val receiver = serviceWith(AuthoringGuard(false))

        withClue("create is refused, so a v1 DRAFT cannot be born here") {
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                receiver.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/nope")), owner, WriteSurface.SESSION)
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
                    WriteSurface.SESSION,
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

        val first = service.update(WORKSPACE_ID, created.record.id, body(renamed("First edit")), hash, owner, WriteSurface.SESSION)
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
                WriteSurface.SESSION,
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

        val noop = service.update(WORKSPACE_ID, created.record.id, body(Fixtures.pipeline()), hash, owner, WriteSurface.SESSION)

        withClue("§5.1 — nothing was opened, so the answer must not paint a draft pointer onto it") {
            noop.version?.status shouldBe PipelineVersionStatus.RELEASED
            noop.draft.shouldBeNull()
        }
        service.findDraft(WORKSPACE_ID, created.record.id).shouldBeNull()
    }

    @Test
    fun `a stale hash is refused with the catalogued conflict`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)

        val error =
            shouldThrow<co.datapipelines.typesystem.DatapipelinesException> {
                service.update(WORKSPACE_ID, created.record.id, body(renamed("x")), "not-the-hash", owner, WriteSurface.SESSION)
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
                WriteSurface.SESSION,
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
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
        val draft =
            service.update(
                WORKSPACE_ID,
                created.record.id,
                body(renamed("Ready")),
                checkNotNull(created.version).bodyHash,
                owner,
                WriteSurface.SESSION,
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
    fun `a node with no template pin does not block the release`() {
        // §6 checks TEMPLATE pins, and a CALCULATOR node has none (a PIPELINE node likewise pins a
        // child pipeline instead). Asking the registry about the empty default `@0` answers
        // MISSING, which refused the release of every pipeline containing one — naming
        // `template_id: ""`, a template that cannot exist. Latent since composition shipped (such a
        // pipeline was born RELEASED and only a re-release met the check); load-bearing since D55,
        // because now EVERY pipeline has to be released once.
        //
        // The port answers per-ID (see `serviceWith`): RELEASED for the real pin, MISSING for the
        // calculator's empty ref — so a release that still asked about that ref fails this test.
        val calculator = Fixtures.calculatorNode()
        val withCalculator =
            Fixtures.pipeline(
                nodes = listOf(calculator, Fixtures.node(id = "reads", dependsOn = listOf(calculator.id))),
            )
        val created = service.create(WORKSPACE_ID, body(withCalculator), owner, WriteSurface.SESSION)

        val released =
            service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)

        released.version.status shouldBe PipelineVersionStatus.RELEASED
        released.record.currentVersion shouldBe 1
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
                WriteSurface.SESSION,
            )

        val outcome = service.purge(WORKSPACE_ID, created.record.id, checkNotNull(draft.version).bodyHash)

        outcome.shouldBeInstanceOf<PipelineReleaseService.Purged.Version>()
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

        service.update(WORKSPACE_ID, created.record.id, body(renamed("Edited")), created.version.bodyHash, owner, WriteSurface.SESSION)

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
        service.create(WORKSPACE_ID, body(named("test/monthly_revenue", "Monthly Revenue", "By customer")), owner, WriteSurface.SESSION)
        service.create(WORKSPACE_ID, body(named("test/daily_churn", "Daily Churn", "Cancellations per day")), owner, WriteSurface.SESSION)

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
        service.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/mine")), owner, WriteSurface.SESSION)
        service.create(WORKSPACE_ID, body(Fixtures.pipeline(name = "test/theirs")), other, WriteSurface.SESSION)

        service.list(WORKSPACE_ID, ownerId = other).map { it.name } shouldContainExactly listOf("test/theirs")
    }

    @Test
    fun `page reports the truthful total and the draft badges of the rows it returned`() {
        val ids =
            (1..PAGED_ROWS).map {
                service.create(
                    WORKSPACE_ID,
                    body(Fixtures.pipeline(name = "test/p_$it")),
                    owner,
                    WriteSurface.SESSION,
                )
            }
        service.update(
            WORKSPACE_ID,
            ids.first().record.id,
            body(renamed("Has a draft")),
            checkNotNull(ids.first().version).bodyHash,
            owner,
            WriteSurface.SESSION,
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
    fun `the read surface - datasource filter, browseLevel, versioned reads - answers in one place`() {
        // 101: these pre-existing reads lost their in-module coverage share when the verbs
        // grew the module; this test pins them where they live (the web controllers' tests
        // exercise them through HTTP, which this module's kover cannot see).
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
        service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)

        withClue("list by datasource name resolves through the repository's pushed-down filter") {
            service.list(WORKSPACE_ID, datasourceName = "pg-prod").map { it.name } shouldContainExactly listOf("test/monthly_revenue")
            service.list(WORKSPACE_ID, datasourceName = "nope").shouldBeEmpty()
        }
        withClue("page with q filters in memory with a truthful total") {
            val page = service.page(WORKSPACE_ID, "monthly", 0, 10)
            page.total shouldBe 1
            page.hasMore shouldBe false
        }
        withClue("browseLevel answers one level and treats an illegal prefix as empty") {
            // The root's level shows `test` as a FOLDER; the pipeline is the `test` prefix's leaf.
            service.browseLevel(WORKSPACE_ID, null).pipelines.shouldBeEmpty()
            service.browseLevel(WORKSPACE_ID, "test").pipelines.map { it.name } shouldContainExactly listOf("test/monthly_revenue")
            service.browseLevel(WORKSPACE_ID, "not a legal prefix!!").pipelines.shouldBeEmpty()
        }
        val record = checkNotNull(service.findRecord(WORKSPACE_ID, created.record.id))
        withClue("the versioned reads compose record, body and detail") {
            service.findVersion(WORKSPACE_ID, record, 1)?.bodyJson shouldNotBe null
            service.findVersionBody(WORKSPACE_ID, record.id, 1) shouldNotBe null
            service.findExecutable(WORKSPACE_ID, record, 1)?.pipeline shouldNotBe null
            service.findDrafts(WORKSPACE_ID, listOf(record.id)) shouldBe emptyMap()
            service.findCurrentVersion(WORKSPACE_ID, record.id)?.version shouldBe 1
        }
    }

    @Test
    fun `the version verbs answer not_found for unknown versions and last_release for released entities`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
        service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)

        val unknown = 99
        val thrownByVerb =
            mapOf(
                "discard" to
                    shouldThrow<DatapipelinesException> {
                        service.discardVersion(
                            WORKSPACE_ID,
                            created.record.id,
                            unknown,
                            owner,
                        )
                    },
                "restore" to shouldThrow<DatapipelinesException> { service.restoreVersion(WORKSPACE_ID, created.record.id, unknown) },
                "purge" to shouldThrow<DatapipelinesException> { service.purgeVersion(WORKSPACE_ID, created.record.id, unknown) },
                "switch" to shouldThrow<DatapipelinesException> { service.switchCurrent(WORKSPACE_ID, created.record.id, unknown) },
            )
        thrownByVerb.forEach { (verb, thrown) ->
            withClue("$verb(unknown) is the catalogued 404") {
                thrown.code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
            }
        }

        withClue("a released version is never purged - last_release (D57)") {
            shouldThrow<DatapipelinesException> { service.purgeVersion(WORKSPACE_ID, created.record.id, 1) }
                .code shouldBe PipelineErrorCodes.Versioning.LAST_RELEASE
        }
        withClue("the entity purge on a released entity refuses last_release, keeping restore alive") {
            shouldThrow<DatapipelinesException> { service.purgeEntity(WORKSPACE_ID, created.record.id) }
                .code shouldBe PipelineErrorCodes.Versioning.LAST_RELEASE
        }
    }

    @Test
    fun `the lifecycle read helpers - any-status lookup, live probe, pin scans, row delete`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
        service.release(WORKSPACE_ID, created.record.id, checkNotNull(created.version).bodyHash, owner)
        val id = created.record.id

        withClue("findByIdAnyStatus reaches live and discarded entities alike") {
            checkNotNull(repository.findByIdAnyStatus(WORKSPACE_ID, id))
            checkNotNull(
                repository.discardVersion(WORKSPACE_ID, id, created.record.name, 1, owner, draftEligible = true),
            )
            checkNotNull(repository.findByIdAnyStatus(WORKSPACE_ID, id))
            repository.findById(WORKSPACE_ID, id).shouldBeNull()
        }
        withClue("hasLiveVersion is the §3.2 derivation") {
            repository.hasLiveVersion(WORKSPACE_ID, id) shouldBe false
        }
        withClue("the pin scans answer empty without parents and the row delete is final") {
            repository.findLiveParentsPinningVersion(WORKSPACE_ID, created.record.name, 1) shouldBe emptyList()
            repository.findLiveVersionsPinningTemplateVersion(WORKSPACE_ID, "test/fetch_orders.sql", 1) shouldBe emptyList()
            repository.deletePipelineRow(WORKSPACE_ID, id) shouldBe true
            repository.deletePipelineRow(WORKSPACE_ID, id) shouldBe false
        }
    }

    @Test
    fun `purgeEntity with include_exclusive_draft_templates purges the offered set`() {
        val offered = mutableListOf<String>()
        val serviceWithOffer =
            serviceWith(
                AuthoringGuard(true),
                draftTemplates =
                    object : ExclusiveDraftTemplates {
                        override fun exclusiveIds(
                            workspaceId: java.util.UUID,
                            pipelineId: java.util.UUID,
                        ) = listOf("test/only_mine.sql")

                        override fun purge(
                            workspaceId: java.util.UUID,
                            templateId: String,
                        ) {
                            offered.add(templateId)
                        }
                    },
            )

        val created = serviceWithOffer.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
        val result = serviceWithOffer.purgeEntity(WORKSPACE_ID, created.record.id, includeExclusiveDraftTemplates = true)

        offered shouldContainExactly listOf("test/only_mine.sql")
        result.exclusiveDraftTemplates shouldContainExactly listOf("test/only_mine.sql")
        result.exclusiveTemplatesPurged shouldBe true
    }

    @Test
    fun `purgeEntity removes an only-draft pipeline and reports its exclusive draft templates`() {
        // 101: DELETE /{id} is the entity purge — the only-draft case; the row GOES.
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)

        val result = service.purgeEntity(WORKSPACE_ID, created.record.id)

        service.findRecord(WORKSPACE_ID, created.record.id).shouldBeNull()
        withClue("the entity row went, with its only draft (D57)") {
            countRows("pipelines") shouldBe 0
            countRows("pipeline_versions") shouldBe 0
        }
        result.executionsDeleted shouldBe 0
    }

    // ---------------------------------------------------------------------------- D6: execute

    @Test
    fun `findExecutable resolves the body and the parsed pipeline for a version`() {
        val created = service.create(WORKSPACE_ID, body(Fixtures.pipeline()), owner, WriteSurface.SESSION)
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
        val created = service.create(WORKSPACE_ID, body(pipeline), owner, WriteSurface.SESSION)
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
