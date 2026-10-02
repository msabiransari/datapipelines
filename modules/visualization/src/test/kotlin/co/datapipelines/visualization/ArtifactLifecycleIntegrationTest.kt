package co.datapipelines.visualization

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * The versioning §3.5 verb table against the real V42 schema, for BOTH families through the ONE generic
 * [ArtifactLifecycle] + [ArtifactRepository] — the `ParameterSetServiceIntegrationTest` cases minus the parameter-set
 * specific ones, each run per [ArtifactKind]: create/hash, the stale base, never renamed, name unique per workspace,
 * release and the pointer, copy-on-write and its no-op arm, discard/restore/switch (D60), purge, the receiver posture,
 * other-workspace absence, the tree and the flat listing, the promoter lens, import (§9.2's rows, id kept, C29), the
 * one-draft index, both races FORCED, and the database-projection hash.
 */
class ArtifactLifecycleIntegrationTest {
    @BeforeEach
    fun reset() = VisualizationTestDb.reset()

    /** One family's typed fixture: its lifecycle and two distinct canonical bodies. */
    private class Family<B : Any>(
        val kind: ArtifactKind,
        val repository: ArtifactRepository<B>,
        val body: B,
        val edited: B,
        val authoringEnabled: Boolean = true,
    ) {
        val lifecycle =
            ArtifactLifecycle(
                repository,
                AuthoringGuard(authoringEnabled),
                TransactionTemplate(DataSourceTransactionManager(VisualizationTestDb.dataSource)),
            )
        val name = "acme/${kind.index}/revenue"

        fun create(
            name: String = this.name,
            workspace: UUID = WORKSPACE,
        ) = lifecycle.create(workspace, name, body, AUTHOR, WriteSurface.MCP)

        fun released(name: String = this.name): ArtifactVersion<B> {
            val created = create(name)
            lifecycle.flipDraft(WORKSPACE, created.record.id, created.detail.bodyHash, AUTHOR)
            return checkNotNull(repository.findCurrent(WORKSPACE, created.record.id))
        }

        fun receiver() = Family(kind, repository, body, edited, authoringEnabled = false)
    }

    /**
     * The family under test as `Family<Any>`: the cases are written once over both body types, and each family's
     * repository only ever receives the bodies built here for it — the cast cannot mix them.
     */
    @Suppress("UNCHECKED_CAST")
    private fun family(kind: ArtifactKind): Family<Any> =
        when (kind) {
            ArtifactKind.VISUALIZATION -> {
                val body = ValidatorFakes.visualizationDocument(DocumentFixtures.visualization()).body
                val repository = VisualizationRepository(VisualizationTestDb.jdbc) as ArtifactRepository<Any>
                Family(kind, repository, body, body.copy(displayName = "Monthly revenue, edited"))
            }

            ArtifactKind.DASHBOARD -> {
                val body = ValidatorFakes.dashboardDocument(DocumentFixtures.dashboard()).body
                val repository = DashboardRepository(VisualizationTestDb.jdbc) as ArtifactRepository<Any>
                Family(kind, repository, body, body.copy(displayName = "Revenue overview, edited"))
            }
        }

    private fun refusal(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException>(block)

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `create lands v1 DRAFT with no pointer, the canonical body stored and hashed by the database, no name in the body`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val created = create()
            created.detail.version shouldBe 1
            created.detail.status shouldBe PipelineVersionStatus.DRAFT
            created.detail.createdVia shouldBe WriteSurface.MCP.wire
            created.record.currentVersion shouldBe null
            created.detail.bodyHash shouldBe repository.computeBodyHash(created.body)
            VisualizationTestDb.jdbc.queryForObject(
                "SELECT (body_json -> 'name') IS NOT NULL FROM ${kind.versions} WHERE ${kind.fk} = :id",
                mapOf("id" to created.record.id),
                Boolean::class.java,
            ) shouldBe false
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `a draft write is in place and hash-preconditioned - a stale base is version_conflict with the current state`(kind: ArtifactKind) {
        with(family(kind)) {
            val created = create()
            val written =
                lifecycle.write(
                    WORKSPACE,
                    created.record.id,
                    name,
                    edited,
                    created.detail.bodyHash,
                    OTHER_AUTHOR,
                    WriteSurface.SESSION,
                )
            written.detail.version shouldBe 1
            written.detail.updatedBy shouldBe OTHER_AUTHOR
            val stale =
                refusal { lifecycle.write(WORKSPACE, created.record.id, name, body, created.detail.bodyHash, AUTHOR, WriteSurface.SESSION) }
            stale.code shouldBe kind.codes.versionConflict
            stale.details["current_body_hash"] shouldBe written.detail.bodyHash
            stale.details["current_status"] shouldBe "DRAFT"
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `an artifact is never renamed, and a name is unique per workspace - another workspace may hold it`(kind: ArtifactKind) {
        with(family(kind)) {
            val created = create()
            val renamed =
                shouldThrow<ArtifactValidationException> {
                    lifecycle.write(WORKSPACE, created.record.id, "$name-2", edited, created.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
                }
            renamed.code shouldBe kind.codes.nameInvalid
            renamed.result.failures
                .single()
                .details["reason"] shouldBe "immutable"
            refusal { create() }.code shouldBe kind.codes.nameTaken
            create(workspace = OTHER_WORKSPACE).record.workspaceId shouldBe OTHER_WORKSPACE
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `release flips the draft, moves the pointer and indexes its metadata - after it the next write opens v2, identical is a no-op`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val released = released()
            released.detail.status shouldBe PipelineVersionStatus.RELEASED
            released.detail.releasedAt shouldNotBe null
            released.record.currentVersion shouldBe 1
            refusal { lifecycle.flipDraft(WORKSPACE, released.record.id, released.detail.bodyHash, AUTHOR) }.code shouldBe
                kind.codes.versionConflict
            val noop = lifecycle.write(WORKSPACE, released.record.id, name, body, released.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            noop.detail.version shouldBe 1
            repository.findDraft(WORKSPACE, released.record.id) shouldBe null
            val v2 = lifecycle.write(WORKSPACE, released.record.id, name, edited, released.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            v2.detail.version shouldBe 2
            v2.detail.status shouldBe PipelineVersionStatus.DRAFT
            lifecycle.flipDraft(WORKSPACE, released.record.id, v2.detail.bodyHash, AUTHOR)
            checkNotNull(repository.findRecord(WORKSPACE, released.record.id)).displayName shouldBe
                (if (kind == ArtifactKind.VISUALIZATION) "Monthly revenue, edited" else "Revenue overview, edited")
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `discard falls back (D60), restore moves the pointer only upward, switch needs a live eligible version`(kind: ArtifactKind) {
        with(family(kind)) {
            val v1 = released()
            val id = v1.record.id
            val v2 = lifecycle.write(WORKSPACE, id, name, edited, v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            lifecycle.flipDraft(WORKSPACE, id, v2.detail.bodyHash, AUTHOR)
            lifecycle.discardVersion(WORKSPACE, id, 2, AUTHOR).detail.status shouldBe PipelineVersionStatus.DISCARDED
            checkNotNull(repository.findRecord(WORKSPACE, id)).currentVersion shouldBe 1
            refusal { lifecycle.discardVersion(WORKSPACE, id, 2, AUTHOR) }.code shouldBe kind.codes.notReleased
            refusal { lifecycle.switchCurrent(WORKSPACE, id, 2) }.code shouldBe kind.codes.notEligible
            lifecycle.restoreVersion(WORKSPACE, id, 2).detail.status shouldBe PipelineVersionStatus.RELEASED
            checkNotNull(repository.findRecord(WORKSPACE, id)).currentVersion shouldBe 2
            refusal { lifecycle.restoreVersion(WORKSPACE, id, 2) }.code shouldBe kind.codes.notDiscarded
            lifecycle.switchCurrent(WORKSPACE, id, 1).pointer.after shouldBe 1
            refusal { lifecycle.switchCurrent(WORKSPACE, id, 9) }.code shouldBe kind.codes.notFound
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `purge - a draft is hard-deleted, the sole draft takes the artifact, a release is never purged`(kind: ArtifactKind) {
        with(family(kind)) {
            val lone = create()
            lifecycle.purgeDraft(WORKSPACE, lone.record.id, lone.detail.bodyHash)
            repository.findRecord(WORKSPACE, lone.record.id) shouldBe null
            val v1 = released()
            refusal { lifecycle.purgeVersion(WORKSPACE, v1.record.id, 1) }.code shouldBe kind.codes.lastRelease
            refusal { lifecycle.purgeEntity(WORKSPACE, v1.record.id) }.code shouldBe kind.codes.lastRelease
            refusal { lifecycle.purgeDraft(WORKSPACE, v1.record.id, v1.detail.bodyHash) }.code shouldBe kind.codes.notDraft
            val v2 = lifecycle.write(WORKSPACE, v1.record.id, name, edited, v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            refusal { lifecycle.purgeDraft(WORKSPACE, v1.record.id, "stale") }.code shouldBe kind.codes.versionConflict
            lifecycle.purgeVersion(WORKSPACE, v1.record.id, 2, v2.detail.bodyHash)
            repository.listVersions(WORKSPACE, v1.record.id).map { it.version } shouldBe listOf(1)
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `a receiver (authoring disabled) refuses every authoring write but switches and imports`(kind: ArtifactKind) {
        val authoring = family(kind)
        val v1 = authoring.released()
        val receiver = authoring.receiver()
        listOf<() -> Unit>(
            { receiver.create("acme/other/x") },
            { receiver.lifecycle.purgeEntity(WORKSPACE, v1.record.id) },
            { receiver.lifecycle.discardVersion(WORKSPACE, v1.record.id, 1, AUTHOR) },
        ).forEach { refusal(it).code shouldBe kind.codes.authoringDisabled }
        receiver.lifecycle
            .switchCurrent(WORKSPACE, v1.record.id, 1)
            .pointer.after shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `an artifact of another workspace is absent to every read and unreachable by every write`(kind: ArtifactKind) {
        with(family(kind)) {
            val mine = released()
            val id = mine.record.id
            lifecycle.findWorking(OTHER_WORKSPACE, ReadLens.Everything, id) shouldBe null
            lifecycle.findVersion(OTHER_WORKSPACE, ReadLens.Everything, id, 1) shouldBe null
            lifecycle.listVersions(OTHER_WORKSPACE, ReadLens.Everything, id).shouldBeEmpty()
            lifecycle.listAll(OTHER_WORKSPACE, ReadLens.Everything).shouldBeEmpty()
            refusal { lifecycle.discardVersion(OTHER_WORKSPACE, id, 1, AUTHOR) }.code shouldBe kind.codes.notFound
            refusal { lifecycle.write(OTHER_WORKSPACE, id, name, edited, mine.detail.bodyHash, AUTHOR, WriteSurface.SESSION) }.code shouldBe
                kind.codes.notFound
            repository.findRecord(WORKSPACE, id)?.currentVersion shouldBe 1
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the tree and the flat listing - folders counted, a never-released artifact listed at its draft, totals truthful`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            create("acme/sales/a")
            released("acme/sales/b")
            create("acme/ops/c")
            create("beta/x/y")
            lifecycle.listChildFolders(WORKSPACE, ReadLens.Everything, null).map { it.path to it.count } shouldBe
                listOf("acme" to 3, "beta" to 1)
            lifecycle.listChildren(WORKSPACE, ReadLens.Everything, "acme/sales").map { it.record.name to it.detail.status } shouldBe
                listOf("acme/sales/a" to PipelineVersionStatus.DRAFT, "acme/sales/b" to PipelineVersionStatus.RELEASED)
            lifecycle.countChildren(WORKSPACE, ReadLens.Everything, "acme/sales") shouldBe 2
            lifecycle.listAll(WORKSPACE, ReadLens.Everything, limit = 2).map { it.record.name } shouldBe
                listOf("acme/ops/c", "acme/sales/a")
            lifecycle.countAll(WORKSPACE, ReadLens.Everything) shouldBe 4
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the promoter lens - only admitted names, only RELEASED - a draft is never a promoter's, a hidden artifact is null`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val visible = released("acme/sales/b")
            val hidden = released("acme/sales/c")
            val draftOnly = create("acme/sales/a")
            lifecycle.write(WORKSPACE, visible.record.id, "acme/sales/b", edited, visible.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
            val lens = ReadLens.Only(setOf("acme/sales/b", "acme/sales/a"))
            checkNotNull(lifecycle.findWorking(WORKSPACE, lens, visible.record.id)).detail.version shouldBe 1
            lifecycle.findVersion(WORKSPACE, lens, visible.record.id, 2) shouldBe null
            lifecycle.listVersions(WORKSPACE, lens, visible.record.id).map { it.version } shouldBe listOf(1)
            lifecycle.findWorking(WORKSPACE, lens, hidden.record.id) shouldBe null
            lifecycle.findWorking(WORKSPACE, lens, draftOnly.record.id) shouldBe null
            lifecycle.listChildren(WORKSPACE, lens, "acme/sales").map { it.record.name } shouldBe listOf("acme/sales/b")
            lifecycle.countChildren(WORKSPACE, lens, "acme/sales") shouldBe 1
            lifecycle.listChildFolders(WORKSPACE, lens, null).map { it.path to it.count } shouldBe listOf("acme" to 1)
            lifecycle.countAll(WORKSPACE, lens) shouldBe 1
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `import - a new artifact lands RELEASED at the exported version keeping its id, and §9-2's rows answer`(kind: ArtifactKind) {
        with(family(kind)) {
            val id = UUID.randomUUID()
            val hash = repository.computeBodyHash(body)
            val export = ArtifactExport(id, name, 3, hash, Instant.parse("2026-09-01T00:00:00Z"), body)
            val landed = lifecycle.importValidated(WORKSPACE, export, AUTHOR)
            landed.created shouldBe true
            landed.detail.version shouldBe 3
            landed.detail.status shouldBe PipelineVersionStatus.RELEASED
            landed.detail.releasedAt shouldBe Instant.parse("2026-09-01T00:00:00Z")
            checkNotNull(repository.findRecord(WORKSPACE, id)).currentVersion shouldBe 3
            lifecycle.importValidated(WORKSPACE, export, AUTHOR).unchanged shouldBe true
            val taken =
                refusal {
                    lifecycle.importValidated(
                        WORKSPACE,
                        export.copy(body = edited, bodyHash = repository.computeBodyHash(edited)),
                        AUTHOR,
                    )
                }
            taken.code shouldBe kind.codes.versionConflict
            taken.details["reason"] shouldBe "version_taken"
            lifecycle
                .importValidated(
                    WORKSPACE,
                    export.copy(version = null, bodyHash = null, body = edited),
                    AUTHOR,
                ).detail.version shouldBe
                4
            checkNotNull(repository.findRecord(WORKSPACE, id)).currentVersion shouldBe 3
            refusal {
                lifecycle.importValidated(
                    WORKSPACE,
                    export.copy(version = 5, bodyHash = "0".repeat(64)),
                    AUTHOR,
                )
            }.details["reason"] shouldBe
                "hash_mismatch"
            refusal { lifecycle.importValidated(WORKSPACE, export.copy(name = "$name-2"), AUTHOR) }.code shouldBe kind.codes.nameInvalid
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `import - a name another artifact holds is name_taken, an id another workspace holds is id_taken (C29, never re-issued)`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val mine = create()
            val export = ArtifactExport(UUID.randomUUID(), name, 1, repository.computeBodyHash(body), null, body)
            refusal { lifecycle.importValidated(WORKSPACE, export, AUTHOR) }.code shouldBe kind.codes.nameTaken
            val elsewhere = ArtifactExport(mine.record.id, "acme/imported/one", 1, repository.computeBodyHash(body), null, body)
            val taken = refusal { lifecycle.importValidated(OTHER_WORKSPACE, elsewhere, AUTHOR) }
            taken.code shouldBe kind.codes.idTaken
            taken.details["id"] shouldBe mine.record.id.toString()
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the one-draft index refuses a second DRAFT row, whatever writes it`(kind: ArtifactKind) {
        with(family(kind)) {
            val created = create()
            shouldThrow<DuplicateKeyException> {
                VisualizationTestDb.jdbc.update(
                    "INSERT INTO ${kind.versions} (${kind.fk}, version, body_json, status, body_hash, created_by) " +
                        "VALUES (:id, 2, '{}'::jsonb, 'DRAFT', 'x', :actor)",
                    mapOf("id" to created.record.id, "actor" to AUTHOR),
                )
            }.mostSpecificCause.message!!.contains(kind.draftIndex) shouldBe true
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the first-writer race, FORCED - the loser blocks, then answers version_conflict with the winner's state`(kind: ArtifactKind) {
        with(family(kind)) {
            val v1 = released()
            val id = v1.record.id
            val winnerJson = ArtifactJson.writeBody(edited)
            val outcome =
                ForcedRace.holdingThenCommitting(
                    hold = { connection ->
                        connection
                            .prepareStatement(
                                "INSERT INTO ${kind.versions} " +
                                    "(${kind.fk}, version, body_json, status, body_hash, created_by, updated_by, updated_at) " +
                                    "VALUES (?, 2, CAST(? AS jsonb), 'DRAFT', 'winner', ?, ?, NOW())",
                            ).use {
                                it.setObject(1, id)
                                it.setString(2, winnerJson)
                                it.setObject(3, OTHER_AUTHOR)
                                it.setObject(4, OTHER_AUTHOR)
                                it.executeUpdate()
                            }
                    },
                    contender = { repository.createDraft(WORKSPACE, id, edited, v1.detail.bodyHash, AUTHOR, WriteSurface.SESSION) },
                )
            val loser = outcome.shouldBeFailure<DatapipelinesException>()
            loser.code shouldBe kind.codes.versionConflict
            loser.details["current_body_hash"] shouldBe "winner"
            loser.details["updated_by"] shouldBe OTHER_AUTHOR.toString()
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the draft-write race, FORCED - the loser re-reads the committed hash under its WHERE and answers version_conflict`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val created = create()
            val id = created.record.id
            val outcome =
                ForcedRace.holdingThenCommitting(
                    hold = { connection ->
                        connection
                            .prepareStatement(
                                "UPDATE ${kind.versions} SET body_hash = 'winner' WHERE ${kind.fk} = ? AND status = 'DRAFT'",
                            ).use {
                                it.setObject(1, id)
                                it.executeUpdate()
                            }
                    },
                    contender = {
                        lifecycle.write(WORKSPACE, id, name, edited, created.detail.bodyHash, AUTHOR, WriteSurface.SESSION)
                    },
                )
            outcome.shouldBeFailure<DatapipelinesException>().code shouldBe kind.codes.versionConflict
            repository.findDraft(WORKSPACE, id)?.bodyHash shouldBe "winner"
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `the stored hash is the database's own projection hash - key order and whitespace never move it`(kind: ArtifactKind) {
        with(family(kind)) {
            val created = create()
            val projection =
                VisualizationTestDb.jdbc.queryForObject(
                    "SELECT encode(sha256(convert_to(body_json::text, 'UTF8')), 'hex') FROM ${kind.versions} WHERE ${kind.fk} = :id",
                    mapOf("id" to created.record.id),
                    String::class.java,
                )
            created.detail.bodyHash shouldBe projection
            // The same body re-serialised with its top-level keys reversed and pretty-printed hashes the same.
            val tree = ArtifactJson.mapper.readTree(ArtifactJson.writeBody(body)) as ObjectNode
            val reversed = ArtifactJson.mapper.createObjectNode()
            tree
                .properties()
                .toList()
                .reversed()
                .forEach { (key, value) -> reversed.set<ObjectNode>(key, value) }
            val text = ArtifactJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reversed)
            text shouldNotBe ArtifactJson.writeBody(body)
            VisualizationTestDb.jdbc.queryForObject(
                "SELECT ${ArtifactSql.HASH_EXPR}",
                mapOf("bodyJson" to text),
                String::class.java,
            ) shouldBe
                created.detail.bodyHash
        }
    }

    @ParameterizedTest
    @EnumSource(ArtifactKind::class)
    fun `non-vacuity - every refusal the generic lifecycle owns is raised once, the covered set equal to the family's codes`(
        kind: ArtifactKind,
    ) {
        with(family(kind)) {
            val v1 = released("acme/codes/one")
            val draft = create("acme/codes/two")
            val triggers: Map<String, () -> Unit> =
                mapOf(
                    kind.codes.nameInvalid to
                        {
                            lifecycle.write(
                                WORKSPACE,
                                draft.record.id,
                                "acme/codes/x",
                                edited,
                                draft.detail.bodyHash,
                                AUTHOR,
                                WriteSurface.SESSION,
                            )
                        },
                    kind.codes.nameTaken to { create("acme/codes/one") },
                    kind.codes.notFound to { lifecycle.switchCurrent(WORKSPACE, UUID.randomUUID(), 1) },
                    kind.codes.versionConflict to { lifecycle.flipDraft(WORKSPACE, draft.record.id, "stale", AUTHOR) },
                    kind.codes.notDraft to { lifecycle.purgeDraft(WORKSPACE, v1.record.id, v1.detail.bodyHash) },
                    kind.codes.notReleased to { lifecycle.discardVersion(WORKSPACE, draft.record.id, 1, AUTHOR) },
                    kind.codes.notDiscarded to { lifecycle.restoreVersion(WORKSPACE, v1.record.id, 1) },
                    kind.codes.lastRelease to { lifecycle.purgeEntity(WORKSPACE, v1.record.id) },
                    kind.codes.notEligible to { receiver().lifecycle.switchCurrent(WORKSPACE, draft.record.id, 1) },
                    kind.codes.idTaken to {
                        lifecycle.importValidated(
                            OTHER_WORKSPACE,
                            ArtifactExport(v1.record.id, "acme/x/y", 1, v1.detail.bodyHash, null, body),
                            AUTHOR,
                        )
                    },
                    kind.codes.authoringDisabled to { receiver().create("acme/codes/three") },
                )
            triggers.forEach { (code, trigger) -> refusal(trigger).code shouldBe code }
            // The two codes the generic core does NOT raise: body_invalid (the family's validator) and
            // release.dependency_not_released (the family's release) — their suites cover them.
            val owned =
                ArtifactCodes::class.java.declaredFields
                    .filter { it.type == String::class.java }
                    .map {
                        it.isAccessible = true
                        it.get(kind.codes) as String
                    }.toSet() - setOf(kind.codes.bodyInvalid, kind.codes.dependencyNotReleased)
            triggers.keys shouldBe owned
        }
    }

    @Test
    fun `every draft name across workspaces - the authoring-disabled boot check's evidence, per family`() {
        val visualizations = family(ArtifactKind.VISUALIZATION)
        visualizations.create("acme/a/one")
        visualizations.create("acme/b/two", workspace = OTHER_WORKSPACE)
        visualizations.released("acme/c/three")
        visualizations.repository.findAllDraftNames() shouldBe listOf("acme/a/one", "acme/b/two")
        family(ArtifactKind.DASHBOARD).repository.findAllDraftNames().shouldBeEmpty()
    }

    @Test
    fun `an artifact's lifecycle read under the everything lens is the working version - the draft, else the current`() {
        val dashboards = family(ArtifactKind.DASHBOARD)
        val v1 = dashboards.released()
        val working = dashboards.lifecycle.findWorking(WORKSPACE, ReadLens.Everything, v1.record.id)
        working.shouldBeInstanceOf<ArtifactVersion<*>>().detail.version shouldBe 1
    }
}
