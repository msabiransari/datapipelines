package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogException
import co.datapipelines.persistence.FailureShape
import org.apache.iceberg.exceptions.CommitFailedException
import org.apache.iceberg.exceptions.CommitStateUnknownException
import org.apache.iceberg.jdbc.UncheckedSQLException

/** Evidence required after a publication commit call; these are not ledger transitions. */
sealed interface CommitVerdict {
    /** The commit returned normally. */
    data object Committed : CommitVerdict

    /** A stale-base/CAS refusal: check durable delivery evidence before deciding whether to retry. */
    data object RecheckRevisionLog : CommitVerdict

    /** The database definitively refused this statement; never infer that an earlier attempt failed. */
    data object Refused : CommitVerdict

    /** Preserve files and settle under a takeover lease before making a negative decision. */
    data object Unknown : CommitVerdict
}

/** §8b's outcome table, by exception type and SQLSTATE only. Messages never influence a verdict. */
object CommitClassifier {
    private const val MAX_CHAIN = 16
    private val REFUSAL_STATES = setOf("DP001", "DP002", "55P03", "57014")

    fun classify(failure: Throwable?): CommitVerdict {
        if (failure == null) return CommitVerdict.Committed
        val chain = generateSequence(failure) { it.cause }.take(MAX_CHAIN).toList()
        // A truncated chain cannot establish that no connection loss or explicit unknown was wrapped.
        if (chain.size == MAX_CHAIN && chain.last().cause != null) return CommitVerdict.Unknown
        // An explicit unknown always wins over an inner refusal; a lost response cannot be replayed.
        if (chain.any { it is CommitStateUnknownException || it is LakeCatalogException.OutcomeUnknown }) {
            return CommitVerdict.Unknown
        }
        val state = FailureShape.sqlState(failure)
        val sqlFailure =
            chain.any {
                it is UncheckedSQLException || it is LakeCatalogException.Fenced || it is LakeCatalogException.Restoring
            }
        return when {
            sqlFailure && state in REFUSAL_STATES -> CommitVerdict.Refused
            state != FailureShape.NO_SQL_STATE -> CommitVerdict.Unknown
            chain.any { it is CommitFailedException } -> CommitVerdict.RecheckRevisionLog
            else -> CommitVerdict.Unknown
        }
    }
}
