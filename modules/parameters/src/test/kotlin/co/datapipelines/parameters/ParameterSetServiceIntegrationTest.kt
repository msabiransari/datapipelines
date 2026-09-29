package co.datapipelines.parameters

import co.datapipelines.parameters.ParametersTestDb.AUTHOR
import co.datapipelines.parameters.ParametersTestDb.OTHER_WORKSPACE
import co.datapipelines.parameters.ParametersTestDb.WORKSPACE
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.Instant
import java.util.UUID

/**
 * Record §8.1–§8.3 against the real schema (V39, applied by [ParametersTestDb] from the shipped
 * migrations): every versioning §3.5 verb, the D60 pointer, the working-version read, the promoter
 * lens, workspace scoping both ways, the 142 cascade's atomicity, release and import re-running steps
 * 4–6, and the two races — FORCED with a second connection holding a lock, never timed.
 */
class ParameterSetServiceIntegrationTest {
    private lateinit var h: ParametersHarness

    @BeforeEach
    fun reset() {
        ParametersTestDb.reset()
        h = ParametersHarness()
    }

    private fun constants(name: String = "acme/sales/region_filters") =
        h.document(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson(), ParameterSetFixtures.amountJson(), name = name))

    private fun edited(name: String = "acme/sales/region_filters") =
        h.document(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson(), name = name))

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    @Nested
    inner class DraftFirst {
        @Test
        fun `create lands v1 DRAFT with no pointer, the canonical body stored and hashed by the database`() {
            val created = h.create(constants())
            created.detail.version shouldBe 1
            created.detail.status shouldBe PipelineVersionStatus.DRAFT
            created.detail.createdVia shouldBe WriteSurface.MCP.wire
            created.record.currentVersion shouldBe null
            created.record.displayName shouldBe "Region filters"
            created.detail.bodyHash shouldBe h.repository.computeBodyHash(created.body)
            h.jdbc.queryForObject(
                "SELECT (body_json -> 'name') IS NOT NULL FROM parameter_set_versions WHERE parameter_set_id = :id",
                mapOf("id" to created.record.id),
                Boolean::class.java,
            ) shouldBe false
            h.service
                .findWorking(WORKSPACE, ReadLens.Everything, created.record.id)!!
                .detail.status shouldBe PipelineVersionStatus.DRAFT
        }

        @Test
        fun `a draft write is in place and hash-preconditioned - a stale base is version_conflict with the current state`() {
            val created = h.create(constants())
            val written = h.service.write(WORKSPACE, created.record.id, edited(), created.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            written.detail.version shouldBe 1
            written.body.parameters.size shouldBe 1
            val stale =
                refusal {
                    h.service.write(
                        WORKSPACE,
                        created.record.id,
                        constants(),
                        created.detail.bodyHash,
                        AUTHOR,
                        WriteSurface.SESSION,
                    )
                }
            stale.code shouldBe ParameterErrorCodes.VERSION_CONFLICT
            stale.details["current_body_hash"] shouldBe written.detail.bodyHash
            stale.details["current_status"] shouldBe "DRAFT"
        }

        @Test
        fun `a set is never renamed, and a name is unique per workspace - another workspace may hold it`() {
            val created = h.create(constants())
            val renamed =
                shouldThrow<ParameterSetValidationException> {
                    h.service.write(
                        WORKSPACE,
                        created.record.id,
                        edited(name = "acme/sales/other"),
                        created.detail.bodyHash,
                        AUTHOR,
                        WriteSurface.SESSION,
                    )
                }
            renamed.result.failures
                .single()
                .details["reason"] shouldBe "immutable"
            refusal { h.create(constants()) }.code shouldBe ParameterErrorCodes.DUPLICATE_NAME
            h.create(constants(), workspaceId = OTHER_WORKSPACE).record.workspaceId shouldBe OTHER_WORKSPACE
        }

        @Test
        fun `an invalid body is refused before anything is written`() {
            val bad = h.document(ParameterSetFixtures.setJson("""{ "name": "1bad", "label": "B", "type": "STRING", "kind": "INPUT" }"""))
            shouldThrow<ParameterSetValidationException> { h.create(bad) }.result.codes shouldBe listOf(ParameterErrorCodes.NAME_INVALID)
            h.jdbc.queryForObject("SELECT COUNT(*) FROM parameter_sets", emptyMap<String, Any>(), Int::class.java) shouldBe 0
        }
    }

    @Nested
    inner class Lifecycle {
        private fun released(): ParameterSetVersion {
            val created = h.create(constants())
            return h.service.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR).version
        }

        @Test
        fun `release flips the draft, moves the pointer, and indexes its metadata - a second release has no draft`() {
            val v1 = released()
            v1.detail.status shouldBe PipelineVersionStatus.RELEASED
            v1.detail.releasedBy shouldBe AUTHOR
            v1.record.currentVersion shouldBe 1
            refusal { h.service.release(WORKSPACE, v1.record.id, v1.detail.bodyHash, AUTHOR) }.code shouldBe
                ParameterErrorCodes.VERSION_NOT_DRAFT
        }

        @Test
        fun `after a release the first write opens v2 (copy-on-write), an identical write is the no-op - no draft, no number`() {
            val v1 = released()
            val same = h.service.write(WORKSPACE, v1.record.id, constants(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            same.detail.status shouldBe PipelineVersionStatus.RELEASED
            same.detail.version shouldBe 1
            h.repository.findDraft(WORKSPACE, v1.record.id) shouldBe null
            val v2 = h.service.write(WORKSPACE, v1.record.id, edited(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            v2.detail.version shouldBe 2
            v2.detail.status shouldBe PipelineVersionStatus.DRAFT
            v2.record.currentVersion shouldBe 1
            // The working version is the draft; the pointer (what dependents follow) still names v1.
            h.service
                .findWorking(WORKSPACE, ReadLens.Everything, v1.record.id)!!
                .detail.version shouldBe 2
            h.repository.findRecord(WORKSPACE, v1.record.id)!!.displayName shouldBe "Region filters"
        }

        @Test
        fun `discard falls back (D60), restore moves the pointer only upward, switch needs a live eligible version`() {
            val v1 = released()
            val v2draft = h.service.write(WORKSPACE, v1.record.id, edited(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            val v2 = h.service.release(WORKSPACE, v1.record.id, v2draft.detail.bodyHash, AUTHOR).version
            v2.record.currentVersion shouldBe 2
            h.service.discardVersion(WORKSPACE, v1.record.id, 2, AUTHOR).status shouldBe PipelineVersionStatus.DISCARDED
            h.repository.findRecord(WORKSPACE, v1.record.id)!!.currentVersion shouldBe 1
            h.service.restoreVersion(WORKSPACE, v1.record.id, 2)
            h.repository.findRecord(WORKSPACE, v1.record.id)!!.currentVersion shouldBe 2
            h.service.switchCurrent(WORKSPACE, v1.record.id, 1) shouldBe 1
            h.service.discardVersion(WORKSPACE, v1.record.id, 2, AUTHOR)
            refusal { h.service.switchCurrent(WORKSPACE, v1.record.id, 2) }.code shouldBe ParameterErrorCodes.VERSION_NOT_ELIGIBLE
            refusal { h.service.discardVersion(WORKSPACE, v1.record.id, 2, AUTHOR) }.code shouldBe ParameterErrorCodes.VERSION_NOT_RELEASED
            refusal { h.service.restoreVersion(WORKSPACE, v1.record.id, 1) }.code shouldBe ParameterErrorCodes.VERSION_NOT_DISCARDED
            // Discarding the sole live release empties the pointer; the entity is DISCARDED, and restore is the way back.
            h.service.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR)
            h.repository.findRecord(WORKSPACE, v1.record.id)!!.currentVersion shouldBe null
            h.repository.isLive(WORKSPACE, v1.record.id) shouldBe false
            h.service.findWorking(WORKSPACE, ReadLens.Everything, v1.record.id) shouldBe null
        }

        @Test
        fun `purge - a draft is hard-deleted, the sole draft takes the set, a release is never purged`() {
            val v1 = released()
            val v2 = h.service.write(WORKSPACE, v1.record.id, edited(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            h.service.purgeDraft(WORKSPACE, v1.record.id, v2.detail.bodyHash)
            h.repository.findVersionDetail(WORKSPACE, v1.record.id, 2) shouldBe null
            refusal { h.service.purgeVersion(WORKSPACE, v1.record.id, 1) }.code shouldBe ParameterErrorCodes.VERSION_LAST_RELEASE
            refusal { h.service.purgeEntity(WORKSPACE, v1.record.id) }.code shouldBe ParameterErrorCodes.VERSION_LAST_RELEASE
            val draftOnly = h.create(constants(name = "acme/sales/scratch"))
            h.service.purgeEntity(WORKSPACE, draftOnly.record.id)
            h.repository.findRecord(WORKSPACE, draftOnly.record.id) shouldBe null
        }

        @Test
        fun `a receiver (authoring disabled) refuses every authoring write but switches and imports - no draft is eligible`() {
            val v1 = released()
            val receiver = ParametersHarness(authoringEnabled = false)
            refusal { receiver.create(constants(name = "acme/sales/new")) }.code shouldBe ParameterErrorCodes.AUTHORING_DISABLED
            refusal {
                receiver.service.write(
                    WORKSPACE,
                    v1.record.id,
                    edited(),
                    v1.detail.bodyHash,
                    AUTHOR,
                    WriteSurface.SESSION,
                )
            }.code shouldBe
                ParameterErrorCodes.AUTHORING_DISABLED
            refusal { receiver.service.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR) }.code shouldBe
                ParameterErrorCodes.AUTHORING_DISABLED
            receiver.service.switchCurrent(WORKSPACE, v1.record.id, 1) shouldBe 1
        }
    }

    @Nested
    inner class WorkspaceScoping {
        @Test
        fun `a set of another workspace is absent to every read and unreachable by every write - same name, no bleed`() {
            // READS on a RELEASED set (a draft-only set's NULL pointer would hide it from the current-version read
            // whatever the workspace predicate said — the first version of this test could not go red for that read).
            val released = h.create(constants())
            h.service.release(WORKSPACE, released.record.id, released.detail.bodyHash, AUTHOR)
            val theirs = h.create(constants(), workspaceId = OTHER_WORKSPACE)
            released.record.name shouldBe theirs.record.name
            h.service.findWorking(OTHER_WORKSPACE, ReadLens.Everything, released.record.id) shouldBe null
            h.repository.findCurrent(OTHER_WORKSPACE, released.record.id) shouldBe null
            h.service.findVersion(OTHER_WORKSPACE, ReadLens.Everything, released.record.id, 1) shouldBe null
            h.service.listVersions(OTHER_WORKSPACE, ReadLens.Everything, released.record.id).shouldBeEmpty()
            h.service.currentVersions(OTHER_WORKSPACE).map { it.id } shouldBe emptyList()
            refusal { h.service.switchCurrent(OTHER_WORKSPACE, released.record.id, 1) }.code shouldBe ParameterErrorCodes.NOT_FOUND
            refusal { h.service.discardVersion(OTHER_WORKSPACE, released.record.id, 1, AUTHOR) }.code shouldBe ParameterErrorCodes.NOT_FOUND
            // WRITES on a DRAFT (a write needs something to write to).
            val draft = h.create(constants(name = "acme/sales/draft_only"))
            refusal {
                h.service.write(
                    OTHER_WORKSPACE,
                    draft.record.id,
                    edited(name = "acme/sales/draft_only"),
                    draft.detail.bodyHash,
                    AUTHOR,
                    WriteSurface.SESSION,
                )
            }.code shouldBe ParameterErrorCodes.NOT_FOUND
            refusal { h.service.release(OTHER_WORKSPACE, draft.record.id, draft.detail.bodyHash, AUTHOR) }.code shouldBe
                ParameterErrorCodes.NOT_FOUND
            refusal { h.service.purgeEntity(OTHER_WORKSPACE, draft.record.id) }.code shouldBe ParameterErrorCodes.NOT_FOUND
            // And the repository's own writes, bypassing the service's reads, touch nothing across the line.
            h.repository.writeDraft(
                OTHER_WORKSPACE,
                draft.record.id,
                edited().body,
                draft.detail.bodyHash,
                AUTHOR,
                WriteSurface.SESSION,
            ) shouldBe
                null
            h.repository.releaseDraft(OTHER_WORKSPACE, draft.record.id, draft.detail.bodyHash, AUTHOR) shouldBe null
            h.repository.purgeDraft(OTHER_WORKSPACE, draft.record.id, null, draftEligible = true) shouldBe false
            h.repository.deleteEntity(OTHER_WORKSPACE, draft.record.id) shouldBe false
            h.repository
                .findWorking(WORKSPACE, draft.record.id)!!
                .body.parameters.size shouldBe 2
            h.service.listChildSets(OTHER_WORKSPACE, ReadLens.Everything, "acme/sales").map { it.record.id } shouldBe
                listOf(theirs.record.id)
        }
    }

    @Nested
    inner class Reads {
        @Test
        fun `the tree - folders counted, a never-released set listed at its draft (D55), totals truthful`() {
            h.create(constants(name = "acme/sales/a"))
            h.create(constants(name = "acme/sales/b"))
            h.create(constants(name = "acme/ops/deep/c"))
            h.service.listChildFolders(WORKSPACE, ReadLens.Everything, null).map { it.path to it.setCount } shouldBe listOf("acme" to 3)
            h.service.listChildFolders(WORKSPACE, ReadLens.Everything, "acme").map { it.segment } shouldBe listOf("ops", "sales")
            h.service.listChildSets(WORKSPACE, ReadLens.Everything, "acme/sales").map { it.record.name to it.detail.status } shouldBe
                listOf("acme/sales/a" to PipelineVersionStatus.DRAFT, "acme/sales/b" to PipelineVersionStatus.DRAFT)
            h.repository.countChildSets(WORKSPACE, "acme/sales") shouldBe 2
        }

        @Test
        fun `the promoter lens - only admitted names, only RELEASED - a draft is never a promoter's, a hidden set is null`() {
            val a = h.create(constants(name = "acme/sales/a"))
            val aReleased = h.service.release(WORKSPACE, a.record.id, a.detail.bodyHash, AUTHOR).version
            h.service.write(WORKSPACE, a.record.id, edited(name = "acme/sales/a"), aReleased.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            val b = h.create(constants(name = "acme/sales/b"))
            val lens = ReadLens.Only(setOf("acme/sales/a", "acme/sales/b"))
            h.service
                .findWorking(WORKSPACE, lens, a.record.id)!!
                .detail.version shouldBe 1
            h.service.findVersion(WORKSPACE, lens, a.record.id, 2) shouldBe null
            h.service.listVersions(WORKSPACE, lens, a.record.id).map { it.version } shouldBe listOf(1)
            h.service.findWorking(WORKSPACE, lens, b.record.id) shouldBe null
            h.service.findWorking(WORKSPACE, ReadLens.Only(setOf("acme/sales/b")), a.record.id) shouldBe null
            h.service.listChildSets(WORKSPACE, lens, "acme/sales").map { it.record.name } shouldBe listOf("acme/sales/a")
            h.service.currentVersions(WORKSPACE).map { it.name to it.version } shouldBe listOf("acme/sales/a" to 1)
        }

        @Test
        fun `countChildSets is the listing's truthful total over the WHOLE level - lens-true, unpaged (#300)`() {
            val a = h.create(constants(name = "acme/sales/a"))
            h.service.release(WORKSPACE, a.record.id, a.detail.bodyHash, AUTHOR)
            val b = h.create(constants(name = "acme/sales/b"))
            h.service.release(WORKSPACE, b.record.id, b.detail.bodyHash, AUTHOR)
            h.create(constants(name = "acme/sales/c")) // never released: a draft is never a promoter's

            // Everything lens: the whole level, exactly what listChildSets pages through.
            h.service.countChildSets(WORKSPACE, ReadLens.Everything, "acme/sales") shouldBe 3
            // A narrowing lens counts what the lens ADMITS with a current version — never the
            // workspace's total (a promoter must not learn how many sets exist outside the lens).
            // The draft-only c is absent both by the lens and by the rule; admit it and it still
            // cannot be counted, because the listing could not list it either.
            val lens = ReadLens.Only(setOf("acme/sales/a", "acme/sales/b", "acme/sales/c"))
            h.service.countChildSets(WORKSPACE, lens, "acme/sales") shouldBe 2
            // The count matches what the listing would admit over the whole level - everything
            // lens too; and the parent level "acme" holds no DIRECT sets, only the sales folder.
            h.service.listChildSets(WORKSPACE, ReadLens.Everything, "acme/sales", 0, 200).size shouldBe 3
            h.service.countChildSets(WORKSPACE, ReadLens.Everything, "acme") shouldBe 0
        }
    }

    @Nested
    inner class TemplatePins {
        private val statesBody =
            "SELECT code AS value, name AS display_value, FALSE AS is_default FROM states WHERE country = :country ORDER BY name"

        private fun cascade(pin: co.datapipelines.pipeline.TemplateRef) =
            h.document(
                ParameterSetFixtures.setJson(
                    ParameterSetFixtures.countryJson(),
                    """{ "name": "state", "label": "State", "type": "STRING", "kind": "SELECT",
                        "source": { "template": { "id": "${pin.id}", "version": ${pin.version} }, "datasource": "warehouse" },
                        "depends_on": ["country"] }""",
                ),
            )

        @Test
        fun `a DRAFT pin refuses the release, naming every pin - with consent it releases in the same transaction`() {
            val pin = h.template("acme/sql/states.sql", statesBody, CreateLifecycle.DRAFT)
            val set = h.create(cascade(pin))
            h.probe.renders.size shouldBe 1 // steps 5-6 ran at save, through the port
            val refused = refusal { h.service.release(WORKSPACE, set.record.id, set.detail.bodyHash, AUTHOR) }
            refused.code shouldBe ParameterErrorCodes.RELEASE_TEMPLATE_NOT_RELEASED
            (refused.details["pins_not_released"] as List<*>).size shouldBe 1
            val released = h.service.release(WORKSPACE, set.record.id, set.detail.bodyHash, AUTHOR, releasePinnedTemplates = true)
            released.templatesReleased shouldBe listOf(pin)
            h.templates.findVersionStatus(WORKSPACE, pin.id, pin.version) shouldBe PipelineVersionStatus.RELEASED
        }

        @Test
        fun `the cascade is atomic - a stale set hash rolls the template's release back`() {
            val pin = h.template("acme/sql/states.sql", statesBody, CreateLifecycle.DRAFT)
            val set = h.create(cascade(pin))
            refusal { h.service.release(WORKSPACE, set.record.id, "0".repeat(64), AUTHOR, releasePinnedTemplates = true) }.code shouldBe
                ParameterErrorCodes.VERSION_CONFLICT
            h.templates.findVersionStatus(WORKSPACE, pin.id, pin.version) shouldBe PipelineVersionStatus.DRAFT
        }

        @Test
        fun `release re-runs steps 4-6 against the pin as it is NOW - a draft template edited after the save is caught`() {
            val pin = h.template("acme/sql/states.sql", statesBody, CreateLifecycle.DRAFT)
            val set = h.create(cascade(pin))
            val draft = checkNotNull(h.templates.findDraftDetail(WORKSPACE, pin.id))
            h.templates.writeDraft(
                WORKSPACE,
                pin.id,
                co.datapipelines.templates.TemplateDraft(
                    id = pin.id,
                    type = co.datapipelines.pipeline.TemplateType.SQL,
                    dialect = co.datapipelines.typesystem.Dialect.POSTGRES,
                    displayName = pin.id,
                    description = "",
                    body = "$statesBody AND region = :region",
                ),
                draft.bodyHash,
                AUTHOR,
                WriteSurface.SESSION,
            )
            val refused =
                shouldThrow<ParameterSetValidationException> {
                    h.service.release(WORKSPACE, set.record.id, set.detail.bodyHash, AUTHOR, releasePinnedTemplates = true)
                }
            refused.result.failures.single().let {
                it.code shouldBe ParameterErrorCodes.BIND_UNDECLARED
                it.details["bind"] shouldBe "region"
            }
            h.templates.findVersionStatus(WORKSPACE, pin.id, pin.version) shouldBe PipelineVersionStatus.DRAFT
        }
    }

    @Nested
    inner class Import {
        private fun export(
            version: Int? = 3,
            id: UUID = UUID.fromString("5e7e0000-0000-0000-0000-000000000194"),
            name: String = "acme/sales/region_filters",
            body: ParameterSetBody = constants().body,
            hash: String? = null,
        ): ParameterSetExport {
            val canonical =
                (
                    h.validator.validate(
                        WORKSPACE,
                        ParameterSetDocument(name, body),
                    ) as ParameterSetValidation.Valid
                ).document.body
            return ParameterSetExport(
                id,
                name,
                version,
                hash ?: h.repository.computeBodyHash(canonical),
                Instant.parse("2026-09-01T00:00:00Z"),
                body,
            )
        }

        @Test
        fun `a new set lands RELEASED at the exported version, keeping the exported id - the first import sets the pointer`() {
            val imported = h.service.import(WORKSPACE, export(), AUTHOR)
            imported.created shouldBe true
            imported.detail.version shouldBe 3
            imported.detail.status shouldBe PipelineVersionStatus.RELEASED
            imported.detail.releasedAt shouldBe Instant.parse("2026-09-01T00:00:00Z")
            h.repository.findRecord(WORKSPACE, export().id)!!.currentVersion shouldBe 3
        }

        @Test
        fun `§9-2 - same version and hash a no-op, different content a conflict, version-less max+1 with the pointer kept`() {
            h.service.import(WORKSPACE, export(), AUTHOR)
            h.service.import(WORKSPACE, export(), AUTHOR).unchanged shouldBe true
            refusal { h.service.import(WORKSPACE, export(body = edited().body), AUTHOR) }.details["reason"] shouldBe "version_taken"
            val appended = h.service.import(WORKSPACE, export(version = null, body = edited().body), AUTHOR)
            appended.detail.version shouldBe 4
            h.repository.findRecord(WORKSPACE, export().id)!!.currentVersion shouldBe 3
        }

        @Test
        fun `the hash proves the body, a name held by another set is a duplicate, and a pin the target lacks is missing_template`() {
            refusal { h.service.import(WORKSPACE, export(hash = "f".repeat(64)), AUTHOR) }.details["reason"] shouldBe "hash_mismatch"
            h.create(constants())
            refusal { h.service.import(WORKSPACE, export(), AUTHOR) }.code shouldBe ParameterErrorCodes.DUPLICATE_NAME
            val pinned =
                ParameterSetFixtures
                    .setJson(
                        ParameterSetFixtures.countryJson(),
                        """{ "name": "state", "label": "State", "type": "STRING", "kind": "SELECT",
                            "source": { "template": { "id": "acme/sql/absent.sql", "version": 2 }, "datasource": "warehouse" },
                            "depends_on": ["country"] }""",
                        name = "acme/sales/pinned",
                    ).let { h.document(it).body }
            val missing =
                shouldThrow<ParameterSetValidationException> {
                    h.service.import(WORKSPACE, ParameterSetExport(UUID.randomUUID(), "acme/sales/pinned", 1, "x", null, pinned), AUTHOR)
                }
            missing.result.codes shouldBe listOf(ParameterErrorCodes.IMPORT_MISSING_TEMPLATE)
        }

        @Test
        fun `importValidated lands a PRE-VALIDATED export without re-validating - the receive's transaction-body arm (#302)`() {
            val pinned =
                ParameterSetFixtures
                    .setJson(
                        ParameterSetFixtures.countryJson(),
                        """{ "name": "state", "label": "State", "type": "STRING", "kind": "SELECT",
                            "source": { "template": { "id": "acme/sql/absent.sql", "version": 2 }, "datasource": "warehouse" },
                            "depends_on": ["country"] }""",
                        name = "acme/sales/received",
                    ).let { h.document(it).body }
            // `import` — the REST one-call path — refuses: the pin does not resolve here.
            shouldThrow<ParameterSetValidationException> {
                h.service.import(WORKSPACE, ParameterSetExport(UUID.randomUUID(), "acme/sales/received", 1, "x", null, pinned), AUTHOR)
            }.result.codes shouldBe listOf(ParameterErrorCodes.IMPORT_MISSING_TEMPLATE)

            // `importValidated` — the promotion receive's transaction body — lands the SAME
            // export: the §4 validation (the probe included) already ran outside the receive's
            // transaction; the landing checks hash/ids/versions and probes nothing.
            val landed =
                h.service.importValidated(
                    WORKSPACE,
                    ParameterSetExport(UUID.randomUUID(), "acme/sales/received", 1, h.repository.computeBodyHash(pinned), null, pinned),
                    AUTHOR,
                )
            landed.created shouldBe true
            landed.detail.status shouldBe PipelineVersionStatus.RELEASED
            landed.detail.version shouldBe 1
        }
    }

    @Nested
    inner class Invariants {
        @Test
        fun `the one-draft index refuses a second DRAFT row, whatever writes it`() {
            val created = h.create(constants())
            shouldThrow<DuplicateKeyException> {
                h.jdbc.update(
                    "INSERT INTO parameter_set_versions (parameter_set_id, version, body_json, status, body_hash, created_by)" +
                        " VALUES (:id, 2, '{}'::jsonb, 'DRAFT', 'x', :actor)",
                    mapOf("id" to created.record.id, "actor" to AUTHOR),
                )
            }
        }

        @Test
        fun `the first-writer race, FORCED - the loser blocks, then answers version_conflict with the winner's state`() {
            val created = h.create(constants())
            val v1 = h.service.release(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR).version
            ForcedRace
                .holdingThenCommitting(
                    hold = { c ->
                        c
                            .prepareStatement(
                                "INSERT INTO parameter_set_versions" +
                                    " (parameter_set_id, version, body_json, status, body_hash, created_by, updated_by, updated_at)" +
                                    " VALUES (?, 2, CAST(? AS jsonb), 'DRAFT', 'winner-hash', ?, ?, NOW())",
                            ).use {
                                it.setObject(1, created.record.id)
                                it.setString(2, ParameterSetJson.writeBody(edited().body))
                                it.setObject(3, AUTHOR)
                                it.setObject(4, AUTHOR)
                                it.executeUpdate()
                            }
                    },
                    contender = {
                        h.service.write(WORKSPACE, created.record.id, edited(), v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
                    },
                ).let { outcome ->
                    val loser = outcome.shouldBeFailure<DatapipelinesException>()
                    loser.code shouldBe ParameterErrorCodes.VERSION_CONFLICT
                    loser.details["current_body_hash"] shouldBe "winner-hash"
                }
        }

        @Test
        fun `the draft-write race, FORCED - the loser re-reads the committed hash under its WHERE and answers version_conflict`() {
            val created = h.create(constants())
            ForcedRace
                .holdingThenCommitting(
                    hold = { c ->
                        c
                            .prepareStatement(
                                "UPDATE parameter_set_versions SET body_hash = 'winner-hash'" +
                                    " WHERE parameter_set_id = ? AND status = 'DRAFT'",
                            ).use {
                                it.setObject(1, created.record.id)
                                it.executeUpdate() shouldBe 1
                            }
                    },
                    contender = {
                        h.service.write(WORKSPACE, created.record.id, edited(), created.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
                    },
                ).let { outcome ->
                    outcome.shouldBeFailure<DatapipelinesException>().code shouldBe ParameterErrorCodes.VERSION_CONFLICT
                    h.repository.findDraft(WORKSPACE, created.record.id)!!.bodyHash shouldBe "winner-hash"
                }
        }

        @Test
        fun `the stored hash is the database's own projection hash, not a writer's`() {
            val created = h.create(constants())
            h.jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(body_json::text, 'UTF8')), 'hex')" +
                    " FROM parameter_set_versions WHERE parameter_set_id = :id",
                mapOf("id" to created.record.id),
                String::class.java,
            ) shouldBe created.detail.bodyHash
            created.detail.bodyHash shouldNotBe ""
        }
    }
}
