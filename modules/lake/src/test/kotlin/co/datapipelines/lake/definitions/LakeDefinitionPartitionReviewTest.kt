package co.datapipelines.lake.definitions

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Accepted partition intents must describe the shape Iceberg actually builds. */
class LakeDefinitionPartitionReviewTest {
    @ParameterizedTest
    @ValueSource(strings = ["bucket[016]", "truncate[004]"])
    fun `noncanonical partition parameters are refused or round trip canonically`(transform: String) {
        val parsed = runCatching { parse(transform) }
        val failure = parsed.exceptionOrNull()
        if (failure != null) {
            val refused = failure.shouldBeInstanceOf<DatapipelinesException>()
            refused.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
            refused.details["path"] shouldBe "partition[0].transform"
            return
        }
        assertRoundTrip(parsed.getOrThrow())
    }

    @ParameterizedTest
    @ValueSource(strings = ["bucket[16]", "truncate[4]"])
    fun `canonical partition parameters remain accepted`(transform: String) {
        assertRoundTrip(parse(transform))
    }

    private fun parse(transform: String): LakeDefinitionBody =
        LakeDefinitionBodies.parse(
            LakeDefinitionFixtures.body(
                fields = listOf(LakeDefinitionFixtures.field(1, "value")),
                partition = listOf(LakeDefinitionFixtures.partition(1, transform)),
            ),
            base = null,
            lockBasis = null,
        )

    private fun assertRoundTrip(body: LakeDefinitionBody) {
        val schema = LakeTableShape.schemaOf(body)
        val spec = LakeTableShape.specOf(body, schema)
        withClue("a validated intent must equal its real Iceberg schema and partition spec") {
            LakeTableShape.of(schema, spec, LakeTableShape.sortOrderOf(body, schema)) shouldBe LakeTableShape.of(body)
        }
        LakeApplyPlanner { null }.forNewTable(body) {}.shouldBeInstanceOf<LakeApplyPlanner.Plan.Commit>()
    }
}
