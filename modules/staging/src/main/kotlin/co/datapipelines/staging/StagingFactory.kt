package co.datapipelines.staging

import co.datapipelines.typesystem.DatapipelinesException
import java.security.SecureRandom
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
 * The JDBC URL is `jdbc:h2:mem:exec_{id};MODE={mode}` with **no `DB_CLOSE_DELAY`**: default
 * H2 semantics discard an in-memory database the moment its last connection closes, which is
 * exactly the per-execution lifetime we want (§3.4). `DB_CLOSE_DELAY=-1` — removed in v1.2 —
 * would keep every abandoned staging database alive until JVM exit: an unbounded leak in a
 * long-lived server.
 *
 * The factory opens the **single** operational connection and hands it to [H2Staging]; the
 * executor holds that connection open for the whole execution (§3.5) and closes it in a
 * `finally` (§3.4).
 *
 * ## Privilege containment (§9.5)
 *
 * The operational connection authenticates as a **non-admin** user, because the SQL it later
 * runs is pipeline-author-authored. An `sa` session would hand that author H2's admin surface —
 * `FILE_READ('/proc/self/environ')` reads `DATAPIPELINES_DB_ENCRYPTION_KEY` and
 * `DATAPIPELINES_JWT_SECRET`, `CREATE ALIAS` loads arbitrary JVM classes — turning "may write
 * tempdb SQL" into "owns every datasource credential and can forge sessions".
 *
 * So creation is two-phase: a **transient** `sa` bootstrap connection creates the database and
 * the restricted user, the operational connection is opened as that user, and only then does the
 * bootstrap close. The overlap is mandatory, not incidental — with default in-memory semantics
 * (§3.1) the database is discarded the moment its *last* connection closes, so a bootstrap that
 * closed first would take the database with it.
 *
 * @param config the already-resolved effective properties (see [H2StagingProperties] — the
 *   per-pipeline `max_memory_mb` override is applied by the caller before construction).
 */
class H2StagingFactory(
    private val config: H2StagingProperties,
) : StagingFactory {
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

        val jdbcUrl = "jdbc:h2:mem:exec_$executionId;MODE=${config.mode}"
        val connection = openConnection(jdbcUrl, executionId)
        return H2Staging(executionId, connection, config)
    }

    /**
     * Bootstraps the database as `sa` and returns the **restricted** operational connection
     * (§9.5). Any failure in either phase — the admin connect, the user creation, or the
     * restricted connect — is one catalogued `creation_failed` (§3.1); the caller cannot act on
     * the difference, and the phase leaks nothing useful into the message.
     */
    private fun openConnection(
        jdbcUrl: String,
        executionId: UUID,
    ): Connection =
        try {
            val password = newExecUserPassword()
            // `use` closes the bootstrap on every path, including the one where opening the
            // operational connection throws — and it closes it only AFTER that connection exists,
            // which is what keeps the in-memory database alive across the handover.
            DriverManager.getConnection(jdbcUrl, BOOTSTRAP_USER, BOOTSTRAP_PASSWORD).use { bootstrap ->
                createExecUser(bootstrap, password, executionId)
                DriverManager.getConnection(jdbcUrl, EXEC_USER, password)
            }
        } catch (e: SQLException) {
            throw creationFailed(executionId, e.message, e)
        }

    /**
     * The catalogued `creation_failed` (§3.1, §7.2). [detail] is the only driver text that
     * reaches the caller, so every call site decides deliberately what may be quoted.
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

    /**
     * Creates the non-admin user the module operates as (§9.5).
     *
     * `ALTER ANY SCHEMA` is the **one** grant that makes `CREATE`/`INSERT`/`SELECT`/`DROP TABLE`
     * work for a non-admin user in the `PUBLIC` schema — verified empirically against the pinned
     * driver (2.3.232): `GRANT ALL ON SCHEMA PUBLIC` alone leaves `CREATE TABLE` refused with
     * SQLState 90096, and adds nothing on top of this one. It is a schema-DDL right, **not** an
     * admin right: under it every host-reaching function is still refused with SQLState 90040
     * (`FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `CREATE ALIAS`, `RUNSCRIPT`,
     * `LINK_SCHEMA`, `CREATE TRIGGER … AS`), as are `ALTER USER … ADMIN TRUE` and `CREATE USER`.
     * `H2StagingPrivilegeTest` is the standing guard on that claim.
     *
     * `SwallowedException` is suppressed deliberately — it is the point here, not an oversight.
     * The original exception carries the cleartext password in its message (see the `catch`), so
     * it must not become the `cause` nor be quoted; the SQLState and vendor code are lifted onto
     * a clean exception so the failure stays diagnosable without the credential riding along.
     */
    @Suppress("SwallowedException")
    private fun createExecUser(
        bootstrap: Connection,
        password: String,
        executionId: UUID,
    ) {
        try {
            bootstrap.createStatement().use { st ->
                // H2 has no parameter binding for CREATE USER, so the password is inlined — safely
                // by construction: newExecUserPassword() emits hex digits only, so no quote,
                // backslash, or statement separator can occur in it. The user name is a constant.
                st.execute("CREATE USER $EXEC_USER PASSWORD '$password'")
                st.execute("GRANT ALTER ANY SCHEMA TO $EXEC_USER")
            }
        } catch (e: SQLException) {
            // This phase's driver text is the one place that must NEVER be quoted: H2 appends the
            // failing statement to its message, and for `CREATE USER … PASSWORD '<hex>'` that puts
            // the cleartext credential into a user-visible catalogued error (and into any log that
            // prints the cause chain). Report the SQLState and vendor code — enough to diagnose a
            // bootstrap failure — and drop the message and the original exception entirely.
            throw creationFailed(
                executionId,
                "restricted-user setup failed (SQLState ${e.sqlState}, error ${e.errorCode})",
                SQLException("restricted-user setup failed", e.sqlState, e.errorCode),
            )
        }
    }

    /**
     * A fresh 256-bit password per execution, hex-encoded. It is never stored on the instance,
     * logged, or returned: the operational connection is the only thing that ever needed it, so
     * once [create] returns, no code in this process holds the credential to open a second
     * session as this user.
     *
     * Note what this does *not* claim: the database's own `sa` account still exists with an empty
     * password for the database's lifetime. That is not reachable from the threat this class
     * defends against — author SQL cannot open a JDBC connection at all, since `LINK_SCHEMA` and
     * `CREATE ALIAS` are exactly what the restricted user is refused — but it does mean the
     * containment is against *author SQL*, not against arbitrary code already running in this JVM.
     */
    private fun newExecUserPassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        /**
         * The transient bootstrap identity (§9.5). H2 creates `sa` with an empty password on the
         * first connection to a fresh in-memory database; that connection exists only long enough
         * to create [EXEC_USER] and is closed before any author SQL can run.
         */
        const val BOOTSTRAP_USER = "sa"
        const val BOOTSTRAP_PASSWORD = ""

        /** The non-admin identity every staging operation and all author SQL runs as (§9.5). */
        const val EXEC_USER = "STAGING_EXEC"

        const val PASSWORD_BYTES = 32
        val SECURE_RANDOM = SecureRandom()
    }
}
