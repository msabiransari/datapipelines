package co.datapipelines.datasources

import co.datapipelines.datasources.crypto.CredentialEncryptor
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [DefaultDatasourceRegistry] end to end: metadata in a real Postgres, a reachable **H2**
 * user datasource (in-memory, so no second container), and the credential lifecycle §7.4
 * describes — encrypt on save, decrypt once at pool build, lease a real connection, and the
 * §6.2 in-use delete guard.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceRegistryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var owner: UUID

    private val encryptor = CredentialEncryptor.fromBase64Key(test32ByteKeyBase64())

    @BeforeAll
    fun createSchema() {
        jdbc = NamedParameterJdbcTemplate(dataSource())
        // The shipped migrations in version order — the ONE shared list (ShippedMigrations),
        // so a new migration lands in every suite that applies them, never a stale copy.
        ShippedMigrations.paths().forEach { path -> jdbc.jdbcTemplate.execute(TestFiles.repoFile(path).readText()) }
    }

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        owner = insertUser()
    }

    private fun registry(
        references: DatasourceReferences = DatasourceReferences.NONE,
        auditSink: DatasourceAuditSink = DatasourceAuditSink.NONE,
    ): DefaultDatasourceRegistry =
        DefaultDatasourceRegistry(DatasourceRepository(jdbc), encryptor, references = references, auditSink = auditSink)

    @Test
    fun `save encrypts the password and get never returns it`() {
        val registry = registry()
        val stored = registry.save(Fixtures.h2(name = "warehouse", password = "topsecret"), owner)

        // Neither the returned object nor a subsequent get carries the plaintext.
        stored.password.shouldBeNull()
        registry
            .get("warehouse")
            .shouldNotBeNull()
            .password
            .shouldBeNull()
        // The row on disk holds ciphertext, not the plaintext.
        val onDisk =
            jdbc.queryForObject(
                "SELECT encode(password_encrypted, 'hex') FROM datasources WHERE name = 'warehouse'",
                emptyMap<String, Any>(),
                String::class.java,
            )
        checkNotNull(onDisk).contains("topsecret".toByteArray().joinToString("") { "%02x".format(it) }) shouldBe false
    }

    @Test
    fun `poolFor builds a real pool - decrypting once - and leases a working connection`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "live", password = "pw"), owner)

        val datasource = registry.get("live").shouldNotBeNull()
        registry.poolFor(datasource).leaseConnection().use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    @Test
    fun `testConnection reports connected with a server version for a reachable datasource`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "probe", password = "pw"), owner)

        val result = registry.testConnection("probe").shouldNotBeNull()

        result.connected shouldBe true
        result.serverVersion.shouldNotBeNull()
        result.latencyMs.shouldNotBeNull()
    }

    @Test
    fun `testConnection on an unknown name is null, not a synthetic failed result`() {
        // §6.1 (v1.4): null = no such datasource, which the web layer maps to 404. The previous
        // behaviour returned connected = false with errorClass = "NotFound" — not an FQCN, and
        // indistinguishable from a real datasource that is merely unreachable.
        registry().testConnection("no_such_datasource").shouldBeNull()
    }

    @Test
    fun `testConnection on an unreachable host is data, not an exception`() {
        val registry = registry()
        registry.save(
            Fixtures.postgres(name = "dead", jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope"),
            owner,
        )

        val result = registry.testConnection("dead").shouldNotBeNull()

        result.connected shouldBe false
        result.error.shouldNotBeNull()
        // §6.1: errorClass is an exception FQCN.
        checkNotNull(result.errorClass).contains('.') shouldBe true
    }

    @Test
    fun `a probe failure never leaks the password or the URL credential segment`() {
        // §6.1: TestResult.error is redaction-scrubbed. Drivers routinely put the whole
        // connection string, credentials included, into their exception text — and this string
        // goes straight into an API response.
        val registry = registry()
        registry.save(
            Fixtures
                .postgres(name = "leaky", jdbcUrl = "jdbc:postgresql://192.0.2.1:5432/nope?ApplicationName=dp")
                .copy(username = "admin", password = "hunter2-the-secret"),
            owner,
        )

        val result = registry.testConnection("leaky").shouldNotBeNull()

        result.connected shouldBe false
        val text = checkNotNull(result.error)
        text shouldNotContain "hunter2-the-secret"
        text shouldNotContain "admin"
    }

    @Test
    fun `a driverless dialect makes testConnection return data, not throw`() {
        // DS-SEC-6: HikariDataSource construction and PoolInitializationException are
        // RuntimeExceptions, so a probe that caught only SQLException would throw out of a method
        // whose contract (and §8.1's failure-as-data rule) says it never does. Oracle's driver is
        // absent without -Poracle, so its pool cannot even be constructed.
        val registry = registry()
        jdbc.update(
            """
            INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, password_encrypted, created_by)
            VALUES ('nodriver', 'No driver', 'ORACLE', 'jdbc:oracle:thin:@//h:1521/svc', 'app', :pw, :owner)
            """.trimIndent(),
            mapOf("pw" to encryptor.encrypt("pw", "nodriver"), "owner" to owner),
        )

        val result = registry.testConnection("nodriver").shouldNotBeNull()

        result.connected shouldBe false
        checkNotNull(result.errorClass).contains('.') shouldBe true
    }

    @Test
    fun `delete is blocked while a pipeline references the datasource`() {
        val registry = registry(references = { name -> if (name == "used") listOf("pipeline_a") else emptyList() })
        registry.save(Fixtures.h2(name = "used", password = "pw"), owner)

        val result = registry.delete("used")

        result.deleted shouldBe false
        result.errorCode shouldBe DatasourceErrorCodes.IN_USE
        result.referencingPipelines shouldBe listOf("pipeline_a")
        // Still live.
        registry.exists("used") shouldBe true
    }

    @Test
    fun `delete soft-deletes an unreferenced datasource`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "free", password = "pw"), owner)

        registry.delete("free").deleted shouldBe true
        registry.exists("free") shouldBe false
    }

    @Test
    fun `save on an existing name updates in place and keeps the password when omitted`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "cfg", password = "keepme"), owner)

        val updated = registry.save(Fixtures.h2(name = "cfg", password = null).copy(displayName = "Config v2"), owner)

        updated.displayName shouldBe "Config v2"
        // The datasource remains usable after an update that omitted the password. (Retention of
        // the exact stored ciphertext is proven at the DB level in DatasourceRepositoryIntegrationTest.)
        registry.testConnection("cfg").shouldNotBeNull().connected shouldBe true
    }

    @Test
    fun `an update really rebuilds the pool - the next lease reaches the new target`() {
        // §5.2. The previous coverage only asserted that save() ran; nothing proved the live pool
        // was replaced, so a registry that forgot to evict would have stayed green while every
        // execution kept querying the OLD database after a PUT repointed the datasource.
        val registry = registry()
        registry.save(Fixtures.h2(name = "movable", jdbcUrl = "jdbc:h2:mem:before_move", password = "pw"), owner)

        val before = registry.get("movable").shouldNotBeNull()
        registry.poolFor(before).leaseConnection().use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE marker_before (id INT)") }
        }

        registry.save(Fixtures.h2(name = "movable", jdbcUrl = "jdbc:h2:mem:after_move", password = "pw"), owner)

        val after = registry.get("movable").shouldNotBeNull()
        after.jdbcUrl shouldBe "jdbc:h2:mem:after_move"
        registry.poolFor(after).leaseConnection().use { connection ->
            // The new pool points at a different in-memory database, so the old marker is absent
            // — a stale pool would still see it.
            connection.metaData.getTables(null, null, "MARKER_BEFORE", null).use { rs -> rs.next() shouldBe false }
            connection.createStatement().use { it.execute("CREATE TABLE marker_after (id INT)") }
        }
    }

    @Test
    fun `the metadata cache serves get and dialectOf, and every write invalidates it`() {
        // §6.3. The load-bearing half is invalidation: a cache that never dropped an entry would
        // keep serving a deleted datasource, and pipeline validation would keep accepting it.
        val registry = registry()
        registry.save(Fixtures.h2(name = "cached", password = "pw"), owner)

        registry.get("cached").shouldNotBeNull().displayName shouldBe "Test H2"
        registry.dialectOf("cached") shouldBe Dialect.H2

        registry.save(Fixtures.h2(name = "cached", password = "pw").copy(displayName = "Renamed"), owner)
        registry.get("cached").shouldNotBeNull().displayName shouldBe "Renamed"

        registry.delete("cached").deleted shouldBe true
        registry.get("cached").shouldBeNull()
        registry.dialectOf("cached").shouldBeNull()
    }

    @Test
    fun `a soft-deleted name cannot be re-created through the registry either`() {
        // §9 global uniqueness, through the full save path: `exists` is false after a soft delete,
        // so save() takes the create branch and the database — the only atomic authority — raises
        // duplicate_name from the primary-key violation.
        val registry = registry()
        registry.save(Fixtures.h2(name = "gone", password = "pw"), owner)
        registry.delete("gone").deleted shouldBe true

        val thrown = shouldThrow<DatapipelinesException> { registry.save(Fixtures.h2(name = "gone", password = "pw"), owner) }

        thrown.code shouldBe DatasourceErrorCodes.DUPLICATE_NAME
    }

    @Test
    fun `dialectOf resolves a registered datasource and is null otherwise`() {
        val registry = registry()
        registry.save(Fixtures.h2(name = "known", password = "pw"), owner)

        registry.dialectOf("known") shouldBe Dialect.H2
        registry.dialectOf("missing").shouldBeNull()
    }

    @Test
    fun `save rejects an invalid datasource before writing`() {
        val registry = registry()

        val thrown =
            shouldThrow<DatasourceValidationException> {
                registry.save(Fixtures.h2(name = "Bad Name!"), owner)
            }

        thrown.code shouldBe DatasourceErrorCodes.NAME_INVALID
        registry.exists("Bad Name!") shouldBe false
    }

    @Test
    fun `a pool build emits exactly one datasource_pool_build audit event`() {
        // §7.4: one event per credential DECRYPTION, never per lease. Two leases, one event —
        // that difference is the whole point of the amended model.
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "audited", password = "pw"), owner)
        val datasource = registry.get("audited").shouldNotBeNull()

        registry.poolFor(datasource).leaseConnection().use { }
        registry.poolFor(datasource).leaseConnection().use { }

        sink.countOf(DatasourceAuditEvents.POOL_BUILD) shouldBe 1
        val event = sink.events.single { it.event == DatasourceAuditEvents.POOL_BUILD }
        event.datasourceName shouldBe "audited"
        event.actor shouldBe DatasourceAuditEvent.SYSTEM_ACTOR
    }

    @Test
    fun `an update that evicts a live pool emits exactly one datasource_pool_rebuild, with the actor`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "rebuilt", password = "pw"), owner)
        registry.poolFor(registry.get("rebuilt").shouldNotBeNull()).leaseConnection().use { }

        registry.save(Fixtures.h2(name = "rebuilt", password = "pw").copy(displayName = "v2"), owner)

        sink.countOf(DatasourceAuditEvents.POOL_REBUILD) shouldBe 1
        sink.events.single { it.event == DatasourceAuditEvents.POOL_REBUILD }.actor shouldBe owner.toString()
    }

    @Test
    fun `an update with no live pool decrypts nothing and so emits no rebuild event`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "never_leased", password = "pw"), owner)

        registry.save(Fixtures.h2(name = "never_leased", password = "pw").copy(displayName = "v2"), owner)

        sink.eventNames() shouldBe emptyList()
    }

    @Test
    fun `testConnection emits exactly one datasource_connection_test audit event`() {
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "tested", password = "pw"), owner)

        registry.testConnection("tested").shouldNotBeNull()

        sink.countOf(DatasourceAuditEvents.CONNECTION_TEST) shouldBe 1
        sink.events.single().datasourceName shouldBe "tested"
    }

    @Test
    fun `an unknown name decrypts nothing, so no connection_test event is written`() {
        val sink = RecordingAuditSink()

        registry(auditSink = sink).testConnection("absent").shouldBeNull()

        sink.eventNames() shouldBe emptyList()
    }

    @Test
    fun `no audit event is written per lease`() {
        // The pre-v1.4 "audit every lease" model produced one row per query, per node, per
        // execution — unbounded volume recording nothing the execution record did not hold, and
        // implying a decrypt that does not happen.
        val sink = RecordingAuditSink()
        val registry = registry(auditSink = sink)
        registry.save(Fixtures.h2(name = "leased", password = "pw"), owner)
        val datasource = registry.get("leased").shouldNotBeNull()
        val pool = registry.poolFor(datasource)

        repeat(5) { pool.leaseConnection().use { } }

        sink.eventNames() shouldBe listOf(DatasourceAuditEvents.POOL_BUILD)
    }

    @Test
    fun `a mixed-case allowlist saved through the registry lands normalized - never silently inert`() {
        // R4 F3: lowercase normalization used to live ONLY in the REST bind, so any
        // non-controller write path (a future MCP create tool, restore tooling) stored mixed
        // case — and matching lowercases the driver-reported schema but compares stored
        // entries verbatim, so such an allowlist silently exempted nothing. The registry's
        // save is the single boundary every write path crosses: the stored and returned
        // entries are normalized, and the exemption matches the driver-reported spelling.
        val registry = registry()
        registry.save(
            Fixtures.h2(name = "mixed", password = "pw", introspectionIncludeSchemas = listOf(" APEX_Reporting ", "Sales")),
            owner,
        )

        checkNotNull(registry.get("mixed")).introspectionIncludeSchemas shouldBe listOf("apex_reporting", "sales")
    }

    private fun insertUser(): UUID =
        checkNotNull(
            jdbc.queryForObject(
                """
                INSERT INTO users (email, display_name, provider, provider_subject)
                VALUES ('owner@example.com', 'Owner', 'google', 'sub-1')
                RETURNING id
                """.trimIndent(),
                emptyMap<String, Any>(),
                UUID::class.java,
            ),
        )

    private fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("dp")
                .withPassword("dp")
    }
}
