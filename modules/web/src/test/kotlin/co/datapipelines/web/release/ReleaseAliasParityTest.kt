package co.datapipelines.web.release

import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #873 §C.3–§C.5 and §C.8 (record §16 rule 4: "do not silently broaden legacy direct-template consent to all drafts"):
 * the compatibility path of the four artifact roots — no plan field — keeps today's consent and today's refusal keys.
 *
 * - The alias consents to EXACTLY the direct drafts its flag named before: a set's and a visualization's DRAFT templates,
 *   a dashboard's DRAFT visualizations (never their templates, its set or its source pipelines), a template nothing.
 * - Each family's refusal `details` equal a RECORDED fixture: the maps below were copied from the native refusals at
 *   the base (`ParameterSetService.draftPinsToRelease`, `VisualizationService.templatesToRelease`,
 *   `DashboardService.dependenciesToRelease`, spec base 13350156); the template root, which had no dependency refusal,
 *   answers the shared shape (record §14).
 * - A dashboard's DRAFT source pipeline is a `dependency_not_released` of kind `pipeline` on this path (§C.4).
 * - §C.8: the root is prepared outside the transaction; the alias's dependencies are prepared and committed inside it,
 *   as today's cascade ran.
 */
class ReleaseAliasParityTest {
    private val fx = CoordinatorFixture()
    private val g = ArtifactRootGraph(fx)

    private fun refused(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException> { block() }

    @Test
    fun `parameter set - without the alias its DRAFT template refuses in today's keys, with it the template flips first`() {
        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(g.set)) }

        error.code shouldBe "parameter.release.dependency_not_released"
        error.details shouldBe
            mapOf(
                "template_id" to "test/set_source.sql",
                "template_version" to 1,
                "template_status" to "DRAFT",
                "pins_not_released" to
                    listOf(mapOf("template_id" to "test/set_source.sql", "template_version" to 1, "template_status" to "DRAFT")),
            )
        fx.log.none { it.startsWith("flip:") } shouldBe true

