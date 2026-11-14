package co.datapipelines.lake.maintenance

import co.datapipelines.persistence.FailureShape
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The error a run row, a part record or a settlement record carries (#957 D6, D7; Security "an error is stored as its code
 * and a message with no connection string"). A catalogued failure keeps its code; anything else is `unclassified`. The
 * message is only the failure's class name: an exception's text can carry a JDBC URL or a bucket path, so it is never
 * stored.
 */
object MaintenanceErrors {
    /** The thread running the run was interrupted (D7). */
    const val INTERRUPTED = "interrupted"

    /** A row its successor closed (D4): its worker never confirmed what it did, and nothing is concluded from it. */
    const val OUTCOME_NOT_CONFIRMED = "outcome_not_confirmed"

    /** A failure that carries no catalogued code. */
    const val UNCLASSIFIED = "unclassified"

    fun codeOf(failure: Throwable): String =
        when (failure) {
            is MaintenanceInterrupted -> INTERRUPTED
            is DatapipelinesException -> failure.code
            else -> UNCLASSIFIED
        }

    /**
     * The stored message: the failure's class name and nothing else. A catalogued exception's text can still quote a
     * value it was handed; the code says what happened, the class says where.
     */
    fun messageOf(failure: Throwable): String = FailureShape.cause(failure)
}
