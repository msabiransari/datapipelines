package co.datapipelines.templates

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

/** [ExactJsonText.arrayEntries] hands an importer each array element's exact text, aligned with a parsed tree's elements. */
class ExactJsonTextArrayEntriesTest {
    @Test
    fun `each element keeps its member order, repeated members and exact decimals`() {
        val json = """{"templates":[{"b":1,"a":0.10,"a":2},{"x":[0.123456789012345678901234567890]}],"other":1}"""
        ExactJsonText.arrayEntries(json, "templates").orEmpty().shouldContainExactly(
            """{"b":1,"a":0.10,"a":2}""",
            """{"x":[0.123456789012345678901234567890]}""",
        )
    }

    @Test
    fun `the last array member of that name wins, as a parsed tree keeps it`() {
        ExactJsonText.arrayEntries("""{"t":[1],"t":[2,3]}""", "t").orEmpty().shouldContainExactly("2", "3")
    }

    @Test
    fun `an empty array gives an empty list`() {
        ExactJsonText.arrayEntries("""{"t":[]}""", "t").orEmpty().shouldContainExactly()
    }

    @Test
    fun `no such member, a member that is not an array, or a document that is not an object gives null`() {
        ExactJsonText.arrayEntries("""{"a":[1]}""", "t").shouldBeNull()
        ExactJsonText.arrayEntries("""{"t":{"x":1}}""", "t").shouldBeNull()
        ExactJsonText.arrayEntries("""[{"t":[1]}]""", "t").shouldBeNull()
    }
}
