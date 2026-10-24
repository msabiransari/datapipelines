package co.datapipelines.web.release

import co.datapipelines.pipeline.release.CheckOverride
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * The release coordinator's algorithm (record §4.1 steps 1–7; brief A.4) over the real pipeline and template participants
 * with their native services mocked ([CoordinatorFixture]): dependency-first order, the root last, a shared node flipped
 * once, the guard before any flip, `plan_conflict` on any drift between the preview's digest and the re-resolution under
 * the guard, the permission recheck per flipped family, the strict audit row per flip in the one transaction, and the
 * arming rule ([co.datapipelines.pipeline.release.LegacyReleaseAlias.LEGACY_ALIAS_WITHOUT_PLAN_ADMITTED]) both ways.
 *
 * The shared graph: pipeline `a` pins template `t1` (DRAFT), child pipeline `c` (DRAFT) and template `t2` (RELEASED);
 * `c` pins `t1` again — the diamond.
 */
class ReleaseCoordinatorTest {
    private val fx = CoordinatorFixture()
    private val t1 = fx.template("test/t1.sql")
    private val t2 = fx.template("test/t2.sql", status = ReleaseNodeStatus.RELEASED)
    private val c = fx.pipeline("test/c", listOf(t1))
    private val a = fx.pipeline("test/a", listOf(t1, c, t2))

