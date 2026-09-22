package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.typesystem.H2RestrictedSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * The #186 A3 containment for a REGISTERED in-process H2 datasource: every operational
 * connection its pool hands out authenticates as a freshly minted **non-admin** user, not the
 * registered credential.
 *
 * Why: A1 makes registration of an in-process engine a super-admin act, but the datasource then
 * exists to be USED — pipeline nodes and `sql_probe` run author-authored SQL through its pool,
 * and a pool authenticating as `sa` hands H2's admin surface (`FILE_READ`, `CREATE ALIAS`, …) to
 * that SQL, in the server's own JVM. This is staging.md §9.5's discipline applied to the
 * datasource path, through the same [H2RestrictedSession] two-phase open: the REGISTERED
 * credential is the bootstrap (it is the database's admin — that is what the operator handed
 * over), it creates/rotates `DP_H2_RESTRICTED` with a per-pool-build random password and the
 * grants a datasource workload needs, and the pool is configured with THAT credential. The
 * registered secret never reaches a pooled connection again.
 *
 * Two mechanics worth stating:
 *
 *  - **The handover overlap.** The session's first connection stays open until the pool has
 *    opened its own first connection — with default in-memory semantics the database dies with
 *    its last connection, and a mem: datasource must not evaporate between bootstrap and pool.
 *    `initializationFailTimeout` is therefore forced to at least 1 here: HikariCP's fail-fast
 *    check is what guarantees the pool's first connection exists before the constructor
 *    returns, and a `properties.hikari` override must not be able to turn it off on exactly the
 *    form that needs it.
 *  - **File databases keep the user.** `rotateIfExists` makes the open idempotent over a file
 *    that already carries `DP_H2_RESTRICTED` from a previous pool build: the password rotates to
 *    this generation's random value, so a retired pool's credential is dead the moment its
 *    replacement exists.
 */
internal object H2InProcessPool {
    /** The non-admin identity every pooled connection to an in-process H2 datasource runs as. */
    const val RESTRICTED_USER = "DP_H2_RESTRICTED"

    /** The grants a datasource workload needs: schema DDL, and DML on the customer's own tables. */
    private fun grants(user: String) =
        listOf(
            "GRANT ALTER ANY SCHEMA TO $user",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA PUBLIC TO $user",
        )

    /**
     * Builds the pool for [datasource] de-privileged: bootstrap with the registered credential
     * (or `sa`/"" for a credential-less fresh database), then pool connections as
     * [RESTRICTED_USER]. On any failure the session's first connection is closed with the pool
     * that never came up — fully built or not at all, the `openLakeInstance` rule.
     */
    fun build(
        datasource: Datasource,
        config: HikariConfig,
    ): HikariConnectionPool {
        val session =
            H2RestrictedSession.open(
                url = datasource.jdbcUrl,
                user = RESTRICTED_USER,
                grants = grants(RESTRICTED_USER),
                rotateIfExists = true,
                // H2 runs a URL's admin-gated settings as `SET` commands on EVERY session open
                // (pinned: 90040 under the restricted user on 2.3.232) — the bootstrap applies
                // them once, and operational connections open against the URL without them. The
                // settings are database-scoped, not session-scoped, so their effect survives.
                operationalUrl = stripSessionAdminSettings(datasource.jdbcUrl),
                bootstrapUser = datasource.username ?: H2RestrictedSession.BOOTSTRAP_USER,
                bootstrapPassword = datasource.secret ?: H2RestrictedSession.BOOTSTRAP_PASSWORD,
            )
        try {
            config.jdbcUrl = session.operationalUrl
            config.username = session.user
            config.password = session.password
            // See the class KDoc: the pool's first connection must exist before the session's
            // first connection closes, or a mem: database evaporates between them.
            config.initializationFailTimeout = maxOf(1, config.initializationFailTimeout)
            val pool = HikariDataSource(config)
            session.firstConnection.close()
            return HikariConnectionPool(datasource.name, pool)
        } catch (
            // HikariDataSource construction and PoolInitializationException are RuntimeExceptions
            // (the §8.1 probe catches the same family for the same reason): whichever half failed,
            // the session's first connection must not outlive a pool that never came up.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            session.firstConnection.close()
            throw e
        }
    }

