package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [DatasourceNameGrammar] — datasources.md §3.3, the ONE definition the registry's validator and the dependency tree
 * route (#875) share: `[a-z0-9_-]`, 1–63 characters.
 */
class DatasourceNameGrammarTest {
    @ParameterizedTest
    @ValueSource(strings = ["a", "warehouse_pg", "nyc-taxi", "0", "a_b-c_9"])
    fun `legal names match`(name: String) {
        DatasourceNameGrammar.matches(name) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Upper", "has space", "has/slash", "dot.ted", "é", "a\n"])
    fun `illegal names do not match`(name: String) {
        DatasourceNameGrammar.matches(name) shouldBe false
    }

    @Test
    fun `the length bound is 63, inclusive`() {
        DatasourceNameGrammar.matches("a".repeat(DatasourceNameGrammar.MAX_LENGTH)) shouldBe true
        DatasourceNameGrammar.matches("a".repeat(DatasourceNameGrammar.MAX_LENGTH + 1)) shouldBe false
        DatasourceNameGrammar.MAX_LENGTH shouldBe 63
    }

    @Test
    fun `the validator publishes the grammar's bound, not a second one`() {
        DatasourceValidator.MAX_NAME_LENGTH shouldBe DatasourceNameGrammar.MAX_LENGTH
    }
}
