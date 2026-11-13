package co.datapipelines.lake.definitions

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** A definition consumes the entire JSON input rather than silently retaining only its first value. */
class LakeDefinitionJsonReviewTest {
    @ParameterizedTest
    @ValueSource(strings = [" {}", " 42"])
    fun `a complete definition followed by another JSON value is refused`(suffix: String) {
        val failure =
            shouldThrow<DatapipelinesException> {
                LakeDefinitionBodies.parse(LakeDefinitionFixtures.ORDERS_V1 + suffix, null, null)
            }
        failure.code shouldBe PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY
    }
}
