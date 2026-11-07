package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Both directions and a nonempty floor for the delivery audit event contract. */
class LakeDeliveryAuditEventsSpecDriftTest {
    @Test
    fun `four declared delivery audit events exactly match enums section fifteen`() {
        val text = LakeTestDb.repoFile("docs/enums.md").readText()
        val start = text.indexOf("## 15.")
        val end = text.indexOf("## 16.", start + 1)
        check(start >= 0 && end > start)
        val rows =
            Regex("^\\|\\s*`(lake\\.delivery\\.[a-z_]+)`\\s*\\|", RegexOption.MULTILINE)
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .toSet()
        rows.size shouldBe 4
        rows shouldBe LakeDeliveryAuditEvents.ALL
    }
}
