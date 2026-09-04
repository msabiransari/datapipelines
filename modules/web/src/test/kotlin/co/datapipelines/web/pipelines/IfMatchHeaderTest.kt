package co.datapipelines.web.pipelines

import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [IfMatchHeader] — the version-lifecycle precondition's surface parse (versioning §4.2):
 * trimmed and non-empty, or the catalogued protocol error. A missing header is not a
 * conflict — the caller has not participated in the protocol at all.
 */
class IfMatchHeaderTest {
    @Test
    fun `a present header is trimmed`() {
        IfMatchHeader.required("  sha256:abc  ") shouldBe "sha256:abc"
    }

    @Test
    fun `an absent header is the precondition-missing protocol error`() {
        val e = shouldThrow<ApiException> { IfMatchHeader.required(null) }

        e.details["reason"] shouldBe "precondition_missing"
        e.details["header"] shouldBe "If-Match"
        e.message shouldContain "If-Match"
    }

    @Test
    fun `a blank header is as good as absent`() {
        shouldThrow<ApiException> { IfMatchHeader.required("   ") }
    }
}
