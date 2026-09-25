package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ImplementsIds] — the §2.3 id list as the wire carries it: lenient parsing for the reads and
 * the import ruling (drop, never refuse), and the one write-time verdict
 * (`template.implements_unresolved`) with its three reasons in precedence order. The fact store
 * is a [CitableFacts] stub: which ids a workspace may cite is the store's rule, not this one's.
 */
class ImplementsIdsTest {
    private val workspaceId = UUID.randomUUID()
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()

    private fun admitting(vararg ids: UUID) = CitableFacts { _, asked -> asked.intersect(ids.toSet()) }

    @Test
    fun `parseLenient trims, drops a malformed entry and keeps first-seen order without duplicates`() {
        ImplementsIds.parseLenient(listOf("$a", " $b ", "not-an-id", "$a", "$b", "${a}x")) shouldContainExactly listOf(a, b)
    }

    @Test
    fun `keepCitable keeps the admitted ids in first-seen order and consults nobody for an empty list`() {
        ImplementsIds.keepCitable(workspaceId, listOf("$a", "$b", "$c", "junk"), admitting(c, b)) shouldContainExactly
            listOf("$b", "$c")
        val neverAsked = CitableFacts { _, _ -> error("must not be consulted for an empty list") }
        ImplementsIds.keepCitable(workspaceId, emptyList(), neverAsked) shouldBe emptyList()
        ImplementsIds.keepCitable(workspaceId, listOf("junk"), neverAsked) shouldBe emptyList()
    }

    @Test
    fun `keepCitable caps the list at MAX_CITATIONS before asking the store`() {
        val many = List(ImplementsIds.MAX_CITATIONS + 10) { UUID.randomUUID() }
        val kept = ImplementsIds.keepCitable(workspaceId, many.map { it.toString() }, CitableFacts { _, asked -> asked })
        kept.size shouldBe ImplementsIds.MAX_CITATIONS
        kept shouldContainExactly many.take(ImplementsIds.MAX_CITATIONS).map { it.toString() }
    }

    @Test
    fun `unresolved is null when every entry is citable, and for an empty list`() {
        ImplementsIds.unresolved(workspaceId, listOf("$a", "$b", "$a"), admitting(a, b)).shouldBeNull()
        ImplementsIds.unresolved(workspaceId, emptyList(), CitableFacts.NONE).shouldBeNull()
    }

    @Test
    fun `too many distinct entries is the too_many refusal, judged before the entries are parsed`() {
        val raw = List(ImplementsIds.MAX_CITATIONS) { UUID.randomUUID().toString() } + "malformed"
        val failure = ImplementsIds.unresolved(workspaceId, raw + raw.first(), CitableFacts { _, asked -> asked }).shouldNotBeNull()
        failure.code shouldBe PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED
        failure.details["reason"] shouldBe "too_many"
        failure.details["max"] shouldBe ImplementsIds.MAX_CITATIONS
        failure.details["count"] shouldBe ImplementsIds.MAX_CITATIONS + 1
    }

    @Test
    fun `a malformed entry is named, before the store is asked`() {
        val neverAsked = CitableFacts { _, _ -> error("a malformed list never reaches the store") }
        val failure = ImplementsIds.unresolved(workspaceId, listOf("$a", "nope", "$b"), neverAsked).shouldNotBeNull()
        failure.code shouldBe PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED
        failure.details["reason"] shouldBe "malformed"
        failure.details["fact_id"] shouldBe "nope"
    }

    @Test
    fun `the first id the workspace cannot cite is the one unknown answer`() {
        val failure = ImplementsIds.unresolved(workspaceId, listOf("$a", "$b", "$c"), admitting(a, c)).shouldNotBeNull()
        failure.code shouldBe PipelineErrorCodes.Template.IMPLEMENTS_UNRESOLVED
        failure.details["reason"] shouldBe "unknown"
        failure.details["fact_id"] shouldBe "$b"
        // Fail closed: a construction that wired no fact store admits nothing.
        ImplementsIds.unresolved(workspaceId, listOf("$a"), CitableFacts.NONE).shouldNotBeNull().details["fact_id"] shouldBe "$a"
    }

    @Test
    fun `an implemented_by entry is the name and the version`() {
        ImplementingVersion("acme/shape/order_lines.jsonata", 3) shouldBe ImplementingVersion("acme/shape/order_lines.jsonata", 3)
    }
}
