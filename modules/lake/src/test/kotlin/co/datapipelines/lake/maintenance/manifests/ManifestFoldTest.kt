package co.datapipelines.lake.maintenance.manifests

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Brief 963 D2a: the pure "fold only what folds" rule over a manifest list. */
class ManifestFoldTest {
    private fun data(
        path: String,
        length: Long,
        spec: Int = 0,
    ) = ManifestFact(path, length, spec, data = true)

    @Test
    fun `small data manifests of one spec fold together`() {
        val plan = ManifestFold.plan(listOf(data("a", 10), data("b", 20), data("c", 30)), TARGET)

        plan.folds shouldBe true
        plan.paths shouldBe setOf("a", "b", "c")
        plan.groups shouldBe mapOf(0 to 3)
        plan.bytes(listOf(data("a", 10), data("b", 20), data("c", 30), data("x", 999))) shouldBe 60
    }

    @Test
    fun `a manifest at or over half the target is not a candidate, one just under is`() {
        val half = TARGET / 2
        val plan = ManifestFold.plan(listOf(data("at", half), data("over", half + 1), data("under1", half - 1), data("under2", 1)), TARGET)

        plan.paths shouldBe setOf("under1", "under2")
    }

    @Test
    fun `an odd target admits the byte below its fractional half without overflowing`() {
        for (target in listOf(TARGET + 1, Long.MAX_VALUE)) {
            val belowHalf = target / 2
            val plan =
                ManifestFold.plan(
                    listOf(data("under1", belowHalf), data("under2", belowHalf), data("over", belowHalf + 1)),
                    target,
                )

            plan.paths shouldBe setOf("under1", "under2")
        }
    }

    @Test
    fun `candidates are grouped by spec and a lone candidate of a spec stays`() {
        val plan =
            ManifestFold.plan(
                listOf(data("s0a", 10, spec = 0), data("s0b", 10, spec = 0), data("s1a", 10, spec = 1), data("s2big", TARGET, spec = 2)),
                TARGET,
            )

        plan.paths shouldBe setOf("s0a", "s0b")
        plan.groups shouldBe mapOf(0 to 2)
    }

    @Test
    fun `a delete manifest is never a candidate`() {
        val plan =
            ManifestFold.plan(
                listOf(data("a", 10), ManifestFact("d1", 10, 0, data = false), ManifestFact("d2", 10, 0, data = false)),
                TARGET,
            )

        plan.folds shouldBe false
        plan.paths shouldBe emptySet()
    }

    @Test
    fun `nothing folds when every manifest is at the target or each spec has one candidate`() {
        ManifestFold.plan(listOf(data("a", TARGET), data("b", TARGET / 2)), TARGET).folds shouldBe false
        ManifestFold.plan(listOf(data("a", 10, spec = 0), data("b", 10, spec = 1)), TARGET).folds shouldBe false
        ManifestFold.plan(emptyList(), TARGET).folds shouldBe false
    }

    private companion object {
        const val TARGET = 8_388_608L
    }
}
