package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialDecryptionException
import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.datasources.pooling.ConnectionPool
import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * The production [DatasourceRegistry]: validation + encryption + persistence + pooling + the
 * §7.4 audit trail wired together (datasources.md §6).
 *
 * ## Credential handling (§7.4)
 *
 * A password is encrypted with [CredentialEncryptor] on the way in — bound to the datasource
 * name as GCM associated data — and **decrypted exactly once per pool build**, never per lease.
 * The pool manager's factory is the only decrypt point on the hot path: it reloads the row and
 * decrypts inside `computeIfAbsent`, so the plaintext materializes once when a datasource's pool
 * is first built and lives only inside HikariCP for the pool's lifetime. [get]/[list] return
 * datasources with `password = null` — the plaintext never leaves this class except into a pool.
 *
 * Every one of those decryption points writes a [DatasourceAuditEvent] to the injected
 * [DatasourceAuditSink]; nothing is written per lease, because no lease decrypts anything.
 *
 * ## Caching (§6.3)
 *
 * Datasource metadata is served from [DatasourceMetadataCache] and invalidated on every save and
 * delete. The pool-build path deliberately does **not** read the cache: it needs the encrypted
 * credential, which the cache never holds.
 *
 * ## Cross-instance pool invalidation (§5.7, 050/R1)
 *
 * Every save/delete evicts the local pool synchronously and then hands the datasource name to
 * [invalidation] so peer instances drop their pools for it too — without it, a replica serves a
 * stale pool (old credentials, old URL) until restart (ARCH-AUDIT M3). Local eviction never
 * waits on the channel: this instance's pool is gone the moment save returns, and the publisher
 * must not rely on receiving its own message back.
 */
