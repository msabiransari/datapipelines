package co.datapipelines.pipeline.release

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #873 §C.3 (record §16 rule 4): the legacy `release_pinned_*` consent selects EXACTLY the root's DIRECT drafts in the
 * family its flag named — a set's, a visualization's and a pipeline's DRAFT templates, a dashboard's DRAFT
 * visualizations — and nothing for a template or an API. Every root below also pins a DRAFT of every OTHER family and a
 * DRAFT one level deeper, so a broadened selection (another family, a deeper node) is red here.
 */
class LegacyReleaseAliasTest {
    private val fx = ReleaseGraphFixture()

    private fun draft(
        key: String,
        family: ReleaseFamily,
    ) = fx.node(fx.id(key, family = family))

    /** A root of [family] pinning one DRAFT of every family, each of which pins a deeper DRAFT template. */
    private fun selectionOf(family: ReleaseFamily): Set<ReleaseNodeIdentity> {
        val root = fx.id("root-${family.wire}", family = family)
        val deep = draft("deep-${family.wire}", ReleaseFamily.TEMPLATE)
        val direct =
            ReleaseFamily.entries.filter { it != ReleaseFamily.ENDPOINT }.map { child ->
                fx.node(fx.id("${family.wire}-${child.wire}", family = child), ReleaseNodeStatus.DRAFT, deep to "inner")
            }
        val released = fx.node(fx.id("${family.wire}-released"), ReleaseNodeStatus.RELEASED, currentVersion = 1)
        fx.node(root, ReleaseNodeStatus.DRAFT, *(direct + released).mapIndexed { i, pin -> pin to "pins[$i]" }.toTypedArray())
        return LegacyReleaseAlias.selection(requireNotNull(fx.graph().walk(root)))
    }

    @Test
    fun `a set, a visualization and a pipeline consent to their direct DRAFT template only`() {
        listOf(ReleaseFamily.PARAMETER_SET, ReleaseFamily.VISUALIZATION, ReleaseFamily.PIPELINE).forEach { family ->
            selectionOf(family).map { it.entityId } shouldContainExactly listOf("${family.wire}-template")
        }
    }

    @Test
    fun `a dashboard consents to its direct DRAFT visualizations only - not its set, its source pipelines or any template`() {
        selectionOf(ReleaseFamily.DASHBOARD).map { it.entityId } shouldContainExactly listOf("dashboard-visualization")
    }

    @Test
    fun `a template and an API consent to nothing`() {
        selectionOf(ReleaseFamily.TEMPLATE) shouldBe emptySet()
        selectionOf(ReleaseFamily.ENDPOINT) shouldBe emptySet()
    }

    @Test
    fun `the consented families per root are exactly the legacy flags`() {
        ReleaseFamily.entries.associateWith(LegacyReleaseAlias::consentedFamilies) shouldBe
            mapOf(
                ReleaseFamily.PIPELINE to setOf(ReleaseFamily.TEMPLATE),
                ReleaseFamily.TEMPLATE to emptySet(),
                ReleaseFamily.PARAMETER_SET to setOf(ReleaseFamily.TEMPLATE),
                ReleaseFamily.VISUALIZATION to setOf(ReleaseFamily.TEMPLATE),
                ReleaseFamily.DASHBOARD to setOf(ReleaseFamily.VISUALIZATION),
                ReleaseFamily.ENDPOINT to emptySet(),
            )
    }
}
