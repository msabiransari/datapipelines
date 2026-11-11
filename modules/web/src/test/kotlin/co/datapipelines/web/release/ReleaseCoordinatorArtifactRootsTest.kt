package co.datapipelines.web.release

import co.datapipelines.application.dependencies.ReleasePlanningDeadline
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseContext
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseGraph
import co.datapipelines.pipeline.release.ReleaseRefusal
import co.datapipelines.pipeline.release.ReleasedDependency
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrorCatalog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #873 §C (record §4.1 steps 1–7, §6): the template, parameter-set, visualization and dashboard roots on the PLAN path
 * of the shared coordinator, over their real participants with the native services mocked ([CoordinatorFixture],
 * graph [ArtifactRootGraph]) — dependencies first and the root last in ONE transaction under the guard; one strict audit
 * row per flip, all sharing one `release_id`, the root's last; every preparation outside the transaction and every
 * commit inside it (§C.8); each participant's commit the family's exact-hash flip, so a drifted dependency is the
 * root's `plan_conflict` and a stale root `If-Match` the family's native conflict; a failing audit sink rolls everything
 * back; and the cumulative planning deadline is the root family's `metadata_unavailable` 503 with `Retry-After: 5` on
 * both entry paths.
 */
class ReleaseCoordinatorArtifactRootsTest {
    private val fx = CoordinatorFixture()
    private val g = ArtifactRootGraph(fx)