    private fun refused(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException> { block() }

    @Test
    fun `a plan flips dependencies first, the shared node once and the root last, guard first, one transaction`() {
        val preview = fx.preview(a)

        val released = fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c))))

        fx.writes() shouldContainExactly
            listOf(
                "prepare:test/c",
                "prepare:test/a",
                "begin",
                "guard:pipeline",
                "flip:test/t1.sql@hash-test/t1.sql",
                "flip:test/c@hash-test/c",
                "flip:test/a@hash-test/a",
                "audit:template.version.released:test/t1.sql",
                "audit:pipeline.version.released:test/c",
                "audit:pipeline.version.released:test/a",
                "commit",
            )
        released.dependenciesReleased shouldContainExactly
            listOf(ReleasedDependency(ReleaseFamily.TEMPLATE, "test/t1.sql", 1), ReleasedDependency(ReleaseFamily.PIPELINE, "test/c", 1))
        released.auditedInTransaction shouldBe true
    }

    @Test
    fun `every audit row carries the one release_id, cascaded rows name the root and the root lists what it released`() {
        val preview = fx.preview(a)

        fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c))))

        withClue("one row per actual flip - nodes, not paths: ${fx.audited}") { fx.audited.size shouldBe 3 }
        fx.audited.map { it.details["release_id"] }.toSet() shouldBe setOf(fx.releaseId.toString())
        fx.audited.map { it.details["scope"] }.toSet() shouldBe setOf("version")
        fx.audited.map { it.userId }.toSet() shouldBe setOf(fx.actor)
        val root = mapOf("family" to "pipeline", "id" to fx.recordOf("test/a").id.toString(), "version" to 1)
        fx.audited.dropLast(1).forEach { row -> row.details["cascade_root"] shouldBe root }
        val rootRow = fx.audited.last()
        rootRow.details.containsKey("cascade_root") shouldBe false
        rootRow.details["dependencies_released"] shouldBe
            listOf(
                mapOf("family" to "template", "name" to "test/t1.sql", "version" to 1),
                mapOf("family" to "pipeline", "name" to "test/c", "version" to 1),
            )
        // The #142 keys stay on the template row and on the root's.
        fx.audited.first().details["cascade_from_pipeline_id"] shouldBe fx.recordOf("test/a").id.toString()
        rootRow.details["templates_released"] shouldBe listOf(mapOf("template_id" to "test/t1.sql", "version" to 1))
    }

    @Test
    fun `permission is checked for every flipped family before any preparation and again under the guard`() {
        val preview = fx.preview(a)

        fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c))))

        val firstPrepare = fx.log.indexOf("prepare:test/c")
        val guard = fx.log.indexOf("guard:pipeline")
        fx.log.subList(0, firstPrepare).toSet() shouldBe setOf("auth:template", "auth:pipeline")
        fx.log
            .subList(guard, fx.log.indexOf("flip:test/t1.sql@hash-test/t1.sql"))
            .filter { it.startsWith("auth:") }
            .toSet() shouldBe
            setOf("auth:template", "auth:pipeline")
    }

    @Test
    fun `a stale preview is plan_conflict before any preparation, guard or flip`() {
        val preview = fx.preview(a)
        fx.edit(t1, "edited-without-a-version-change")

        val error = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c)))) }

        error.code shouldBe "pipeline.release.plan_conflict"
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `a dependency body changed during preparation is caught by the re-resolution under the guard, before any flip`() {
        val preview = fx.preview(a)
        fx.onPrepare["test/a"] = { fx.edit(t1, "edited-while-the-checks-ran") }

        val error = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c)))) }

        error.code shouldBe "pipeline.release.plan_conflict"
        fx.writes() shouldContainExactly listOf("prepare:test/c", "prepare:test/a", "begin", "guard:pipeline", "rollback")
        // The re-resolution answers first: no participant's compare-and-set is ever reached.
        verify(exactly = 0) { fx.templateReleases.releaseExact(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a pointer moved during preparation on a node nobody flips is plan_conflict - only the digest can see it`() {
        val preview = fx.preview(a)
        fx.onPrepare["test/a"] = { fx.moveCurrent(t2, 2) }

        val error = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c)))) }

        error.code shouldBe "pipeline.release.plan_conflict"
        fx.writes() shouldNotContain "commit"
        fx.audited shouldBe emptyList()
    }

    @Test
    fun `an audit row the sink cannot write refuses the release and rolls every flip back`() {
        val preview = fx.preview(a)
        fx.auditFails = true

        shouldThrow<IllegalStateException> {
            fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1, c))))
        }

        fx.writes().last() shouldBe "rollback"
        fx.writes() shouldNotContain "commit"
    }

    @Test
    fun `a DRAFT the plan leaves unticked blocks with the shared details shape and nothing is prepared`() {
        val preview = fx.preview(a)

        val error = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, preview.ids(t1)))) }

        error.code shouldBe "pipeline.release.dependency_not_released"
        @Suppress("UNCHECKED_CAST")
        val blockers = error.details["blockers"] as List<Map<String, Any?>>
        blockers.map { it["name"] to it["reason"] } shouldBe listOf("test/c" to "not_included")
        error.details["blockers_truncated"] shouldBe false
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `a MISSING pin blocks even when every DRAFT is ticked`() {
        val m = fx.pipeline("test/m", listOf(t1, fx.absent("test/gone.sql")))
        val preview = fx.preview(m)

        val error = refused { fx.coordinator().release(fx.request(m, ReleasePlanRequest(preview.treeHash, preview.ids(t1)))) }

        error.code shouldBe "pipeline.release.dependency_not_released"
        @Suppress("UNCHECKED_CAST")
        val blockers = error.details["blockers"] as List<Map<String, Any?>>
        blockers.map { Triple(it["name"], it["status"], it["reason"]) } shouldBe listOf(Triple("test/gone.sql", "MISSING", "missing"))
    }

    @Test
    fun `the selection names DRAFT dependencies of this snapshot only, each once, never the root`() {
        val preview = fx.preview(a)
        val cases =
            mapOf(
                "the root" to listOf(preview.nodeIds.getValue(a)),
                "a RELEASED node" to preview.ids(t2),
                "an unknown node" to listOf("n99"),
                "a node twice" to preview.ids(t1, t1),
            )
        cases.forEach { (case, ids) ->
            withClue(case) {
                val error = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(preview.treeHash, ids))) }
                error.code shouldBe "pipeline.release.plan_invalid"
                error.details["field"].toString().startsWith("include_dependencies[") shouldBe true
            }
        }
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `each dependency is prepared with its OWN override reason - a root reason never waives a child's checks`() {
        val preview = fx.preview(a)
        val childOverride = CheckOverride(preview.nodeIds.getValue(c), "child checks are known red")
        val plan = ReleasePlanRequest(preview.treeHash, preview.ids(t1, c), listOf(childOverride))

        fx.coordinator().release(fx.request(a, plan, rootReason = "root reason"))

        verify { fx.releases.prepare(fx.workspaceId, fx.recordOf("test/c").id, fx.actor, "child checks are known red", true) }
        verify { fx.releases.prepare(fx.workspaceId, fx.recordOf("test/a").id, fx.actor, "root reason", true) }
    }

    @Test
    fun `an override for a node the plan does not flip, or a second override for the root, is plan_invalid`() {
        val preview = fx.preview(a)
        val notFlipped = ReleasePlanRequest(preview.treeHash, preview.ids(t1, c), listOf(CheckOverride(preview.nodeIds.getValue(t2), "r")))
        val twice = ReleasePlanRequest(preview.treeHash, preview.ids(t1, c), listOf(CheckOverride(preview.nodeIds.getValue(a), "r")))

        refused { fx.coordinator().release(fx.request(a, notFlipped)) }.details["field"] shouldBe "check_overrides[0]"
        refused { fx.coordinator().release(fx.request(a, twice, rootReason = "q")) }.details["field"] shouldBe "check_overrides[0]"
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `a plan without tree_hash is plan_required and a malformed tree_hash is plan_invalid`() {
        val preview = fx.preview(a)

        refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest(includeDependencies = preview.ids(t1, c)))) }
            .code shouldBe "pipeline.release.plan_required"
        val malformed = refused { fx.coordinator().release(fx.request(a, ReleasePlanRequest("v1:not-hex"))) }
        malformed.code shouldBe "pipeline.release.plan_invalid"
        malformed.details["field"] shouldBe "tree_hash"
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `arming rule while admitted - a bare alias is the legacy direct-template cascade, alias plus a selection is plan_invalid`() {
        val direct = fx.pipeline("test/direct", listOf(t1, fx.template("test/t3.sql"), t2))

        fx.coordinator(legacyAliasAdmitted = true).release(fx.request(direct, alias = true))

        fx.writes() shouldContainExactly
            listOf(
                "prepare:test/direct",
                "begin",
                "guard:pipeline",
                "flip:test/t1.sql@hash-test/t1.sql",
                "flip:test/t3.sql@hash-test/t3.sql",
                "flip:test/direct@hash-test/direct",
                "audit:template.version.released:test/t1.sql",
                "audit:template.version.released:test/t3.sql",
                "audit:pipeline.version.released:test/direct",
                "commit",
            )
        val preview = fx.preview(direct)
        val both = ReleasePlanRequest(preview.treeHash, preview.ids(t1))
        val error = refused { fx.coordinator(legacyAliasAdmitted = true).release(fx.request(direct, both, alias = true)) }
        error.code shouldBe "pipeline.release.plan_invalid"
        error.details["field"] shouldBe "include_dependencies"
    }

    @Test
    fun `arming rule once S6 flips it - a bare alias is plan_required and nothing runs`() {
        val error = refused { fx.coordinator(legacyAliasAdmitted = false).release(fx.request(a, alias = true)) }

        error.code shouldBe "pipeline.release.plan_required"
        fx.writes() shouldBe emptyList()
    }

    @Test
    fun `the legacy alias never reaches past the root's direct template pins - a draft child still blocks`() {
        val error = refused { fx.coordinator().release(fx.request(a, alias = true)) }

        error.code shouldBe "pipeline.release.dependency_not_released"
        @Suppress("UNCHECKED_CAST")
        val blockers = error.details["blockers"] as List<Map<String, Any?>>
        blockers.map { it["name"] } shouldBe listOf("test/c")
        fx.writes() shouldContainExactly listOf("prepare:test/a", "begin", "guard:pipeline", "rollback")
    }

    @Test
    fun `no plan and no alias - a draft pin refuses dependency_not_released naming every draft, as the native gate did`() {
        val error = refused { fx.coordinator().release(fx.request(a)) }

        error.code shouldBe "pipeline.release.dependency_not_released"
        error.message shouldBe
            "Release blocked: 2 dependencies are not released. Release them first, or include the drafts in this release."
        @Suppress("UNCHECKED_CAST")
        val blockers = error.details["blockers"] as List<Map<String, Any?>>
        blockers.map { it["name"] to it["paths"] } shouldBe
            listOf(
                "test/t1.sql" to listOf("nodes[0].template", "nodes[1].pipeline/nodes[0].template"),
                "test/c" to listOf("nodes[1].pipeline"),
            )
        error.details["root"] shouldBe mapOf("node_id" to "n0", "family" to "pipeline", "name" to "test/a", "version" to 1)
    }
}