class DefaultDatasourceRegistry(
    private val repository: DatasourceRepository,
    private val encryptor: CredentialEncryptor,
    private val validator: DatasourceValidator = DatasourceValidator(),
    private val references: DatasourceReferences = DatasourceReferences.NONE,
    private val auditSink: DatasourceAuditSink = DatasourceAuditSink.NONE,
    private val cache: DatasourceMetadataCache = DatasourceMetadataCache(),
    private val invalidation: PoolInvalidationPublisher = PoolInvalidationPublisher.NONE,
) : DatasourceRegistry {
    private val log = org.slf4j.LoggerFactory.getLogger(DefaultDatasourceRegistry::class.java)

    /**
     * Decrypts inside the pool-build factory only (§7.4). `computeIfAbsent` runs this at most
     * once per datasource, so the credential is decrypted once per pool build, not per lease —
     * and the `pool_build` audit event is emitted exactly there, on the same at-most-once path.
     */
    private val poolManager =
        ConnectionPoolManager { datasource ->
            val withCredential = loadWithCredential(datasource.name)
            audit(DatasourceAuditEvents.POOL_BUILD, datasource.name, DatasourceAuditEvent.SYSTEM_ACTOR)
            ConnectionPoolManager.buildHikariPool(withCredential)
        }

    override fun list(dialect: Dialect?): List<Datasource> = repository.findAll(dialect).map { it.toDatasource() }

    /** Workspaces design §5.3 — the repository's SQL predicate is the single visibility authority. */
    override fun listVisible(
        dialect: Dialect?,
        workspaceId: UUID,
    ): List<Datasource> = repository.findAllVisible(workspaceId, dialect).map { it.toDatasource() }

    override fun get(name: String): Datasource? = cache.get(name) { repository.findByName(it)?.toDatasource() }

    /**
     * Served from the §6.3 cache like [get] (025 C4, the 022 review's perf note): this is
     * the control plane's hot path — every REST GET and every save-time validation — and
     * was the one registry read going straight to the repository. Keyed (workspaceId,
     * name); misses never cached; invalidated with the name across every workspace.
     */
    override fun getVisible(
        name: String,
        workspaceId: UUID,
    ): Datasource? = cache.getVisible(workspaceId, name) { repository.findVisibleByName(it, workspaceId)?.toDatasource() }

    /**
     * The D10 channel made structural: one direct indexed PK read past the §6.3 cache — the
     * pool-build path bypasses it for the same reason (a state the cache may not have seen
     * yet is the state this question is asking about). Save-time validation's principal-less
     * fallback path reads this (044 F4). Password stays null: no live read ever carries the
     * credential — the flag-only [isReadonlyLive] is the hot-path twin that doesn't even
     * fetch it.
     */
    override fun getLive(name: String): Datasource? = repository.findByName(name)?.toDatasource()

    /**
     * Save-time validation's principal read (044 F4): the live twin of [getVisible] — same
     * repository predicate, no cache in front of it, so a row-level flag flip (either
     * direction) is honored by the next save instead of by the next cache expiry.
     */
    override fun getVisibleLive(
        name: String,
        workspaceId: UUID,
    ): Datasource? = repository.findVisibleByName(name, workspaceId)?.toDatasource()

    /**
     * The executor backstop's read (workspaces §6 layer 2a, 020 F7): one flag-only indexed PK
     * read per write-shaped node — the full-row fetch this replaces decrypted nothing but did
     * carry the credential ciphertext and two `properties_json` parses across the wire per
     * node. The `null`-means-absent mapping is the repository's ([DatasourceRepository.isReadonly]).
     */
    override fun isReadonlyLive(name: String): Boolean? = repository.isReadonly(name)

    override fun exists(name: String): Boolean = repository.exists(name)

    /**
     * The dry-run path — validates exactly what [save] would persist: the NORMALIZED form
     * (R5 F6; save validates the normalized copy, so a validate() that checked the raw
     * caller-supplied input could disagree with save's verdict on the same entity — the
     * asymmetry a dry-run endpoint would surface).
     */
    override fun validate(datasource: Datasource): ValidationResult =
        validator.validate(
            datasource.copy(introspectionIncludeSchemas = Datasource.normalizeIncludeSchemas(datasource.introspectionIncludeSchemas)),
            isCreate = !repository.exists(datasource.name),
        )

    /**
     * §3.3: the allowlist's lowercase normalization happens HERE — the single write-path
     * boundary every programmatic save crosses (REST create/update today, any future MCP
     * create tool tomorrow), so no write path can store a mixed-case entry that would
     * silently never match (matching lowercases the reported schema and compares stored
     * entries verbatim). The REST bind still normalizes as defense in depth, but it is no
     * longer load-bearing.
     */
    override fun save(
        datasource: Datasource,
        actor: UUID,
    ): Datasource {
        val toPersist =
            datasource.copy(
                introspectionIncludeSchemas = Datasource.normalizeIncludeSchemas(datasource.introspectionIncludeSchemas),
            )
        val isCreate = !repository.exists(toPersist.name)
        validator.validate(toPersist, isCreate).orThrow()
        cache.invalidate(toPersist.name)
        return if (isCreate) {
            val password = requireNotNull(toPersist.password) { "password required on create" }
            repository.create(toPersist, encryptor.encrypt(password, toPersist.name), actor).toDatasource()
        } else {
            val encrypted = toPersist.password?.let { encryptor.encrypt(it, toPersist.name) }
            val row =
                repository.update(toPersist, encrypted)
                    ?: error("datasource '${toPersist.name}' vanished during update")
            // Drain the old pool; it rebuilds lazily on the next lease under the new config
            // (§5.2). Deliberately not eager — a save must not require the database to be
            // reachable (§5.4). The audit event is conditional on a pool having existed: §7.4
            // audits credential *decryption*, and evicting nothing decrypted nothing (the
            // subsequent lazy build emits its own `pool_build`).
            if (poolManager.evict(toPersist.name)) {
                audit(DatasourceAuditEvents.POOL_REBUILD, toPersist.name, actor.toString())
            }
            cache.invalidate(toPersist.name)
            // Cross-instance fan-out (§5.7, 050/R1): AFTER the row write returned (committed)
            // and beside the synchronous local eviction above. The publisher's own instance
            // does not act on its own message — local eviction stays THIS synchronous call.
            invalidation.publish(toPersist.name)
            row.toDatasource()
        }
    }

    /**
     * §6.2's in-use guard, over the ANY-VERSION scan (061/T79). The refusal carries one entry
     * per referencing NODE — with the pipeline VERSION whose body holds it — because a
     * reference that lives only in a released v1 is exactly the one the old
     * `current_version`-only join could not see, and the operator's next move is to go and
     * change that node in that version.
     */
    override fun delete(name: String): DeleteResult {
        val referencing = references.referencesTo(name)
        if (referencing.isNotEmpty()) {
            return DeleteResult(
                deleted = false,
                name = name,
                errorCode = DatasourceErrorCodes.IN_USE,
                references = referencing,
            )
        }
        val deleted = repository.softDelete(name)
        if (deleted) {
            poolManager.evict(name)
            cache.invalidate(name)
            // Same §5.7 contract as update: peers learn the row is gone and drop their pools,
            // so a soft-deleted datasource stops serving queries on every instance (M3's
            // soft-delete tail), not just the one that happened to take the DELETE.
            invalidation.publish(name)
        }
        return DeleteResult(deleted = deleted, name = name)
    }

    /** The subscriber's target (§5.7, 050/R1): same drain the local save/delete paths run. */
    override fun evictPool(name: String): Boolean = poolManager.evict(name)

    override fun poolFor(datasource: Datasource): ConnectionPool = poolManager.poolFor(datasource)

    /**
     * The §8.1 probe — and, since 061/T84, the write that makes its outcome VISIBLE without
     * an execution (§8.1B): the result is recorded on the row before it is returned, so the
     * datasources list can say "auth failed since 2026-08-30" instead of nothing at all. The
     * write touches the three `last_test_*` columns only and leaves `updated_at` alone
     * ([DatasourceRepository.recordTestOutcome]); the §6.3 cache is invalidated so the next
     * read serves the fresh badge rather than a minute-old one.
     */
    override fun testConnection(name: String): TestResult? {
        val row = repository.findByName(name) ?: return null
        val datasource = row.toDatasource(encryptor.decrypt(row.passwordEncrypted, row.name))
        audit(DatasourceAuditEvents.CONNECTION_TEST, name, DatasourceAuditEvent.SYSTEM_ACTOR)
        return probe(datasource).also { record(name, it) }
    }

    /**
     * §8A.3 rule 3 (061/T84) — the bootstrap credential reconcile, decided by CONNECTING.
     *
     * Lives here because this is where the encryptor is: comparing the file's credential with
     * the stored one needs the decrypt, and writing the resynced one needs the encrypt. The
     * probe is [probe] — the SAME one [testConnection] runs, not a second one — and every
     * branch that probes records its outcome, so the screen tells the truth afterwards either
     * way.
     *
     * A soft-deleted row answers [CredentialResync.NO_LIVE_ROW] and is not touched: rule 1
     * says a deleted datasource never resurrects, and `findByName` filters `is_deleted` for
     * exactly that reason.
     */
    override fun resyncBootstrapCredential(
        name: String,
        fileCredential: String,
    ): CredentialResync {
        // Three guards, then the decision. The guards are the cases where NOTHING is probed —
        // no live row, an unreadable ciphertext, or credentials that already agree — and
        // separating them from [decideByProbing] is what keeps "rule 3 fires only on a
        // difference" readable as one line rather than inferred from control flow.
        val row = repository.findByName(name) ?: return CredentialResync.NO_LIVE_ROW
        val stored = decryptStored(row) ?: return CredentialResync.STORED_UNREADABLE
        // The gate that keeps rule 3 free on a healthy boot: equal credentials mean there is
        // no desync to diagnose, and probing every bootstrap datasource on every restart to
        // rediscover that would be a cost with no question behind it.
        if (stored == fileCredential) return CredentialResync.CREDENTIAL_MATCHES
        return decideByProbing(row, stored, fileCredential)
    }

    /**
     * The stored plaintext, or **null when the ciphertext cannot be read at all** — a wrong
     * master key, a row restored from another deployment, corruption.
     *
     * Null is not a login failure and must never be treated as one: the remedies differ (fix
     * `datapipelines.db.encryption-key`, not the database role's password), and overwriting an
     * unreadable credential would paper over a key problem every other datasource shares.
     * Startup must not die on it either — the app has to boot for the key to be fixable.
     */
    private fun decryptStored(row: DatasourceRow): String? =
        try {
            encryptor.decrypt(row.passwordEncrypted, row.name)
        } catch (e: CredentialDecryptionException) {
            log.debug("bootstrap credential for {} could not be decrypted", row.name, e)
            null
        }

    /**
     * Rule 3's actual decision, once the two credentials are known to differ: probe the STORED
     * one, and only if it fails probe the FILE's. Every branch records its outcome (§8.1B), so
     * the screen tells the truth whichever way this goes.
     */
    private fun decideByProbing(
        row: DatasourceRow,
        stored: String,
        fileCredential: String,
    ): CredentialResync {
        val name = row.name
        audit(DatasourceAuditEvents.CONNECTION_TEST, name, DatasourceAuditEvent.SYSTEM_ACTOR)
        val storedProbe = probe(row.toDatasource(stored))
        if (storedProbe.connected) {
            // Rule 1 wins: the operator's value works, so the row keeps it and only the
            // observation is written.
            record(name, storedProbe)
            return CredentialResync.STORED_WORKS
        }
        val fileProbe = probe(row.toDatasource(fileCredential))
        if (!fileProbe.connected) {
            // Nothing to resync TO. The stored probe's failure is what the row's credential
            // actually does, so that is the outcome the screen should show.
            record(name, storedProbe)
            return CredentialResync.BOTH_FAILED
        }
        repository.updateCredential(name, encryptor.encrypt(fileCredential, name))
        record(name, fileProbe)
        // The credential changed: the same three-step every save does (§5.2/§5.7) — drain the
        // local pool, drop the cached row, tell the peers. A pool built during startup from
        // the OLD ciphertext would otherwise outlive the fix.
        if (poolManager.evict(name)) {
            audit(DatasourceAuditEvents.POOL_REBUILD, name, DatasourceAuditEvent.SYSTEM_ACTOR)
        }
        cache.invalidate(name)
        invalidation.publish(name)
        return CredentialResync.RESYNCED
    }

    /**
     * Persists a probe's outcome (§8.1B) and drops the cached row so the next read sees it.
     * Silent when the row vanished between probe and write — a datasource deleted mid-probe
     * has no outcome to carry, and a test must not resurrect a deleted row's columns.
     */
    private fun record(
        name: String,
        result: TestResult,
    ) {
        if (repository.recordTestOutcome(name, DatasourceTestOutcome.of(result))) {
            cache.invalidate(name)
        }
    }

    /** Closes every pool (application shutdown). */
    fun shutdown() = poolManager.close()

    private fun loadWithCredential(name: String): Datasource {
        val row = requireNotNull(repository.findByName(name)) { "datasource '$name' not found for pool build" }
        return row.toDatasource(encryptor.decrypt(row.passwordEncrypted, row.name))
    }

    private fun audit(
        event: String,
        datasourceName: String,
        actor: String,
    ) = auditSink.record(
        DatasourceAuditEvent(
            timestamp = Instant.now(),
            datasourceName = datasourceName,
            event = event,
            actor = actor,
        ),
    )

    /**
     * A throwaway single-connection probe (§8.1). `initializationFailTimeout = -1` so an
     * unreachable host fails at the lease (returned as data) rather than at construction, and a
     * bounded `connectionTimeout` so the probe cannot hang. **Never throws** — failure is a
     * `connected = false` [TestResult] with a redaction-scrubbed message (no password, no
     * credential-bearing URL).
     *
     * Both exception families are caught on purpose (DS-SEC-6): a driver reports connection
     * failure as an [SQLException], but `HikariDataSource` construction and
     * `PoolInitializationException` are **RuntimeExceptions**, and a missing driver class or a
     * driver that rejects a property at parse time arrives that way. Catching only `SQLException`
     * would let those escape a method whose contract — and §8.1's failure-as-data rule — say it
     * never throws. `Error` is deliberately not caught.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun probe(datasource: Datasource): TestResult =
        try {
            val config =
                DialectAdapters.forDialect(datasource.dialect).buildHikariConfig(datasource).apply {
                    maximumPoolSize = 1
                    connectionTimeout = PROBE_CONNECTION_TIMEOUT_MS
                    initializationFailTimeout = -1
                    poolName = "ds-test-${datasource.name}"
                }
            HikariDataSource(config).use { pool ->
                val startedAt = System.nanoTime()
                pool.connection.use { connection ->
                    val version = connection.metaData.databaseProductVersion
                    val latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
                    TestResult(connected = true, testedAt = Instant.now(), latencyMs = latencyMs, serverVersion = version)
                }
            }
        } catch (e: SQLException) {
            failedProbe(e, datasource)
        } catch (e: RuntimeException) {
            failedProbe(e, datasource)
        }

    private fun failedProbe(
        e: Exception,
        datasource: Datasource,
    ) = TestResult(
        connected = false,
        testedAt = Instant.now(),
        error = rootMessage(e)?.scrubbedForError(datasource.password),
        errorClass = e.javaClass.name,
    )

    /**
     * The DEEPEST message in the cause chain, not the outermost one.
     *
     * HikariCP wraps a rejected login in its own "Connection is not available, request timed
     * out after 10007ms" — which is what the operator's screen showed, and which says nothing
     * about the actual problem. The driver's `FATAL: password authentication failed for user
     * "dp_demo_ro"` is two causes down, and it is the sentence that ends the investigation
     * (061/T84 — the incident's message is the incident's diagnosis). Scrubbing runs on the
     * result either way, so a deeper message is no more of a leak surface than a shallower one.
     */
    private fun rootMessage(e: Throwable): String? =
        generateSequence(e) { it.cause }
            .toList()
            .lastOrNull { !it.message.isNullOrBlank() }
            ?.message

    private companion object {
        const val PROBE_CONNECTION_TIMEOUT_MS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
