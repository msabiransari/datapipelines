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
            // #786 — the API, the sixth releasable root (dependency-graph record §8).
            ReleaseFamily.ENDPOINT to PipelineErrorCodes.Endpoint::class.java,
        )

    @Test
    fun `every family spells each of the nine refusals as its catalogued RELEASE constant`() {
        groups.keys shouldBe ReleaseFamily.entries.toSet()
        ReleaseFamily.entries.forEach { family ->
            val catalogued = releaseConstants(groups.getValue(family))
            val spelled = ReleaseRefusal.entries.associate { "RELEASE_${it.name}" to family.code(it) }
            spelled.mapValues { (name, _) -> catalogued[name] } shouldBe spelled
        }
    }

    @Test
    fun `the parameter set and the API are the two families whose wire word and code prefix differ`() {
        ReleaseFamily.entries.filter { it.wire != it.codePrefix } shouldBe listOf(ReleaseFamily.PARAMETER_SET, ReleaseFamily.ENDPOINT)
        ReleaseFamily.PARAMETER_SET.code(ReleaseRefusal.BUSY) shouldBe "parameter.release.busy"
        ReleaseFamily.PARAMETER_SET.wire shouldBe "parameter_set"
        // #786: the API's codes keep §13.14's `endpoint.` prefix; its wire word is G1's frozen `api_endpoint` key.
        ReleaseFamily.ENDPOINT.code(ReleaseRefusal.BUSY) shouldBe "endpoint.release.busy"
        ReleaseFamily.ENDPOINT.wire shouldBe "api_endpoint"
    }

    @Test
    fun `there are exactly nine refusals with the record's reason words`() {
        // The 2026-10-18 eight, extended by metadata_unavailable (owner ruling 2026-11-08, record Draft 10 erratum (6)).
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
                "metadata_unavailable",
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
