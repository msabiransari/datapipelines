package co.datapipelines.web.api

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID

/** The correlation id's single-origin discipline (rest-api §3.4, observability §3.3). */
class CorrelationIdTest {
    @Test
    fun `a UUID inbound header is adopted, anything else is replaced`() {
        val inbound = UUID.randomUUID()
        CorrelationId.resolve(inbound.toString()) shouldBe inbound
        CorrelationId.resolve("not a uuid") shouldNotBe null
        CorrelationId.resolve("not a uuid").toString() shouldNotBe "not a uuid"
        CorrelationId.resolve(null).toString().length shouldBe 36
    }

    @Test
    fun `current reads the MDC slot and the fallback mints a fresh id`() {
        MDC.remove(CorrelationId.MDC_KEY)
        CorrelationId.currentUuid().shouldBeNull()
        CorrelationId.current().isNotBlank() shouldBe true

        val id = UUID.randomUUID()
        CorrelationId.withId(id) {
            CorrelationId.currentUuid() shouldBe id
            CorrelationId.current() shouldBe id.toString()
        }
        // The slot is restored afterwards — no leak onto the next user of the thread.
        CorrelationId.currentUuid().shouldBeNull()
    }
}

/** The pagination clamps and the honest-total rules (rest-api §4.3). */
class PaginationTest {
    @Test
    fun `limit clamps into 1-to-200 and offset into non-negative`() {
        Pagination.clampLimit(null) shouldBe 50
        Pagination.clampLimit(0) shouldBe 1
        Pagination.clampLimit(10_000) shouldBe 200
        Pagination.clampOffset(null) shouldBe 0
        Pagination.clampOffset(-5) shouldBe 0
    }

    @Test
    fun `has_more is derived, never supplied`() {
        Pagination.of(offset = 0, limit = 50, total = 237, pageSize = 50).hasMore shouldBe true
        Pagination.of(offset = 200, limit = 50, total = 237, pageSize = 37).hasMore shouldBe false
    }

    @Test
    fun `unknownTotal reports the proven lower bound`() {
        val page = Pagination.unknownTotal(offset = 100, limit = 50, pageSize = 50, hasMore = true)
        page.total shouldBe 150L
        page.hasMore shouldBe true
    }
}
