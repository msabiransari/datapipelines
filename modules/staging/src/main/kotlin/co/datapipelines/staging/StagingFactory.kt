package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.H2RestrictedSession
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * Creates a [Staging] instance per execution (staging.md §3.1). The
 * `create(executionId, engine)` signature is **canonical in dag-executor.md §9**; this spec
 * aligns to it.
 */
interface StagingFactory {
    /**
     * Opens a fresh per-execution staging database.
     *
     * @param executionId the discriminator baked into the in-memory JDBC URL.
     * @param engine the staging engine; defaults to [StagingEngine.H2], the only v1 engine.
     */
    fun create(
        executionId: UUID,
        engine: StagingEngine = StagingEngine.H2,
    ): Staging
}

/**
 * The H2 staging factory (staging.md §3.1).
 *
 * The JDBC URL is `jdbc:h2:mem:exec_{id};MODE={mode};DATABASE_TO_LOWER=TRUE` with **no
 * `DB_CLOSE_DELAY`**: default H2 semantics discard an in-memory database the moment its last
 * connection closes, which is exactly the per-execution lifetime we want (§3.4).
 * `DB_CLOSE_DELAY=-1` — removed in v1.2 — would keep every abandoned staging database alive
 * until JVM exit: an unbounded leak in a long-lived server.
 *
 * ## Why `DATABASE_TO_LOWER=TRUE` is load-bearing
 *
 * Staging emits every generated identifier double-quoted (§4.5), and table names are lowercase
 * by contract (§4.1) — so a staged table is `"stg_orders"`, stored lowercase. `MODE=PostgreSQL`
 * alone does **not** make H2 fold *unquoted* identifiers the way PG does: H2 keeps its own
 * upper-folding, so the author SQL `SELECT n FROM stg_orders` — the style used throughout
 * dag-executor.md §6.5, pipeline-contract.md §10.1 and templates.md §11 — resolves as
 * `STG_ORDERS` and fails with SQLState 42S03 against the lowercase table. `DATABASE_TO_LOWER=TRUE`
 * is the setting that lower-folds unquoted identifiers, which is what makes §11.3's "table names
 * are lowercase by contract, so unquoted references to them work" actually true. Verified against
 * the pinned driver (2.3.232): with `MODE=PostgreSQL` alone the unquoted select fails 42S03; with
 * this parameter it returns the row.
 *
 * It is hardcoded rather than exposed as config: it is a correctness invariant of the staged
 * identifier scheme, not an operator choice — a deployment that turned it off would break every
 * multi-node pipeline. Note the consequence downstream: an *upper*-case staged column (which an
 * H2 source's unquoted `SELECT id` produces, since the source database does its own folding) now
 * needs quoting in author SQL, exactly as §4.2/§11.3 already require for any mixed-case column.
 * It also lower-cases the catalog's own schema names, which is why the §10/§3.4 catalog
 * projection in [H2Staging] compares them case-insensitively.
 *
 * The factory opens the **first** operational connection and hands it to a bounded
 * [H2ConnectionPool] behind [H2Staging]; the pool opens further restricted connections on
 * demand up to `max-connections` (§9, #118), the executor holds the instance open for the
 * whole execution (§3.5) and closes it in a `finally` (§3.4).
 *
 * ## Privilege containment (§9.5)
 *
 * Every operational connection authenticates as a **non-admin** user, because the SQL it later
 * runs is pipeline-author-authored. An `sa` session would hand that author H2's admin surface —
 * `FILE_READ('/proc/self/environ')` reads `DATAPIPELINES_DB_ENCRYPTION_KEY` and
 * `DATAPIPELINES_JWT_SECRET`, `CREATE ALIAS` loads arbitrary JVM classes — turning "may write
 * tempdb SQL" into "owns every datasource credential and can forge sessions".
 *
 * So creation is two-phase — [H2RestrictedSession.open] (the helper the probe's scratch engine
 * and the H2 datasource pool share since 186): a **transient** `sa` bootstrap connection creates
 * the database and the restricted user, the first operational connection is opened as that user,
 * and only then does the bootstrap close. The overlap is mandatory, not incidental — with
 * default in-memory semantics (§3.1) the database is discarded the moment its *last* connection
 * closes, so a bootstrap that closed first would take the database with it. Every later pool
 * connection is opened with the same URL, mode, folding and restricted credential, and only
 * while at least one operational connection is already open — so no connection ever creates a
 * database, and every one lands in the execution's.
 *
 * What the helper does *not* claim: the database's own `sa` account still exists with an empty
 * password for the database's lifetime. That is not reachable from the threat this class defends
 * against — author SQL cannot open a JDBC connection at all, since `LINK_SCHEMA` and
 * `CREATE ALIAS` are exactly what the restricted user is refused — but it does mean the
 * containment is against *author SQL*, not against arbitrary code already running in this JVM.
 *
 * @param config the already-resolved effective properties (see [H2StagingProperties] — the
 *   per-pipeline `max_memory_mb` override is applied by the caller before construction).
 */
