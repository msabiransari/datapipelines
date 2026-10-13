package co.datapipelines.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The shared search-needle rule (#504b) in isolation — the one semantics every adopting surface
 * inherits: trim, blank means no search, one bound, one typed refusal carrying the two numbers
 * and the shared code, the needle never reflected into the message.
 */
class SearchNeedleTest {
    @Test
    fun `a term is trimmed, a blank or absent term is no search`() {
        SearchNeedle.of("  region  ") shouldBe "region"
        SearchNeedle.of(null) shouldBe null
        SearchNeedle.of("") shouldBe null
        SearchNeedle.of("   ") shouldBe null
        SearchNeedle.of("\t\n ") shouldBe null
    }

    @Test
    fun `the bound is exactly 200 and a term at it passes`() {
        SearchNeedle.MAX_LENGTH shouldBe 200
        SearchNeedle.of("r".repeat(200)) shouldBe "r".repeat(200)
    }

    @Test
    fun `one over is refused with the shared code, the two numbers, and no echo of the term`() {
        val over = "r".repeat(201)
        val refused =
            shouldThrow<SearchQueryTooLongException> {
                SearchNeedle.of(over)
            }
        refused.code shouldBe PipelineErrorCodes.Request.QUERY_TOO_LONG
        refused.limit shouldBe 200
        refused.length shouldBe 201
        refused.details shouldBe mapOf("limit" to 200, "length" to 201)
        // The needle is never reflected into the message — an error body is not a search log.
        (refused.message ?: "").contains(over) shouldBe false
    }

    @Test
    fun `the bound is measured after the trim`() {
        val refused =
            shouldThrow<SearchQueryTooLongException> {
                SearchNeedle.of("   ${"r".repeat(201)}   ")
            }
        refused.length shouldBe 201
    }

    @Test
    fun `the refusal is a DatapipelinesException so every surface's translator already speaks it`() {
        val refused = shouldThrow<SearchQueryTooLongException> { SearchNeedle.of("r".repeat(201)) }
        refused as co.datapipelines.typesystem.DatapipelinesException
    }
}
