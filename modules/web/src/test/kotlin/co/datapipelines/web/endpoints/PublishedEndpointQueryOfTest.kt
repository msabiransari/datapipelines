package co.datapipelines.web.endpoints

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #1039 — a mutation's query parameters are read from the RAW query string, never from the container's parameter map
 * (which, for a form-labelled body, would merge body fields into the query and drain the stream). Repeats stay visible,
 * and a malformed percent-escape refuses the whole query rather than dropping one parameter that would then not bind.
 */
class PublishedEndpointQueryOfTest {
    @Test
    fun `an absent or empty query is no parameters`() {
        PublishedEndpointController.queryOf(null) shouldBe emptyMap()
        PublishedEndpointController.queryOf("") shouldBe emptyMap()
    }

    @Test
    fun `names and values are percent-decoded, repeats kept in order, a bare name is an empty value`() {
        PublishedEndpointController.queryOf("a=1&b=x%20y&a=2&flag&&c=%C3%A9") shouldBe
            mapOf("a" to listOf("1", "2"), "b" to listOf("x y"), "flag" to listOf(""), "c" to listOf("é"))
    }

    @Test
    fun `a malformed escape refuses the whole query`() {
        PublishedEndpointController.queryOf("a=1&b=%zz") shouldBe null
        PublishedEndpointController.queryOf("%=1") shouldBe null
    }
}
