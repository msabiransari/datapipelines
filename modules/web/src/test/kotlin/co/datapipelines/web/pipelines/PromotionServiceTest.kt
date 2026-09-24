package co.datapipelines.web.pipelines

import co.datapipelines.auth.PromotionProperties
import co.datapipelines.pipeline.CurrentPipelineVersion
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateImport
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The SENDER's rules over mocked repositories and a mocked target (versioning §10.2–§10.5).
 *
 * The two-deployment E2E proves promotion works end to end; it cannot cheaply enumerate the
 * decision table, because every row there costs two application contexts. These are the rows:
 * what [PromotionService.plan] lists and what it silently drops, which refusal each §10.3
 * guard raises, and — the property the whole round rests on — that the batch is ordered
 * children-before-parents and imports-before-importers.
 *
 * The ordering assertions are written against the BATCH the sender hands the client, captured
 * with a slot, rather than against anything the receiver did: push order is the sender's
 * contract (§10.4 — "the receiver applies them in the order given and does not re-derive it"),
 * so it is the sender's test that has to pin it.
 */
class PromotionServiceTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val client = mockk<PromotionTargetClient>()

    private val workspaceId = UUID.randomUUID()
    private val workspace = "analytics"

    private val properties =
        PromotionProperties(
            serverKey = null,
            target = PromotionProperties.Target(baseUrl = TARGET_URL, serverKey = "sender-side-secret"),
        )

    private val service = PromotionService(pipelines, templates, client, properties, "dev")

    init {
        every { client.targetBaseUrl } returns TARGET_URL
        // 178: the push path rebuilds §10.2's view over the FRESH inventory; the ordering tests
        // below carry roots the target lacks, so an empty local view is the honest default —
        // the root guard only consults it for a name the target already serves.
        every { pipelines.findCurrentVersions(workspaceId) } returns emptyList()
        every { templates.findCurrentVersions(workspaceId) } returns emptyList()
        every { client.invalidate(workspace) } returns Unit
    }

    // ---------------------------------------------------------------- §10.2, the listing rule

    @Test
    fun `plan lists released pipelines the target does not already serve, and nothing else`() {
        // One row per reason §10.2 gives for listing or not listing a pipeline. Since 178 the
        // rule is PromotableView's over the one-join current-version read, which by
        // construction carries no draft: a draft-only pipeline has no row (D55), see the
        // never-released test below.
        val newer = record("newer", version = 3)
        val same = record("same_hash", version = 4)
        val behind = record("behind", version = 1)
        val absent = record("absent_there", version = 1)
        every { pipelines.findCurrentVersions(workspaceId) } returns
            listOf(
                current(newer, "hash-newer"),
                current(same, "hash-shared"),
                current(behind, "hash-behind"),
                current(absent, "hash-absent"),
            )
        every { client.cachedInventory(workspace) } returns
            present(
                inventory(
                    pipelines =
                        listOf(
                            entry("newer", 2, "hash-older"),
                            // Same content at a lower number: hash wins, nothing to push (§10.2).
                            entry("same_hash", 3, "hash-shared"),
                            // The target is AHEAD — never listed.
                            entry("behind", 5, "hash-theirs"),
                        ),
                ),
            )

        val plan = service.plan(workspaceId, workspace)

        plan.promotable.map { it.name } shouldContainExactly listOf("absent_there", "newer")
        plan.promotable.single { it.name == "newer" }.targetVersion shouldBe 2
        // A pipeline the target does not have counts as version 0 (§10.2).
        plan.promotable.single { it.name == "absent_there" }.targetVersion shouldBe 0
        // Every live pipeline with a current version was examined, so an empty listing reads
        // as "in sync", not "broken".
        plan.examined shouldBe 4
        plan.targetDeployment shouldBe "uat"
        plan.workspace shouldBe workspace
    }

    @Test
    fun `plan IS the promotable view - one rule for the page and the lens`() {
        // Gate 4 (178): the listing the page renders and the set the lens admits are the same
        // computation, asserted by comparing the plan with the view built from the same rows.
        val newer = record("newer", version = 3)
        val behind = record("behind", version = 1)
        val rows = listOf(current(newer, "hash-newer"), current(behind, "hash-behind"))
        val inventory = inventory(pipelines = listOf(entry("newer", 2, "hash-older"), entry("behind", 5, "hash-theirs")))
        every { pipelines.findCurrentVersions(workspaceId) } returns rows
        every { client.cachedInventory(workspace) } returns present(inventory)

        val plan = service.plan(workspaceId, workspace)
        val view = PromotableView.of(rows, emptyList(), inventory)

        plan.promotable.map { it.name } shouldContainExactly view.pipelines.map { it.name }
        view.pipelineLens.admits("newer") shouldBe true
        view.pipelineLens.admits("behind") shouldBe false
    }

    @Test
    fun `plan re-raises an unreadable target as the page's error state, with the reason`() {
        every { client.cachedInventory(workspace) } returns
            PromotionTargetClient.CachedInventory.Unreachable(
                "ConnectException",
                PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE,
            )

        val thrown = shouldThrow<ApiException> { service.plan(workspaceId, workspace) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE
        thrown.details["reason"] shouldBe "ConnectException"
        thrown.details["target"] shouldBe TARGET_URL
    }

    @Test
    fun `plan skips a pipeline with no current version at all`() {
        // The one-join read has no row for it — the rule never sees it, examined counts it out.
        every { pipelines.findCurrentVersions(workspaceId) } returns emptyList()
        every { client.cachedInventory(workspace) } returns present(inventory())

        service.plan(workspaceId, workspace).promotable.shouldBeEmptyList()
    }

    @Test
    fun `a never-released pipeline is not a promotion candidate`() {
        // D55's everyday shape — created, never released, `current_version` null. Promotion still
        // pushes released versions only (D6), so this pipeline simply is not offered; the rule did
        // not change, but the state it excludes is now the one every new pipeline starts in.
        // Since 178 that exclusion is the READ's: `findCurrentVersions` joins on the pointer,
        // so a NULL pointer yields no row, and the view never has to ask a status.
        every { pipelines.findCurrentVersions(workspaceId) } returns emptyList()
        every { client.cachedInventory(workspace) } returns present(inventory())

        val plan = service.plan(workspaceId, workspace)

        plan.promotable.shouldBeEmptyList()
        plan.examined shouldBe 0
    }

    // ------------------------------------------------------------------ §10.3, the push guards

    @Test
    fun `promote refuses a target that has authoring enabled`() {
        every { client.inventory(workspace) } returns inventory(authoringEnabled = true)

        val thrown = shouldThrow<ApiException> { service.promote(workspaceId, workspace, listOf("anything")) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_TARGET_IS_AUTHORING
        // Refused before any work: no repository read, nothing pushed.
        verify(exactly = 0) { client.push(any()) }
    }

    @Test
    fun `promote refuses a root whose current version is a draft`() {
        val drafted = record("drafted", version = 2)
        every { client.inventory(workspace) } returns inventory()
        every { pipelines.findByName(workspaceId, "drafted") } returns drafted
        every { pipelines.findCurrentVersionDetail(workspaceId, drafted.id) } returns
            detail(drafted, "hash", PipelineVersionStatus.DRAFT)

        val thrown = shouldThrow<ApiException> { service.promote(workspaceId, workspace, listOf("drafted")) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_NOT_RELEASED
        verify(exactly = 0) { client.push(any()) }
    }

    @Test
    fun `promote refuses a root the target already serves at the same version`() {
        val level = record("level", version = 4)
        every { client.inventory(workspace) } returns
            inventory(pipelines = listOf(entry("level", 4, "hash-theirs")))
        every { pipelines.findByName(workspaceId, "level") } returns level
        every { pipelines.findCurrentVersionDetail(workspaceId, level.id) } returns detail(level, "hash-ours")

        val thrown = shouldThrow<ApiException> { service.promote(workspaceId, workspace, listOf("level")) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_NOT_NEWER
        verify(exactly = 0) { client.push(any()) }
    }

    @Test
    fun `promote refuses a root whose content the target already serves under a lower number - the page's rule, not a second one`() {
        // 178: before, the page hid a same-hash root while the push guard let it through. One
        // rule now — a root the view does not list is not newer, whichever clause dropped it.
        val level = record("level", version = 5)
        every { client.inventory(workspace) } returns inventory(pipelines = listOf(entry("level", 3, "hash-shared")))
        every { pipelines.findCurrentVersions(workspaceId) } returns listOf(current(level, "hash-shared"))
        every { pipelines.findByName(workspaceId, "level") } returns level
        every { pipelines.findCurrentVersionDetail(workspaceId, level.id) } returns detail(level, "hash-shared")

        val thrown = shouldThrow<ApiException> { service.promote(workspaceId, workspace, listOf("level")) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_NOT_NEWER
        thrown.message.orEmpty().contains("same content") shouldBe true
        verify(exactly = 0) { client.push(any()) }
    }

    @Test
    fun `promote carries a root the target serves at a lower version, and invalidates the lens's cache after the push`() {
        val ahead = record("ahead", version = 2)
        stubReleased(ahead, bodyOf("ahead"))
        every { client.inventory(workspace) } returns inventory(pipelines = listOf(entry("ahead", 1, "hash-older")))
        every { pipelines.findCurrentVersions(workspaceId) } returns listOf(current(ahead, "hash-ahead"))
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("ahead")) }

        batch.pipelines.map { it.get("name").asText() } shouldContainExactly listOf("ahead")
        // The push path read the FRESH inventory, never the lens's cached one …
        verify(exactly = 0) { client.cachedInventory(any()) }
        // … and dropped the cached entry so the next lensed read sees the target as it now is.
        verify(exactly = 1) { client.invalidate(workspace) }
    }

    // ------------------------------------------------------- §10.5, datasource pre-validation

    @Test
    fun `promote refuses the whole batch when a datasource is missing on the target, naming every one`() {
        val parent = record("parent", version = 1)
        stubReleased(parent, bodyOf("parent", sources = listOf("warehouse", "ledger")))
        every { client.inventory(workspace) } returns inventory(datasources = listOf("warehouse"))
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val thrown = shouldThrow<ApiException> { service.promote(workspaceId, workspace, listOf("parent")) }

        thrown.code shouldBe PipelineErrorCodes.Versioning.PROMOTION_MISSING_DATASOURCES
        // Consolidated and sorted — one refusal naming all of them, not a mid-batch failure.
        thrown.details["missing_datasources"] shouldBe listOf("ledger")
        // Nothing was pushed: the target is byte-unchanged.
        verify(exactly = 0) { client.push(any()) }
    }

    // ------------------------------------------------------------------ §10.4, the push order

    @Test
    fun `the batch carries children before their parents`() {
        // parent -> child -> grandchild. The push must land the deepest first, or the receiver's
        // import cannot resolve the PIPELINE node of whatever arrives before its callee.
        val parent = record("parent", version = 5)
        val child = record("child", version = 2)
        val grandchild = record("grandchild", version = 7)
        stubReleased(parent, bodyOf("parent", child = "child" to 2))
        stubReleased(child, bodyOf("child", child = "grandchild" to 7), atVersion = 2)
        stubReleased(grandchild, bodyOf("grandchild"), atVersion = 7)
        every { client.inventory(workspace) } returns inventory()
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("parent")) }

        batch.pipelines.map { it.get("id").asText() } shouldContainExactly
            listOf(grandchild.id.toString(), child.id.toString(), parent.id.toString())
    }

    @Test
    fun `a child shared by two parents is carried once, ahead of both`() {
        val left = record("left", version = 1)
        val right = record("right", version = 1)
        val shared = record("shared", version = 3)
        stubReleased(left, bodyOf("left", child = "shared" to 3))
        stubReleased(right, bodyOf("right", child = "shared" to 3))
        stubReleased(shared, bodyOf("shared"), atVersion = 3)
        every { client.inventory(workspace) } returns inventory()
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("left", "right")) }

        val ids = batch.pipelines.map { it.get("id").asText() }
        ids.count { it == shared.id.toString() } shouldBe 1
        ids.indexOf(shared.id.toString()) shouldBe 0
        ids.size shouldBe 3
    }

    @Test
    fun `template imports are carried before the templates that import them`() {
        val root = record("root", version = 1)
        stubReleased(root, bodyOf("root", template = "test/importer.sql" to 4))
        every { client.inventory(workspace) } returns inventory()
        // test/importer.sql imports test/base.sql@2 — the transitive imports_json closure (§10.4 step 1).
        every { templates.lookupVersion(workspaceId, "test/importer.sql", 4) } returns
            templateVersion("test/importer.sql", 4, imports = listOf(TemplateImport("test/base.sql", 2, "base")))
        every { templates.lookupVersion(workspaceId, "test/base.sql", 2) } returns templateVersion("test/base.sql", 2)
        every { templates.findVersion(workspaceId, "test/importer.sql", 4) } returns storedTemplate("test/importer.sql", 4)
        every { templates.findVersion(workspaceId, "test/base.sql", 2) } returns storedTemplate("test/base.sql", 2)

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("root")) }

        batch.templates.map { it.get("id").asText() } shouldContainExactly listOf("test/base.sql", "test/importer.sql")
    }

    @Test
    fun `a dependency already on the target at the same version and hash is left out of the batch`() {
        val parent = record("parent", version = 2)
        val child = record("child", version = 1)
        stubReleased(parent, bodyOf("parent", child = "child" to 1), hash = "hash-parent")
        stubReleased(child, bodyOf("child"), atVersion = 1, hash = "hash-child")
        // The target already holds the child at exactly this version and content.
        every { client.inventory(workspace) } returns
            inventory(pipelines = listOf(entry("child", 1, "hash-child")))
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("parent")) }

        batch.pipelines.map { it.get("id").asText() } shouldContainExactly listOf(parent.id.toString())
    }

    @Test
    fun `the batch names the source deployment and a key fingerprint, never the key`() {
        val only = record("only", version = 1)
        stubReleased(only, bodyOf("only"))
        every { client.inventory(workspace) } returns inventory()
        every { templates.lookupVersion(workspaceId, any(), any()) } returns null

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("only")) }

        batch.sourceEnv shouldBe "dev"
        batch.workspace shouldBe workspace
        batch.keyFingerprint.startsWith("sha256:") shouldBe true
        batch.keyFingerprint.contains("sender-side-secret") shouldBe false
    }

    // ----------------------------------------------------------------- 7b: the transform blocks

    @Test
    fun `a transform template's payload carries its contract, invariants and tests`() {
        // 7b §A.3(4): the payload is built field by field, so the three blocks ride only
        // because templatePayloadOf sets them — and the receiver's hash guard recomputes
        // over them, so a dropped block is a refused import, not a silent loss.
        val root = record("root", version = 1)
        stubReleased(root, bodyOf("root", template = "test/xform.jsonata" to 1))
        every { templates.lookupVersion(workspaceId, "test/xform.jsonata", 1) } returns
            TemplateVersion(
                id = "test/xform.jsonata",
                version = 1,
                type = co.datapipelines.pipeline.TemplateType.JSONATA,
                isLibrary = false,
                imports = emptyList(),
                body = "rows",
                createdAt = EPOCH,
                createdBy = UUID.randomUUID(),
            )
        val contract =
            TransformContract(
                mode = TransformMode.ROW,
                inputs = mapOf("orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER)))),
                output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
            )
        val stored =
            storedTemplate("test/xform.jsonata", 1).copy(
                type = co.datapipelines.pipeline.TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                contract = contract,
                invariants = emptyList(),
                tests = emptyList(),
            )
        every { templates.findVersion(workspaceId, "test/xform.jsonata", 1) } returns stored
        every { client.inventory(workspace) } returns inventory()

        val batch = capturePush { service.promote(workspaceId, workspace, listOf("root")) }

        val payload = batch.templates.single()
        payload["type"].asText() shouldBe "jsonata"
        payload["engine"].asText() shouldBe "none"
        payload["contract"]["mode"].asText() shouldBe "row"
        payload["contract"]["inputs"]["orders"]["kind"].asText() shouldBe "table"
        payload["invariants"].isArray shouldBe true
        payload["tests"].isArray shouldBe true
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun capturePush(promote: () -> Unit): PromotionWire.Batch {
        val captured = slot<PromotionWire.Batch>()
        every { client.push(capture(captured)) } returns PromotionWire.Applied(workspace, "dev", 0, 0)
        promote()
        return captured.captured
    }

    /**
     * Everything [PromotionService.Closure] reads for one pipeline it decides to carry. Named
     * for what it means rather than for the four calls it stubs, because every ordering test
     * needs the whole set and none of them cares which call supplied which field.
     */
    private fun stubReleased(
        record: PipelineRecord,
        body: String,
        atVersion: Int = checkNotNull(record.currentVersion),
        hash: String = "hash-${record.name}",
    ) {
        every { pipelines.findByName(workspaceId, record.name) } returns record
        every { pipelines.findByNameAnyStatus(workspaceId, record.name) } returns record
        every { pipelines.findCurrentVersionDetail(workspaceId, record.id) } returns detail(record, hash)
        every { pipelines.findVersionDetail(workspaceId, record.id, atVersion) } returns
            detail(record, hash).copy(version = atVersion)
        every { pipelines.findVersionBody(workspaceId, record.id, atVersion) } returns body
        every { pipelines.releasedAtFor(workspaceId, any()) } returns emptyMap()
    }

    private fun record(
        name: String,
        version: Int,
    ) = PipelineRecord(
        id = UUID.randomUUID(),
        name = name,
        displayName = name.replaceFirstChar(Char::uppercase),
        description = "",
        ownerId = UUID.randomUUID(),
        currentVersion = version,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun current(
        record: PipelineRecord,
        hash: String,
    ) = CurrentPipelineVersion(record.id, record.name, record.displayName, checkNotNull(record.currentVersion), hash)

    private fun present(inventory: PromotionWire.Inventory) = PromotionTargetClient.CachedInventory.Present(inventory)

    private fun detail(
        record: PipelineRecord,
        hash: String,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = PipelineVersionDetail(
        pipelineId = record.id,
        version = checkNotNull(record.currentVersion),
        status = status,
        bodyHash = hash,
        createdAt = EPOCH,
        createdBy = UUID.randomUUID(),
    )

    private fun inventory(
        authoringEnabled: Boolean = false,
        pipelines: List<PromotionWire.Entry> = emptyList(),
        templates: List<PromotionWire.Entry> = emptyList(),
        datasources: List<String> = listOf("warehouse", "ledger"),
    ) = PromotionWire.Inventory(
        deployment = "uat",
        authoringEnabled = authoringEnabled,
        workspace = workspace,
        pipelines = pipelines,
        templates = templates,
        datasources = datasources,
    )

    private fun entry(
        name: String,
        version: Int,
        hash: String,
    ) = PromotionWire.Entry(name, version, hash)

    private fun templateVersion(
        id: String,
        version: Int,
        imports: List<TemplateImport> = emptyList(),
    ) = TemplateVersion(
        id = id,
        version = version,
        isLibrary = false,
        imports = imports,
        body = "SELECT 1",
        createdAt = EPOCH,
        createdBy = UUID.randomUUID(),
    )

    private fun storedTemplate(
        id: String,
        version: Int,
    ) = Template(
        id = id,
        version = version,
        dialect = null,
        displayName = id,
        description = "",
        body = "SELECT 1",
        createdAt = EPOCH,
        createdBy = UUID.randomUUID(),
        bodyHash = "hash-$id",
    )

    /**
     * One pipeline body. [child] adds a PIPELINE node at a PINNED version (§10.4's recursive
     * half), [template] a template reference, [sources] the datasource names §10.5 collects.
     */
    private fun bodyOf(
        name: String,
        child: Pair<String, Int>? = null,
        template: Pair<String, Int>? = null,
        sources: List<String> = listOf("warehouse"),
    ): String {
        val dql =
            sources.mapIndexed { index, source ->
                val ref = template ?: ("test/q_$name.sql" to 1)
                """
                {"id": "n$index", "type": "DQL", "source": "$source",
                 "template": {"id": "${ref.first}", "version": ${ref.second}},
                 "output": {"target": "tempdb", "table": "t$index"}, "depends_on": []}
                """.trimIndent()
            }
        val pipelineNode =
            child?.let {
                """
                {"id": "run_child", "type": "PIPELINE",
                 "pipeline": {"name": "${it.first}", "version": ${it.second}}, "depends_on": []}
                """.trimIndent()
            }
        val nodes = (dql + listOfNotNull(pipelineNode)).joinToString(",\n")
        return """
            {
              "schema_version": 1,
              "name": "$name",
              "display_name": "$name",
              "description": "",
              "settings": {"tempdb": {"engine": "H2"}},
              "parameters": {},
              "nodes": [$nodes]
            }
            """.trimIndent()
    }

    private fun <T> List<T>.shouldBeEmptyList() = this.size shouldBe 0

    private companion object {
        const val TARGET_URL = "https://uat.example.com"
        val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
