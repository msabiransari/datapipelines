package co.datapipelines.typesystem

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class CauseChainTest {
    @Test
    fun `no cause is an empty fragment`() {
        CauseChain.summarize(IllegalStateException("top")) shouldBe ""
    }

    @Test
    fun `the chain lists every cause's type and message, innermost last, and never the top`() {
        val root = java.io.IOException("HTTP GET error reading 's3://b/p/part-' in region 'us-east-1' (HTTP 403 Forbidden)")
        val mid = java.sql.SQLException("Pool init failed", root)
        val top = DatapipelinesException("x", "Datasource 'lake' could not be reached.", cause = mid)

        val out = CauseChain.summarize(top)

        out shouldBe
            " cause=SQLException: Pool init failed; " +
            "IOException: HTTP GET error reading 's3://b/p/part-' in region 'us-east-1' (HTTP 403 Forbidden)"
        out shouldNotContain "could not be reached"
    }

    @Test
    fun `newlines collapse, a long message is cut, and depth is bounded`() {
        var chain: Throwable = RuntimeException("leaf")
        repeat(10) { chain = RuntimeException("level$it\nline two   " + "x".repeat(400), chain) }

        val out = CauseChain.summarize(RuntimeException("top", chain))

        out shouldNotContain "\n"
        out shouldContain "level9 line two"
        out.split("; ").size shouldBe 5
        out shouldNotContain "level4:" // the sixth level is beyond the bound
    }

    @Test
    fun `a cycle terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        CauseChain.summarize(RuntimeException("top", a)) shouldBe " cause=RuntimeException: a; RuntimeException: b"
    }
}
