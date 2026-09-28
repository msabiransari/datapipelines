package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The #290 acceptance plant: ONE red test on a scratch branch, so the CI gate job can show its report step and its failing-test summary. Deleted with the branch. */
class CiAcceptancePlantTest {
    @Test
    fun `the plant is red on purpose`() {
        1 shouldBe 2
    }
}
