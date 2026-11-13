package co.datapipelines.web.release

import co.datapipelines.pipeline.release.PrerequisiteCheck
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.pipeline.release.ReleaseRefusal
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * #873 §F.3 c: the coordinator calls its [PrerequisiteCheck] inside the guarded release transaction — after the guard and
 * the re-walk, before any permission recheck under the guard and any flip — on all FOUR entry paths (pipeline plan,
 * pipeline compatibility, artifact-root plan, artifact-root compatibility), with the guarded closure and the acting
 * caller. An unavailable prerequisite is the ROOT family's `dependency_not_released` over versionless datasource
 * blockers, the transaction rolled back with nothing flipped and nothing audited; an available one changes nothing.
 */
class ReleaseCoordinatorPrerequisitesTest {
    private val fx = CoordinatorFixture()
    private val g = ArtifactRootGraph(fx)
    private val t1 = fx.template("test/t1.sql")
    private val t2 = fx.template("test/t2.sql", status = ReleaseNodeStatus.RELEASED)
    private val planned = fx.pipeline("test/planned", listOf(t1))
    private val compatible = fx.pipeline("test/compatible", listOf(t2))

    /** Answers `warehouse` available and every [unavailable] name not; logs each call with the closure root it was given. */
    private fun check(vararg unavailable: String) =
        PrerequisiteCheck { closure, actor ->
            fx.log += "prerequisites:${closure.root.entityId}:${actor == fx.actor}"
            listOf(ReleasePrerequisite("warehouse", available = true, readonly = false)) +
                unavailable.map { ReleasePrerequisite(it, available = false, readonly = null) }
        }

    private fun pipelinePlan(): ReleasePlanRequest {
        val preview = fx.preview(planned)
        return ReleasePlanRequest(preview.treeHash, preview.ids(t1))
    }

    @Test
    fun `the pipeline plan path refuses an unavailable datasource under the guard - nothing flipped, nothing audited`() {
        val request = fx.request(planned, pipelinePlan())

        val error = shouldThrow<DatapipelinesException> { fx.coordinator(prerequisites = check("ghost")).release(request) }

        refusedUnderGuard(error, ReleaseFamily.PIPELINE, "test/planned")
    }

    @Test
    fun `the pipeline compatibility path refuses an unavailable datasource under the guard`() {
        val error =
            shouldThrow<DatapipelinesException> { fx.coordinator(prerequisites = check("ghost")).release(fx.request(compatible)) }

        refusedUnderGuard(error, ReleaseFamily.PIPELINE, "test/compatible")
    }

    @ParameterizedTest
    @EnumSource(ReleaseFamily::class, names = ["TEMPLATE", "PARAMETER_SET", "VISUALIZATION", "DASHBOARD"])
    fun `an artifact root's plan path refuses with the ROOT family's code`(family: ReleaseFamily) {
        val error = shouldThrow<DatapipelinesException> { fx.coordinator(prerequisites = check("ghost")).releaseRoot(g.planned(family)) }

        refusedUnderGuard(error, family, g.root(family).entityId)
    }

    @Test
    fun `an artifact root's compatibility path refuses with the ROOT family's code`() {
        val error =
            shouldThrow<DatapipelinesException> {
                fx.coordinator(prerequisites = check("ghost")).releaseRoot(fx.rootRequest(g.set), releasePinned = true)
            }

        refusedUnderGuard(error, ReleaseFamily.PARAMETER_SET, g.set.entityId)
    }

    @Test
    fun `every unavailable name is a blocker - absent and not granted are one reason, bounded by max_blockers`() {
        val error =
            shouldThrow<DatapipelinesException> {
                fx.coordinator(prerequisites = check("ghost", "theirs")).release(fx.request(planned, pipelinePlan()))
            }

        error.details["blockers"] shouldBe
            listOf(
                mapOf("kind" to "datasource", "name" to "ghost", "reason" to "unavailable"),
                mapOf("kind" to "datasource", "name" to "theirs", "reason" to "unavailable"),
            )
        error.details["blockers_truncated"] shouldBe false
    }

    @Test
    fun `available prerequisites change nothing - the plan releases as before, the check called once under the guard`() {
        fx.coordinator(prerequisites = check()).release(fx.request(planned, pipelinePlan()))

        fx.writes().filter { it.startsWith("flip:") } shouldContainExactly
            listOf("flip:test/t1.sql@hash-test/t1.sql", "flip:test/planned@hash-test/planned")
        fx.log.filter { it.startsWith("prerequisites:") } shouldContainExactly listOf("prerequisites:test/planned:true")
        fx.log.indexOf("prerequisites:test/planned:true") shouldBe fx.log.indexOf("guard:pipeline") + 1
    }

    private fun refusedUnderGuard(
        error: DatapipelinesException,
        family: ReleaseFamily,
        root: String,
    ) {
        error.code shouldBe family.code(ReleaseRefusal.DEPENDENCY_NOT_RELEASED)
        error.details["blockers"] shouldBe listOf(mapOf("kind" to "datasource", "name" to "ghost", "reason" to "unavailable"))
        val guard = fx.log.indexOf("guard:${family.wire}")
        val call = fx.log.indexOf("prerequisites:$root:true")
        withClue("the check runs inside the guarded transaction, after the guard: ${fx.log}") {
            (fx.log.indexOf("begin") in 0 until guard) shouldBe true
            (call > guard) shouldBe true
        }
        withClue("no permission recheck under the guard precedes the refusal, and nothing flips: ${fx.log}") {
            fx.log.subList(guard, fx.log.size).none { it.startsWith("auth:") || it.startsWith("flip:") } shouldBe true
        }
        fx.log.last() shouldBe "rollback"
        fx.audited shouldBe emptyList()
    }
}