class H2StagingFactory(
    /** The effective properties every execution's staging is built from; readable so wiring is testable. */
    val properties: H2StagingProperties,
    /**
     * How a JDBC connection is opened — `DriverManager` in production, which the one-argument
     * constructor supplies. This is a **fault-injection seam for tests**, not a configuration
     * point: the executor suite (`StagingLostExecutorTest`) wraps the restricted sessions to
     * fail a reset and refuse the replacement, which is the only way to reach a LOST pool
     * through the real executor; the staging suite uses it to fail one phase of creation.
     * Production wiring (`DomainConfiguration.stagingFactory`) never passes it.
     */
    private val connect: (url: String, user: String, password: String) -> Connection,
) : StagingFactory {
    constructor(config: H2StagingProperties) : this(config, DriverManager::getConnection)

    override fun create(
        executionId: UUID,
        engine: StagingEngine,
    ): Staging {
        // v1 supports H2 only. A future engine whose driver is absent from the classpath fails
        // with `pipeline.staging.engine_unavailable`; with a single-constant enum the `when` is
        // exhaustive and that branch is unreachable today.
        when (engine) {
            StagingEngine.H2 -> Unit
        }

        val jdbcUrl = "jdbc:h2:mem:exec_$executionId;MODE=${properties.mode};$LOWER_FOLDING"
        return openPool(jdbcUrl, executionId)
    }

    /**
     * Bootstraps the database as `sa`, opens the first **restricted** operational connection
     * (§9.5) and builds the pool around it — the two-phase open itself is
     * [H2RestrictedSession.open], the helper the `sql_probe` scratch engine and the H2
     * datasource pool share; what stays here is the error SHAPING: any failure in any phase —
     * the admin connect, the user creation, the restricted connect, or the pool's own capture
     * of the session defaults — is one catalogued `creation_failed` (§3.1); the caller cannot
     * act on the difference, and the phase leaks nothing useful into the message. A failure
     * AFTER the operational connection opened closes it, so the half-built database dies with
     * the failure instead of outliving it.
     */
    private fun openPool(
        jdbcUrl: String,
        executionId: UUID,
    ): Staging =
        try {
            val session =
                H2RestrictedSession.open(
                    url = jdbcUrl,
                    user = EXEC_USER,
                    grants = listOf("GRANT ALTER ANY SCHEMA TO $EXEC_USER"),
                    connect = connect,
                )
            val pool =
                try {
                    // The opener retains the credential inside the session object and nowhere
                    // else: it is what lets the pool grow on demand as the restricted user (§9.5).
                    H2ConnectionPool(executionId, session.firstConnection, session::openConnection, properties.maxConnections)
                } catch (e: SQLException) {
                    session.firstConnection.close()
                    throw e
                }
            H2Staging(executionId, pool, properties)
        } catch (e: SQLException) {
            throw creationFailed(executionId, e.message, e)
        }

    /**
     * The catalogued `creation_failed` (§3.1, §7.2). [detail] is the only driver text that
     * reaches the caller, so every call site decides deliberately what may be quoted. The one
     * phase whose raw driver text is NEVER quoted is the user setup: H2 appends the failing
     * statement to its message, and `CREATE USER … PASSWORD '<hex>'` would put the cleartext
     * credential into a user-visible error — which is why [H2RestrictedSession] sanitizes that
     * phase to SQLState + vendor code before it can reach here.
     */
    private fun creationFailed(
        executionId: UUID,
        detail: String?,
        cause: SQLException,
    ): DatapipelinesException =
        DatapipelinesException(
            code = StagingErrorCodes.CREATION_FAILED,
            message = "Could not create the H2 staging database for execution $executionId: $detail",
            details = mapOf("execution_id" to executionId.toString()),
            cause = cause,
        )

    private companion object {
        /** The non-admin identity every staging operation and all author SQL runs as (§9.5). */
        const val EXEC_USER = "STAGING_EXEC"

        /**
         * Lower-folding for unquoted identifiers — the class KDoc explains why this is a
         * correctness invariant of the staged identifier scheme and not a configuration key.
         */
        const val LOWER_FOLDING = "DATABASE_TO_LOWER=TRUE"
    }
}
