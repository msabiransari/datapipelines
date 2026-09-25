package co.datapipelines.application.templates

import co.datapipelines.application.SharedPostgres
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFactKind
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.datasources.semantics.LearnedFactTrust
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.RetiredFactCitation
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.ImplementingVersion
import co.datapipelines.templates.LibraryResolver
import co.datapipelines.templates.RepositoryTemplateRegistry
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateImplementsRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidationFailure
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TransformBlocks
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.templates.TransformTestCase
import co.datapipelines.templates.TransformTestExpect
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
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
import java.util.UUID

/**
 * The semantic link's write and read halves (lane 7e, transform-nodes design §2.3, §8.2, §8.3)
 * against a real Postgres: the REAL template repository, citation store, validator, draft
 * service and learned-fact store, wired as production wires them. Every property here is a
 * statement about SQL — a join to `learned_facts` on read, the lens in the reverse read's WHERE,
 * a cascade from the composite key — so a mocked store would assert string-passing.
 *
 * The gates this suite carries (each falsified at birth, the handback records both directions):
 *  - [`an implements-only write lands on the RELEASED version and leaves the hash alone`] goes red
 *    when the citation is made part of the version's hash;
 *  - [`a cited fact that is not retired never marks the version`] goes red when `needs_review` is
 *    computed from `trust <> 'verified'` instead of `trust = 'retired'`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateImplementsIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var templates: TemplateRepository
    private lateinit var citations: TemplateImplementsRepository
    private lateinit var facts: LearnedFactRepository
    private lateinit var service: TemplateImplementsService
    private lateinit var validator: TemplateValidator
    private lateinit var drafts: TemplateDraftService

    private lateinit var actor: UUID
    private lateinit var rule: UUID
    private lateinit var exclusion: UUID
    private lateinit var foreignRule: UUID
    private lateinit var dataFact: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        templates = TemplateRepository(jdbc)
        citations = TemplateImplementsRepository(jdbc)
        facts = LearnedFactRepository(jdbc)
        service = TemplateImplementsService(facts, citations)
        validator =
            TemplateValidator(
                LibraryResolver { workspace -> RepositoryTemplateRegistry(templates, REGISTRY_CACHE, workspace) },
                citableFacts = service,
            )
        drafts = TemplateDraftService(templates, AuthoringGuard(true), citations)
    }

    @BeforeEach
    fun seed() {
        // Clean what we touch; the CASCADE reaches templates, versions, citations and facts.
        jdbc.jdbcTemplate.execute("TRUNCATE templates, learned_facts, datasources, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'default', 'Default'), " +
                "('$OTHER', 'other', 'Other') ON CONFLICT (id) DO NOTHING",
        )
        actor = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES ('warehouse', 'Warehouse', 'POSTGRES', 'jdbc:postgresql://x/y', 'none', :actor)",
            mapOf("actor" to actor),
        )
        rule = fact(LearnedFactKind.DEFINITION, "rainy = precipitation_mm >= 2.5")
        exclusion = fact(LearnedFactKind.EXCLUSION, "tips are excluded from revenue")
        foreignRule = fact(LearnedFactKind.DEFINITION, "their rule, not ours to cite", workspace = OTHER)
        dataFact = fact(LearnedFactKind.UNIT, "amount is in cents", scope = LearnedFactScope.DATASOURCE)
    }

    // ------------------------------------------------------------------ A.1 — the write

    @Test
    fun `a create cites WORKSPACE facts and every read carries them, sorted`() {
        val created = drafts.create(WORKSPACE, validated(transform(implements = listOf("$rule", "$exclusion"))), actor)

        created.implements shouldBe listOf(rule, exclusion).map { it.toString() }.sorted()
        created.needsReview shouldBe false
        templates.findWorking(WORKSPACE, NAME)!!.implements shouldBe created.implements
        templates.list(WORKSPACE).single().implements shouldBe created.implements
    }

    @Test
    fun `a DATASOURCE fact, another workspace's rule, an unknown id and a malformed one are all implements_unresolved`() {
        val cases =
            mapOf(
                "$dataFact" to "unknown",
                "$foreignRule" to "unknown",
                "${UUID.randomUUID()}" to "unknown",
                "not-a-fact" to "malformed",
            )
        cases.forEach { (id, reason) ->
            withClue(id) {
                val failure = implementsFailure(transform(implements = listOf("$rule", id)))
                failure.shouldNotBeNull()
                failure.details["reason"] shouldBe reason
                // Not-found semantics: the answer never says whose fact it is or that it exists.
                failure.message.contains("other") shouldBe false
            }
        }
        // Nothing reached the store: the validator runs before any write.
        templates.existsId(WORKSPACE, NAME) shouldBe false
    }

    @Test
    fun `sql and html refuse implements with blocks_not_allowed`() {
        val sql =
            TemplateDraft(
                id = "test/plain.sql",
                dialect = Dialect.POSTGRES,
                displayName = "Plain",
                description = "d",
                body = "SELECT 1",
                implements = listOf("$rule"),
            )
        validator.validate(sql, WORKSPACE).failures.map { it.code to it.details["block"] } shouldContainExactly
            listOf(PipelineErrorCodes.Template.BLOCKS_NOT_ALLOWED to "implements")
    }

    @Test
    fun `an implements-only write lands on the RELEASED version and leaves the hash alone`() {
        val v1 = releasedV1(implements = listOf("$rule"))
        val hashBefore = templates.findVersionDetail(WORKSPACE, NAME, 1)!!.bodyHash

        val written =
            drafts.write(
                WORKSPACE,
                NAME,
                validated(transform(implements = listOf("$rule", "$exclusion"))),
                v1.bodyHash,
                actor,
                WriteSurface.MCP,
            )

        // The §5.1 no-op: no draft opened, no version burned — the citation is not content.
        written.status shouldBe PipelineVersionStatus.RELEASED
        written.version shouldBe 1
        templates.findDraftDetail(WORKSPACE, NAME).shouldBeNull()
        val after = templates.findVersion(WORKSPACE, NAME, 1)!!
        after.implements shouldBe listOf(rule, exclusion).map { it.toString() }.sorted()
        // R9: the hash is byte-identical before and after — and equals the hash the database
        // computes for the same content, so no hidden column moved it either.
        after.bodyHash shouldBe hashBefore
        after.bodyHash shouldBe hashOf(transform())
    }

    @Test
    fun `a write that opens a draft inherits the released citations, in place keeps them, and an empty list clears`() {
        val v1 = releasedV1(implements = listOf("$rule"))

        // Absent implements + a body change: v2 DRAFT inherits v1's citation (owner ruling 2026-09-25).
        val v2 = drafts.write(WORKSPACE, NAME, validated(transform(body = "rows ~> \$count()")), v1.bodyHash, actor, WriteSurface.MCP)
        v2.status shouldBe PipelineVersionStatus.DRAFT
        v2.version shouldBe 2
        templates.findVersion(WORKSPACE, NAME, 2)!!.implements shouldBe listOf("$rule")

        // In place, absent: kept.
        val inPlace =
            drafts.write(
                WORKSPACE,
                NAME,
                validated(transform(body = "rows ~> \$count() + 0")),
                v2.bodyHash,
                actor,
                WriteSurface.MCP,
            )
        templates.findVersion(WORKSPACE, NAME, 2)!!.implements shouldBe listOf("$rule")

        // In place, []: cleared — and the released version is untouched throughout.
        drafts.write(
            WORKSPACE,
            NAME,
            validated(transform(body = "rows ~> \$count() + 0", implements = emptyList())),
            inPlace.bodyHash,
            actor,
            WriteSurface.MCP,
        )
        templates.findVersion(WORKSPACE, NAME, 2)!!.implements shouldBe emptyList()
        templates.findVersion(WORKSPACE, NAME, 1)!!.implements shouldBe listOf("$rule")
    }

    @Test
    fun `a purged draft takes its citations with it`() {
        val draft = drafts.create(WORKSPACE, validated(transform(implements = listOf("$rule"))), actor)
        citationRows() shouldBe 1

        templates.purgeDraft(WORKSPACE, NAME, draft.bodyHash, draftEligible = true) shouldBe true

        citationRows() shouldBe 0
    }

    // ------------------------------------------------------------------ A.2 — needs_review on read

    @Test
    fun `retiring a cited fact marks every citing version on read, naming the successor, until the successor is cited`() {
        val v1 = releasedV1(implements = listOf("$rule"))
        val v2 = drafts.write(WORKSPACE, NAME, validated(transform(body = "rows ~> \$count()")), v1.bodyHash, actor, WriteSurface.MCP)
        val successor = supersede(rule, "rainy = precipitation_mm >= 5.0")
        val expected = listOf(RetiredFactCitation("$rule", "superseded", "$successor"))

        listOf(1, 2).forEach { version ->
            withClue("v$version") {
                val read = templates.findVersion(WORKSPACE, NAME, version)!!
                read.needsReview shouldBe true
                read.retiredFacts shouldBe expected
            }
        }
        // Every read path carries it: the list's row is the RELEASED projection (the pointer wins).
        templates.list(WORKSPACE).single().needsReview shouldBe true
        // The release port reads the SAME expression.
        citations.retiredCitations(WORKSPACE, listOf(TemplateRef(NAME, 1), TemplateRef(NAME, 2))) shouldBe
            mapOf(TemplateRef(NAME, 1) to expected, TemplateRef(NAME, 2) to expected)

        // Clearing is a deliberate write: re-cite the successor on the draft.
        drafts.write(
            WORKSPACE,
            NAME,
            validated(transform(body = "rows ~> \$count()", implements = listOf("$successor"))),
            v2.bodyHash,
            actor,
            WriteSurface.MCP,
        )
        templates.findVersion(WORKSPACE, NAME, 2)!!.needsReview shouldBe false
        templates.findVersion(WORKSPACE, NAME, 1)!!.needsReview shouldBe true
        citations.retiredCitations(WORKSPACE, listOf(TemplateRef(NAME, 2))) shouldBe emptyMap()
    }

    @Test
    fun `a plain retirement names its reason and no successor`() {
        releasedV1(implements = listOf("$exclusion"))
        facts.retire(exclusion, "no longer our policy")

        templates.findVersion(WORKSPACE, NAME, 1)!!.retiredFacts shouldBe
            listOf(RetiredFactCitation("$exclusion", "no longer our policy", null))
    }

    @Test
    fun `a cited fact that is not retired never marks the version`() {
        // Every live trust state — the falsification target: a predicate other than
        // `trust = 'retired'` (e.g. `trust <> 'verified'`) marks the ASSERTED one.
        val live =
            listOf(LearnedFactTrust.ASSERTED, LearnedFactTrust.VERIFIED, LearnedFactTrust.NEEDS_REVIEW, LearnedFactTrust.STALE)
                .map { trust -> fact(LearnedFactKind.PREFERENCE, "prefer the ${trust.wire} reading", trust = trust) }
        releasedV1(implements = live.map { it.toString() })

        val read = templates.findVersion(WORKSPACE, NAME, 1)!!
        read.implements!!.size shouldBe live.size
        read.needsReview shouldBe false
        read.retiredFacts.shouldBeEmpty()
    }

    // ------------------------------------------------------------------ A.4 — discovery

    @Test
    fun `implemented_by honours the reader's lens and never lists a discarded version`() {
        val v1 = releasedV1(implements = listOf("$rule"))
        drafts.write(WORKSPACE, NAME, validated(transform(body = "rows ~> \$count()")), v1.bodyHash, actor, WriteSurface.MCP)

        service.implementedBy(WORKSPACE, ReadLens.Everything, listOf(rule, exclusion)) shouldBe
            mapOf(rule to listOf(ImplementingVersion(NAME, 1), ImplementingVersion(NAME, 2)))
        // A promoter's lens: RELEASED versions of the admitted names only (178).
        service.implementedBy(WORKSPACE, ReadLens.Only(setOf(NAME)), listOf(rule)) shouldBe
            mapOf(rule to listOf(ImplementingVersion(NAME, 1)))
        service.implementedBy(WORKSPACE, ReadLens.Only(setOf("test/else.jsonata")), listOf(rule)) shouldBe emptyMap()
        service.implementedBy(WORKSPACE, ReadLens.NOTHING, listOf(rule)) shouldBe emptyMap()
        // Another workspace sees nothing of ours.
        service.implementedBy(OTHER, ReadLens.Everything, listOf(rule)) shouldBe emptyMap()

        templates.discardVersion(WORKSPACE, NAME, 1, actor, draftEligible = true).shouldNotBeNull()
        service.implementedBy(WORKSPACE, ReadLens.Everything, listOf(rule)) shouldBe mapOf(rule to listOf(ImplementingVersion(NAME, 2)))
    }

    @Test
    fun `the implements filter keeps the templates whose listed version cites the fact, and a foreign id matches nothing`() {
        releasedV1(implements = listOf("$rule"))
        drafts.create(WORKSPACE, validated(transform(name = OTHER_NAME, implements = listOf("$exclusion"))), actor)

        templates.list(WORKSPACE, implements = rule).map { it.id } shouldContainExactly listOf(NAME)
        templates.count(WORKSPACE, implements = rule) shouldBe 1
        templates.list(WORKSPACE, implements = exclusion).map { it.id } shouldContainExactly listOf(OTHER_NAME)
        templates.list(WORKSPACE, implements = foreignRule).shouldBeEmpty()
        templates.list(WORKSPACE, implements = UUID.randomUUID()).shouldBeEmpty()
        templates.list(WORKSPACE).size shouldBe 2
    }

    @Test
    fun `the citable read is the section 2-3 rule - visible, WORKSPACE, and one of the three kinds`() {
        service.citable(WORKSPACE, setOf(rule, exclusion, foreignRule, dataFact, UUID.randomUUID())) shouldBe setOf(rule, exclusion)
        service.citable(OTHER, setOf(rule, foreignRule)) shouldBe setOf(foreignRule)
    }

    // ------------------------------------------------------------------ fixtures

    private fun fact(
        kind: LearnedFactKind,
        text: String,
        scope: LearnedFactScope = LearnedFactScope.WORKSPACE,
        workspace: UUID = WORKSPACE,
        trust: LearnedFactTrust = LearnedFactTrust.ASSERTED,
        supersedes: UUID? = null,
    ): UUID =
        facts
            .insert(
                LearnedFactRepository.NewFact(
                    scope = scope,
                    workspaceId = if (scope == LearnedFactScope.WORKSPACE) workspace else null,
                    datasourceName = "warehouse",
                    kind = kind,
                    fact = text,
                    refs = if (scope == LearnedFactScope.WORKSPACE) emptyList() else listOf(FactRef(null, "orders", "amount")),
                    evidenceSql = null,
                    evidenceSummary = null,
                    trust = trust,
                    schemaFingerprint = "orders=seed",
                    recordedBy = actor,
                    recordedVia = "mcp",
                    recordedIn = workspace,
                    sourcePipelineId = null,
                    sourceVersion = null,
                    supersedes = supersedes,
                ),
            ).id

    /** The recorder's supersession, as its two statements: the successor, then the predecessor retired. */
    private fun supersede(
        old: UUID,
        text: String,
    ): UUID {
        val successor = fact(LearnedFactKind.DEFINITION, text, supersedes = old)
        facts.retire(old, "superseded")
        return successor
    }

    /** The record's row-mode shape: one table input, a table output, the mandatory empty case. */
    private fun transform(
        name: String = NAME,
        body: String = "rows",
        implements: List<String>? = null,
    ) = TemplateDraft(
        id = name,
        engine = Template.NONE_ENGINE,
        type = TemplateType.JSONATA,
        dialect = null,
        displayName = "Rainy days",
        description = "Marks each day rainy by the workspace definition.",
        body = body,
        contract =
            TransformContract(
                mode = TransformMode.ROW,
                inputs = mapOf("days" to TransformInput.Table(listOf(ContractColumn("day", LogicalType.STRING)))),
                output = TransformOutput.Table(listOf(ContractColumn("day", LogicalType.STRING))),
            ),
        invariants = emptyList(),
        tests =
            listOf(
                TransformTestCase(
                    name = "empty",
                    input = TransformTestInput(rows = emptyList()),
                    expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
                ),
            ),
        implements = implements,
    )

    private fun validated(draft: TemplateDraft): TemplateDraft = validator.validateOrThrow(draft, WORKSPACE)

    private fun implementsFailure(draft: TemplateDraft): TemplateValidationFailure? =
        validator.validate(draft, WORKSPACE).failures.singleOrNull { it.code == PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED }

    /** v1 created with [implements] and released — the RELEASED version a pipeline would pin. */
    private fun releasedV1(implements: List<String>): Template {
        val created = drafts.create(WORKSPACE, validated(transform(implements = implements)), actor)
        templates.releaseDraft(WORKSPACE, NAME, created.bodyHash, actor).shouldNotBeNull()
        return templates.findVersion(WORKSPACE, NAME, 1)!!
    }

    private fun TemplateDraftService.create(
        workspaceId: UUID,
        draft: TemplateDraft,
        actor: UUID,
    ): Template = create(workspaceId, draft, actor, CreateLifecycle.DRAFT, WriteSurface.MCP)

    /** The database's own hash of [draft]'s content — the expression every write stores. */
    private fun hashOf(draft: TemplateDraft): String =
        templates.computeBodyHash(
            engine = draft.engine,
            dialect = null,
            isLibrary = draft.isLibrary,
            importsJson = "[]",
            body = draft.body,
            contractJson = TransformBlocks.writeContract(draft.contract),
            invariantsJson = TransformBlocks.writeInvariants(draft.invariants),
            testsJson = TransformBlocks.writeTests(draft.tests),
        )

    private fun citationRows(): Int = jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM template_implements", Int::class.java)!!

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("0e7e0000-0000-0000-0000-000000000002")
        const val NAME = "test/rainy_days.jsonata"
        const val OTHER_NAME = "test/revenue_rules.jsonata"
        const val REGISTRY_CACHE = 16
    }
}
