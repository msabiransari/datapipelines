package co.datapipelines.pipeline.release

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Every `family × refusal` code the coordinator raises is the family's catalogued constant (record §14): the enum spells
 * the code, the family's error-code group catalogues it, and the two may never drift — a family whose constant is renamed
 * or missing is red here before a refusal ships an uncatalogued string.
 */
class ReleaseFamilyTest {
    private val groups: Map<ReleaseFamily, Class<*>> =
        mapOf(
            ReleaseFamily.PIPELINE to PipelineErrorCodes.Versioning::class.java,
            ReleaseFamily.TEMPLATE to PipelineErrorCodes.Template::class.java,
            ReleaseFamily.PARAMETER_SET to PipelineErrorCodes.Parameters::class.java,
            ReleaseFamily.VISUALIZATION to PipelineErrorCodes.Visualization::class.java,
            ReleaseFamily.DASHBOARD to PipelineErrorCodes.Dashboard::class.java,
        )

    @Test
    fun `every family spells each of the eight refusals as its catalogued RELEASE constant`() {
        groups.keys shouldBe ReleaseFamily.entries.toSet()
        ReleaseFamily.entries.forEach { family ->
            val catalogued = releaseConstants(groups.getValue(family))
            val spelled = ReleaseRefusal.entries.associate { "RELEASE_${it.name}" to family.code(it) }
            spelled.mapValues { (name, _) -> catalogued[name] } shouldBe spelled
        }
    }

    @Test
    fun `the parameter set is the one family whose wire word and code prefix differ`() {
        ReleaseFamily.entries.filter { it.wire != it.codePrefix } shouldBe listOf(ReleaseFamily.PARAMETER_SET)
        ReleaseFamily.PARAMETER_SET.code(ReleaseRefusal.BUSY) shouldBe "parameter.release.busy"
        ReleaseFamily.PARAMETER_SET.wire shouldBe "parameter_set"
    }

    @Test
    fun `there are exactly eight refusals with the record's reason words`() {
        ReleaseRefusal.entries.map { it.wire } shouldBe
            listOf(
                "dependency_not_released",
                "plan_conflict",
                "plan_required",
                "plan_invalid",
                "dependency_cycle",
                "dependency_limit",
                "busy",
                "bundle_unsupported",
            )
    }

    private fun releaseConstants(group: Class<*>): Map<String, String> =
        group.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.filter { field -> ReleaseRefusal.entries.any { field.name == "RELEASE_${it.name}" } }
            .associate { it.name to it.get(null) as String }
}
