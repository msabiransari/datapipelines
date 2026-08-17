package co.datapipelines.datasources

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
 */
class DefaultDatasourceRegistry(
    private val repository: DatasourceRepository,
    private val encryptor: CredentialEncryptor,
    private val validator: DatasourceValidator = DatasourceValidator(),
    private val references: DatasourceReferences = DatasourceReferences.NONE,
    private val auditSink: DatasourceAuditSink = DatasourceAuditSink.NONE,
    private val cache: DatasourceMetadataCache = DatasourceMetadataCache(),
) : DatasourceRegistry {
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

    override fun get(name: String): Datasource? = cache.get(name) { repository.findByName(it)?.toDatasource() }

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
            row.toDatasource()
        }
    }

    override fun delete(name: String): DeleteResult {
        val referencing = references.pipelinesReferencing(name)
        if (referencing.isNotEmpty()) {
            return DeleteResult(
                deleted = false,
                name = name,
                errorCode = DatasourceErrorCodes.IN_USE,
                referencingPipelines = referencing,
            )
        }
        val deleted = repository.softDelete(name)
        if (deleted) {
            poolManager.evict(name)
            cache.invalidate(name)
        }
        return DeleteResult(deleted = deleted, name = name)
    }

    override fun poolFor(datasource: Datasource): ConnectionPool = poolManager.poolFor(datasource)

    override fun testConnection(name: String): TestResult? {
        val row = repository.findByName(name) ?: return null
        val datasource = row.toDatasource(encryptor.decrypt(row.passwordEncrypted, row.name))
        audit(DatasourceAuditEvents.CONNECTION_TEST, name, DatasourceAuditEvent.SYSTEM_ACTOR)
        return probe(datasource)
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
        error = e.message?.scrubbedForError(datasource.password),
        errorClass = e.javaClass.name,
    )

    private companion object {
        const val PROBE_CONNECTION_TIMEOUT_MS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
