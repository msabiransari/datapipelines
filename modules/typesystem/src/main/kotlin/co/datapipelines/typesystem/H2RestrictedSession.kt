package co.datapipelines.typesystem

import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The two-phase **de-privileged open** of an in-process H2 database (staging.md §9.5, 186): a
 * transient bootstrap admin connection creates the database and a restricted user, the first
 * operational connection is opened as that user, and only then does the bootstrap close.
 *
 * ## Why this lives in `typesystem`
 *
 * The two callers are `staging` (per-execution staging databases) and `datasources` (the
 * `sql_probe` scratch engine and the H2 datasource pool's operational connections), and
 * module-structure.md §5.4's allowed-dependency table gives them exactly ONE shared module:
 * this one. A `staging` → `datasources` edge (or the reverse) would be a new module dependency;
 * a third module would be a new layer. The code itself is pure JDK (`java.sql`) — no driver
 * import, no Spring — which is what layer 0 may carry (the module already ships H2-specific
 * code: `H2IngressMapper`/`H2EgressMapper`).
 *
 * ## The overlap is mandatory
 *
 * With default H2 in-memory semantics the database is discarded the moment its LAST connection
 * closes, so a bootstrap that closed first would take the database with it. `open` therefore
 * opens the first restricted connection BEFORE the bootstrap closes, and hands it back as
 * [firstConnection]; a caller with a pool opens further connections through [openConnection],
 * which reuses the retained credential — the credential lives inside this object and nowhere
 * an author's SQL can reach.
 *
 * ## What the restricted user can and cannot do
 *
 * The created user is non-admin, so every host-reaching function is refused with SQLState
 * `90040` ("Admin rights are required", pinned against H2 2.3.232 by `H2StagingPrivilegeTest`):
 * `FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `CREATE ALIAS`, `RUNSCRIPT`, `LINK_SCHEMA`,
 * `CREATE TRIGGER … AS`, `SET` of server properties, and the self-escalation routes
 * `ALTER USER … ADMIN TRUE` / `CREATE USER`. Grants beyond that baseline are the CALLER's
 * choice, supplied as trusted constant DDL through [grants] — staging passes
 * `GRANT ALTER ANY SCHEMA TO …` (the least grant that makes PUBLIC-schema DDL work, §9.5);
 * the probe's scratch user gets nothing, because a probe only ever runs one classified SELECT.
 *
 * The user-management DDL carries the password INLINE — H2 has no parameter binding for it —
 * which is safe by construction: [newPassword] emits hex digits only, and [user] is validated
 * against a strict identifier alphabet, so no quote, backslash or statement separator can
 * occur. Because H2 appends the failing statement to its exception messages, a setup failure is
 * rethrown as a SANITIZED [SQLException] — SQLState and vendor code only — so the cleartext
 * password can never ride a log line or an error envelope.
 */
class H2RestrictedSession internal constructor(
    /** The non-admin identity every operational connection authenticates as. */
    val user: String,
    /**
     * The per-open random password — retained privately so [openConnection] can mint further
     * restricted sessions; never logged, never returned by any surface, dies with this object.
     */
    val password: String,
    /**
     * The first OPERATIONAL connection, already open as [user] when [open] returns. Handing it
     * back — rather than closing it — is what keeps an in-memory database alive across the
     * handover; the caller owns closing it.
     */
    val firstConnection: Connection,
    /** The URL restricted connections open against — [open]'s `operationalUrl`, or its `url`. */
    val operationalUrl: String,
    private val connect: (url: String, user: String, password: String) -> Connection,
) {
    /** A further operational connection as [user] — the pool-growth seam. */
    fun openConnection(): Connection = connect(operationalUrl, user, password)

    companion object {
        /** H2 creates `sa` with an empty password on the first connection to a fresh database. */
        const val BOOTSTRAP_USER = "sa"
        const val BOOTSTRAP_PASSWORD = ""

        /**
         * The identifier alphabet [user] is confined to before it is inlined into DDL. Every
         * caller passes its own constant (`STAGING_EXEC`, the probe's scratch user, the
         * datasource-pool user) — the check is what keeps "trusted constant" honest.
         */
        private val USER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * Opens [url] de-privileged: bootstrap as [bootstrapUser] (the registered credential of
         * an existing file database, or `sa`/"" for a fresh in-memory one), create [user] with a
         * fresh [newPassword], apply [grants], open the first restricted connection, close the
         * bootstrap — in that order, and only in that order.
         *
         * [rotateIfExists] decides what an ALREADY-EXISTING [user] means. `false` (staging): the
         * database is created fresh per execution, so an existing user is a squatter and the
         * bare `CREATE USER` fails loudly. `true` (a file-backed datasource, 186 §A3): the user
         * persists in the file across pool builds, so the open is idempotent —
         * `CREATE USER IF NOT EXISTS` + `ALTER USER … SET PASSWORD` brings the stored password
         * to this generation's random value, and `ALTER USER … ADMIN FALSE` (186b) clears the
         * ADMIN flag a pre-existing file's user of that name may carry — a rotation that only
         * re-passworded would leave the de-privileged identity an admin.
         *
         * @param grants trusted constant DDL statements run on the bootstrap after the user
         *   exists (e.g. staging's `GRANT ALTER ANY SCHEMA TO STAGING_EXEC`). Never
         *   author-derived text.
         * @param operationalUrl the URL restricted connections open against when it differs
         *   from [url] (#186: a registered H2 URL may carry `DB_CLOSE_DELAY`, which H2 executes
         *   as an admin-gated `SET` on EVERY session open — the admin bootstrap can apply it,
         *   the restricted user cannot connect with it). Null = one URL for both.
         * @param connect how a JDBC connection is opened — `DriverManager::getConnection` in
         *   production; a fault-injection seam for tests (the staging suites fail one phase of
         *   creation through it).
         * @throws SQLException the bootstrap or restricted connect failed (raw — no credential
         *   can ride it), or the user setup failed (SANITIZED: SQLState and vendor code only,
         *   because H2 quotes the failing statement, and the statement carries the password).
         */
        fun open(
            url: String,
            user: String,
            grants: List<String> = emptyList(),
            rotateIfExists: Boolean = false,
            operationalUrl: String? = null,
            bootstrapUser: String = BOOTSTRAP_USER,
            bootstrapPassword: String = BOOTSTRAP_PASSWORD,
            connect: (url: String, user: String, password: String) -> Connection = DriverManager::getConnection,
        ): H2RestrictedSession {
            require(USER_NAME.matches(user)) { "restricted user name '$user' is not a plain SQL identifier" }
            val password = newPassword()
            val restrictedUrl = operationalUrl ?: url
            // `use` closes the bootstrap on every path, including the one where opening the
            // restricted connection throws — and it closes it only AFTER that connection exists,
            // which is what keeps an in-memory database alive across the handover.
            val first =
                connect(url, bootstrapUser, bootstrapPassword).use { bootstrap ->
                    createRestrictedUser(bootstrap, user, password, grants, rotateIfExists)
                    connect(restrictedUrl, user, password)
                }
            return H2RestrictedSession(user, password, first, restrictedUrl, connect)
        }

        /** A fresh 256-bit password, hex-encoded — hex digits only, so the inlined DDL cannot break out of its quotes. */
        fun newPassword(): String {
            val bytes = ByteArray(PASSWORD_BYTES)
            SECURE_RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { byte -> "%02x".format(byte) }
        }

        // The swallow is the point, not an oversight: the raw message carries the cleartext
        // password (H2 quotes the failing statement); the sanitized rethrow keeps SQLState +
        // vendor code and drops the text.
        @Suppress("SwallowedException")
        private fun createRestrictedUser(
            bootstrap: Connection,
            user: String,
            password: String,
            grants: List<String>,
            rotateIfExists: Boolean,
        ) {
            try {
                bootstrap.createStatement().use { st ->
                    if (rotateIfExists) {
                        st.execute("CREATE USER IF NOT EXISTS $user PASSWORD '$password'")
                        st.execute("ALTER USER $user SET PASSWORD '$password'")
                        // The rotation must also DE-ESCALATE: a pre-existing file may carry a
                        // user of this name with ADMIN TRUE (created outside the product), and
                        // re-passwording alone would leave the restricted identity an admin.
                        st.execute("ALTER USER $user ADMIN FALSE")
                    } else {
                        st.execute("CREATE USER $user PASSWORD '$password'")
                    }
                    grants.forEach { st.execute(it) }
                }
            } catch (e: SQLException) {
                // This phase's driver text is the one place that must NEVER be quoted: H2 appends
                // the failing statement to its message, which would put the cleartext password
                // into a user-visible error and any log printing the cause chain. Report the
                // SQLState and vendor code — enough to diagnose — and drop the message.
                throw SQLException(
                    "restricted-user setup failed (SQLState ${e.sqlState}, error ${e.errorCode})",
                    e.sqlState,
                    e.errorCode,
                )
            }
        }

        private const val PASSWORD_BYTES = 32
        private val SECURE_RANDOM = SecureRandom()
    }
}
