package co.datapipelines.persistence

import java.sql.SQLException

/**
 * A store's failure as a log line may name it (#266b; observability §3.4G, §9.2): its CLASS and,
 * when the store gave one, its SQLState — never its message and never its cause chain. A store's
 * message can carry the row it refused (Postgres renders the failing row in DETAIL for a NOT NULL or
 * CHECK refusal, and a fragment of the JSON in CONTEXT for a JSONB parse refusal), and a row's
 * values are never logged. The class and the SQLState are what an operator needs to tell a poison
 * row (`23xxx`) from an outage (`08xxx`, a pool timeout) — and neither can carry a value.
 */
object FailureShape {
    /** What [sqlState] answers when no `SQLException` in the chain carries one. */
    const val NO_SQL_STATE = "none"

    /** How far [sqlState] walks the cause chain — a chain can be made cyclic with `initCause`. */
    private const val MAX_CHAIN = 16

    /** The failure's simple class name; an anonymous class has none, so its binary name. */
    fun cause(failure: Throwable): String = failure.javaClass.simpleName.ifEmpty { failure.javaClass.name }

    /**
     * The first SQLState in the cause chain. Spring wraps the driver's `SQLException` in a
     * `DataAccessException` (and a transaction manager in a `TransactionException`), so the top of
     * the chain rarely carries it; only the state code is read, never a message.
     */
    fun sqlState(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .take(MAX_CHAIN)
            .filterIsInstance<SQLException>()
            .firstNotNullOfOrNull { it.sqlState } ?: NO_SQL_STATE
}
