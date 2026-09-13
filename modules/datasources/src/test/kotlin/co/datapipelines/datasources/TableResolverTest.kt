package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The 123 §A did-you-mean helpers ([nearestTableName] over [levenshtein]): case-folded
 * equality beats any edit distance, the distance boundary is exactly
 * [MAX_SUGGESTION_DISTANCE], and nothing close means no suggestion at all.
 */
class TableResolverTest {
    @Test
    fun `levenshtein distances - the boundary values`() {
        assertAll(
            { levenshtein("orders", "orders") shouldBe 0 },
            { levenshtein("orders", "orderrs") shouldBe 1 },
            { levenshtein("orders", "oredrs") shouldBe 2 },
            { levenshtein("orders", "odrrse") shouldBe 3 },
            { levenshtein("", "ab") shouldBe 2 },
            { levenshtein("kitten", "sitting") shouldBe 3 },
        )
    }

    @Test
    fun `case-folded equality beats edit distance - the stored-case spelling wins`() {
        // The caller asked `Orders`, the engine stores `ORDERS`: the suggestion is the
        // case-folded equal, never a nearer-by-distance different table.
        nearestTableName("Orders", listOf("Order", "ORDERS")) shouldBe "ORDERS"
    }

    @Test
    fun `the distance boundary - two suggests, three does not`() {
        assertAll(
            { nearestTableName("hvfhv_companies", listOf("hvfhs_companies")) shouldBe "hvfhs_companies" },
            { nearestTableName("oredrs", listOf("orders")) shouldBe "orders" },
            { nearestTableName("odrrse", listOf("orders")) shouldBe null },
        )
    }

    @Test
    fun `no suggestion when nothing is close - or the namespace lists nothing`() {
        assertAll(
            { nearestTableName("anything", emptyList()) shouldBe null },
            { nearestTableName("completely_unrelated", listOf("orders", "events")) shouldBe null },
            // The requested name itself (an exact hit) is never its own suggestion.
            { nearestTableName("orders", listOf("orders")) shouldBe null },
        )
    }

    @Test
    fun `the nearest of several candidates wins`() {
        nearestTableName("event", listOf("orders", "events", "eviction")) shouldBe "events"
    }
}
