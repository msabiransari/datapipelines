package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogException
import io.kotest.matchers.shouldBe
import org.apache.iceberg.exceptions.CommitFailedException
import org.apache.iceberg.exceptions.CommitStateUnknownException
import org.apache.iceberg.jdbc.UncheckedSQLException
import org.junit.jupiter.api.Test
import java.sql.SQLException

class CommitClassifierTest {
    @Test
    fun `normal return is committed`() {
        CommitClassifier.classify(null) shouldBe CommitVerdict.Committed
    }

    @Test
    fun `stale base requires revision log recheck even when wrapped`() {
        CommitClassifier.classify(CommitFailedException("stale")) shouldBe CommitVerdict.RecheckRevisionLog
        CommitClassifier.classify(IllegalStateException(CommitFailedException("stale"))) shouldBe CommitVerdict.RecheckRevisionLog
    }

    @Test
    fun `four known SQLSTATE refusals never mean committed`() {
        listOf("DP001", "DP002", "55P03", "57014").forEach { state ->
            CommitClassifier.classify(sql(state, "published successfully")) shouldBe CommitVerdict.Refused
        }
    }

    @Test
    fun `localized message cannot hide a lock timeout`() {
        listOf("lock timeout", "Zeitüberschreitung", "タイムアウト", "", "COMMITTED").forEach { message ->
            CommitClassifier.classify(IllegalStateException(sql("55P03", message))) shouldBe CommitVerdict.Refused
        }
    }

    @Test
    fun `other states and missing states are unknown regardless of text`() {
        listOf("08006", "08001", "23505", "XX000", "dp001", "").forEach { state ->
            CommitClassifier.classify(sql(state, "DP001 55P03 lock timeout")) shouldBe CommitVerdict.Unknown
        }
        CommitClassifier.classify(UncheckedSQLException(SQLException("55P03"), "DP001")) shouldBe CommitVerdict.Unknown
        CommitClassifier.classify(IllegalStateException("DP001")) shouldBe CommitVerdict.Unknown
    }

    @Test
    fun `explicit unknown dominates nested known refusal`() {
        CommitClassifier.classify(CommitStateUnknownException("unknown", sql("55P03", "timeout"))) shouldBe CommitVerdict.Unknown
        CommitClassifier.classify(CommitStateUnknownException(IllegalStateException("unknown"))) shouldBe CommitVerdict.Unknown
    }

    @Test
    fun `connection loss under commit failed is never a retry verdict`() {
        CommitClassifier.classify(CommitFailedException(SQLException("lost ack", "08006"), "stale")) shouldBe CommitVerdict.Unknown
        var deep: Throwable = SQLException("lost ack", "08006")
        repeat(20) { deep = IllegalStateException("wrapper", deep) }
        CommitClassifier.classify(CommitFailedException(deep, "stale")) shouldBe CommitVerdict.Unknown
    }

    @Test
    fun `already classified fence restore and lost ack retain their meanings`() {
        CommitClassifier.classify(LakeCatalogException.Fenced(SQLException("x", "DP001"))) shouldBe CommitVerdict.Refused
        CommitClassifier.classify(LakeCatalogException.Restoring(SQLException("x", "DP002"))) shouldBe CommitVerdict.Refused
        CommitClassifier.classify(LakeCatalogException.OutcomeUnknown(SQLException("x", "08006"))) shouldBe CommitVerdict.Unknown
    }

    @Test
    fun `cyclic causes terminate conservatively`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        CommitClassifier.classify(first) shouldBe CommitVerdict.Unknown
    }

    private fun sql(
        state: String,
        message: String,
    ) = UncheckedSQLException(SQLException(message, state), "wrapped")
}