    /**
     * The URL without the admin-gated settings H2 runs as `SET` commands on every session open
     * (186b: the full set, not only `DB_CLOSE_DELAY`) — from the `;`-separated H2 property tail
     * (the same separator `JdbcUrlGuard` tokenizes on).
     *
     * The stripped set is the admin-gated list of H2 2.3.232's `Set` command
     * (`org/h2/command/dml/Set.java` — every `case` whose body calls `checkAdmin()`), minus
     * `IGNORE_CATALOGS`, which is a `DbSettings` key and so never reaches the session-open SET
     * loop (`Engine.openSession` skips it), and minus `DB_CLOSE_ON_EXIT` and the other
     * connection-level settings, which the parser turns into a `NoOperation`
     * (`ConnectionInfo.isIgnoredByParser`) — they are never admin-gated and need no stripping.
     * Every stripped setting is database-scoped (stored via `addOrUpdateSetting`, or set on the
     * shared `Database`/`TraceSystem` object), so the admin bootstrap's one application carries
     * the effect and operational connections lose nothing — the same contract `DB_CLOSE_DELAY`
     * had. The alternative, refusing these settings at registration, was rejected: `CACHE_SIZE`
     * and friends are legitimate tuning a super admin may want, and this is an availability
     * question (an unstripped setting fails every pooled open with 90040), not an exposure one
     * — the SET fails closed under the restricted user.
     *
     * Key comparison is case-insensitive because H2 uppercases URL setting keys
     * (`ConnectionInfo.readSettingsFromURL` → `StringUtils.toUpperEnglish`).
     */
    private fun stripSessionAdminSettings(url: String): String {
        val cut = url.indexOf(';')
        if (cut < 0) return url
        val kept =
            url
                .substring(cut + 1)
                .split(';')
                .filter { it.isNotBlank() }
                .filterNot { it.substringBefore('=').trim().uppercase() in SESSION_ADMIN_SETTINGS }
        return listOf(listOf(url.substring(0, cut)), kept).flatten().joinToString(";")
    }

    /**
     * The admin-gated settings of H2 2.3.232's session-open SET loop — see
     * [stripSessionAdminSettings] for provenance and the two deliberate exclusions.
     */
    private val SESSION_ADMIN_SETTINGS =
        setOf(
            "ALLOW_LITERALS",
            "AUTHENTICATOR",
            "BUILTIN_ALIAS_OVERRIDE",
            "CACHE_SIZE",
            "CLUSTER",
            "COLLATION",
            "CREATE_BUILD",
            "DATABASE_EVENT_LISTENER",
            "DB_CLOSE_DELAY",
            "DEFAULT_LOCK_TIMEOUT",
            "DEFAULT_NULL_ORDERING",
            "DEFAULT_TABLE_TYPE",
            "EXCLUSIVE",
            "IGNORECASE",
            "JAVA_OBJECT_SERIALIZER",
            "LOCK_MODE",
            "MAX_LENGTH_INPLACE_LOB",
            "MAX_LOG_SIZE",
            "MAX_MEMORY_ROWS",
            "MAX_MEMORY_UNDO",
            "MAX_OPERATION_MEMORY",
            "MODE",
            "OPTIMIZE_REUSE_RESULTS",
            "QUERY_STATISTICS",
            "QUERY_STATISTICS_MAX_ENTRIES",
            "REFERENTIAL_INTEGRITY",
            "RETENTION_TIME",
            "TRACE_LEVEL_FILE",
            "TRACE_LEVEL_SYSTEM_OUT",
            "TRACE_MAX_FILE_SIZE",
            "WRITE_DELAY",
        )
}
