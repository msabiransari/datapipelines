package co.datapipelines.persistence

import java.sql.SQLException

/**
 * A store's failure as a log line may name it (#266b; observability §3.4G, §9.2): its CLASS and,
 * when the store gave one, its SQLState — never its message and never its cause chain. A store's
 * message can carry the row it refused (Postgres renders the failing row in DETAIL for a NOT NULL or
 * CHECK refusal, and a fragment of the JSON in CONTEXT for a JSONB parse refusal), and a row's
 * values are never logged. The class and the SQLState are what an operator needs to tell a poison
 * row (`23xxx`) from an outage (`08xxx`, a pool timeout). Driver-controlled states are accepted
 * only as one to five ASCII letters or digits; malformed states become a fixed token.
 */
object FailureShape {
    /** What [sqlState] answers when no `SQLException` in the chain carries one. */
    const val NO_SQL_STATE = "none"

    /** What [sqlState] answers when the first non-null driver state has an invalid shape. */
    const val INVALID_SQL_STATE = "invalid"

    private const val MAX_SQL_STATE_LENGTH = 5

    /** How far [sqlState] walks the cause chain — a chain can be made cyclic with `initCause`. */
    private const val MAX_CHAIN = 16

    /** The failure's simple class name; an anonymous class has none, so its binary name. */
    fun cause(failure: Throwable): String = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name }

    /**
     * The first SQLState in the cause chain. Spring wraps the driver's `SQLException` in a
     * `DataAccessException` (and a transaction manager in a `TransactionException`), so the top of
     * the chain rarely carries it; only the state code is read, never a message. A state passes
     * unchanged only when it contains one to five ASCII letters or digits; otherwise it becomes
     * [INVALID_SQL_STATE], without trying later states. No state yields [NO_SQL_STATE].
     */
    fun sqlState(failure: Throwable): String {
        val state =
            generateSequence(failure) { it.cause }
                .take(MAX_CHAIN)
                .filterIsInstance<SQLException>()
                .firstNotNullOfOrNull { it.sqlState } ?: return NO_SQL_STATE
        if (state.length !in 1..MAX_SQL_STATE_LENGTH) return INVALID_SQL_STATE
        return if (state.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) {
            state
        } else {
            INVALID_SQL_STATE
        }
    }
}
