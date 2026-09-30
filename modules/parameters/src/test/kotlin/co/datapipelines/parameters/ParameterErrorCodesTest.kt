package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The engine's codes and the catalog's copy are ONE set (the `ScheduleErrorCodes` /
 * `PipelineErrorCodes.Schedule` precedent): `web`'s `ApiErrorCatalog` reads the copy, the engine
 * raises its own, and a code added or dropped on one side only is red here.
 */
class ParameterErrorCodesTest {
    @Test
    fun `ParameterErrorCodes and PipelineErrorCodes-Parameters are the same set, read by reflection`() {
        val catalog = constantsOf(PipelineErrorCodes.Parameters::class.java)
        catalog.size shouldBeGreaterThan 70
        catalog shouldBe ParameterErrorCodes.ALL
        // And constant-for-constant: the same NAME carries the same code on both sides.
        namedConstantsOf(PipelineErrorCodes.Parameters::class.java) shouldBe namedConstantsOf(ParameterErrorCodes::class.java)
    }

    @Test
    fun `every code is in the parameter family and follows the segmentation scheme`() {
        ParameterErrorCodes.ALL.filterNot { it.startsWith("parameter.") }.shouldBeEmpty()
        ParameterErrorCodes.ALL.filterNot { SEGMENTATION.matches(it) }.shouldBeEmpty()
        // `parameter.not_found` and `parameter.in_use` (#320: a dashboard pins the set — the `template.in_use` shape) are
        // the family's two-segment codes: both are states of the entity itself.
        ParameterErrorCodes.ALL.filter { it.count { c -> c == '.' } == 1 }.toSet() shouldBe
            setOf(ParameterErrorCodes.NOT_FOUND, ParameterErrorCodes.IN_USE)
    }

    private companion object {
        val SEGMENTATION = Regex("^[a-z0-9_]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$")

        fun constantsOf(type: Class<*>): Set<String> = namedConstantsOf(type).values.toSet()

        fun namedConstantsOf(type: Class<*>): Map<String, String> =
            type.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .associate { it.name to (it.get(null) as String) }
    }
}
