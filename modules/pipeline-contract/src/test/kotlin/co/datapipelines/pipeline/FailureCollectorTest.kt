package co.datapipelines.pipeline

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * [FailureCollector] — the §17.2 exhaustiveness mechanism's own contract. It has run inside
 * every validator rule for months (as test FIXTURES too), but its two promises were never
 * the thing asserted: failures accumulate with no early-return shape able to skip later
 * rules, and the returned snapshot is immutable against later adds. Plus the single
 * [validationFailure] constructor's reflection hygiene (CF-1/CF-2): a payload-sourced
 * `path` is truncated and control-character-scrubbed HERE, in the one place that builds a
 * failure — a new rule cannot forget.
 */
class FailureCollectorTest {
    @Test
    fun `failures accumulate across rules - there is no fail-fast`() {
        val collector = FailureCollector()

        collector.add("rule.one", "nodes[0]", "first")
        collector.add("rule.two", "nodes[1]", "second")

        val result = collector.toResult()
        result.failures shouldHaveSize 2
        result.failures.map { it.code } shouldBe listOf("rule.one", "rule.two")
        result.isValid shouldBe false
    }

    @Test
    fun `toResult is a snapshot - adds after it cannot mutate a returned result`() {
        val collector = FailureCollector()
        collector.add("before", "p", "m")

        val result = collector.toResult()
        collector.add("after", "p", "m")

        result.failures shouldHaveSize 1
        collector.toResult().failures shouldHaveSize 2
    }

    @Test
    fun `a payload-length path is truncated at the reflected-input bound`() {
        val longPath = "a".repeat(500)

        val failure = validationFailure("code", longPath, "message")

        failure.path shouldHaveMaxLength 129 // 128 + the ellipsis
        failure.path shouldStartWith "a".repeat(128)
    }

    @Test
    fun `a control character in the path is scrubbed, not echoed`() {
        val failure = validationFailure("code", "nodes[0].na\u0000me", "message")

        failure.path shouldBe "nodes[0].na�me"
    }

    @Test
    fun `the identifier grammar is the frozen 15-1 rule`() {
        IDENTIFIER.matches("orders_v2") shouldBe true
        IDENTIFIER.matches("a") shouldBe true
        IDENTIFIER.matches("a".repeat(63)) shouldBe true

        IDENTIFIER.matches("Orders") shouldBe false
        IDENTIFIER.matches("a.b") shouldBe false
        IDENTIFIER.matches("-x") shouldBe false
        IDENTIFIER.matches("") shouldBe false
        IDENTIFIER.matches("a".repeat(64)) shouldBe false
    }

    @Test
    fun `the reserved namespace and the tempdb literal are reserved`() {
        isReservedIdentifier("__system__") shouldBe true
        isReservedIdentifier(NodeSource.TEMPDB_LITERAL) shouldBe true

        isReservedIdentifier("system") shouldBe false
        isReservedIdentifier("__incomplete") shouldBe false
    }
}