    private fun refused(block: () -> Unit): DatapipelinesException = shouldThrow<DatapipelinesException> { block() }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `a plan flips the selected dependencies first and the root last, guard first, one transaction, one row per flip`(
        family: ReleaseFamily,
    ) {
        val released = fx.coordinator().releaseRoot(g.planned(family))

        fx.writes() shouldContainExactly EXPECTED_WRITES.getValue(family)
        released.dependenciesReleased shouldContainExactly
            g.dependencies(family).map { ReleasedDependency(it.family, it.entityId, it.version) }
        withClue("one row per flip, the root's last: ${fx.audited}") { fx.audited.size shouldBe g.dependencies(family).size + 1 }
        fx.audited.map { it.details["release_id"] }.toSet() shouldBe setOf(fx.releaseId.toString())
        fx.audited.last().details["dependencies_released"] shouldBe
            g.dependencies(family).map { mapOf("family" to it.family.wire, "name" to it.entityId, "version" to it.version) }
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `the phase table - every native preparation runs outside the transaction, every native commit inside it`(family: ReleaseFamily) {
        fx.coordinator().releaseRoot(g.planned(family))

        val prepares = fx.phases.filter { it.startsWith("prepare:") }
        val commits = fx.phases.filter { it.startsWith("commit:") }
        withClue("non-vacuity: the root commits natively on every path: ${fx.phases}") { commits.isNotEmpty() shouldBe true }
        withClue("prepared inside the transaction: $prepares") { prepares.all { it.endsWith(":false") } shouldBe true }
        withClue("committed outside the transaction: $commits") { commits.all { it.endsWith(":true") } shouldBe true }
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `a dependency edited during preparation is the root's plan_conflict - nothing flipped, nothing audited`(family: ReleaseFamily) {
        val request = g.planned(family)
        val drifted = g.dependencies(family).last()
        // The dashboard root and every artifact dependency prepare natively; a template root prepares nothing, so its
        // drift lands between the preview and the release instead.
        fx.onPrepare[g.root(family).entityId] = { fx.edit(drifted, "drifted") }
        if (family == ReleaseFamily.TEMPLATE) fx.edit(drifted, "drifted")

        refused { fx.coordinator().releaseRoot(request) }.code shouldBe family.code(ReleaseRefusal.PLAN_CONFLICT)
        fx.log.none { it.startsWith("flip:") } shouldBe true
        fx.audited shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION"])
    fun `each dependency participant commits through its family's exact-hash flip - a planted mismatch is the root's plan_conflict`(
        family: ReleaseFamily,
    ) {
        // A dashboard is never a dependency: its own stale draft is the native conflict below.
        val (root, dependency, participant) =
            when (family) {
                ReleaseFamily.TEMPLATE -> {
                    Triple(g.chart, g.chartTemplate, TemplateReleaseParticipant(fx.templateReleases))
                }

                ReleaseFamily.PARAMETER_SET -> {
                    Triple(
                        fx.artifact(ReleaseFamily.DASHBOARD, "test/boards/with_set", listOf(g.set)),
                        g.set,
                        ParameterSetReleaseParticipant(fx.sets),
                    )
                }

                else -> {
                    Triple(g.board, g.boardChart, VisualizationReleaseParticipant(fx.visualizations))
                }
            }
        val closure = requireNotNull(ReleaseGraph(fx.sources, ReleaseBounds()).walk(root))
        val node = closure.node(dependency)
        val consented = requireNotNull(node.bodyHash)
        val prepared = participant.prepare(node, ReleaseContext(fx.actor, closure, setOf(dependency, root), consented, null))
        fx.edit(dependency, "planted")

        val error = refused { participant.commitPrepared(prepared, SimpleTransactionStatus()) }

        error.code shouldBe root.family.code(ReleaseRefusal.PLAN_CONFLICT)
        error.details["node_id"] shouldBe node.nodeId
        fx.log.none { it.startsWith("flip:") } shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `a stale root If-Match keeps the family's native version conflict on both paths, nothing audited`(family: ReleaseFamily) {
        // On the compatibility path the alias consents to the direct drafts, so the stale root is what refuses; a
        // template has no alias, so its compatibility case is a template with nothing beneath it.
        val aliasRoot = if (family == ReleaseFamily.TEMPLATE) fx.template("test/plain.sql") else g.root(family)
        val planned = fx.rootRequest(g.root(family), g.plan(family), expectedHash = "stale")
        val alias = fx.rootRequest(aliasRoot, expectedHash = "stale")

        listOf(planned to false, alias to true).forEach { (request, releasePinned) ->
            withClue(if (releasePinned) "compatibility" else "plan") {
                val error =
                    refused {
                        fx.coordinator().releaseRoot(
                            request,
                            releasePinned =
                                releasePinned && family != ReleaseFamily.TEMPLATE,
                        )
                    }
                error.code shouldBe "${family.codePrefix}.version.conflict"
            }
        }
        fx.log.contains("commit") shouldBe false
        fx.audited shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `a failing strict audit sink rolls the whole release back - zero rows`(family: ReleaseFamily) {
        fx.auditFails = true

        shouldThrow<IllegalStateException> { fx.coordinator().releaseRoot(g.planned(family)) }

        fx.audited shouldBe emptyList()
        fx.log.last() shouldBe "rollback"
        fx.log.contains("commit") shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `the planning deadline is the root family's metadata_unavailable, 503 with Retry-After 5, on both paths, nothing written`(
        family: ReleaseFamily,
    ) {
        val plan = g.plan(family)
        val requests = listOf(fx.rootRequest(g.root(family), plan), fx.rootRequest(g.root(family)))

        requests.forEach { request ->
            withClue(if (request.plan == null) "alias" else "plan") {
                fx.reads.clear()
                val error =
                    refused {
                        fx
                            .coordinator(
                                deadline = ReleasePlanningDeadline.after(Duration.ofSeconds(BUDGET_SECONDS), clock),
                            ).releaseRoot(request)
                    }
                error.code shouldBe family.code(ReleaseRefusal.METADATA_UNAVAILABLE)
                error.details shouldBe emptyMap()
                ApiErrorCatalog.statusFor(error.code) shouldBe HttpStatus.SERVICE_UNAVAILABLE
                ApiErrorCatalog.retryAfterSeconds(error.code) shouldBe RETRY_AFTER_SECONDS
            }
        }
        fx.writes() shouldBe emptyList()
        fx.audited shouldBe emptyList()
    }

    /** Time is the number of provider reads so far, times three seconds (`ReleasePlanningDeadlineTest`'s clock). */
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.EPOCH.plusSeconds(SECONDS_PER_READ * fx.reads.size)
        }

    private companion object {
        const val SECONDS_PER_READ = 3L
        const val BUDGET_SECONDS = 5L
        const val RETRY_AFTER_SECONDS = 5

        val EXPECTED_WRITES =
            mapOf(
                ReleaseFamily.TEMPLATE to
                    listOf(
                        "begin",
                        "guard:template",
                        "flip:test/lib.sql@hash-test/lib.sql",
                        "flip:test/root.sql@hash-test/root.sql",
                        "audit:template.version.released:test/lib.sql",
                        "audit:template.version.released:test/root.sql",
                        "commit",
                    ),
                ReleaseFamily.PARAMETER_SET to
                    listOf(
                        "prepare:test/sets/regions",
                        "begin",
                        "guard:parameter_set",
                        "flip:test/set_source.sql@hash-test/set_source.sql",
                        "flip:test/sets/regions@hash-test/sets/regions",
                        "audit:template.version.released:test/set_source.sql",
                        "audit:parameter_set.version.released:test/sets/regions",
                        "commit",
                    ),
                ReleaseFamily.VISUALIZATION to
                    listOf(
                        "prepare:test/charts/revenue",
                        "begin",
                        "guard:visualization",
                        "flip:test/chart_transform.sql@hash-test/chart_transform.sql",
                        "flip:test/charts/revenue@hash-test/charts/revenue",
                        "audit:template.version.released:test/chart_transform.sql",
                        "audit:visualization.version.released:test/charts/revenue",
                        "commit",
                    ),
                ReleaseFamily.DASHBOARD to
                    listOf(
                        "prepare:test/source",
                        "prepare:test/charts/board",
                        "prepare:test/boards/revenue",
                        "begin",
                        "guard:dashboard",
                        "flip:test/source@hash-test/source",
                        "flip:test/charts/board@hash-test/charts/board",
                        "flip:test/boards/revenue@hash-test/boards/revenue",
                        "audit:pipeline.version.released:test/source",
                        "audit:visualization.version.released:test/charts/board",
                        "audit:dashboard.version.released:test/boards/revenue",
                        "commit",
                    ),
            )
    }
}