        val released = fx.coordinator().releaseRoot(fx.rootRequest(g.set), releasePinned = true)
        released.dependenciesReleased shouldContainExactly listOf(ReleasedDependency(ReleaseFamily.TEMPLATE, "test/set_source.sql", 1))
        fx.writes().filter { it.startsWith("flip:") } shouldContainExactly
            listOf("flip:test/set_source.sql@hash-test/set_source.sql", "flip:test/sets/regions@hash-test/sets/regions")
    }

    @Test
    fun `visualization - without the alias its DRAFT transform template refuses in today's keys, with it the template flips`() {
        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(g.chart)) }

        error.code shouldBe "visualization.release.dependency_not_released"
        error.details shouldBe
            mapOf(
                "template" to "test/chart_transform.sql",
                "template_version" to 1,
                "template_status" to "DRAFT",
                "pins_not_released" to
                    listOf(mapOf("template" to "test/chart_transform.sql", "template_version" to 1, "template_status" to "DRAFT")),
            )

        fx.coordinator().releaseRoot(fx.rootRequest(g.chart), releasePinned = true).dependenciesReleased shouldContainExactly
            listOf(ReleasedDependency(ReleaseFamily.TEMPLATE, "test/chart_transform.sql", 1))
    }

    @Test
    fun `dashboard - the DRAFT source pipeline and the DRAFT visualization refuse in today's keys, the pipeline as kind pipeline`() {
        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(g.board)) }

        error.code shouldBe "dashboard.release.dependency_not_released"
        val pipeline = mapOf("kind" to "pipeline", "name" to "test/source", "version" to 1, "status" to "DRAFT")
        val visualization = mapOf("kind" to "visualization", "name" to "test/charts/board", "version" to 1, "status" to "DRAFT")
        error.details shouldBe pipeline + ("dependencies_not_released" to listOf(pipeline, visualization))
    }

    @Test
    fun `dashboard - the alias consents to the DRAFT visualization ONLY - the DRAFT source pipeline still refuses`() {
        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(g.board), releasePinned = true) }

        error.code shouldBe "dashboard.release.dependency_not_released"
        val pipeline = mapOf("kind" to "pipeline", "name" to "test/source", "version" to 1, "status" to "DRAFT")
        error.details shouldBe pipeline + ("dependencies_not_released" to listOf(pipeline))
        fx.log.none { it.startsWith("flip:") } shouldBe true
    }

    @Test
    fun `dashboard - the alias does not reach the visualization's DRAFT template or the dashboard's DRAFT set`() {
        val draftTemplate = fx.template("test/deep_transform.sql")
        val chart = fx.artifact(ReleaseFamily.VISUALIZATION, "test/charts/deep", listOf(draftTemplate))
        val set = fx.artifact(ReleaseFamily.PARAMETER_SET, "test/sets/board", status = ReleaseNodeStatus.DRAFT)
        val board = fx.artifact(ReleaseFamily.DASHBOARD, "test/boards/deep", listOf(set, chart))

        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(board), releasePinned = true) }

        error.code shouldBe "dashboard.release.dependency_not_released"
        val setEntry = mapOf("kind" to "parameter_set", "name" to "test/sets/board", "version" to 1, "status" to "DRAFT")
        val templateEntry = mapOf("kind" to "template", "name" to "test/deep_transform.sql", "version" to 1, "status" to "DRAFT")
        error.details shouldBe setEntry + ("dependencies_not_released" to listOf(setEntry, templateEntry))
        fx.log.none { it.startsWith("flip:") } shouldBe true
    }

    @Test
    fun `dashboard - with a RELEASED source the alias flips the DRAFT visualization first, prepared and committed in the transaction`() {
        val released = fx.template("test/ok_transform.sql", status = ReleaseNodeStatus.RELEASED)
        val chart = fx.artifact(ReleaseFamily.VISUALIZATION, "test/charts/ok", listOf(released))
        val source = fx.pipeline("test/released_source", status = ReleaseNodeStatus.RELEASED)
        val board = fx.artifact(ReleaseFamily.DASHBOARD, "test/boards/ok", listOf(source, chart))

        fx.coordinator().releaseRoot(fx.rootRequest(board), releasePinned = true).dependenciesReleased shouldContainExactly
            listOf(ReleasedDependency(ReleaseFamily.VISUALIZATION, "test/charts/ok", 1))

        // §C.8's phase table on the compatibility path: the root outside, the alias's dependency inside.
        fx.phases shouldContainExactly
            listOf(
                "prepare:test/boards/ok:false",
                "prepare:test/charts/ok:true",
                "commit:test/charts/ok:true",
                "commit:test/boards/ok:true",
            )
        fx.writes().filter { it.startsWith("audit:") } shouldContainExactly
            listOf("audit:visualization.version.released:test/charts/ok", "audit:dashboard.version.released:test/boards/ok")
    }

    @Test
    fun `template - no alias exists - a DRAFT import refuses the shared shape whatever the flag says`() {
        listOf(false, true).forEach { releasePinned ->
            withClue("releasePinned=$releasePinned") {
                val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(g.template), releasePinned = releasePinned) }
                error.code shouldBe "template.release.dependency_not_released"
                error.details.keys shouldBe setOf("root", "blockers", "blockers_truncated")
                @Suppress("UNCHECKED_CAST")
                val blockers = error.details["blockers"] as List<Map<String, Any?>>
                blockers.map { it["name"] to it["reason"] } shouldContainExactly listOf("test/lib.sql" to "not_included")
            }
        }
        fx.log.none { it.startsWith("flip:") } shouldBe true
    }

    @Test
    fun `a deeper DRAFT beneath a consented template stays a blocker - the alias is never transitive`() {
        val deep = fx.template("test/deep_lib.sql")
        val direct = fx.template("test/direct.sql", imports = listOf(deep))
        val set = fx.artifact(ReleaseFamily.PARAMETER_SET, "test/sets/deep", listOf(direct))

        val error = refused { fx.coordinator().releaseRoot(fx.rootRequest(set), releasePinned = true) }

        error.code shouldBe "parameter.release.dependency_not_released"
        error.details["template_id"] shouldBe "test/deep_lib.sql"
        @Suppress("UNCHECKED_CAST")
        val listed = (error.details["pins_not_released"] as List<Map<String, Any?>>).map { it["template_id"] }
        withClue("the consented DRAFT direct pin is still named, as draftPinsToRelease named every non-RELEASED pin") {
            listed shouldContainExactly listOf("test/direct.sql", "test/deep_lib.sql")
        }
        fx.log.none { it.startsWith("flip:") } shouldBe true
    }
}
